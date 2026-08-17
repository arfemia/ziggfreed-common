package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The flat {@code type} plus fields reader: what becomes the kind, what becomes a parameter, what
 * the consumer's dialect gets to change, and that a refusal happens at LOAD with a warning naming
 * the file rather than at payout with a player standing there.
 */
class RewardJsonTest {

    private List<String> warnings;
    private RewardJson reader;

    /** A dialect with a little of everything a consumer has: an old kind id, an old field name, a rule. */
    @BeforeEach
    void setUp() {
        warnings = new ArrayList<>();
        UnaryOperator<String> kinds = authored ->
                "trinket".equalsIgnoreCase(authored) ? "Mymod_Trinket" : authored;
        UnaryOperator<String> paramKeys = authored -> {
            String folded = authored.trim().toLowerCase(Locale.ROOT);
            return "trinketid".equals(folded) ? "trinket" : folded;
        };
        reader = RewardJson.using(kinds, paramKeys,
                spec -> "Mymod_Trinket".equals(spec.kind()) && spec.param("trinket") == null
                        ? "a trinket reward naming no trinket" : null,
                warnings::add);
    }

    @Nonnull
    private static JsonObject json(@Nonnull String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    void typeNamesTheKindAndEveryOtherFieldBecomesAParameter() {
        RewardSpec spec = reader.parse(json("""
                { "type": "Item", "Item": "Ore_Iron", "count": 5 }
                """), "test");

        assertNotNull(spec);
        assertEquals(LootRewardKinds.KIND_ITEM, spec.kind());
        assertEquals("Ore_Iron", spec.param("item"));
        assertEquals(5L, spec.longParam("count", 0L));
    }

    @Test
    void aKindNobodyHereDefinesStillParses() {
        RewardSpec spec = reader.parse(json("""
                { "type": "Somebodyelse_Title", "title": "Trailblazer" }
                """), "test");

        assertNotNull(spec, "the mod that defines it may simply not be installed yet");
        assertEquals("Somebodyelse_Title", spec.kind());
        assertEquals("Trailblazer", spec.param("title"));
    }

    @Test
    void theDialectTranslatesBothTheKindAndTheFieldNames() {
        RewardSpec spec = reader.parse(json("""
                { "type": "trinket", "TrinketId": "brass_owl" }
                """), "test");

        assertNotNull(spec);
        assertEquals("Mymod_Trinket", spec.kind(), "an old spelling reaches the id that is registered");
        assertEquals("brass_owl", spec.param("trinket"), "and so does an old field name");
    }

    @Test
    void aRewardMissingWhatItsKindRequiresIsRefusedAtLoadWithAWarning() {
        assertNull(reader.parse(json("""
                { "type": "trinket" }
                """), "Trinkets.json"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("Trinkets.json: "),
                "the warning names the file so whoever wrote it can find it: " + warnings.get(0));
    }

    @Test
    void aRewardWithNoTypeAtAllIsACommand() {
        RewardSpec spec = reader.parse(json("""
                { "command": "/say hello" }
                """), "test");

        assertNotNull(spec);
        assertEquals(LootRewardKinds.KIND_COMMAND, spec.kind());
    }

    @Test
    void nestedValuesAreNotParameters() {
        RewardSpec spec = reader.parse(json("""
                { "type": "Item", "item": "Ore_Iron", "nested": { "a": 1 }, "list": [1, 2] }
                """), "test");

        assertNotNull(spec);
        assertNull(spec.param("nested"), "a parameter is a single value whichever kind reads it");
        assertNull(spec.param("list"));
    }

    @Test
    void theSystemsOwnOfflineDefaultIsWrittenOnUnlessTheRewardSaysOtherwise() {
        RewardSpec silent = reader.parse(json("""
                { "type": "Item", "item": "Ore_Iron" }
                """), "test", true);
        RewardSpec explicit = reader.parse(json("""
                { "type": "Item", "item": "Ore_Iron", "queueIfOffline": false }
                """), "test", true);

        assertNotNull(silent);
        assertNotNull(explicit);
        assertTrue(silent.flagParam(RewardGrants.P_QUEUE_IF_OFFLINE, false));
        assertTrue(!explicit.flagParam(RewardGrants.P_QUEUE_IF_OFFLINE, false),
                "what the reward itself says always wins over the system default");
    }

    @Test
    void anIdentityDialectStillHonoursAnAuthoredOfflineFlagUnderADifferentCase() {
        // The identity dialect does not fold field casing (unlike this suite's own dialect, whose
        // paramKeys lower-cases every field), so an authored "QueueIfOffline" lands as its own
        // parameter distinct from the constant's "queueifoffline" spelling right up until RewardSpec
        // folds every key - which is exactly the case the default-fill has to survive.
        RewardJson identity = RewardJson.using(UnaryOperator.identity(), UnaryOperator.identity(),
                null, warnings::add);

        RewardSpec spec = identity.parse(json("""
                { "type": "Item", "item": "Ore_Iron", "QueueIfOffline": "false" }
                """), "test", true);

        assertNotNull(spec);
        assertTrue(!spec.flagParam(RewardGrants.P_QUEUE_IF_OFFLINE, true),
                "the authored value must survive the system default fill: " + spec);
    }

    @Test
    void aDialectThatRefusesNothingParsesEverything() {
        RewardJson permissive = RewardJson.using(UnaryOperator.identity(), UnaryOperator.identity(),
                null, warnings::add);

        assertNotNull(permissive.parse(json("{ \"type\": \"Mymod_Trinket\" }"), "test"));
        assertTrue(warnings.isEmpty());
    }
}
