package com.ziggfreed.common.board;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.rotation.SelectionSpec;

/**
 * What the board engine needs to know about one board. The seam between the ENGINE and whatever
 * authored it.
 *
 * <p>A board is a rotating VIEW over the bounty pool and nothing more: when it turns over, how it
 * draws, which positions it fills, what a reroll costs, and who may take what off it. Its title,
 * its icon, its ordering and which worlds it exists in belong to the authoring layer.
 *
 * <p><b>{@link #acceptRequires} is a map of ORDINARY requirement blocks, keyed by grade.</b> The
 * schema learns nothing about levels or power: a board that gates its hard contracts writes a
 * shared {@code Requires} block against whatever factor its author likes, and the same evaluator a
 * quest accept uses answers it. Checked at ACCEPT only, never in the draw, so a locked contract is
 * still shown, still legible, and still something to go and earn.
 */
public interface BoardSpec {

    /**
     * The id this board is known by, and the SITE a bounty taken off it is bound to. Any placed
     * board of this id answers for it, so a player who took a contract at one board can hand it in
     * at another of the same board.
     */
    @Nonnull
    String boardId();

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

    /** The positions it fills. Empty draws {@link #defaultCount()} from the whole pool. */
    @Nonnull
    default List<PoolSlot> slots() {
        return List.of();
    }

    /** How many to draw when no slots are authored. */
    default int defaultCount() {
        return 5;
    }

    /** What a reroll costs and how many a period allows, or null when the board offers none. */
    @Nullable
    default RerollSpec reroll() {
        return null;
    }

    /**
     * Who may accept a contract of each grade, keyed by the grade a membership carries and matched
     * case-insensitively. A grade with no entry is open to everyone.
     */
    @Nonnull
    default Map<String, GateSpec> acceptRequires() {
        return Map.of();
    }

    /** Who may use the board at all, as the ONE shared requirement block. Null asks for nothing. */
    @Nullable
    default GateSpec requires() {
        return null;
    }

    /** False takes the board out of use without deleting the file. */
    default boolean enabled() {
        return true;
    }

    /** The currencies a board's balance strip shows, in authored order. */
    @Nonnull
    default Collection<String> currencies() {
        return List.of();
    }
}
