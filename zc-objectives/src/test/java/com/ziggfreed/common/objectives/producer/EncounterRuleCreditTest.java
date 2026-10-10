package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterRuleAsset;
import com.ziggfreed.common.encounter.asset.EncounterRuleConfig;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;

/**
 * The whole decision a rule credit makes, with no server near it: a kill of a role an enabled rule
 * names fires the attempt then the win, targeted at the role id; a copy of the role under another id,
 * a rule switched off or a live run's subject fires nothing; and the role is matched without regard to
 * case. The rules are the library's own fold over decoded rows ({@link EncounterRuleConfig.RoleIndex}),
 * and the live-subject read is {@link EncounterRuns}' own over its real tables.
 */
class EncounterRuleCreditTest {

    private static final Ref<EntityStore> FEASTMASTER = new Ref<>((Store<EntityStore>) null, 7);
    private static final BooleanSupplier NOT_A_SUBJECT = () -> false;

    /** One recorded moment. */
    private record Fired(@Nonnull String kind, @Nonnull String target, @Nonnull EncounterRulePayload payload) {
    }

    @AfterEach
    void tearDown() {
        EncounterRuns.resetForTests();
    }

    private static EncounterRuleAsset row(@Nonnull String id, @Nonnull String json) throws IOException {
        return EncounterRuleAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(EncounterRuleAsset.class, id, null)));
    }

    /** The fold's lookup over {@code rows}, keyed as {@code all()} keys them. */
    private static Function<String, EncounterRuleAsset> rules(EncounterRuleAsset... rows) {
        Map<String, EncounterRuleAsset> byId = new LinkedHashMap<>();
        for (EncounterRuleAsset row : rows) {
            byId.put(row.getId().toLowerCase(Locale.ROOT), row);
        }
        return EncounterRuleConfig.RoleIndex.of(byId)::forRole;
    }

    private static Function<String, EncounterRuleAsset> feastmaster() throws IOException {
        return rules(row("Goblin_Feastmaster", "{\"Match\": {\"Role\": \"Goblin_Feastmaster\"},"
                + " \"NameKey\": \"server.npcRoles.Goblin_Feastmaster.name\"}"));
    }

    private static List<Fired> fanOut(@Nonnull String roleId, @Nonnull Function<String, EncounterRuleAsset> rules,
            @Nonnull BooleanSupplier liveSubject) {
        List<Fired> fired = new ArrayList<>();
        int count = EncounterRuleCredit.fanOut(roleId, rules, liveSubject, FEASTMASTER,
                (kind, target, payload) -> fired.add(new Fired(kind, target, payload)));
        assertEquals(fired.size(), count, "the count is what went out");
        return fired;
    }

    @Test
    void aCountedKillIsTheAttemptThenTheWinTargetedAtTheRole() throws IOException {
        List<Fired> fired = fanOut("Goblin_Feastmaster", feastmaster(), NOT_A_SUBJECT);
        assertEquals(List.of(ZigEncounterProducer.KIND_ATTEMPT, ZigEncounterProducer.KIND_DEFEATED),
                fired.stream().map(Fired::kind).toList(), "the attempt comes first, as a fight's settlement does");
        for (Fired one : fired) {
            assertEquals("Goblin_Feastmaster", one.target());
            assertSame(FEASTMASTER, one.payload().victimRef());
            assertEquals("Goblin_Feastmaster", one.payload().roleId());
            assertEquals("Goblin_Feastmaster", one.payload().ruleId());
            assertEquals("server.npcRoles.Goblin_Feastmaster.name", one.payload().nameKey());
        }
    }

    @Test
    void aCopyOfTheRoleUnderAnotherIdCountsNothingAndNeverAsksAboutRuns() throws IOException {
        assertTrue(fanOut("Coffer_Goblin_Feastmaster", feastmaster(), () -> {
            throw new AssertionError("the live-subject read is asked only once a rule matched");
        }).isEmpty());
        assertTrue(fanOut("unknown", feastmaster(), NOT_A_SUBJECT).isEmpty());
    }

    @Test
    void aRuleSwitchedOffCountsNothingAndLeavesAnEnabledTwinTheRole() throws IOException {
        EncounterRuleAsset off = row("A_Feastmaster", "{\"Match\": {\"Role\": \"Goblin_Feastmaster\"}, \"Enabled\": false}");
        assertTrue(fanOut("Goblin_Feastmaster", rules(off), NOT_A_SUBJECT).isEmpty());
        EncounterRuleAsset on = row("B_Feastmaster", "{\"Match\": {\"Role\": \"Goblin_Feastmaster\"}}");
        List<Fired> fired = fanOut("Goblin_Feastmaster", rules(off, on), NOT_A_SUBJECT);
        assertEquals(2, fired.size(), "the switched-off row does not shadow the enabled one");
        assertEquals("B_Feastmaster", fired.get(0).payload().ruleId());
    }

    @Test
    void theRoleIsMatchedWithoutRegardToCaseAndTheTargetIsTheVictimsOwnId() throws IOException {
        Function<String, EncounterRuleAsset> lower = rules(row("Bear", "{\"Match\": {\"Role\": \"bear_voidtaken\"}}"));
        List<Fired> fired = fanOut("Bear_Voidtaken", lower, NOT_A_SUBJECT);
        assertEquals(2, fired.size());
        assertEquals("Bear_Voidtaken", fired.get(0).target(), "the target is the role id the kill reported");
        assertEquals(2, fanOut("BEAR_VOIDTAKEN", lower, NOT_A_SUBJECT).size());
    }

    @Test
    void aLiveRunsSubjectIsSkippedByItsIndexedReferenceOrByItsUuid() throws IOException {
        UUID runId = UUID.randomUUID();
        EncounterRuns.indexSubject(runId, FEASTMASTER);
        assertTrue(fanOut("Goblin_Feastmaster", feastmaster(),
                () -> EncounterRuns.isLiveSubject(FEASTMASTER, () -> null)).isEmpty(),
                "the indexed subject is its run's to credit");

        EncounterRuns.resetForTests();
        UUID boss = UUID.randomUUID();
        ZigEncounterRun run = new ZigEncounterRun();
        run.bindSubject(boss, "Goblin_Feastmaster", 1L);
        EncounterRuns.track(run, UUID.randomUUID(), "Some_Bound_Fight", new Ref<>((Store<EntityStore>) null, 3));
        assertTrue(fanOut("Goblin_Feastmaster", feastmaster(),
                () -> EncounterRuns.isLiveSubject(FEASTMASTER, () -> boss)).isEmpty(),
                "a reference the index has not caught up with is still found by its uuid");
        assertEquals(2, fanOut("Goblin_Feastmaster", feastmaster(),
                () -> EncounterRuns.isLiveSubject(FEASTMASTER, UUID::randomUUID)).size(),
                "a creature no run is about counts");
    }
}
