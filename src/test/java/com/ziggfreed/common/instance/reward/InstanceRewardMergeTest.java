package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link InstanceReward#merge(List)}: a loot roll yields repeated picks of the same item, and the
 * results screen / claim store must collapse them into one entry per identity (the "four Moonbloom
 * chips" bug). Same {@code (kind, id, displayKey)} sums; COMMAND stays distinct; order is preserved.
 */
class InstanceRewardMergeTest {

    @Test
    void sumsSameItemQuantities() {
        List<InstanceReward> merged = InstanceReward.merge(List.of(
                InstanceReward.item("Moonbloom", 2, null),
                InstanceReward.item("Moonbloom", 3, null),
                InstanceReward.item("Moonbloom", 4, null)));
        assertEquals(1, merged.size());
        assertEquals("Moonbloom", merged.get(0).id());
        assertEquals(9, merged.get(0).quantity());
    }

    @Test
    void keepsDistinctIdsAndPreservesFirstSeenOrder() {
        List<InstanceReward> merged = InstanceReward.merge(List.of(
                InstanceReward.item("Moonbloom", 2, null),
                InstanceReward.item("Essence", 1, null),
                InstanceReward.item("Moonbloom", 3, null),
                InstanceReward.item("Mirebloom", 3, null)));
        assertEquals(3, merged.size());
        assertEquals("Moonbloom", merged.get(0).id());
        assertEquals(5, merged.get(0).quantity());
        assertEquals("Essence", merged.get(1).id());
        assertEquals("Mirebloom", merged.get(2).id());
    }

    @Test
    void differentDisplayKeyDoesNotMerge() {
        List<InstanceReward> merged = InstanceReward.merge(List.of(
                InstanceReward.item("Moonbloom", 2, "a.key"),
                InstanceReward.item("Moonbloom", 3, "b.key")));
        assertEquals(2, merged.size());
    }

    @Test
    void differentKindDoesNotMerge() {
        List<InstanceReward> merged = InstanceReward.merge(List.of(
                InstanceReward.item("token", 2, null),
                InstanceReward.currency("token", 3, null)));
        assertEquals(2, merged.size());
    }

    @Test
    void commandsNeverMerge() {
        List<InstanceReward> merged = InstanceReward.merge(List.of(
                InstanceReward.command("say hi", null),
                InstanceReward.command("say hi", null)));
        assertEquals(2, merged.size());
    }
}
