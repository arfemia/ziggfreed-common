package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The kinds of control as data: a kind file and the shared settings decode with their documented defaults, a pack's
 * own kind takes a bit of its own past the library's twelve, the rules of several kinds fold into one landing (refused
 * by immunity if any is, shrinking if any does, the largest ramp, the lowest cap), and an effect's fixed tag reads
 * through its own tags with an unknown value reported.
 */
class ControlKindsTest {

    @Test
    void aKindFile_decodesItsRule_withTheDocumentedDefaults() throws IOException {
        ControlKindAsset bare = kind("Bare", "{}");
        assertTrue(bare.refusedByImmune(), "refused by immunity unless written");
        assertFalse(bare.shrinks());
        assertFalse(bare.ramps());
        assertNull(bare.rampAmount(), "the shared amount unless written");
        assertEquals(0.0, bare.maxSeconds(), "no cap unless written");
        assertNull(bare.onBossOrElite(), "usual on a boss unless written");

        ControlKindAsset slow = kind("Slow", "{ \"RefusedByImmune\": false, \"Shrinks\": true, \"Ramps\": true,"
                + " \"RampAmount\": 10, \"MaxSeconds\": 6, \"OnBossOrElite\": \"shrugs\" }");
        assertFalse(slow.refusedByImmune());
        assertTrue(slow.shrinks());
        assertTrue(slow.ramps());
        assertEquals(10.0, slow.rampAmount());
        assertEquals(6.0, slow.maxSeconds());
        assertEquals(ControlReading.SHRUGS, slow.onBossOrElite(), "matched without regard to case");
        assertEquals(ControlReading.LANDS, ControlKindAsset.readingOf("Lands"));
        assertNull(ControlKindAsset.readingOf("Usual"), "Usual is the unwritten reading");
        assertEquals(0.0, kind("Odd", "{ \"RampAmount\": -5 }").rampAmount(), "never below 0");
    }

    @Test
    void theSettings_decodeWithTheDocumentedDefaults() throws IOException {
        ControlSettingsAsset bare = settings("{}");
        assertEquals(ControlSettingsAsset.DEFAULT_RAMP_AMOUNT, bare.rampAmount());
        assertTrue(bare.fixedSkipsImmunity());
        assertTrue(bare.fixedSkipsResist());
        assertTrue(bare.fixedSkipsAddedSeconds());

        ControlSettingsAsset written = settings("{ \"RampAmount\": 20, \"Fixed\": { \"SkipsResist\": false } }");
        assertEquals(20.0, written.rampAmount());
        assertFalse(written.fixedSkipsResist());
        assertTrue(written.fixedSkipsImmunity(), "an unwritten leaf of the group keeps its default");
    }

    @Test
    void aPacksOwnKind_takesABitPastTheLibrarysTwelve_andIsTheSameKindEverAfter() {
        ControlKind silence = ControlKind.declare("Test_Silence", "A silence");
        assertNotNull(silence);
        assertFalse(silence.isBuiltIn());
        assertTrue(silence.bit() > ControlKind.MARK.bit(), "past every library kind's bit");
        assertSame(silence, ControlKind.parse(" test_SILENCE "), "matched without regard to case");
        assertSame(silence, ControlKind.declare("TEST_SILENCE", null), "declared once");
        assertSame(ControlKind.STUN, ControlKind.declare("stun", null), "a library kind is never declared again");
        assertTrue(ControlKind.ofMask(silence.bit() | ControlKind.SLOW.bit()).contains(silence));
        assertTrue(List.of(ControlKind.ids()).contains("Test_Silence"));
    }

    @Test
    void severalKindsRules_foldIntoOneLanding() {
        ControlKinds.Rule[] byBit = new ControlKinds.Rule[ControlKind.MAX];
        byBit[bit(ControlKind.STUN)] = new ControlKinds.Rule(true, true, true, null, 0.0, null);
        byBit[bit(ControlKind.SLOW)] = new ControlKinds.Rule(true, true, true, 10.0, 6.0, null);
        byBit[bit(ControlKind.PUSH)] = new ControlKinds.Rule(false, false, false, null, 0.0, null);
        byBit[bit(ControlKind.WITHER)] = new ControlKinds.Rule(true, false, false, null, 4.0, null);

        ControlKinds.Landing stun = ControlKinds.combine(byBit, ControlKind.STUN.bit(), 25.0);
        assertTrue(stun.refusedByImmune());
        assertTrue(stun.shrinks());
        assertEquals(25.0, stun.rampAmount(), "a ramping kind with no amount of its own takes the shared one");
        assertEquals(0.0, stun.maxSeconds());

        assertEquals(10.0, ControlKinds.combine(byBit, ControlKind.SLOW.bit(), 25.0).rampAmount(),
                "a kind's own amount");
        ControlKinds.Landing push = ControlKinds.combine(byBit, ControlKind.PUSH.bit(), 25.0);
        assertFalse(push.refusedByImmune());
        assertFalse(push.shrinks());
        assertFalse(push.ramps(), "a free kind: no immunity, no shrink, no ramp");

        ControlKinds.Landing slowAndWither = ControlKinds.combine(byBit,
                ControlKind.SLOW.bit() | ControlKind.WITHER.bit(), 25.0);
        assertTrue(slowAndWither.shrinks(), "shrinks if any kind does");
        assertEquals(10.0, slowAndWither.rampAmount(), "the largest ramp among the kinds that ramp");
        assertEquals(4.0, slowAndWither.maxSeconds(), "the lowest cap among the kinds that cap");

        assertEquals(ControlKinds.Landing.NONE, ControlKinds.combine(byBit, 0, 25.0), "a control of no kind");
        assertEquals(ControlKinds.Landing.NONE, ControlKinds.combine(byBit, ControlKind.DECOY.bit(), 25.0),
                "a kind with no file reads nothing of its own");
    }

    @Test
    void anEffectsFixedTag_readsThroughItsOwnTags_andAnUnknownValueIsReported() {
        List<String> unknown = new ArrayList<>();
        assertTrue(EffectFamilies.fixedIn(Map.of("zigcontrol", new String[] {" fixed ", "Loud"}), unknown::add),
                "the key and the value matched without regard to case");
        assertEquals(List.of("Loud"), unknown, "a value that is no way of landing is reported and counts nothing");
        assertFalse(EffectFamilies.fixedIn(Map.of("ZigEffectFamily", new String[] {"Stunned"}), unknown::add),
                "another tag makes nothing fixed");
    }

    private static int bit(ControlKind kind) {
        return Integer.numberOfTrailingZeros(kind.bit());
    }

    private static ControlKindAsset kind(String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ControlKindAsset.class, id, null);
        return ControlKindAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    private static ControlSettingsAsset settings(String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ControlSettingsAsset.class, "Default", null);
        return ControlSettingsAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }
}
