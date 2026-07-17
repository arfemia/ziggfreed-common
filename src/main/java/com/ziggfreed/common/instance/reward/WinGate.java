package com.ziggfreed.common.instance.reward;

/**
 * A per-entry outcome gate on a {@link LootEntry}: whether the entry is eligible on a WIN, a LOSS, or
 * either. It lets one loot table pay a consolation / participation reward on a loss without also
 * handing out the win-only spoils - the "keep the win economy intact, add a loss payout" seam.
 *
 * <p>The default when an entry authors no gate is {@link #WIN} (see {@link LootEntry}): this loot
 * table is rolled at a win/lose instance's resolve, and the historical behaviour gated the whole
 * table ON_WIN at the preset level, so an un-annotated entry stays win-only. A consumer marks an
 * entry {@code loss} or {@code any} to pay it on a loss.
 */
public enum WinGate {
    /** Eligible on both a win and a loss. */
    ANY,
    /** Eligible only when the player won (the default). */
    WIN,
    /** Eligible only when the player lost (a consolation / participation payout). */
    LOSS;

    /** True when this gate admits the given outcome. */
    public boolean matches(boolean win) {
        return this == ANY || (win ? this == WIN : this == LOSS);
    }
}
