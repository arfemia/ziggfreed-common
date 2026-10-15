package com.ziggfreed.common.instance.leaderboard.statboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One group of a Records split as it is shown ({@link RecordGroupAsset}): a segment of the split's row.
 *
 * @param id      the file's id, lower-cased: what a thing names to sit in it
 * @param nameKey the translation key the segment is named by, or null to show the id
 * @param order   where the segment sits, lowest first
 * @param icon    the item the group is pictured by, or null for none
 */
public record RecordGroup(@Nonnull String id, @Nullable String nameKey, int order, @Nullable String icon) {
}
