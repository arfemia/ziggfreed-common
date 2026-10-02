package com.ziggfreed.common.encounter.validate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.ziggfreed.common.encounter.types.BuilderActionZigGrant;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.LootableValidator;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A script's {@code ZigGrant} loot is read the way the action reads it, so the audit sees what the
 * fight pays: a table named by id, inline rolls, a whole table written inline, and a loot that
 * cannot be read at all.
 */
class ZigGrantLootReadTest {

    private static JsonObject script(String loot) {
        return JsonParser.parseString("{\"Content\": {\"Instructions\": [{\"Actions\": ["
                + "{\"Type\": \"ZigGrant\", \"Loot\": " + loot + "}]}]}}").getAsJsonObject();
    }

    @Test
    void aZigGrantNamingATableIsReadWithThatTable() {
        List<LootRef> loots = EncounterValidator.zigGrantLoots(script("{\"Lootables\": [\"Boss_Hoard\"]}"),
                name -> null);

        assertEquals(1, loots.size(), "a named table decodes in the asset context the action reads it in");
        assertArrayEquals(new String[] {"Boss_Hoard"}, loots.get(0).getLootables());
    }

    @Test
    void aZigGrantWithATableAndInlineRollsKeepsBoth() {
        List<LootRef> loots = EncounterValidator.zigGrantLoots(script("{\"Lootables\": [\"Boss_Hoard\"], "
                + "\"Rolls\": [{\"Grants\": {\"Items\": [{\"Item\": \"Boss_Coin\"}]}}]}"), name -> null);

        assertEquals(1, loots.size());
        assertArrayEquals(new String[] {"Boss_Hoard"}, loots.get(0).getLootables());
        assertEquals("Boss_Coin", loots.get(0).getRolls()[0].getGrants().getItems()[0].getItem());
    }

    @Test
    void anInlineRollsLootReadsAsBefore() {
        List<Finding> unreadable = new ArrayList<>();
        List<LootRef> loots = EncounterValidator.zigGrantLoots(
                script("{\"Rolls\": [{\"Grants\": {\"Items\": [{\"Item\": \"Boss_Coin\"}]}}]}"), name -> null,
                "Boss_Encounter", unreadable::add);

        assertEquals(1, loots.size());
        assertNull(loots.get(0).getLootables());
        assertEquals("Boss_Coin", loots.get(0).getRolls()[0].getGrants().getItems()[0].getItem());
        assertTrue(unreadable.isEmpty(), () -> unreadable.toString());
    }

    @Test
    void aLootThatCannotBeReadIsAFindingAgainstItsScript() {
        List<Finding> unreadable = new ArrayList<>();
        List<LootRef> loots = EncounterValidator.zigGrantLoots(script("{\"Rolls\": 5}"), name -> null,
                "Boss_Encounter", unreadable::add);

        assertTrue(loots.isEmpty(), "a loot that cannot be read pays nothing, so the audit reads nothing for it");
        assertEquals(1, unreadable.size(), () -> unreadable.toString());
        Finding finding = unreadable.get(0);
        assertEquals(EncounterValidator.GRANT_LOOT_UNREADABLE, finding.code());
        assertEquals(EncounterValidator.DOMAIN, finding.domain());
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals("Boss_Encounter", finding.sourceId(), "the finding names the script");
    }

    @Test
    void aLootThatIsNotAGroupIsAFindingToo() {
        List<Finding> unreadable = new ArrayList<>();
        EncounterValidator.zigGrantLoots(script("[\"Boss_Hoard\"]"), name -> null, "Boss_Encounter",
                unreadable::add);

        assertEquals(1, unreadable.size(), "a Loot written as a bare list is not the loot group, so it cannot be read");
        assertEquals(EncounterValidator.GRANT_LOOT_UNREADABLE, unreadable.get(0).code());
    }

    @Test
    void anUnreadableLootInAReferencedBuilderIsReportedAgainstTheScriptReadingIt() {
        JsonObject root = JsonParser.parseString("{\"Content\": {\"Actions\": [{\"Reference\": \"Payout_Macro\"}]}}")
                .getAsJsonObject();
        JsonObject macro = script("{\"Lootables\": 7}");
        List<Finding> unreadable = new ArrayList<>();

        EncounterValidator.zigGrantLoots(root, name -> name.equals("Payout_Macro") ? macro : null, "Boss_Encounter",
                unreadable::add);

        assertEquals(1, unreadable.size(), () -> unreadable.toString());
        assertEquals("Boss_Encounter", unreadable.get(0).sourceId());
    }

    @Test
    void theTwoArgumentWalkerStillLeavesAnUnreadableLootOut() {
        assertTrue(EncounterValidator.zigGrantLoots(script("{\"Rolls\": 5}"), name -> null).isEmpty());
    }

