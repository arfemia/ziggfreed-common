package com.ziggfreed.common.ui.form;

import java.util.Collections;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The immutable outcome of {@link SettingsForm#collectLeaves}: either the parsed leaf map (spec
 * order, ready for {@code JsonOverrideWriter.setLeaves}/{@code setLeaf}) or the first validation
 * failure, identified by the failing spec's label key and its error kind.
 *
 * <p>Constructed only by {@link SettingsForm} (the two factories below are package-private) so
 * every instance is well-formed: {@link #ok()} true always pairs with an empty {@link #errorKind()}
 * / {@link #errorLabelKey()}, and {@link #ok()} false always pairs with empty {@link #leaves()}.
 */
public final class FormResult {

    private final boolean ok;
    @Nonnull private final Map<String, Object> leaves;
    @Nullable private final String errorLabelKey;
    @Nullable private final String errorKind;

    private FormResult(boolean ok, @Nonnull Map<String, Object> leaves, @Nullable String errorLabelKey,
            @Nullable String errorKind) {
        this.ok = ok;
        this.leaves = leaves;
        this.errorLabelKey = errorLabelKey;
        this.errorKind = errorKind;
    }

    /** A successful collection; {@code leaves} is the caller's spec-ordered map (null values = remove). */
    @Nonnull
    static FormResult ok(@Nonnull Map<String, Object> leaves) {
        return new FormResult(true, leaves, null, null);
    }

    /** A validation failure at the given spec's label key / error kind ({@code "number"|"chance"|"int"}). */
    @Nonnull
    static FormResult error(@Nonnull String errorLabelKey, @Nonnull String errorKind) {
        return new FormResult(false, Collections.emptyMap(), errorLabelKey, errorKind);
    }

    public boolean ok() {
        return ok;
    }

    /** LinkedHashMap in spec order on success (empty on failure); a null value means "remove this leaf". */
    @Nonnull
    public Map<String, Object> leaves() {
        return leaves;
    }

    /** The label key of the first failing spec, or null when {@link #ok()}. */
    @Nullable
    public String errorLabelKey() {
        return errorLabelKey;
    }

    /** {@code "number"|"chance"|"int"}, or null when {@link #ok()}. */
    @Nullable
    public String errorKind() {
        return errorKind;
    }
}
