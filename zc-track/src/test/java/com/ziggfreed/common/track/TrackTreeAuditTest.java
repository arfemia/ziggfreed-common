package com.ziggfreed.common.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The tree audit's mechanics over a small tree (R736, R649, R757): a row the nodes below can never open, one opened
 * with too little spare, a branch short of its template, a one-face node, a starter build that stops on a gate, a
 * stat one build raises past its ceiling (a knapsack over the earned points, not every node at once) and a node
 * granting what is no stat. A tree that keeps every rule reports nothing.
 */
class TrackTreeAuditTest {

    private static final String TRACK = "{ \"Points\": { \"PerLevel\": 1, \"To\": 12,"
            + " \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Health\", \"Add\": 0.5 } ] },"
            + " \"Branches\": { \"Guard\": {}, \"Swift\": {} },"
            + " \"Tree\": { \"BranchPoints\": 9, \"GateSlack\": 2, \"Ceilings\": { \"Health\": 10 } },"
            + " \"Starter\": [ \"Your_Wall\", \"Your_Stand\" ] }";

    /**
     * A grant kind that writes no stat, standing for any a module registers (the library ships Stat alone since the
     * WeakSpot kind retired into stats, R757): the audit tells a node granting one.
     */
    static final class Marker extends Grant {
        static final String KIND = "Test_Marker";
        static final BuilderCodec<Marker> CODEC = BuilderCodec.builder(Marker.class, Marker::new).build();

        @Override
        public String kind() {
            return KIND;
        }
    }

    /** A module's kind joins the union at its setup; Stat is in it from class init. */
    @BeforeAll
    static void aMarkerKindReads() {
        Grant.CODEC.register(Marker.KIND, Marker.class, Marker.CODEC);
    }

    @Test
    void aTreeBreakingEachRuleIsToldEach() throws IOException {
        Map<String, String> picks = new LinkedHashMap<>();
        picks.put("Your_Wall", "{ \"Branch\": \"Guard\", \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 3 },"
                + " \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Health\", \"Add\": 2 } ] }");
        picks.put("Your_Grit", "{ \"Branch\": \"Guard\", \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 2 } }");
        picks.put("Your_Stand", gated("Guard", 4, "\"Node\": [ \"Your_Stance\" ], \"Cost\": { \"Points\": 2 }"));
        picks.put("Your_Rush", gated("Guard", 4, "\"Node\": [ \"Your_Stance\" ], \"Cost\": { \"Points\": 2 }"));
        picks.put("Your_Dash", gated("Swift", 3, "\"Cost\": { \"Points\": 1 }, \"Node\": [ \"Your_Lonely\" ],"
                + " \"Grants\": [ { \"Kind\": \"Test_Marker\" } ]"));
        Map<String, Integer> codes = codes(TrackTreeAudit.audit(List.of(track(TRACK)), fold(picks)));
        assertEquals(Integer.valueOf(2), codes.get(TrackTreeAudit.BRANCH_POINTS_OFF), "Guard adds up to 7, Swift to 1");
        assertEquals(Integer.valueOf(1), codes.get(TrackTreeAudit.GATE_SLACK_THIN),
                "Guard's row at 4 has 5 below it, 1 spare: one finding for the two faces of the row");
        assertEquals(Integer.valueOf(1), codes.get(TrackTreeAudit.GATE_OUT_OF_REACH), "Swift's row at 3 has nothing below");
        assertEquals(Integer.valueOf(1), codes.get(TrackTreeAudit.NODE_ONE_FACE));
        assertEquals(Integer.valueOf(1), codes.get(TrackTreeAudit.STARTER_STOPS), "the Stand needs 4 in Guard first");
        assertEquals(Integer.valueOf(1), codes.get(TrackTreeAudit.STAT_PAST_CEILING),
                "11 points: 5.5 Health from the points and 6 from three Walls, past 10");
        assertEquals(Integer.valueOf(1), codes.get(TrackTreeAudit.NON_STAT_GRANT));
    }

    @Test
    void aTreeKeepingEveryRuleSaysNothing() throws IOException {
        String track = TRACK.replace("\"BranchPoints\": 9", "\"BranchPoints\": 7").replace("\"Swift\": {}, ", "")
                .replace(", \"Swift\": {}", "").replace("\"Health\": 10", "\"Health\": 12")
                .replace("\"GateSlack\": 2", "\"GateSlack\": 1")
                .replace("[ \"Your_Wall\", \"Your_Stand\" ]",
                        "[ \"Your_Wall\", \"Your_Wall\", \"Your_Wall\", \"Your_Grit\", \"Your_Grit\", \"Your_Stand\","
                                + " \"Your_Root\", \"Your_Root\", \"Your_Root\", \"Your_Root\" ]");
        Map<String, String> picks = new LinkedHashMap<>();
        picks.put("Your_Wall", "{ \"Branch\": \"Guard\", \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 3 },"
                + " \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Health\", \"Add\": 2 } ] }");
        picks.put("Your_Grit", "{ \"Branch\": \"Guard\", \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 2 } }");
        picks.put("Your_Stand", gated("Guard", 4, "\"Node\": [ \"Your_Stance\" ], \"Cost\": { \"Points\": 2 }"));
        picks.put("Your_Rush", gated("Guard", 4, "\"Node\": [ \"Your_Stance\" ], \"Cost\": { \"Points\": 2 }"));
        picks.put("Your_Root", "{ \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 4 } }");
        List<Finding> findings = TrackTreeAudit.audit(List.of(track(track)), fold(picks));
        assertTrue(findings.isEmpty(), () -> "a tree within every rule: " + findings);
    }

