package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.authoring.WizardField;
import com.ziggfreed.common.authoring.WizardSession;
import com.ziggfreed.common.authoring.WizardStep;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardParam;
import com.ziggfreed.common.loot.reward.RewardParams;
import com.ziggfreed.common.ui.form.FieldSpec;

/**
 * An editor's reward slots, every one a Claim the player collects (M428): the page choosing each slot's kind from
 * the live reward kinds, the page after it filling each kind's parameters from its owner's description (a kind that
 * describes none takes {@code Name=Value} pairs), their checks, their preview lines, and the rows a save writes
 * ({@link ClaimSlots}). A slot's fields are {@code r<n>.<part>}, {@code n} from 1. World thread, as every wizard
 * method; the words are the editor's own ({@link EditorWords}), read under the key names used here.
 */
final class RewardSlotPages {

    /** Every reward slot's field ({@link #field}). */
    private static final Pattern FIELD = Pattern.compile("r\\d+\\..*");

    private static final String[] FLAGS = {"", "true", "false"};

    @Nonnull private final EditorWords words;
    private final int slots;
    @Nonnull private final Pickers.Source kinds;

    RewardSlotPages(@Nonnull EditorWords words, int slots, @Nonnull Pickers.Source kinds) {
        this.words = words;
        this.slots = slots;
        this.kinds = kinds;
    }

    /** How many reward rows the editor shows. */
    int slots() {
        return slots;
    }

    /** A reward slot's field: {@code r<n>.<part>}, {@code n} from 1. */
    @Nonnull
    static String field(int n, @Nonnull String part) {
        return "r" + n + "." + part;
    }

    /** A reward slot's parameter field: {@code r<n>.p.<Name>}. */
    @Nonnull
    static String param(int n, @Nonnull String name) {
        return field(n, "p." + name);
    }

    // ---------------------------------------------------------------- the pages

