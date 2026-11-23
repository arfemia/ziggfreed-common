package com.ziggfreed.common.ui.form;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The audit of every owner form in force, once every pack has folded (the boot audit): a form naming a file no
 * installed mod registered, an entry or a path a form may not write, a field with no kind or a kind off the list, two
 * fields writing one path, a choice with no choices, bounds the wrong way round, a kind that does not match the value
 * the file's layer under the owner holds (a decimal field over a whole-number leaf writes the wrong number type), an
 * audit or a preview no installed mod registered, and a word no lang file ships. Unknown ids and keys are WARNINGs, a
 * shape that cannot work an ERROR.
 */
public final class OwnerFormValidator {

    /** The label the audit's lines carry. */
    public static final String LOG_LABEL = "[owner] form audit";

    /** The domain its findings are stamped with. */
    public static final String DOMAIN = "ownerforms";

    private OwnerFormValidator() {
    }

    /** Audit every form in force. Never throws. */
    @Nonnull
    public static List<Finding> audit() {
        List<Finding> out = new ArrayList<>();
        Predicate<String> shipped = TextKeyAudit.liveCatalogue();
        try {
            for (OwnerFormAsset form : OwnerFormConfig.getInstance().forms()) {
                audit(form, shipped, out);
            }
        } catch (Throwable t) {
            out.add(Finding.error(DOMAIN, "AUDIT_FAILED", "the owner form audit could not finish: " + t, "forms"));
        }
        return out;
    }

