package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.authoring.WizardField;
import com.ziggfreed.common.authoring.WizardSession;
import com.ziggfreed.common.authoring.WizardStep;
import com.ziggfreed.common.authoring.WorldPick;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.form.FieldSpec;

/**
 * An editor's objective slots (a quest's steps, an achievement's criteria): the rows choosing each slot's kind from
 * the live objective kinds and its count, the page after them picking each slot's target (from its kind's own list or
 * pointed at), how it matches and the line players read, their checks, their preview lines, and the keyed rows a save
 * writes ({@link KeyedRows}). A slot's fields are {@code <prefix><n>.<part>}, {@code n} from 1. World thread, as every
 * wizard method; the words are the editor's own ({@link EditorWords}), read under the key names used here.
 *
 * <p>A slot keeps, beside its rows, the key it was saved under ({@code key}) and the kind it was saved as
 * ({@link #KEY_KIND}), so a changed kind takes a new key; {@code keep} holds a line's key whose words could not be
 * shown, so a save leaves it as written.
 */
final class ObjectiveSlotPages {

    /** No row, per slot: the kind its key was saved under, so a changed kind takes a new key. */
    static final String KEY_KIND = "keyKind";

    private static final String[] MATCH_MODES = {"", "EXACT", "CONTAINS", "PREFIX"};

    /** What a save collected: the rows in slot order, and every saved key a slot let go (emptied or replaced). */
    record Collected(@Nonnull List<KeyedRows.Row> rows, @Nonnull List<String> dropped) {

