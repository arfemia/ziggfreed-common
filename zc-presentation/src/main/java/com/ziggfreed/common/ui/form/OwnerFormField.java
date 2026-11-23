package com.ziggfreed.common.ui.form;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One setting of an owner form as the writer sees it ({@link OwnerFormWrite}): its row on the page, its kind, the
 * dotted path it is written at from the file's root (the entry's key first, as the file spells it), its bounds and,
 * for a choice, the values it may take.
 *
 * @param index     the setting's place among the form's settings, which the page's events name it by
 * @param writePath the dotted path from the file's root, the only path this setting writes
 */
public record OwnerFormField(int index, @Nonnull OwnerFormKind kind, @Nonnull String writePath, @Nullable Double min,
        @Nullable Double max, @Nonnull List<String> choices) {

    public OwnerFormField {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(writePath, "writePath");
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
