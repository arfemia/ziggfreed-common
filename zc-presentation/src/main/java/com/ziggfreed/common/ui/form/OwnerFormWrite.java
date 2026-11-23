package com.ziggfreed.common.ui.form;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.owner.OwnerFileBinding;

/**
 * Turns what an owner typed on an owner form into the leaves its file is written with, and writes them through the
 * file's own binding. The typed text is read by {@link SettingsForm}'s own parse ({@link OwnerFormKind#fieldKind}),
 * so a number field reads exactly as every settings form reads one, then held to the field's bounds and choices.
 *
 * <p><b>Number types.</b> A {@code Whole} leaf is boxed as an {@code Integer} and written {@code 16}; a {@code Number},
 * {@code Chance} or {@code Signed} leaf as a {@code Double}, written {@code 1.5}, {@code 2.0} or {@code -0.9} (a
 * {@code Signed} one is typed as text and read here, any sign, a finite number only); a {@code Switch} as a
 * {@code Boolean}; a {@code List} as a list of strings. A blank field (or a switch or choice left empty) is a null leaf,
 * which takes the owner's value out, so the layer under it reads again. Only the settings handed in are written, at
 * their own paths; nothing else in the file moves.
 */
public final class OwnerFormWrite {

    /** Why a typed value was refused. */
    public enum Problem {
        /** Not a number of 0 or more. */
        NUMBER,
        /** Not a whole number. */
        WHOLE,
        /** Not a number from 0 to 1. */
        CHANCE,
        /** Under the field's {@code Min}. */
        BELOW,
        /** Over the field's {@code Max}. */
        ABOVE,
        /** Not one of the field's {@code Choices}. */
        CHOICE,
        /** Not a number (a {@code Signed} field, which takes any sign). */
        SIGNED
    }

    /** The first setting refused, and why. */
    public record Refusal(@Nonnull OwnerFormField field, @Nonnull Problem problem) {
    }

    /** The leaves to write (dotted paths from the file's root, a null value removing its leaf), or the refusal. */
    public record Collected(@Nonnull Map<String, Object> leaves, @Nullable Refusal refusal) {

        public boolean ok() {
            return refusal == null;
        }
    }

    private static final Message UNUSED = Message.raw("");

    private OwnerFormWrite() {
    }

    /**
     * Read {@code typed} (a setting's index to the text in its control) for {@code fields}: the leaves in field order,
     * or the first setting refused (then no leaf at all). A setting not in {@code typed} is not written.
     */
    @Nonnull
    public static Collected collect(@Nonnull List<OwnerFormField> fields, @Nonnull Map<Integer, String> typed) {
        List<OwnerFormField> changed = new ArrayList<>();
        List<FieldSpec> specs = new ArrayList<>();
        for (OwnerFormField field : fields) {
            if (typed.containsKey(field.index())) {
                changed.add(field);
                specs.add(spec(field));
            }
        }
        if (changed.isEmpty()) {
            return new Collected(Collections.emptyMap(), null);
        }
        SettingsForm form = new SettingsForm(specs, UNUSED, UNUSED);
        for (OwnerFormField field : changed) {
            String raw = typed.get(field.index());
            form.seedValue(id(field), raw == null ? "" : raw);
        }
        FormResult parsed = form.collectLeaves(true);
        if (!parsed.ok()) {
            OwnerFormField field = byId(changed, parsed.errorLabelKey());
            return refused(field != null ? field : changed.get(0), problemOf(field));
        }
        Map<String, Object> leaves = new LinkedHashMap<>();
        for (OwnerFormField field : changed) {
            Object value = parsed.leaves().get(field.writePath());
            if (field.kind() == OwnerFormKind.SIGNED && value != null) {
                value = signed(String.valueOf(value));
                if (value == null) {
                    return refused(field, Problem.SIGNED);
                }
            }
            Problem problem = outOfBounds(field, value);
            if (problem != null) {
                return refused(field, problem);
            }
            leaves.put(field.writePath(), value);
        }
        return new Collected(leaves, null);
    }