    /**
     * A node shipped switched off in its own file for a later pass is in the tree as authored: its branch still adds up
     * to its 56, the row above it still opens, and the starter build walks through it, said once at INFO (a claim today
     * passes the off node over). Only the live fold, or an owner's word switching it off, leaves it out.
     */
    @Test
    void aBranchWithAnOffNodeStillTotalsItsPoints() throws IOException {
        List<String> steps = new ArrayList<>();
        steps.addAll(Collections.nCopies(20, "\"Your_Wall\""));
        steps.addAll(Collections.nCopies(10, "\"Your_Later\""));
        steps.addAll(Collections.nCopies(4, "\"Your_Top\""));
        TrackAsset track = track("{ \"Points\": { \"PerLevel\": 1, \"To\": 57 }, \"Branches\": { \"Guard\": {} },"
                + " \"Tree\": { \"BranchPoints\": 56 }, \"Starter\": [ " + String.join(", ", steps) + " ] }");
        Map<String, String> picks = new LinkedHashMap<>();
        picks.put("Your_Wall", "{ \"Branch\": \"Guard\", \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 20 } }");
        picks.put("Your_Later", gated("Guard", 20,
                "\"Enabled\": false, \"Cost\": { \"Points\": 2 }, \"Repeat\": { \"Max\": 10 }"));
        picks.put("Your_Top", gated("Guard", 40, "\"Cost\": { \"Points\": 4 }, \"Repeat\": { \"Max\": 4 }"));

        List<Finding> authored = TrackTreeAudit.audit(List.of(track), foldAuthored(picks, id -> true));
        assertEquals(Map.of(TrackTreeAudit.STARTER_WALKS_OFF, 1), codes(authored),
                () -> "20 + 20 off + 16 is 56, every row reachable, the starter whole, and one INFO that ten of its "
                        + "steps walk the off node: " + authored);
        assertEquals(Severity.INFO, authored.get(0).severity(), "development stays green on it");
        assertTrue(authored.get(0).message().contains("walks 10 step(s) through 1 node(s)"), authored.get(0).message());

        Map<String, Integer> live = codes(TrackTreeAudit.audit(List.of(track), fold(picks)));
        assertEquals(Integer.valueOf(1), live.get(TrackTreeAudit.BRANCH_POINTS_OFF), "36 live points");
        assertEquals(Integer.valueOf(1), live.get(TrackTreeAudit.GATE_OUT_OF_REACH), "20 live below the row at 40");
        assertEquals(Integer.valueOf(1), live.get(TrackTreeAudit.STARTER_STOPS), "the Top waits on 40 in Guard");
        assertEquals(live, codes(TrackTreeAudit.audit(List.of(track), foldAuthored(picks, id -> false))),
                "a pick an owner's word switched off stays out of the tree as authored");
    }

    private static String gated(String branch, int min, String rest) {
        return "{ \"Branch\": \"" + branch + "\", " + rest + ", \"Requires\": { \"Factors\": [ { \"Factor\": \""
                + TrackFactors.BRANCH_SPENT + "\", \"Param\": \"Your_Sample:" + branch + "\", \"Min\": " + min
                + " } ] } }";
    }

    private static Map<String, Integer> codes(List<Finding> findings) {
        Map<String, Integer> out = new TreeMap<>();
        for (Finding finding : findings) {
            out.merge(finding.code(), 1, Integer::sum);
        }
        return out;
    }

    private static TrackAsset track(String json) throws IOException {
        return TrackAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(TrackAsset.class, "Your_Sample", null)));
    }

    private static List<TrackPicks.PickDef> fold(Map<String, String> files) throws IOException {
        TrackAsset sample = track(TRACK);
        return List.copyOf(TrackPicks.fold(decode(files), id -> null,
                id -> "your_sample".equals(TrackKey.normalize(id)) ? sample : null).byId().values());
    }

    /** The tree as authored from {@code files}, an off pick in it where {@code offInOwnFile} says so. */
    private static List<TrackPicks.PickDef> foldAuthored(Map<String, String> files, Predicate<String> offInOwnFile)
            throws IOException {
        TrackAsset sample = track(TRACK);
        return List.copyOf(TrackPicks.foldAuthored(decode(files), id -> null,
                id -> "your_sample".equals(TrackKey.normalize(id)) ? sample : null, offInOwnFile).byId().values());
    }

    private static Map<String, TrackPickAsset> decode(Map<String, String> files) throws IOException {
        Map<String, TrackPickAsset> picks = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String json = file.getValue().replaceFirst("\\{", "{ \"Track\": \"Your_Sample\", ");
            picks.put(file.getKey(), TrackPickAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(TrackPickAsset.class, file.getKey(), null))));
        }
        return picks;
    }
}
