package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The additive-by-contribution union ({@code LootTableConfig.resolveUnion}): several assets sharing one
 * logical {@code TableId} fold to one rollable table, entries concatenated, scalars from the base, and a
 * lone table folds to itself. This is the seam that lets one pack add entries to another's table without
 * overriding its file.
 */
class LootTableUnionTest {

    private static final LootTableConfig CFG = LootTableConfig.getInstance();

    @AfterEach
    void reset() {
        CFG.mergePackLayer(Map.of());
    }

    private static LootTable tbl(String sourceId, String tableId, String[] guaranteed, String[] pool,
                                 int rolls, int spb, int maxRolls) {
        return new LootTable(LootEntry.parseAll(guaranteed), LootEntry.parseAll(pool),
                rolls, spb, maxRolls, sourceId, tableId);
    }

    @Test
    void loneTableFoldsToItself() {
        Map<String, LootTable> layer = new LinkedHashMap<>();
        layer.put("chase_nightmare",
                tbl("chase_nightmare", "chase_nightmare", new String[]{"item Staple 1"}, new String[]{}, 2, 1800, 5));
        CFG.mergePackLayer(layer);

        LootTable u = CFG.resolveUnion("chase_nightmare");
        assertNotNull(u);
        assertEquals(2, u.rolls());
        assertEquals(1, u.guaranteed().size());
        assertEquals("chase_nightmare", u.tableId());
    }

    @Test
    void contributionUnionsEntriesAndKeepsBaseScalars() {
        Map<String, LootTable> layer = new LinkedHashMap<>();
        // Base (its own id == tableId) owns the scalars.
        layer.put("chase_nightmare",
                tbl("chase_nightmare", "chase_nightmare", new String[]{"item Staple 1"},
                        new String[]{"w10 item A 1"}, 2, 1800, 5));
        // A contributor keyed under a DIFFERENT source id, pointing at the same logical table, with
        // deliberately different (ignored) scalars.
        layer.put("mmo_chase_nightmare",
                tbl("mmo_chase_nightmare", "chase_nightmare", new String[]{"any item Consolation 1"},
                        new String[]{"w5 item B 1"}, 99, 1, 99));
        CFG.mergePackLayer(layer);

        LootTable u = CFG.resolveUnion("chase_nightmare");
        assertNotNull(u);
        // Scalars from the base only.
        assertEquals(2, u.rolls());
        assertEquals(1800, u.scorePerBonusRoll());
        assertEquals(5, u.maxRolls());
        // Entries from BOTH contributors.
        assertEquals(2, u.guaranteed().size());
        assertEquals(2, u.pool().size());
    }

    @Test
    void unionOrderIsStableAcrossContributorMapOrder() {
        // Same two contributors, inserted in the opposite order; the union must be identical (sorted by id).
        Map<String, LootTable> a = new LinkedHashMap<>();
        a.put("chase", tbl("chase", "chase", new String[]{}, new String[]{"w1 item A 1"}, 4, 0, 4));
        a.put("z_extra", tbl("z_extra", "chase", new String[]{}, new String[]{"w1 item B 1"}, 0, 0, 0));
        CFG.mergePackLayer(a);
        List<InstanceReward> first = CFG.resolveUnion("chase").roll(0, true, new Random(55));

        Map<String, LootTable> b = new LinkedHashMap<>();
        b.put("z_extra", tbl("z_extra", "chase", new String[]{}, new String[]{"w1 item B 1"}, 0, 0, 0));
        b.put("chase", tbl("chase", "chase", new String[]{}, new String[]{"w1 item A 1"}, 4, 0, 4));
        CFG.mergePackLayer(b);
        List<InstanceReward> second = CFG.resolveUnion("chase").roll(0, true, new Random(55));

        assertEquals(first, second);
    }

    @Test
    void unknownTableIdIsNull() {
        CFG.mergePackLayer(Map.of());
        assertNull(CFG.resolveUnion("nope"));
    }

    @Test
    void contributorOnlyTableStillResolves() {
        // No asset's own id equals the logical id (only contributors); the first-by-id becomes the base.
        Map<String, LootTable> layer = new LinkedHashMap<>();
        layer.put("mmo_a", tbl("mmo_a", "shared", new String[]{"any item A 1"}, new String[]{}, 3, 0, 3));
        CFG.mergePackLayer(layer);
        LootTable u = CFG.resolveUnion("shared");
        assertNotNull(u);
        assertTrue(u.guaranteed().size() == 1);
        assertEquals(3, u.rolls());
    }
}
