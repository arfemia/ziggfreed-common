package com.ziggfreed.common.ui.rows;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * ONE reward/summary-LEDGER row: an icon item id plus one already-composed, already-styled
 * {@link Message} line (optionally a SECOND, subordinate line - see {@link #subText()}), tagged
 * with a semantic {@link Kind} the row came from. A clean, transport
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
 * <p>{@link Kind#ENHANCE} is the generic "an existing item was modified in place" ledger role
 * (a stamp/durability/enchant style outcome line), sitting alongside {@code CONSUMED}/{@code
 * PRODUCED} for a consumer whose session summary needs to distinguish "changed" from "spent" or
 * "gained".
 *
 * <p>All display text / colour / bold is the CALLER's job (skill / item / currency icon
 * resolution, name resolution, {@link Message#color}/{@link Message#bold} composition); this type
 * and {@link SummaryRowRenderer} only carry / paint the result. {@code kind} is descriptive
 * metadata for the caller (deciding fixed-slot ordering or a per-kind icon fallback); {@link
 * SummaryRowRenderer} does NOT branch on it - every row renders identically (icon + {@code
 * TextSpans}), so a row's look is entirely how its {@link #text()} was composed. The values are
 * generic reward-ledger roles ({@link Kind#PROGRESS} advancement, {@link Kind#CONSUMED} spent,
 * {@link Kind#PRODUCED} gained, {@link Kind#LUCKY} bonus), not tied to any one consumer's domain.
 *
 * <p><b>The optional second line.</b> A row whose headline needs a subordinate detail line (a
 * factor breakdown behind a number, a source attribution, a smaller caption) carries it as
 * {@link #subText()} rather than concatenating it into {@link #text()}: two Messages on two
 * Labels is the only way the detail line can render at its own SMALLER font, since the native
 * rich-text markup set ({@code <color is>}/{@code <b>}/{@code <i>}/{@code \n}) has no font-size
 * tag - an embedded {@code \n} would wrap to a second line at the SAME size. A consumer that
 * wants the second line declares a sub Label in its own row slots and names it when rendering
 * (see {@link SummaryRowRenderer#render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder,
 * String, int, java.util.List, String)}); a consumer that does not is entirely unaffected.
 */
public final class SummaryRow {

    /** Semantic role a row was built from; purely descriptive, not read by the renderer. */
    public enum Kind {
        PROGRESS, CONSUMED, PRODUCED, LUCKY, ENHANCE
    }

    @Nonnull
    private final String iconItemId;
    @Nonnull
    private final Message text;
    @Nullable
    private final Message subText;
    @Nonnull
    private final Kind kind;

    /** A single-line row. */
    public SummaryRow(@Nonnull String iconItemId, @Nonnull Message text, @Nonnull Kind kind) {
        this(iconItemId, text, null, kind);
    }

    /** A row with an optional subordinate second line ({@code subText} null = single-line). */
    public SummaryRow(@Nonnull String iconItemId, @Nonnull Message text, @Nullable Message subText,
            @Nonnull Kind kind) {
        this.iconItemId = iconItemId;
        this.text = text;
        this.subText = subText;
        this.kind = kind;
    }

    /** The item id to show in the row's {@code ItemGrid} icon slot. */
    @Nonnull
    public String iconItemId() {
        return iconItemId;
    }

    /** The row's headline display line, already localized and already styled by the caller. */
    @Nonnull
    public Message text() {
        return text;
    }

    /**
     * The row's optional second line, rendered beneath {@link #text()} at whatever smaller style
     * the consumer's own sub Label declares; {@code null} for a single-line row (which hides that
     * Label). Already localized and already styled by the caller, same as {@link #text()}.
     */
    @Nullable
    public Message subText() {
        return subText;
    }

    @Nonnull
    public Kind kind() {
        return kind;
    }
}
