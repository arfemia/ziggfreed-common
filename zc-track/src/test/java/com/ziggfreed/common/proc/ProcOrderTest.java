package com.ziggfreed.common.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The order contract content leans on: the procs answering one moment run in the order of their ids compared
 * lower-cased, whatever case the files spell them in and whatever order the packs handed them over (a shatter proc is
 * named to run before every proc that freezes, so a freeze the same hit lays is never broken by it).
 */
class ProcOrderTest {

    @Test
    void procsOnOneMoment_runInLowerCasedIdOrder_whateverTheirCaseOrLoadOrder() throws IOException {
        Map<String, ProcAsset> loaded = new LinkedHashMap<>();
        loaded.put("Mmo_Frostweaving_Freeze", hit());
        loaded.put("mmo_frostweaving_break_shatter", hit());
        loaded.put("MMO_Frostweaving_Chill", hit());
        loaded.put("Mmo_Arcana_Drain", hit());
        loaded.put("Mmo_Frostweaving_Break_1", hit());
        List<String> order = new ArrayList<>();
        for (ProcConfig.Def def : ProcConfig.fold(loaded).get(ProcTrigger.HIT)) {
            order.add(def.key());
        }
        assertEquals(List.of("mmo_arcana_drain", "mmo_frostweaving_break_1", "mmo_frostweaving_break_shatter",
                "mmo_frostweaving_chill", "mmo_frostweaving_freeze"), order);
    }

    @Test
    void aProcOnAnotherMoment_orSwitchedOff_keepsOutOfTheList() throws IOException {
        Map<String, ProcAsset> loaded = new LinkedHashMap<>();
        loaded.put("b", hit());
        loaded.put("a", proc("{ \"Trigger\": \"Dodge\" }"));
        loaded.put("c", proc("{ \"Trigger\": \"Hit\", \"Enabled\": false }"));
        assertEquals(List.of("b"), ProcConfig.fold(loaded).get(ProcTrigger.HIT).stream().map(ProcConfig.Def::key)
                .toList());
    }

    /**
     * ProcStrength counts once: a counter's add stores the authored share of the hit whatever the strength, and the
     * effect that spends it takes the strength on its damage multiplier, so a share struck at strength {@code s} lands
     * {@code s} times the share, never {@code s} squared.
     */
    @Test
    void aStoredShareOfAHit_isScaledByStrengthOnceWhereItIsSpent() {
        double strength = 1.5;
        double stored = ProcActions.counterAdd(0.5, true, 40.0);
        assertEquals(20.0, stored, 1e-9, "the add stores half the hit, unscaled");
        assertEquals(30.0, ProcActions.scaledMultiplier(stored, strength), 1e-9,
                "the spend takes the strength once: 1.5 times the share");
        assertEquals(3.0, ProcActions.counterAdd(3.0, false, 40.0), 1e-9, "a plain add is the add");
        assertEquals(0.0, ProcActions.scaledMultiplier(-2.0, strength), 1e-9, "never below nothing");
    }

    /** A refund of a cost never grows with strength: gear must not make a dodge's refund pay more than the dodge. */
    @Test
    void aRefundOfACost_neverGrowsWithStrength() throws IOException {
        assertEquals(2.0, ProcActions.statAmount(2.0, false, 1.8), 1e-9, "a refund is what was spent");
        assertEquals(3.6, ProcActions.statAmount(2.0, true, 1.8), 1e-9, "any other stat change takes the strength");
        ProcAction refund = proc("{ \"Trigger\": \"Dodge\", \"Actions\": [ { \"ChangeStat\": { \"Stat\": \"Stamina\","
                + " \"OnSelf\": true, \"Amount\": { \"Base\": 2 }, \"Scaled\": false } } ] }").actions().get(0);
        assertEquals(false, refund.changeStat().scaled());
        ProcAction heal = proc("{ \"Trigger\": \"Kill\", \"Actions\": [ { \"ChangeStat\": { \"Stat\": \"Health\","
                + " \"OnSelf\": true, \"Amount\": { \"Base\": 2 } } } ] }").actions().get(0);
        assertEquals(true, heal.changeStat().scaled(), "scaled unless written");
    }

    private static ProcAsset hit() throws IOException {
        return proc("{ \"Trigger\": \"Hit\" }");
    }

    private static ProcAsset proc(String json) throws IOException {
        ProcAsset noParent = null;
        return ProcAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ProcAsset.class, "Your_Proc", null)));
    }
}