    @Test
    void aNamedTableNowReachesTheFightsLootAudit() {
        List<LootRef> loots = EncounterValidator.zigGrantLoots(script("{\"Lootables\": [\"nowhere\"]}"),
                name -> null, "Boss_Encounter", finding -> { });

        List<Finding> findings = EncounterValidator.validate(Map.of(), List.of(), List.of(), id -> false,
                List.of(), null, Map.of("Boss_Encounter", loots), null);

        List<Finding> unknown = findings.stream().filter(f -> f.code().equals(LootableValidator.UNKNOWN_TABLE))
                .toList();
        assertEquals(1, unknown.size(), () -> findings.toString());
        assertEquals("Boss_Encounter ZigGrant Loot", unknown.get(0).sourceId());
    }


    /**
     * A whole table written inline under a {@code ZigGrant}'s {@code Lootables} is steered to a shared
     * table id or to {@code Rolls}: the engine names an inline table from the script's id and the keys
     * above it, and a script's builders push no list position, so two such grants in one script can
     * be given the same name. The warning reads the authored JSON, so it holds whatever the decode
     * does, and no generated id ever reaches the fight's loot audit.
     *
     * <p>A unit JVM registers no loot-table store, so the detached decode cannot mint an inline
     * table's id here (the contained-asset codec's key generator asks that store to transform the
     * key and finds none), and the grant reads as unreadable; on a server it decodes. That the named
     * table beside an inline one is kept, and only the generated id dropped, is pinned on the filter
     * itself ({@link #theAuditDropsOnlyGeneratedTableIds}).
     */
    @Test
    void aWholeTableWrittenInlineIsSteeredToASharedIdOrRolls() {
        String inline = "{\"Lootables\": [{\"Rolls\": [{\"Grants\": {\"Items\": [{\"Item\": \"Boss_Coin\"}]}}]}, "
                + "\"Boss_Hoard\"]}";
        JsonObject root = JsonParser.parseString("{\"Content\": {\"Instructions\": [{\"Actions\": ["
                + "{\"Type\": \"ZigGrant\", \"Loot\": " + inline + "},"
                + "{\"Type\": \"ZigGrant\", \"Loot\": " + inline + "}]}]}}").getAsJsonObject();
        List<Finding> found = new ArrayList<>();

        List<LootRef> loots = EncounterValidator.zigGrantLoots(root, name -> null, "Boss_Encounter", found::add);

        List<Finding> steered = found.stream().filter(f -> f.code().equals(EncounterValidator.GRANT_INLINE_TABLE))
                .toList();
        assertEquals(2, steered.size(), () -> "one warning per grant that writes a table inline: " + found);
        for (Finding finding : steered) {
            assertEquals(Severity.WARNING, finding.severity());
            assertEquals(EncounterValidator.DOMAIN, finding.domain());
            assertEquals("Boss_Encounter", finding.sourceId(), "the finding names the script");
        }
        for (LootRef loot : loots) {
            for (String table : loot.getLootables() == null ? new String[0] : loot.getLootables()) {
                assertFalse(table.startsWith(ExtraInfo.GENERATED_ID_PREFIX), "no generated id is audited: " + table);
            }
        }
    }

    @Test
    void aTableNamedByIdOrInlineRollsIsNotSteered() {
        List<Finding> found = new ArrayList<>();
        EncounterValidator.zigGrantLoots(script("{\"Lootables\": [\"Boss_Hoard\"], "
                + "\"Rolls\": [{\"Grants\": {\"Items\": [{\"Item\": \"Boss_Coin\"}]}}]}"), name -> null,
                "Boss_Encounter", found::add);

        assertTrue(found.isEmpty(), () -> found.toString());
    }

    /**
     * The audit's detached read names a whole table written inline by a generated id nothing loads
     * (the table loads with the script, where it is audited as a table), so that id is dropped and
     * a table named beside it, and the inline rolls, are kept.
     */
    @Test
    void theAuditDropsOnlyGeneratedTableIds() {
        Roll[] rolls = BuilderActionZigGrant.decodeLoot(
                JsonParser.parseString("{\"Rolls\": [{\"Grants\": {\"Items\": [{\"Item\": \"Boss_Coin\"}]}}]}"),
                null, "Boss_Encounter").getRolls();
        String generated = ExtraInfo.GENERATED_ID_PREFIX + "Boss_Encounter_Instructions_Actions_Loot_Lootables_0";

        LootRef mixed = EncounterValidator.withoutGeneratedTables(
                LootRef.of(new String[] {generated, "Boss_Hoard"}, rolls));
        assertArrayEquals(new String[] {"Boss_Hoard"}, mixed.getLootables(), "the named table is kept");
        assertSame(rolls, mixed.getRolls(), "the inline rolls are kept");

        LootRef onlyGenerated = EncounterValidator.withoutGeneratedTables(LootRef.of(new String[] {generated}, null));
        assertTrue(onlyGenerated.isEmpty(), "a loot that is only an inline table leaves nothing for the walker to audit");

        LootRef named = LootRef.of(new String[] {"Boss_Hoard"}, rolls);
        assertSame(named, EncounterValidator.withoutGeneratedTables(named), "a loot naming no generated id is unchanged");
        assertNull(EncounterValidator.withoutGeneratedTables(null));
    }
}
