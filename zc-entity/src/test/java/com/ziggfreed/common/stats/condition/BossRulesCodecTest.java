package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;

/**
 * The crowd-control seam's structural gate: the two conditions decode ({@code Kind} without regard to case,
 * {@code ByTalent} false unwritten), an effect family's {@code Control} decodes, an asset's native {@code Tags} parse
 * into a rule through the engine's own tag expansion with each value that names no kind reported, and the layers
 * resolve in order (window over standing over own group over family over structural, shrugs over lands inside a
 * layer, the family layer read only for a boss or an elite).
 */
class BossRulesCodecTest {

    @BeforeAll
    static void registerTheConditions() {
        // The library registers its conditions at setup; a unit JVM registers the two this test decodes.
        if (Condition.CODEC.getCodecFor(ZigControlCondition.TYPE_NAME) == null) {
            Condition.CODEC.register(ZigControlCondition.TYPE_NAME, ZigControlCondition.class,
                    ZigControlCondition.codec());
        }
        if (Condition.CODEC.getCodecFor(ZigBossOrEliteCondition.TYPE_NAME) == null) {
            Condition.CODEC.register(ZigBossOrEliteCondition.TYPE_NAME, ZigBossOrEliteCondition.class,
                    ZigBossOrEliteCondition.codec());
        }
    }

    @Test
    void theConditionsDecode_kindWithoutRegardToCase_byTalentFalseUnwritten() throws IOException {
        ZigControlCondition push = assertInstanceOf(ZigControlCondition.class,
                decode("{ \"Id\": \"ZigControl\", \"Kind\": \"push\" }"));
        assertEquals(ControlKind.PUSH, push.kind());
        assertFalse(push.byTalent(), "ByTalent reads false unwritten");

        ZigControlCondition talent = assertInstanceOf(ZigControlCondition.class,
                decode("{ \"Id\": \"ZigControl\", \"Kind\": \" INTERRUPT \", \"ByTalent\": true }"));
        assertEquals(ControlKind.INTERRUPT, talent.kind());
        assertTrue(talent.byTalent());

        ZigControlCondition unknown = assertInstanceOf(ZigControlCondition.class,
                decode("{ \"Id\": \"ZigControl\", \"Kind\": \"Fear\" }"));
        assertNull(unknown.kind(), "a name that is no kind reads none");

        assertInstanceOf(ZigBossOrEliteCondition.class, decode("{ \"Id\": \"ZigBossOrElite\", \"Inverse\": true }"));
    }

    @Test
    void aFamilysControl_decodes_andANameThatIsNoKindReadsNone() throws IOException {
        assertEquals(ControlKind.STUN, family("Stunned", "{ \"Effects\": [ \"Stun\" ], \"Control\": \"stun\" }")
                .control());
        assertNull(family("Hindered", "{ \"Includes\": [ \"Slowed\" ] }").control(), "a mixed family names none");
        EffectFamilyAsset bogus = family("Odd", "{ \"Control\": \"Fear\" }");
        assertNull(bogus.control());
        assertEquals("Fear", bogus.rawControl(), "kept as written, for the warning");
    }

    @Test
    void anAssetsTags_parseIntoARule_throughTheEnginesExpansion_reportingEachUnknownValue() throws IOException {
        EffectFamilyAsset tagged = family("Tagged", "{ \"Tags\": { \"ZigControlLands\": [ \"Stun\", \"interrupt\" ],"
                + " \"zigcontrolshrugs\": [ \"Taunt\", \"Fear\" ], \"ZigRank\": [ \"Boss\" ] } }");
        Map<String, String[]> raw = EffectFamilyAsset.CODEC.getData(tagged).getRawTags();
        List<String> unknown = new ArrayList<>();

        ControlRule rule = ControlRule.parse(raw, unknown::add);
        assertEquals(Set.of(ControlKind.STUN, ControlKind.INTERRUPT), rule.landing());
        assertEquals(Set.of(ControlKind.TAUNT), rule.shrugging(), "the key matched without regard to case");
        assertEquals(List.of("Fear"), unknown, "a value that names no kind is reported and counts nothing");
        assertTrue(ControlRule.ranked(raw, unknown::add), "ZigRank Boss counts the group's roles");

        List<String> badRank = new ArrayList<>();
        EffectFamilyAsset captain = family("Captain", "{ \"Tags\": { \"ZigRank\": [ \"Captain\" ] } }");
        Map<String, String[]> captainTags = EffectFamilyAsset.CODEC.getData(captain).getRawTags();
        assertFalse(ControlRule.ranked(captainTags, badRank::add));
        assertEquals(List.of("Captain"), badRank);
        assertTrue(ControlRule.parse(captainTags, badRank::add).isEmpty(), "no rule tag, no rule");
    }

    @Test
    void theLayersResolveInOrder_shrugsOverLandsInsideALayer() {
        ControlRule landsStun = rule(ControlKind.STUN.bit(), 0);
        ControlRule shrugsStun = rule(0, ControlKind.STUN.bit());
        ControlKind stun = ControlKind.STUN;

        assertEquals(ControlReading.LANDS, ControlReading.resolve(stun, true, landsStun, shrugsStun, null, null),
                "a window over a standing rule");
        assertEquals(ControlReading.SHRUGS, ControlReading.resolve(stun, true, null, shrugsStun, landsStun, null),
                "a standing rule over the boss's own group");
        assertEquals(ControlReading.LANDS, ControlReading.resolve(stun, true, null, null, landsStun, shrugsStun),
                "the boss's own group over the family default");
        assertEquals(ControlReading.SHRUGS, ControlReading.resolve(stun, true, null, null, null, shrugsStun),
                "the family default over structural");
        assertEquals(ControlReading.USUAL, ControlReading.resolve(stun, true, null, null, null, null),
                "structural: a boss or an elite reads usual");
        assertEquals(ControlReading.LANDS, ControlReading.resolve(stun, false, null, null, null, shrugsStun),
                "the family default is read for a boss or an elite only");
        assertEquals(ControlReading.SHRUGS, ControlReading.resolve(stun, false, shrugsStun, null, null, null),
                "a rule effect binds anyone who wears it");
        assertEquals(ControlReading.USUAL, ControlReading.resolve(null, true, shrugsStun, landsStun, null, null),
                "a control of no kind reads structural alone");
        assertEquals(ControlReading.USUAL, ControlReading.resolve(ControlKind.SLOW, true, landsStun, null, null, null),
                "a kind a layer does not name falls through it, here to structural");

        ControlRule both = landsStun.with(shrugsStun);
        assertEquals(ControlReading.SHRUGS, ControlReading.resolve(stun, true, both, null, null, null),
                "two sources in one layer: shrugs over lands");
        assertEquals(ControlReading.SHRUGS, ControlReading.resolve(stun, true, rule(stun.bit(), stun.bit()), null,
                null, null), "one rule naming both: shrugs");

        assertTrue(ControlReading.LANDS.lands(true));
        assertTrue(ControlReading.USUAL.lands(false), "an ability's own lands as usual");
        assertFalse(ControlReading.USUAL.lands(true), "a talent's does not");
        assertFalse(ControlReading.SHRUGS.lands(false));
    }

    private static ControlRule rule(int lands, int shrugs) {
        return new ControlRule(lands, shrugs);
    }

    private static Condition decode(String json) throws IOException {
        return Condition.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    private static EffectFamilyAsset family(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(EffectFamilyAsset.class, id, null);
        return EffectFamilyAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }
}
