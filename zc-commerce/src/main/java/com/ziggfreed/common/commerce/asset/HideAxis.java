package com.ziggfreed.common.commerce.asset;

import javax.annotation.Nullable;

import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * The HIDE axis every commerce type reads off its own {@code Requires} block: whether a storefront,
 * a board, an offer, a contract or a wallet EXISTS on this server right now, as opposed to whether
 * one player has earned their way to it.
 *
 * <p>A plain top-level feature condition ({@code <namespace>:feature} of a namespace that has
 * declared features, bounds-less or {@code Min: 1}) or mod-presence condition
 * ({@code hytale:mod_installed}) answers that question, through the ONE lift the quest and
 * achievement folds use ({@link FeatureLift#liftKnown}), so a seasonal file hides the same way
 * whatever kind of content it is. Everything else in the block is the LOCK: what a player must
 * meet, still shown to them with its reason.
 *
 * <p>Both reads are live. The lift is taken at the moment of the call, so a feature toggled while
 * the server is up, or a namespace declared after the content loaded, moves the content on the next
 * look with nothing to rebuild. A feature factor of a namespace nothing has declared is not lifted:
 * it stays in the lock, where fail-closed keeps the content locked rather than hidden.
 */
public final class HideAxis {

    private HideAxis() {
    }

    /**
     * Is content with this {@code Enabled} switch and this {@code Requires} block present right now?
     * False when it is switched off, or when any lifted condition reads off at this moment.
     */
    public static boolean present(boolean enabled, @Nullable GateSpec requires) {
        return enabled && FeatureLift.allOn(FeatureLift.liftKnown(requires).lifted());
    }

    /**
     * The part of {@code requires} that LOCKS rather than hides: the block with every lifted
     * condition taken out, or null when nothing is left to ask. The same instance comes back when
     * nothing was lifted.
     */
    @Nullable
    public static GateSpec lock(@Nullable GateSpec requires) {
        return FeatureLift.liftKnown(requires).requires();
    }
}