        /** Add every key written or let go, lower-cased, to {@code fileKeys}: none of them is minted again. */
        void remember(@Nonnull Set<String> fileKeys) {
            for (KeyedRows.Row row : rows) {
                fileKeys.add(row.key().toLowerCase(Locale.ROOT));
            }
            for (String key : dropped) {
                fileKeys.add(key.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The {@code TextKey} a slot's row saves: from the line as typed, or the key the file already held. */
    @FunctionalInterface
    interface LineKeys {

        /**
         * @param n        the slot, from 1
         * @param key      the key the row saves under
         * @param line     the line as typed, trimmed
         * @param replaced true when the row is new in the slot's place (its kind changed)
         * @return the {@code TextKey} to write, or null for none
         */
        @Nullable
        String lineKey(int n, @Nonnull String key, @Nonnull String line, boolean replaced);
    }

    @Nonnull private final EditorWords words;
    private final int slots;
    @Nonnull private final String prefix;
    @Nonnull private final List<String> extraParts;

    /**
     * @param prefix     the slot fields' letter ({@code s} for a step)
     * @param extraParts the editor's own per-slot parts, cleared with the rest when a slot opens afresh
     */
    ObjectiveSlotPages(@Nonnull EditorWords words, int slots, @Nonnull String prefix,
            @Nonnull List<String> extraParts) {
        this.words = words;
        this.slots = slots;
        this.prefix = prefix;
        this.extraParts = List.copyOf(extraParts);
    }

    int slots() {
        return slots;
    }

    /** A slot's field: {@code <prefix><n>.<part>}, {@code n} from 1. */
    @Nonnull
    String field(int n, @Nonnull String part) {
        return prefix + n + "." + part;
    }

    // ---------------------------------------------------------------- the pages

    /** The rows choosing each slot's kind and count, under the page's note. */
    @Nonnull
    List<WizardField> kindRows() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(words.note("stepsNote", "note.steps"));
        for (int n = 1; n <= slots; n++) {
            fields.add(EditorWords.own(FieldSpec.header(field(n, "head"), field(n, "head")),
                    words.line("steps.slot", n)));
            fields.add(words.picker(field(n, "kind"), "field.step_kind", QuestEditorPicks.objectiveKinds()));
            fields.add(words.integer(field(n, "amount"), "field.step_amount"));
        }
        return fields;
    }

    /** The page picking each chosen slot's target, how it matches and its line. */
    @Nonnull
    WizardStep targetsPage(@Nonnull WizardSession session) {
        List<WizardField> fields = new ArrayList<>();
        fields.add(words.note("targetsNote", "note.targets"));
        boolean any = false;
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            any = true;
            fields.add(EditorWords.own(FieldSpec.header(field(n, "thead"), field(n, "thead")),
                    words.line("targets.slot", n, Msg.raw(kind))));
            String id = field(n, "target");
            Pickers.Source listed = QuestEditorPicks.targets(kind);
            WizardField target = (listed != null ? WizardField.picker(id, id, id, listed)
                    : WizardField.of(FieldSpec.text(id, id, id))).withLabel(words.line("field.target", n));
            Function<WorldPick, String> reading = QuestEditorPicks.reading(kind);
            if (reading != null) {
                target = target.fromWorld(reading);
            }
            String hint = QuestEditorPicks.namesSpot(kind) ? "hint.target_spot"
                    : QuestEditorPicks.namesPlace(kind) ? "hint.target_place" : "hint.target";
            fields.add(target.withHint(words.key(hint)));
            fields.add(words.dropdown(field(n, "match"), "field.match", MATCH_MODES));
            fields.add(words.text(field(n, "line"), "field.line"));
        }
        if (!any) {
            fields.add(words.note("targetsNone", "note.targets_none"));
        }
        return new WizardStep(words.key("page.targets"), fields);
    }

    // ---------------------------------------------------------------- seeding

    /** Every slot counts 1 until the admin says otherwise. */
    void seedAmounts(@Nonnull WizardSession session) {
        for (int n = 1; n <= slots; n++) {
            session.put(field(n, "amount"), "1");
        }
    }

    /** Slot 1 asks for {@code kind} of {@code target}: what a tool pointed at. */
    void seed(@Nonnull WizardSession session, @Nonnull String kind, @Nullable String target) {
        session.put(field(1, "kind"), kind);
        session.put(field(1, "target"), target);
    }

    /**
     * Every slot from a saved file's rows: each keeps the key and kind it was saved under, and its line shows the
     * words {@code shown} reads off its {@code TextKey}, or, when it reads none, the key is kept as written.
     */
    void fill(@Nonnull WizardSession session, @Nonnull List<KeyedRows.Row> rows,
            @Nonnull Function<String, String> shown) {
        for (int n = 1; n <= slots; n++) {
            forget(session, n);
            for (String part : List.of("kind", "target", "match", "line")) {
                session.put(field(n, part), null);
            }
            session.put(field(n, "amount"), "1");
        }
        for (int i = 0; i < rows.size() && i < slots; i++) {
            KeyedRows.Row row = rows.get(i);
            int n = i + 1;
            session.put(field(n, "key"), row.key());
            session.put(field(n, KEY_KIND), row.kind());
            session.put(field(n, "kind"), row.kind());
            session.put(field(n, "amount"), Long.toString(row.amount()));
            session.put(field(n, "target"), row.target());
            session.put(field(n, "match"), row.matchMode());
            String line = row.textKey() == null ? "" : shown.apply(row.textKey());
            if (line != null && !line.isEmpty()) {
                session.put(field(n, "line"), line);
            } else {
                session.put(field(n, "keep"), row.textKey());
            }
        }
    }

    /** Slot {@code n} holds no saved key any more: its next row mints a new one. */
    void forget(@Nonnull WizardSession session, int n) {
        session.put(field(n, "key"), null);
        session.put(field(n, KEY_KIND), null);
        session.put(field(n, "keep"), null);
        for (String part : extraParts) {
            session.put(field(n, part), null);
        }
    }

    // ---------------------------------------------------------------- the save

    /**
     * Every filled slot as the row a save writes, each under its key: kept while its kind is the one it was saved
     * as, else minted afresh from its line (or its kind and target) through {@code keyed}, never a key
     * {@code fileKeys} or another slot holds. An emptied slot lets its key go and starts afresh; a changed kind lets
     * the old key go, and the new row takes its place ({@link KeyedRows.Row#replaces}). Each slot remembers the key
     * and kind it saved, so the next save writes over the same rows.
     */
    @Nonnull
    Collected collect(@Nonnull WizardSession session, @Nonnull Set<String> fileKeys, @Nonnull KeyedRows keyed,
            @Nonnull LineKeys lineKeys) {
        Set<String> taken = new HashSet<>(fileKeys);
        for (int n = 1; n <= slots; n++) {
            String key = session.value(field(n, "key")).trim();
            if (!key.isEmpty()) {
                taken.add(key.toLowerCase(Locale.ROOT));
            }
        }
        List<KeyedRows.Row> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            String saved = Typed.blankToNull(session.value(field(n, "key")));
            if (kind == null) {
                if (saved != null) {
                    // An emptied slot leaves the file and its key is retired; the slot starts afresh.
                    dropped.add(saved);
                    forget(session, n);
                }
                continue;
            }
            String target = session.value(field(n, "target")).trim();
            String line = session.value(field(n, "line")).trim();
            String key = keyed.keyFor(saved, Typed.blankToNull(session.value(field(n, KEY_KIND))), kind,
                    line.isEmpty() ? kind + " " + target : line, taken::contains);
            String replaces = null;
            if (saved != null && !saved.equals(key)) {
                // A changed kind is a new row: it takes a new key in the old one's place, which is retired, so no
                // player's progress on the old row carries onto it.
                replaces = saved;
                dropped.add(saved);
                session.put(field(n, "keep"), null);
            }
            taken.add(key.toLowerCase(Locale.ROOT));
            session.put(field(n, "key"), key);
            session.put(field(n, KEY_KIND), kind);
            String textKey = lineKeys.lineKey(n, key, line, replaces != null);
            Long amount = amountOf(session, n);
            rows.add(new KeyedRows.Row(key, kind, Typed.blankToNull(target), amount == null ? 1L : amount,
                    Typed.blankToNull(session.value(field(n, "match"))), textKey, replaces));
        }
        return new Collected(rows, dropped);
    }

