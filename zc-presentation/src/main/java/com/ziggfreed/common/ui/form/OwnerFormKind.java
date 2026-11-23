package com.ziggfreed.common.ui.form;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What one field of an owner form edits (its {@code Kind}, a closed list): which control it shows, how the
 * typed value is read ({@link SettingsForm}'s own parse, through {@link #fieldKind}), and the JSON type it is
 * written as, so a whole-number leaf is written {@code 16} and a decimal one {@code 1.5}.
 *
 * <ul>
 *   <li>{@code Text}: a word or a line, written as a string.</li>
 *   <li>{@code Number}: a number of 0 or more, written as a decimal.</li>
 *   <li>{@code Whole}: a whole number of any sign, written as an integer.</li>
 *   <li>{@code Chance}: a number from 0 to 1, written as a decimal.</li>
 *   <li>{@code List}: words split on commas, written as a list of strings.</li>
 *   <li>{@code Switch}: on or off, written as true or false.</li>
 *   <li>{@code Choice}: one of the field's {@code Choices}, written as its value.</li>
 * </ul>
 */
public enum OwnerFormKind {

    TEXT("Text", FieldKind.TEXT),
    NUMBER("Number", FieldKind.NUMBER),
    WHOLE("Whole", FieldKind.INT),
    CHANCE("Chance", FieldKind.CHANCE),
    LIST("List", FieldKind.CSV),
    SWITCH("Switch", FieldKind.TRISTATE),
    CHOICE("Choice", FieldKind.DROPDOWN);

    /** Every authored word, in order, for the editor's dropdown. */
    public static final String[] WORDS = {"Text", "Number", "Whole", "Chance", "List", "Switch", "Choice"};

    @Nonnull private final String word;
    @Nonnull private final FieldKind fieldKind;

    OwnerFormKind(@Nonnull String word, @Nonnull FieldKind fieldKind) {
        this.word = word;
        this.fieldKind = fieldKind;
    }

    /** The word a form file writes. */
    @Nonnull
    public String word() {
        return word;
    }

    /** The settings form's kind that parses it. */
    @Nonnull
    public FieldKind fieldKind() {
        return fieldKind;
    }

    /** Is it picked from a dropdown (a switch or a choice) rather than typed? */
    public boolean picked() {
        return this == SWITCH || this == CHOICE;
    }

    /** Is it a number? */
    public boolean numeric() {
        return this == NUMBER || this == WHOLE || this == CHANCE;
    }

    /** The kind {@code word} names, matched without regard to case; null for anything else. */
    @Nullable
    public static OwnerFormKind of(@Nullable String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        String wanted = word.trim().toLowerCase(Locale.ROOT);
        for (OwnerFormKind kind : values()) {
            if (kind.word.toLowerCase(Locale.ROOT).equals(wanted)) {
                return kind;
            }
        }
        return null;
    }
}
