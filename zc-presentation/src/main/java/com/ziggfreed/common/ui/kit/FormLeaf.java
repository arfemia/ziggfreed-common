package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;

/**
 * One setting of an owner file as an owner editor shows it ({@link FormLeafPainter}, {@code Pages/ZigFormLeafRow.ui}):
 * its name and hint, the control it is edited with and the value in it, what the layer under the owner holds
 * ({@code defaultLine}), whether the owner's own file sets it ({@code overridden}, which shows the Reset), and the
 * words the row's state and Reset read. Every word is the caller's.
 *
 * @param control  a typed value ({@link Control#FIELD}) or a pick from {@code choices} ({@link Control#CHOICE})
 * @param value    the control's value: the text typed, or the chosen entry's value (blank for none)
 * @param choices  the dropdown's entries, for {@link Control#CHOICE}; empty otherwise
 */
public record FormLeaf(@Nonnull Message label, @Nullable Message hint, @Nonnull Control control,
        @Nonnull String value, @Nonnull List<DropdownEntryInfo> choices, @Nonnull Message defaultLine,
        boolean overridden, @Nonnull Message state, @Nonnull Message reset) {

    /** How the setting is edited. */
    public enum Control {
        /** A text field, for a word, a number or a list. */
        FIELD,
        /** A dropdown, for a switch or a choice. */
        CHOICE
    }

    public FormLeaf {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(defaultLine, "defaultLine");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reset, "reset");
        value = value == null ? "" : value;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** The same setting with another value in its control, the owner's file setting it or not. */
    @Nonnull
    public FormLeaf with(@Nonnull String newValue, boolean nowOverridden, @Nonnull Message newState) {
        return new FormLeaf(label, hint, control, newValue, choices, defaultLine, nowOverridden, newState, reset);
    }
}