    /** The leaf that takes {@code field}'s value out of the owner's file, so the layer under it reads again. */
    @Nonnull
    public static Map<String, Object> reset(@Nonnull OwnerFormField field) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put(field.writePath(), null);
        return leaves;
    }

    /**
     * Write {@code leaves} through {@code binding} (the file's own writer, which never overwrites a file it cannot
     * read) and have its mod read the file again. True when written, or when there was nothing to write.
     */
    public static boolean write(@Nonnull OwnerFileBinding binding, @Nonnull Map<String, Object> leaves) {
        if (leaves.isEmpty()) {
            return true;
        }
        boolean written = binding.write(leaves);
        if (written) {
            binding.reload();
        }
        return written;
    }

    /**
     * Write {@code leaves} (dotted paths inside one entry, a null value removing its leaf) into the entry
     * {@code entryId} of an id-keyed file, through the store's own entry writer ({@link OwnerFileBinding#putEntry}: the
     * id held to the store's id pattern first, the file's own spelling of it kept), and have its mod read the file
     * again. True when written; false, nothing written, otherwise. An empty {@code leaves} makes the entry, empty, where
     * the file has none.
     */
    public static boolean writeEntry(@Nonnull OwnerFileBinding binding, @Nonnull String entryId,
            @Nonnull Map<String, Object> leaves) {
        boolean written = binding.putEntry(entryId, leaves);
        if (written) {
            binding.reload();
        }
        return written;
    }

    /**
     * Take the whole entry {@code entryId} out of an id-keyed file through the store's own entry writer, and have its
     * mod read the file again. True when nothing is left under the id.
     */
    public static boolean removeEntry(@Nonnull OwnerFileBinding binding, @Nonnull String entryId) {
        boolean written = binding.removeEntry(entryId);
        if (written) {
            binding.reload();
        }
        return written;
    }

    @Nonnull
    private static FieldSpec spec(@Nonnull OwnerFormField field) {
        String id = id(field);
        String path = field.writePath();
        return switch (field.kind()) {
            case TEXT -> FieldSpec.text(id, path, id);
            case NUMBER -> FieldSpec.number(id, path, id);
            case WHOLE -> FieldSpec.integer(id, path, id);
            case CHANCE -> FieldSpec.chance(id, path, id);
            case LIST -> FieldSpec.csv(id, path, id);
            case SWITCH -> FieldSpec.tristate(id, path, id);
            case CHOICE -> FieldSpec.dropdown(id, path, id, field.choices().toArray(String[]::new));
            case SIGNED -> FieldSpec.text(id, path, id);
        };
    }

    /** A {@code Signed} field's typed text as a finite number of any sign, or null when it is no number. */
    @Nullable
    static Double signed(@Nonnull String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nonnull
    private static String id(@Nonnull OwnerFormField field) {
        return String.valueOf(field.index());
    }

    @Nullable
    private static OwnerFormField byId(@Nonnull List<OwnerFormField> fields, @Nullable String id) {
        for (OwnerFormField field : fields) {
            if (id(field).equals(id)) {
                return field;
            }
        }
        return null;
    }

    @Nonnull
    private static Problem problemOf(@Nullable OwnerFormField field) {
        if (field == null) {
            return Problem.NUMBER;
        }
        return switch (field.kind()) {
            case WHOLE -> Problem.WHOLE;
            case CHANCE -> Problem.CHANCE;
            case SIGNED -> Problem.SIGNED;
            default -> Problem.NUMBER;
        };
    }

    /** Why a parsed {@code value} is outside {@code field}'s bounds or choices, or null when it is inside them. */
    @Nullable
    private static Problem outOfBounds(@Nonnull OwnerFormField field, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (field.kind() == OwnerFormKind.CHOICE) {
            return field.choices().contains(String.valueOf(value)) ? null : Problem.CHOICE;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (field.min() != null && d < field.min()) {
                return Problem.BELOW;
            }
            if (field.max() != null && d > field.max()) {
                return Problem.ABOVE;
            }
        }
        return null;
    }

    @Nonnull
    private static Collected refused(@Nonnull OwnerFormField field, @Nonnull Problem problem) {
        return new Collected(Collections.emptyMap(), new Refusal(field, problem));
    }
}
