package com.ziggfreed.common.quest.asset;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Answers "what values does this axis have?" for a quest generator whose axis names a
 * {@code Source} instead of listing its values by hand.
 *
 * <p>A mod registers one per list it can enumerate - the ores it ships, the regions it defines, the
 * tiers it knows about - and content then writes one generator that stays right as that list grows.
 *
 * <pre>{@code
 * enumerators.register("yourmod:ores", filter ->
 *         ores(filter.get("Rarity")).stream().map(QuestAxisRow::of).toList());
 * }</pre>
 *
 * <p>The {@code Filter} map is whatever the authored generator wrote under that axis, forwarded
 * verbatim: its keys mean exactly what the enumerator's owner documents and nothing here reads
 * them. Answering an empty list is legitimate (that axis produces nothing, so the generator emits
 * nothing) and is reported by the validator rather than treated as an error.
 *
 * <p>An enumerator is asked once per content load, never per player, but it must still be
 * side-effect free: a validator run asks the same question.
 */
@FunctionalInterface
public interface QuestValueEnumerator {

    /** The rows this source answers with, given the axis's authored filter (possibly empty). */
    @Nonnull
    List<QuestAxisRow> rows(@Nonnull Map<String, String> filter);
}