    // ---------------------------------------------------------------- reads

    /** Slot {@code n}'s kind, or null for an empty slot. */
    @Nullable
    String kindOf(@Nonnull WizardSession session, int n) {
        return Typed.blankToNull(session.value(field(n, "kind")));
    }

    /** Slot {@code n}'s count, a whole number of at least 1, or null when it reads as none. */
    @Nullable
    Long amountOf(@Nonnull WizardSession session, int n) {
        Long amount = Typed.wholeLong(session.value(field(n, "amount")));
        return amount == null || amount < 1L ? null : amount;
    }

    // ---------------------------------------------------------------- checks and preview

    /** Why the kinds page may not be left, or null: no slot filled, or a count that does not read. */
    @Nullable
    Message checkKinds(@Nonnull WizardSession session) {
        boolean any = false;
        for (int n = 1; n <= slots; n++) {
            if (kindOf(session, n) == null) {
                continue;
            }
            any = true;
            if (amountOf(session, n) == null) {
                return words.line("check.amount", n);
            }
        }
        return any ? null : words.line("check.steps");
    }

    /** Why the targets page may not be left, or null: a spot or place a kind needs, or a backslash in a line. */
    @Nullable
    Message checkTargets(@Nonnull WizardSession session) {
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            String target = session.value(field(n, "target")).trim();
            if (QuestEditorPicks.namesSpot(kind) && !QuestEditorPicks.isSpot(target)) {
                return words.line("check.target_spot", n);
            }
            if (QuestEditorPicks.namesPlace(kind) && target.isEmpty()) {
                return words.line("check.target_place", n);
            }
            if (Typed.hasBackslash(session.value(field(n, "line")))) {
                return words.line("check.backslash");
            }
        }
        return null;
    }

    /** One line per filled slot, or the line saying there are none. */
    void preview(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        int filled = 0;
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            filled++;
            String target = session.value(field(n, "target")).trim();
            Long amount = amountOf(session, n);
            long count = amount == null ? 1L : amount;
            lines.add(target.isEmpty() ? words.line("preview.step_any", n, Msg.raw(kind), count)
                    : words.line("preview.step", n, Msg.raw(kind), Msg.raw(target), count));
        }
        if (filled == 0) {
            lines.add(words.line("preview.no_steps"));
        }
    }
}