    /** The page choosing each slot's kind. */
    @Nonnull
    WizardStep kindsPage() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(words.note("rewardsNote", "note.rewards"));
        for (int n = 1; n <= slots; n++) {
            fields.add(EditorWords.own(FieldSpec.header(field(n, "head"), field(n, "head")),
                    words.line("rewards.slot", n)));
            fields.add(words.picker(field(n, "kind"), "field.reward_kind", kinds));
        }
        return new WizardStep(words.key("page.rewards"), fields);
    }

    /** The page filling each chosen kind's parameters, then {@code after}. */
    @Nonnull
    WizardStep paramsPage(@Nonnull WizardSession session, @Nonnull List<WizardField> after) {
        List<WizardField> fields = new ArrayList<>();
        boolean any = false;
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            any = true;
            fields.add(EditorWords.own(FieldSpec.header(field(n, "phead"), field(n, "phead")),
                    words.line("params.slot", n, Msg.raw(kind))));
            RewardParams described = described(kind);
            if (described == null) {
                fields.add(words.text(field(n, "params"), "field.params_free")
                        .withHint(words.key("hint.params_free")));
            } else if (described.params().isEmpty()) {
                fields.add(words.note(field(n, "pnone"), "note.params_none"));
            } else {
                for (RewardParam param : described.params()) {
                    fields.add(paramField(n, param));
                }
            }
        }
        if (!any) {
            fields.add(words.note("paramsNone", "note.params_empty"));
        }
        fields.addAll(after);
        return new WizardStep(words.key("page.params"), fields);
    }

    /** One parameter of a reward kind's description, as the row its type takes, labelled with its name. */
    @Nonnull
    private WizardField paramField(int n, @Nonnull RewardParam param) {
        String id = param(n, param.name());
        WizardField field = switch (param.type()) {
            case INTEGER -> WizardField.of(FieldSpec.integer(id, id, id));
            case NUMBER -> WizardField.of(FieldSpec.number(id, id, id));
            case BOOLEAN -> WizardField.of(FieldSpec.dropdown(id, id, id, FLAGS));
            case TEXT -> {
                Pickers.Source listed = QuestEditorPicks.of(param.pick());
                yield listed != null ? WizardField.picker(id, id, id, listed) : WizardField.of(FieldSpec.text(id, id, id));
            }
        };
        return field.withLabel(words.line(param.required() ? "field.param_required" : "field.param",
                Msg.raw(param.name())));
    }

    // ---------------------------------------------------------------- seeding

    /**
     * The slots from a file's Claim rows, each slot the row at its place: at an open, and again after every save,
     * since a save closes up an emptied slot's row and the slots must name the rows the next save writes over.
     */
    void seed(@Nonnull WizardSession session, @Nonnull List<? extends ClaimSlots.Row> rows) {
        for (String id : new ArrayList<>(session.values().keySet())) {
            if (FIELD.matcher(id).matches()) {
                session.put(id, null);
            }
        }
        for (ClaimSlots.Row row : rows) {
            int n = row.slot() + 1;
            session.put(field(n, "kind"), row.kind());
            RewardParams described = described(row.kind());
            if (described == null) {
                session.put(field(n, "params"), freeText(row.params()));
                continue;
            }
            for (RewardParam param : described.params()) {
                for (Map.Entry<String, String> e : row.params().entrySet()) {
                    if (e.getKey().equalsIgnoreCase(param.name())) {
                        session.put(param(n, param.name()), e.getValue());
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- reads

    /** Slot {@code n}'s kind, or null for an empty slot. */
    @Nullable
    static String kindOf(@Nonnull WizardSession session, int n) {
        return Typed.blankToNull(session.value(field(n, "kind")));
    }

    /** Every filled slot as the row a save writes, at its place from 0. */
    @Nonnull
    List<ClaimSlots.Slot> collect(@Nonnull WizardSession session) {
        List<ClaimSlots.Slot> out = new ArrayList<>();
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            RewardParams described = described(kind);
            if (described == null) {
                Map<String, String> free = freeParams(session.value(field(n, "params")));
                out.add(new ClaimSlots.Slot(n - 1, kind, free == null ? Map.of() : free, Set.of(), true));
                continue;
            }
            Set<String> managed = new LinkedHashSet<>();
            for (RewardParam param : described.params()) {
                managed.add(param.name());
            }
            out.add(new ClaimSlots.Slot(n - 1, kind, params(session, n, kind), managed, false));
        }
        return out;
    }

    /** Slot {@code n}'s parameters as typed, by the names its kind describes; blanks left out. */
    @Nonnull
    private static Map<String, String> params(@Nonnull WizardSession session, int n, @Nonnull String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        RewardParams described = described(kind);
        if (described == null) {
            Map<String, String> free = freeParams(session.value(field(n, "params")));
            return free == null ? out : free;
        }
        for (RewardParam param : described.params()) {
            String value = session.value(param(n, param.name())).trim();
            if (!value.isEmpty()) {
                out.put(param.name(), value);
            }
        }
        return out;
    }

    /** What reward kind {@code kind}'s rewards may name, as its owner described it; null when undescribed. */
    @Nullable
    static RewardParams described(@Nonnull String kind) {
        try {
            return RewardKinds.shared().described(kind);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code Name=Value, Name=Value} as typed for a kind that describes nothing; null when a part does not read. */
    @Nullable
    static Map<String, String> freeParams(@Nullable String typed) {
        Map<String, String> out = new LinkedHashMap<>();
        if (typed == null || typed.isBlank()) {
            return out;
        }
        for (String part : typed.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0 || part.substring(0, eq).isBlank()) {
                return null;
            }
            out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    /** {@code params} as {@code Name=Value, Name=Value}. */
    @Nonnull
    static String freeText(@Nonnull Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- checks and preview

    /** Why the parameters page may not be left, or null: free settings that do not read, a needed one empty. */
    @Nullable
    Message check(@Nonnull WizardSession session) {
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            RewardParams described = described(kind);
            if (described == null) {
                if (freeParams(session.value(field(n, "params"))) == null) {
                    return words.line("check.params_free", n);
                }
                continue;
            }
            for (RewardParam param : described.params()) {
                String value = session.value(param(n, param.name())).trim();
                if (value.isEmpty()) {
                    if (param.required()) {
                        return words.line("check.param_required", n, Msg.raw(param.name()));
                    }
                    continue;
                }
                boolean reads = switch (param.type()) {
                    case INTEGER -> Typed.wholeLong(value) != null;
                    case NUMBER -> Typed.decimal(value) != null;
                    default -> true;
                };
                if (!reads) {
                    return words.line("check.param_number", n, Msg.raw(param.name()));
                }
            }
        }
        return null;
    }

    /** One line per filled slot, then whether there are rewards to collect. */
    void preview(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        int rewards = 0;
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            rewards++;
            String params = freeText(params(session, n, kind));
            lines.add(params.isEmpty() ? words.line("preview.reward_bare", n, Msg.raw(kind))
                    : words.line("preview.reward", n, Msg.raw(kind), Msg.raw(params)));
        }
        lines.add(words.line(rewards == 0 ? "preview.no_rewards" : "preview.claim"));
    }

    /** On the parameters page: what each chosen kind pays and what each parameter means, as its owner says. */
    void previewKinds(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        for (int n = 1; n <= slots; n++) {
            String kind = kindOf(session, n);
            RewardParams described = kind == null ? null : described(kind);
            if (described == null) {
                continue;
            }
            String sentence = described.documentation();
            lines.add(words.line("preview.kind_doc", Msg.raw(kind), Msg.raw(sentence == null ? "" : sentence)));
            for (RewardParam param : described.params()) {
                if (param.documentation() != null && !param.documentation().isBlank()) {
                    lines.add(words.line("preview.param_doc", Msg.raw(param.name()), Msg.raw(param.documentation())));
                }
            }
        }
    }
}
