package com.ziggfreed.common.loot.stamp;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * The WRITE boundary for stamped stats: the one place that decides how an item carries them.
 *
 * <p>Everything else in this package is arithmetic - pick entries, roll points, hold the result
 * inside its budgets - and that arithmetic must not care whether the numbers end up in stack
 * metadata, in a display line, or somewhere a richer mod invented. So it does not: it produces a
 * {@link StatRoll} list and hands it here.
 *
 * <h2>The write is display-blind; the stamper may still NAME what it wrote</h2>
 *
 * <p>{@link #apply} decides where the numbers go and nothing else - it never renders. But a stat's
 * NAME belongs to the same place its meaning does, and that is here: the stamper is the one object
 * that knows {@code Swing_Speed} is "Attack Speed", in what colour, and in the player's own locale.
 * So {@link #describe} lets it say so, and the engines that report an enhancement ask for it instead
 * of either inventing a vocabulary they do not own or printing a raw id at a player.
 *
 * <p>It is optional on purpose. The default answers null, and a caller that gets null falls back to
 * its own plain report - so a server with the library's bare stamper still says what a ritual did,
 * just without the wording a richer mod would give it.
 *
 * <p>That split is what lets several mods stamp the same item without fighting. As long as they
 * agree on ONE stamper, every budget check reads the same history the last write left, and cap
 * accounting holds no matter which of them stamped the item first.
 *
 * <p><b>Items are immutable.</b> {@link #apply} returns a NEW stack; the caller must use the return
 * value and must not assume the one it passed in changed.
 */
public interface Stamper {

    /** What {@code stack} already carries - the history every budget is measured against. */
    @Nonnull
    StampInspection inspect(@Nonnull ItemStack stack);

    /**
     * Write {@code entries} onto {@code stack} and answer the resulting stack. The entries have
     * ALREADY been rolled and held inside their budgets, so a stamper writes them as given; it never
     * re-derives a cap of its own.
     */
    @Nonnull
    ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries);

    /**
     * {@link #apply} plus the authored identity: a rename and a rarity, both optional.
     *
     * <p>Separate from the entries because it is not a stat and never was: nothing here is rolled,
     * budgeted or capped. It is what a pool or a stamp SAID this item should be called and look
     * like, and a stamper that has no opinion on identity keeps the default and writes the stats.
     */
    @Nonnull
    default ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries,
            @Nullable StampIdentity identity) {
        return apply(stack, entries);
    }

    /**
     * How {@code entry} reads to a player - a fully-styled, client-resolved line naming the stat and
     * its points - or null when this stamper has no wording for it.
     *
     * <p>Asked AFTER the entry was written, by whatever reports the enhancement. Null is a normal
     * answer, not a failure: the caller then says the plain true thing (the stat id and its points)
     * rather than pretending to know a vocabulary it does not own. Implement it whenever the stat
     * ids are yours, so one surface's wording and colour is every surface's.
     */
    @Nullable
    default Message describe(@Nonnull StatRoll entry) {
        return null;
    }
}
