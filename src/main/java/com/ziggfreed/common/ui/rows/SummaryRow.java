package com.ziggfreed.common.ui.rows;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * ONE reward/summary-LEDGER row: an icon item id plus one already-composed, already-styled
 * {@link Message} line, tagged with a semantic {@link Kind} the row came from. A clean, transport
 * -agnostic value (no consumer / HUD / page imports beyond the engine {@link Message}) that a
 * fixed-slot ledger surface paints via {@link SummaryRowRenderer}.
 *
 * <p><b>Sibling to {@code ui/toast/ToastLine}, not the same model (deliberate).</b> A
 * {@code ToastLine} is a TRANSIENT overlay line welded to the toast transport (its
 * {@code ToastController}/{@code ToastRenderer}/{@code ToastStore} lifecycle, a {@code quantity}
 * badge field, a {@code ToastKind} that also drives a per-kind SFX). A {@code SummaryRow} is a
 * PERSISTENT fixed-slot ledger row painted by a generic parameterized-selector renderer with
 * overflow - a different rendering contract. Keeping them as siblings avoids forcing the toast's
 * transport semantics onto a plain ledger (or dragging the fixed-slot painter into the toast
 * model); a consumer that wants an icon + rich-text ledger uses these, one that wants a transient
 * toast uses {@code ToastLine}. Both are the SAME shape at heart - an optional icon id + a
 * client-resolved {@link Message} - so a consumer maps its own reward / feedback / stat rows into
 * whichever fits.
 *
 * <p>All display text / colour / bold is the CALLER's job (skill / item / currency icon
 * resolution, name resolution, {@link Message#color}/{@link Message#bold} composition); this type
 * and {@link SummaryRowRenderer} only carry / paint the result. {@code kind} is descriptive
 * metadata for the caller (deciding fixed-slot ordering or a per-kind icon fallback); {@link
 * SummaryRowRenderer} does NOT branch on it - every row renders identically (icon + {@code
 * TextSpans}), so a row's look is entirely how its {@link #text()} was composed. The values are
 * generic reward-ledger roles ({@link Kind#XP} progression, {@link Kind#CONSUMED} spent, {@link
 * Kind#PRODUCED} gained, {@link Kind#LUCKY} bonus), not tied to any one consumer's domain.
 */
public final class SummaryRow {

    /** Semantic role a row was built from; purely descriptive, not read by the renderer. */
    public enum Kind {
        XP, CONSUMED, PRODUCED, LUCKY
    }

    @Nonnull
    private final String iconItemId;
    @Nonnull
    private final Message text;
    @Nonnull
    private final Kind kind;

    public SummaryRow(@Nonnull String iconItemId, @Nonnull Message text, @Nonnull Kind kind) {
        this.iconItemId = iconItemId;
        this.text = text;
        this.kind = kind;
    }

    /** The item id to show in the row's {@code ItemGrid} icon slot. */
    @Nonnull
    public String iconItemId() {
        return iconItemId;
    }

    /** The row's full display line, already localized and already styled by the caller. */
    @Nonnull
    public Message text() {
        return text;
    }

    @Nonnull
    public Kind kind() {
        return kind;
    }
}
