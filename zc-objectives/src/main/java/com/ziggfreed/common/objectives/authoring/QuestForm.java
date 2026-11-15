package com.ziggfreed.common.objectives.authoring;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What the quest editor collects, as the quest file names it: the text keys, the giver, the steps under the keys they
 * were minted with, the reward rows by slot, the requirements and the repeat rule. Pure; {@link QuestDraft} turns it
 * into the quest document and reads one back into it.
 *
 * @param titleKey       {@code Text.TitleKey}, the registered key of the typed name
 * @param flavorKey      {@code Text.FlavorKey}, or null for none
 * @param giver          {@code Npc.ViewId}, or null for a quest taken from the book
 * @param returnToGiver  {@code TurnInAt} giver: the reward is collected back at the giver
 * @param sequential     {@code Flow.Sequential}
 * @param autoTrack      {@code Flow.AutoTrack}
 * @param steps          the steps, each under the key it was minted with, in slot order
 * @param droppedSteps   keys of steps the editor emptied, taken out of the file and retired
 * @param rewardSlots    how many reward rows the editor shows: {@code Rewards.Claim} rows past them are kept as written
 * @param rewards        the reward rows the editor filled, each at its slot (from 0)
 * @param requires       the requirements, or null to leave the file's {@code Requires} as written
 * @param repeat         the repeat rule
 */
public record QuestForm(@Nonnull String titleKey, @Nullable String flavorKey, @Nullable String giver,
        boolean returnToGiver, boolean sequential, boolean autoTrack, @Nonnull List<Step> steps,
        @Nonnull List<String> droppedSteps, int rewardSlots, @Nonnull List<Reward> rewards,
        @Nullable Requirements requires, @Nonnull Repeat repeat) {

    public QuestForm {
        steps = List.copyOf(steps);
        droppedSteps = List.copyOf(droppedSteps);
        rewards = List.copyOf(rewards);
    }

    /**
     * One step: the key its progress is saved under (minted once, never renamed) and the leaves the editor writes.
     *
     * @param target    {@code Target}, or null for any
     * @param matchMode {@code MatchMode}, or null for the default
     * @param textKey   {@code TextKey}, or null for none
     * @param replaces  the key of the step this one takes the place of (the same slot, saved as another kind), which
     *                  is retired; null for none
     */
    public record Step(@Nonnull String key, @Nonnull String kind, @Nullable String target, long amount,
            @Nullable String matchMode, @Nullable String textKey, @Nullable String replaces) {
    }

    /**
     * One reward row, always a Claim.
     *
     * @param slot    its place among the editor's reward rows, from 0
     * @param params  the parameters to write, by name
     * @param managed the parameter names the editor showed: each is written from {@code params} or taken out, and
     *                any other the row already wrote is kept
     * @param whole   true when {@code params} replaces the row's whole {@code Params} (a kind that describes none)
     */
    public record Reward(int slot, @Nonnull String kind, @Nonnull Map<String, String> params,
            @Nonnull Set<String> managed, boolean whole) implements ClaimSlots.Row {

        public Reward {
            params = new LinkedHashMap<>(params);
            managed = new LinkedHashSet<>(managed);
        }
    }

    /**
     * The requirements the editor writes, the whole {@code Requires} block: each part null when unasked.
     *
     * @param min the least the factor must read, or null for none
     */
    public record Requirements(@Nullable String quest, @Nullable String permission, @Nullable String factor,
            @Nullable String param, @Nullable Double min) {

        /** True when it asks for nothing. */
        public boolean isEmpty() {
            return quest == null && permission == null && factor == null;
        }
    }

    /**
     * The repeat rule: off writes no {@code Repeat}; on writes the block, which alone makes the quest repeatable.
     *
     * @param cooldownHours a rolling wait in whole hours, or 0 for none
     * @param period        {@code Reset.Period} (Daily or Weekly), or null for no calendar limit
     * @param maxCompletions a lifetime cap, or 0 for none
     */
    public record Repeat(boolean on, int cooldownHours, @Nullable String period, int maxCompletions) {

        /** A quest done once. */
        public static final Repeat ONCE = new Repeat(false, 0, null, 0);
    }
}
