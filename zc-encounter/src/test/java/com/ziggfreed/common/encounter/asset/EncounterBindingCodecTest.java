package com.ziggfreed.common.encounter.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The two encounter types' decode contract: what an absent leaf reads as, that {@code Enabled}
 * defaults on, and that an owner overlay decoded against the pack row keeps every leaf it did not
 * mention (the leaf-by-leaf merge every owner file relies on).
 */
public class EncounterBindingCodecTest {

    public static EncounterBindingAsset binding(String json, String id, EncounterBindingAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(EncounterBindingAsset.class, id, parent == null ? null : id);
        return EncounterBindingAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }

    public static EncounterParticipationAsset rule(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(EncounterParticipationAsset.class, id, null);
        return EncounterParticipationAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    @Test
    void anEmptyRowIsEnabledAndBindsItsOwnId() throws IOException {
        EncounterBindingAsset row = binding("{}", "my_boss", null);
        assertTrue(row.isEnabled());
        assertEquals("my_boss", row.encounterAsset());
        assertNull(row.getSubject());
        assertNull(row.getScale());
        assertNull(row.getLoot());
        assertNull(row.getNameKey());
    }

    @Test
    void everyGroupDecodesAndReadsItsDefaultsForAnAbsentLeaf() throws IOException {
        EncounterBindingAsset row = binding("{\"EncounterAsset\": \"Some_Script\", \"Enabled\": false,"
                + " \"Subject\": {\"AnyOccupiedSlot\": true}, \"Scale\": {\"HealthPerMember\": 0.35},"
                + " \"Timing\": {\"WipeGraceSeconds\": 20}, \"Loot\": {\"ToKiller\": true},"
                + " \"Feedback\": {\"Defeated\": \"My_Defeated\"}, \"Discovery\": {\"MapMarker\": true},"
                + " \"Leaderboard\": {\"Bucket\": \"bosses\"}, \"Progression\": {\"Difficulty\": \"hard\"}}", "row", null);
        assertFalse(row.isEnabled());
        assertEquals("Some_Script", row.encounterAsset());
        assertEquals(EncounterBindingAsset.DEFAULT_TARGET_SLOT, row.getSubject().targetSlot());
        assertTrue(row.getSubject().anyOccupiedSlot());
        assertEquals(0.35, row.getScale().healthPerMember(), 1e-9);
        assertEquals(EncounterBindingAsset.Scale.DEFAULT_MAX_HEALTH_MULTIPLIER, row.getScale().maxHealthMultiplier(), 1e-9);
        assertTrue(row.getScale().reconcileOnPhase());
        assertEquals(20, row.getTiming().wipeGraceSeconds());
        assertEquals(EncounterBindingAsset.Timing.DEFAULT_MAX_RUN_SECONDS, row.getTiming().maxRunSeconds());
        assertTrue(row.getLoot().toKiller());
        assertTrue(row.getLoot().queueIfOffline());
        assertEquals("My_Defeated", row.getFeedback().defeated());
        assertEquals(EncounterBindingAsset.DEFAULT_ENGAGED_MOMENT, row.getFeedback().engaged());
        assertTrue(row.getDiscovery().mapMarker());
        assertEquals(EncounterBindingAsset.Discovery.DEFAULT_FOLLOW_SECONDS, row.getDiscovery().followSeconds());
        assertEquals("bosses", row.getLeaderboard().getBucket());
        assertEquals("hard", row.getProgression().getDifficulty());
    }

    @Test
    void thePhaseLootMapIsKeyedByTheScriptsOwnStateName() throws IOException {
        EncounterBindingAsset row = binding("{\"Loot\": {\"OnPhase\": {\"Phase_2\": {\"Lootables\": [\"p2\"]}}}}",
                "row", null);
        assertNotNull(row.getLoot().getOnPhase().get("Phase_2"));
        assertEquals("p2", row.getLoot().getOnPhase().get("Phase_2").getLootables()[0]);
    }

    @Test
    void anOwnerOverlayKeepsEveryLeafItDidNotMention() throws IOException {
        EncounterBindingAsset pack = binding("{\"EncounterAsset\": \"Some_Script\", \"NameKey\": \"my.name\","
                + " \"Scale\": {\"HealthPerMember\": 0.35, \"MaxHealthMultiplier\": 4.0},"
                + " \"Timing\": {\"WipeGraceSeconds\": 20}}", "row", null);
        EncounterBindingAsset owner = binding("{\"Enabled\": false, \"Scale\": {\"HealthPerMember\": 0.5}}", "row", pack);
        assertFalse(owner.isEnabled());
        assertEquals("Some_Script", owner.encounterAsset(), "the script name survived the overlay");
        assertEquals("my.name", owner.getNameKey());
        assertEquals(0.5, owner.getScale().healthPerMember(), 1e-9, "the mentioned leaf changed");
        assertEquals(4.0, owner.getScale().maxHealthMultiplier(), 1e-9, "the unmentioned sibling leaf survived");
        assertEquals(20, owner.getTiming().wipeGraceSeconds(), "an unmentioned group survived whole");
    }

    @Test
    void aParticipationRuleDecodesBareNumbersAndGroupsAlike() throws IOException {
        EncounterParticipationAsset rule = rule("{\"Match\": \"*Warden*\", \"Where\": {\"GameplayConfig\": [\"Grove\"]},"
                + " \"DamageDealt\": 1.0, \"Presence\": {\"Base\": 0.1}, \"MinShare\": 0.05, \"CreditDead\": false}", "r");
        assertEquals("*Warden*", rule.matchOrAll());
        assertEquals("Grove", rule.getWhere().getGameplayConfig()[0]);
        assertEquals(1.0, rule.getDamageDealt().baseOrZero(), 1e-9);
        assertEquals(0.1, rule.getPresence().baseOrZero(), 1e-9);
        assertNull(rule.getDamageTaken());
        assertEquals(0.05, rule.getMinShare(), 1e-9);
        assertFalse(rule.getCreditDead());
        assertTrue(rule.isEnabled());
        assertEquals("*", rule("{}", "bare").matchOrAll());
    }

    @Test
    void theSpecLaysTheOverrideOverTheRuleOverThePosture() throws IOException {
        EncounterParticipationAsset rule = rule("{\"DamageDealt\": 2.0, \"DamageTaken\": 0.5, \"MinShare\": 0.1}", "r");
        EncounterBindingAsset row = binding("{\"Participation\": {\"DamageTaken\": 0.75, \"CreditDead\": false}}", "row", null);
        ParticipationSpec spec = ParticipationSpec.of(rule, row.getParticipation());
        assertEquals(2.0, spec.damageDealt().baseOrZero(), 1e-9, "from the rule");
        assertEquals(0.75, spec.damageTaken().baseOrZero(), 1e-9, "from the override");
        assertEquals(0.1, spec.minShare(), 1e-9, "from the rule");
        assertFalse(spec.creditDead(), "from the override");
        assertTrue(spec.creditDisconnected(), "the posture");
        assertNull(spec.presence(), "nothing authored presence");
        ParticipationSpec bare = ParticipationSpec.of(null, null);
        assertEquals(1.0, bare.weightsFor((f, p) -> null).damageDealt(), 1e-9);
        assertEquals(0.0, bare.weightsFor((f, p) -> null).presence(), 1e-9);
    }
}
