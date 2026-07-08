package com.ziggfreed.common.ui.form;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One immutable {@link SettingsForm} field: an id, a {@link FieldKind}, an optional dotted
 * PascalCase leaf path (null for {@code HEADER}/{@code NOTE}/{@code TOGGLE}, which never collect a
 * leaf), a mod-agnostic label KEY (resolved by the CONSUMER's {@code Function<String, Message>} at
 * render time, never resolved here), and ({@code DROPDOWN} only) its literal entries.
 *
 * <p>Built only via the static factories below so every instance is well-formed for its kind; the
 * constructor stays private. This class carries no i18n keys of its own and no mod-specific types -
 * {@code labelKey} is an opaque string the consumer's own key space defines.
 */
public final class FieldSpec {

    private final String id;
    private final FieldKind kind;
    @Nullable private final String leafPath;
    private final String labelKey;
    @Nullable private final String[] values;
    @Nullable private final String hintKey;

    private FieldSpec(@Nonnull String id, @Nonnull FieldKind kind, @Nullable String leafPath,
            @Nonnull String labelKey, @Nullable String[] values, @Nullable String hintKey) {
        this.id = id;
        this.kind = kind;
        this.leafPath = leafPath;
        this.labelKey = labelKey;
        this.values = values == null ? null : values.clone();
        this.hintKey = hintKey;
    }

    /** A section heading row: label only, never collected. */
    @Nonnull
    public static FieldSpec header(@Nonnull String id, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.HEADER, null, labelKey, null, null);
    }

    /** A wrapping hint line: label only, never collected. */
    @Nonnull
    public static FieldSpec note(@Nonnull String id, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.NOTE, null, labelKey, null, null);
    }

    /** A free-string leaf. */
    @Nonnull
    public static FieldSpec text(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.TEXT, leafPath, labelKey, null, null);
    }

    /** A non-negative {@code double} leaf. */
    @Nonnull
    public static FieldSpec number(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.NUMBER, leafPath, labelKey, null, null);
    }

    /** A {@code double} leaf clamped to {@code 0..1}. */
    @Nonnull
    public static FieldSpec chance(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.CHANCE, leafPath, labelKey, null, null);
    }

    /** An {@code int} leaf of any sign (offsets may be negative). */
    @Nonnull
    public static FieldSpec integer(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.INT, leafPath, labelKey, null, null);
    }

    /** A comma-separated id list, collected as a {@code List<String>} leaf. */
    @Nonnull
    public static FieldSpec csv(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.CSV, leafPath, labelKey, null, null);
    }

    /** An instant on/off button. NO leaf path - a toggle is never collected by {@link SettingsForm#collectLeaves}. */
    @Nonnull
    public static FieldSpec toggle(@Nonnull String id, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.TOGGLE, null, labelKey, null, null);
    }

    /** An Inherit/On/Off dropdown, collected as {@code Boolean.TRUE}/{@code Boolean.FALSE}/{@code null}. */
    @Nonnull
    public static FieldSpec tristate(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey) {
        return new FieldSpec(id, FieldKind.TRISTATE, leafPath, labelKey, null, null);
    }

    /** A fixed literal-entry dropdown (label == value for each entry); collected as a {@code String} or {@code null}. */
    @Nonnull
    public static FieldSpec dropdown(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey,
            @Nonnull String[] values) {
        return new FieldSpec(id, FieldKind.DROPDOWN, leafPath, labelKey, values, null);
    }

    /**
     * Returns a COPY of this spec with {@code hintKey} set as its optional {@code #Hint} sub-label
     * key (resolved by the consumer's {@code Function<String, Message>} at render time, exactly like
     * {@link #labelKey()}). This spec is unchanged; {@code FieldSpec} stays immutable.
     *
     * @throws IllegalArgumentException if this spec is a {@code HEADER} or {@code NOTE} - their row
     *                                   templates carry no {@code #Hint} element
     */
    @Nonnull
    public FieldSpec withHint(@Nonnull String hintKey) {
        if (kind == FieldKind.HEADER || kind == FieldKind.NOTE) {
            throw new IllegalArgumentException(
                    "A " + kind + " field cannot carry a hint (its row template has no #Hint): " + id);
        }
        return new FieldSpec(id, kind, leafPath, labelKey, values, hintKey);
    }

    @Nonnull
    public String id() {
        return id;
    }

    @Nonnull
    public FieldKind kind() {
        return kind;
    }

    /** The dotted PascalCase {@code JsonOverrideWriter} leaf path, or null for a HEADER/NOTE/TOGGLE field. */
    @Nullable
    public String leafPath() {
        return leafPath;
    }

    /** The consumer's opaque label key, resolved to a {@link com.hypixel.hytale.server.core.Message} at render time. */
    @Nonnull
    public String labelKey() {
        return labelKey;
    }

    /** The literal dropdown entries (DROPDOWN only); null for every other kind. */
    @Nullable
    public String[] values() {
        return values == null ? null : values.clone();
    }

    /** The optional {@code #Hint} sub-label key (resolved by the consumer like {@link #labelKey()}), or null when unset. */
    @Nullable
    public String hintKey() {
        return hintKey;
    }
}
