package com.ziggfreed.common.objectives.authoring;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How the editors read what an admin typed into a wizard row: blanks as none, numbers only when they read. Pure.
 * Public so a consumer's own editor reads its rows by the same rules.
 */
public final class Typed {

    private Typed() {
    }

    /** The value trimmed, or null when it is blank. */
    @Nullable
    public static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** True when {@code value} holds a backslash, which no saved words can carry. */
    public static boolean hasBackslash(@Nullable String value) {
        return value != null && value.indexOf('\\') >= 0;
    }

    @Nullable
    public static Long wholeLong(@Nullable String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    public static Integer wholeOf(@Nullable String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A whole number of at least 0, or null. */
    @Nullable
    public static Integer atLeastZero(@Nullable String value) {
        Integer whole = wholeOf(value);
        return whole == null || whole < 0 ? null : whole;
    }

    @Nullable
    public static Double decimal(@Nullable String value) {
        try {
            if (value == null || value.isBlank()) {
                return null;
            }
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A number as the admin would type it: whole when it is whole. */
    @Nonnull
    public static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : Double.toString(value);
    }
}
