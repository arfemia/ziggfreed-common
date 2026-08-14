package com.ziggfreed.common.shop;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.rotation.SelectionSpec;

/**
 * What the purchase engine needs to know about one rotating SHELF. The seam between the ENGINE and
 * whatever authored it, exactly as {@link ShopOffer} is for one thing on sale.
 *
 * <p>A shelf is a rotating view over the offers tagged to it and nothing more: when it turns over,
 * how it draws, which positions it fills, and what a reroll costs. What it is CALLED, which
 * storefront it stands in and where it sorts belong to the authoring layer, and asking about them
 * here would drag every one of them into the engine.
 *
 * <p>It is the storefront twin of {@code board.BoardSpec}, down to the leaf names, because the two
 * are the same rotating primitive pointed at different content - and two spellings of "how often
 * does this turn over" is exactly the drift the shared {@code rotation} package exists to prevent.
 */
public interface ShopShelf {

    /**
     * The id this shelf is known by, and the key its rotation and reroll state are recorded under.
     * Stable across restarts, because the state is.
     */
    @Nonnull
    String shelfId();

    /** When it turns over. Defaults to a daily rotation. */
    @Nonnull
    default RotationSpec rotation() {
        return RotationSpec.daily();
    }

    /** How it draws. Defaults to a weighted draw keyed on the period. */
    @Nonnull
    default SelectionSpec selection() {
        return SelectionSpec.DEFAULT;
    }

    /** The positions it fills. Empty draws {@link #defaultCount()} from every candidate. */
    @Nonnull
    default List<PoolSlot> slots() {
        return List.of();
    }

    /** How many to draw when no slots are authored. */
    default int defaultCount() {
        return 4;
    }

    /** What a reroll costs and how many a period allows, or null when the shelf offers none. */
    @Nullable
    default RerollSpec reroll() {
        return null;
    }

    /** False takes the shelf off the page without deleting the file. */
    default boolean enabled() {
        return true;
    }
}
