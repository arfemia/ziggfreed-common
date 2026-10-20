package com.ziggfreed.common.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The proc audit's structural gate, over decoded files and a stand-in vocabulary: what a server lacks (an effect, a
 * stat, a chain, a family, a counter, a trigger) is an error, a proc that can never run as written or that no pick
 * reaches is a warning, and a well-formed set of files says nothing.
 */
class ProcValidatorTest {

    private static final ProcValidator.Vocabulary KNOWN = new ProcValidator.Vocabulary(
            id -> id.equalsIgnoreCase("Known_Effect"),
            id -> id.equalsIgnoreCase("Known_Stat"),
            id -> id.equalsIgnoreCase("Known_Root"),
            id -> id.equalsIgnoreCase("Burning"),
            id -> id.equalsIgnoreCase("Known_Line"),
            (track, pick) -> track.equalsIgnoreCase("Swords") && pick.equalsIgnoreCase("Flow"));

    @Test
    void wellFormedFiles_sayNothing() throws IOException {
        ProcAsset proc = proc("{ \"Trigger\": \"Hit\", \"Like\": \"Known_Line\","
                + " \"Requires\": { \"Factors\": [ { \"Factor\": \"ziggfreedcommon:track_pick\", \"Param\": \"Swords/Flow\","
                + " \"Min\": 1 } ] },"
                + " \"Actions\": [ { \"Counter\": { \"Of\": \"Flow\", \"Add\": { \"Base\": 1 } } },"
                + " { \"ApplyEffect\": { \"Effect\": \"Known_Effect\", \"Overlap\": \"Extend\" } },"
                + " { \"RemoveEffect\": { \"Families\": [ \"Burning\" ], \"OnSelf\": true } },"
                + " { \"ChangeStat\": { \"Stat\": \"Known_Stat\", \"Amount\": { \"Base\": 5 } } },"
                + " { \"RunInteraction\": { \"Interaction\": \"Known_Root\" } } ] }");
        CounterAsset counter = counter("{ \"PerStack\": [ { \"Stat\": \"Known_Stat\", \"Amount\": 2 } ],"
                + " \"Effects\": [ \"Known_Effect\" ], \"ClearedBy\": [ \"HitTaken\" ] }");
        HeldEffectAsset held = held("{ \"Effect\": \"Known_Effect\", \"Stat\": \"Known_Stat\" }");
        List<Finding> findings = ProcValidator.audit(Map.of("flow_proc", proc), Map.of("flow", counter),
                Map.of("held", held), KNOWN);
        assertEquals(List.of(), findings);
    }

    @Test
    void whatTheServerLacksIsAnError() throws IOException {
        ProcAsset proc = proc("{ \"Trigger\": \"Hit\","
                + " \"Actions\": [ { \"Counter\": { \"Of\": \"Missing\" } },"
                + " { \"ApplyEffect\": { \"Effect\": \"Missing_Effect\" } },"
                + " { \"RemoveEffect\": { \"Families\": [ \"Missing_Family\" ] } },"
                + " { \"ChangeStat\": { \"Stat\": \"Missing_Stat\", \"Amount\": { \"Base\": 5 } } },"
                + " { \"RunInteraction\": { \"Interaction\": \"Missing_Root\" } } ] }");
        ProcAsset unknownTrigger = proc("{ \"Trigger\": \"Sneeze\", \"Actions\": [ { \"RunInteraction\":"
                + " { \"Interaction\": \"Known_Root\" } } ] }");
        CounterAsset counter = counter("{ \"PerStack\": [ { \"Stat\": \"Missing_Stat\", \"Amount\": 2 } ],"
                + " \"ClearedBy\": [ \"Sneeze\" ] }");
        List<Finding> findings = ProcValidator.audit(Map.of("a", proc, "b", unknownTrigger), Map.of("c", counter),
                Map.of(), KNOWN);
        assertEquals(Set.of(ProcValidator.UNKNOWN_COUNTER, ProcValidator.UNKNOWN_EFFECT, ProcValidator.UNKNOWN_FAMILY,
                ProcValidator.UNKNOWN_STAT, ProcValidator.UNKNOWN_INTERACTION, ProcValidator.UNKNOWN_TRIGGER),
                codes(findings, Severity.ERROR));
        assertEquals(Set.of(), codes(findings, Severity.WARNING));
    }

    @Test
    void aProcThatCannotRunOrNoPickReaches_isAWarning() throws IOException {
        ProcAsset unreachable = proc("{ \"Trigger\": \"Dodge\","
                + " \"Requires\": { \"Factors\": [ { \"Factor\": \"ziggfreedcommon:track_pick\", \"Param\": \"Swords/Gone\","
                + " \"Min\": 1 } ] },"
                + " \"Hit\": { \"Projectile\": true }, \"Like\": \"Missing_Line\" }");
        List<Finding> findings = ProcValidator.audit(Map.of("a", unreachable), Map.of(), Map.of(), KNOWN);
        assertEquals(Set.of(ProcValidator.UNREACHABLE_PICK, ProcValidator.HIT_WITHOUT_HIT, ProcValidator.LIKE_NO_LINE,
                ProcValidator.NO_ACTIONS), codes(findings, Severity.WARNING));
        assertTrue(codes(findings, Severity.ERROR).isEmpty());
    }

    @Test
    void aSwitchedOffOrSkeletonFile_isNotAudited() throws IOException {
        ProcAsset off = proc("{ \"Trigger\": \"Sneeze\", \"Enabled\": false }");
        ProcAsset skeleton = proc("{ \"Abstract\": true }");
        assertEquals(List.of(), ProcValidator.audit(Map.of("a", off, "b", skeleton), Map.of(), Map.of(), KNOWN));
    }

    private static Set<String> codes(List<Finding> findings, Severity severity) {
        Set<String> out = new TreeSet<>();
        for (Finding finding : findings) {
            if (finding.severity() == severity) {
                out.add(finding.code());
            }
        }
        return out;
    }

    private static ProcAsset proc(String json) throws IOException {
        ProcAsset noParent = null;
        return ProcAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ProcAsset.class, "Your_Proc", null)));
    }

    private static CounterAsset counter(String json) throws IOException {
        CounterAsset noParent = null;
        return CounterAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(CounterAsset.class, "Your_Counter", null)));
    }

    private static HeldEffectAsset held(String json) throws IOException {
        HeldEffectAsset noParent = null;
        return HeldEffectAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(HeldEffectAsset.class, "Your_Held", null)));
    }
}