    /** Audit one form into {@code out}, its words against {@code shipped}. */
    static void audit(@Nonnull OwnerFormAsset form, @Nonnull Predicate<String> shipped, @Nonnull List<Finding> out) {
        String id = form.getId();
        String where = "the owner form '" + id + "'";
        OwnerFileBinding binding = OwnerFileBindings.find(form.file());
        if (form.file() == null) {
            out.add(Finding.error(DOMAIN, "NO_FILE", where + " names no File, so it can change nothing", id));
        } else if (binding == null) {
            out.add(Finding.warning(DOMAIN, "UNBOUND_FILE", where + " edits " + form.file() + ", which no installed "
                    + "mod registered, so it opens with nothing it can change", id));
        }
        String entry = form.entry();
        if (entry != null && !OwnerFormAsset.isEntry(entry)) {
            out.add(Finding.error(DOMAIN, "BAD_ENTRY", where + " writes under the entry '" + entry + "', which is not "
                    + "letters, digits and underscores, so it writes nothing", id));
        }
        OwnerFormAsset.Entries entries = form.entries();
        if (entries != null) {
            auditEntries(form, entries, binding, out);
        }
        if (form.audit() != null && !OwnerFormAudits.has(form.audit())) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_AUDIT", where + " counts the problems of the audit '"
                    + form.audit() + "', which no installed mod registered, so it shows no count", id));
        }
        if (form.preview() != null && !OwnerFormPreviews.has(form.preview())) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_PREVIEW", where + " shows the preview '" + form.preview()
                    + "', which no installed mod registered, so it shows none", id));
        }
        TextKeyAudit.check(out, DOMAIN, id, where + " Title", form.title(), shipped, "its tile reads the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " Description", form.description(), shipped,
                "its tile reads the raw key");
        Set<String> paths = new HashSet<>();
        int fields = 0;
        for (OwnerFormAsset.Section section : form.sections()) {
            TextKeyAudit.check(out, DOMAIN, id, where + " heading", section.heading(), shipped,
                    "the heading reads the raw key");
            TextKeyAudit.check(out, DOMAIN, id, where + " note", section.note(), shipped, "the note reads the raw key");
            for (OwnerFormAsset.Field field : section.fields()) {
                fields++;
                auditField(form, field, binding, shipped, paths, out);
            }
        }
        if (fields == 0) {
            out.add(Finding.warning(DOMAIN, "NO_FIELDS", where + " has no settings, so its screen is empty", id));
        }
    }

    /**
     * A form of the Entries shape: its file must be keyed by id, its {@code Having}, {@code Gate} and {@code Name} must
     * be paths, and an {@code Entry} beside it is never read.
     */
    private static void auditEntries(@Nonnull OwnerFormAsset form, @Nonnull OwnerFormAsset.Entries entries,
            @Nullable OwnerFileBinding binding, @Nonnull List<Finding> out) {
        String id = form.getId();
        String where = "the owner form '" + id + "'";
        if (binding != null && !binding.keyed()) {
            out.add(Finding.error(DOMAIN, "UNKEYED_FILE", where + " lists the entries of " + form.file() + ", which "
                    + "its mod does not read as a file keyed by id, so it lists nothing", id));
        }
        if (form.entry() != null) {
            out.add(Finding.warning(DOMAIN, "ENTRY_WITH_ENTRIES", where + " writes both Entry and Entries; it edits "
                    + "the entries it lists, so its Entry is never read", id));
        }
        String[][] paths = {{"Having", entries.having()}, {"Gate", entries.gate()}, {"Name", entries.name()}};
        for (String[] path : paths) {
            if (path[1] != null && !OwnerFormAsset.isPath(path[1])) {
                out.add(Finding.error(DOMAIN, "BAD_ENTRIES_PATH", where + " has an Entries " + path[0] + " '"
                        + path[1] + "' that is not dotted letters and digits, so it matches nothing", id));
            }
        }
    }

    private static void auditField(@Nonnull OwnerFormAsset form, @Nonnull OwnerFormAsset.Field field,
            @Nullable OwnerFileBinding binding, @Nonnull Predicate<String> shipped, @Nonnull Set<String> paths,
            @Nonnull List<Finding> out) {
        String id = form.getId();
        String path = field.path();
        String where = "the owner form '" + id + "' setting " + (path == null ? "(no Path)" : path);
        if (!OwnerFormAsset.isPath(path)) {
            out.add(Finding.error(DOMAIN, "BAD_PATH", where + " has a Path that is not dotted letters and digits, so "
                    + "it is left off the form", id));
            return;
        }
        if (!paths.add(path.toLowerCase(Locale.ROOT))) {
            out.add(Finding.warning(DOMAIN, "DUPLICATE_PATH", where + " writes a Path another setting of the form "
                    + "writes too", id));
        }
        OwnerFormKind kind = field.kind();
        if (kind == null) {
            out.add(Finding.error(DOMAIN, "BAD_KIND", where + " has the Kind '" + field.kindWord() + "', which is not "
                    + "one of " + String.join(", ", OwnerFormKind.WORDS) + ", so it is left off the form", id));
            return;
        }
        if (kind == OwnerFormKind.CHOICE && field.choices().isEmpty()) {
            out.add(Finding.error(DOMAIN, "NO_CHOICES", where + " is a Choice with no Choices, so nothing can be "
                    + "picked", id));
        }
        if (field.min() != null && field.max() != null && field.min() > field.max()) {
            out.add(Finding.error(DOMAIN, "BOUNDS_REVERSED", where + " has a Min over its Max, so every value is "
                    + "refused", id));
        }
        if (kind == OwnerFormKind.NUMBER && field.min() != null && field.min() < 0) {
            out.add(Finding.warning(DOMAIN, "NEGATIVE_NUMBER", where + " is a Number, which takes 0 or more, so its "
                    + "Min under 0 never applies; a whole number of any sign is a Whole", id));
        }
        TextKeyAudit.check(out, DOMAIN, id, where + " Label", field.label(), shipped, "the row reads the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " Hint", field.hint(), shipped, "the hint reads the raw key");
        for (OwnerFormAsset.Choice choice : field.choices()) {
            TextKeyAudit.check(out, DOMAIN, id, where + " choice " + choice.value(), choice.label(), shipped,
                    "the dropdown reads the raw key");
        }
        if (binding != null) {
            String mismatch = mismatch(kind, binding.below(form.entry(), path));
            if (mismatch != null) {
                out.add(Finding.warning(DOMAIN, "KIND_MISMATCH", where + " is a " + kind.word() + ", but the file's "
                        + "value under the owner's is " + mismatch + ", so a save would write the wrong type", id));
            }
        }
    }

    /** What the value under the owner's word is when it does not fit {@code kind}, or null when it fits (or is none). */
    @Nullable
    static String mismatch(@Nonnull OwnerFormKind kind, @Nullable Object below) {
        if (below == null) {
            return null;
        }
        boolean fits = switch (kind) {
            case WHOLE -> below instanceof Integer || below instanceof Long;
            case NUMBER, CHANCE -> below instanceof Double || below instanceof Float;
            case SWITCH -> below instanceof Boolean;
            case LIST -> below instanceof List<?>;
            case TEXT, CHOICE -> below instanceof String;
        };
        if (fits) {
            return null;
        }
        if (below instanceof Integer || below instanceof Long) {
            return "a whole number";
        }
        if (below instanceof Number) {
            return "a decimal";
        }
        if (below instanceof Boolean) {
            return "true or false";
        }
        if (below instanceof List<?>) {
            return "a list";
        }
        return "text";
    }
}
