package com.ziggfreed.common.objectives.authoring;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What the achievement editor collects, as the achievement file names it: the text keys, the listing, the points,
 * whether it is in circulation, the criteria under the keys they were minted with, the Claim rows by slot, and the
 * title it gives. Pure; {@link AchievementDraft} turns it into the achievement document and reads one back.
 *
 * @param titleKey         {@code Text.TitleKey}, or null to write none (the convention line names it)
 * @param flavorKey        {@code Text.FlavorKey}, or null for none
 * @param category         {@code Listing.Category}, or null to leave it as written
 * @param subcategory      {@code Listing.Subcategory}, or null for none
 * @param points           {@code Scoring.Points}
 * @param enabled          false writes {@code Enabled: false}: out of circulation, kept by whoever earned it
 * @param criteria         the criteria, each under the key it was minted with, in slot order
 * @param droppedCriteria  keys of criteria the editor emptied or replaced, taken out of the file and retired
 * @param rewardSlots      how many reward rows the editor shows: {@code Rewards.Claim} rows past them are kept
 * @param rewards          the reward rows the editor filled, each at its slot (from 0), every one a Claim
 * @param title            the title the achievement gives, its one {@code Auto} row, or null for none
 */
public record AchievementForm(@Nullable String titleKey, @Nullable String flavorKey, @Nullable String category,
        @Nullable String subcategory, int points, boolean enabled, @Nonnull List<KeyedRows.Row> criteria,
        @Nonnull List<String> droppedCriteria, int rewardSlots, @Nonnull List<ClaimSlots.Slot> rewards,
        @Nullable String title) {

    public AchievementForm {
        criteria = List.copyOf(criteria);
        droppedCriteria = List.copyOf(droppedCriteria);
        rewards = List.copyOf(rewards);
    }
}
