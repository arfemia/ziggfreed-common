package com.ziggfreed.common.objectives.authoring;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.authoring.AuthoringUse;
import com.ziggfreed.common.authoring.AuthoringWizard;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.authoring.WizardField;
import com.ziggfreed.common.authoring.WizardSession;
import com.ziggfreed.common.authoring.WizardStep;
import com.ziggfreed.common.authoring.WorldPick;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardParam;
import com.ziggfreed.common.loot.reward.RewardParams;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.npc.placement.runtime.PlacedNpcComponent;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.ui.form.FieldSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * The quest editor: the web quest builder's steps in game, on the authoring wizard frame. It makes a quest, or opens
 * one the server content pack already holds, and saves it there through {@link ServerContent}: the quest file
 * ({@link QuestDraft}) and the words typed for it, each a language key ({@code quests.<Id>.title},
 * {@code quests.<Id>.flavor}, {@code quests.<Id>.step.<key>}). The frame says when the save reaches players.
 *
 * <p>Seven pages: the quest's name and words (or a saved quest to open); its giver (a character picked from the list
 * or pointed at, the book when none, or a new quest giver stood for it); its steps' kinds and counts, from the live
 * objective kinds; each step's target, from its kind's own list or pointed at, with how it matches and the line the
 * player reads; its rewards' kinds, from the live reward kinds; each reward's parameters, from its kind's description;
 * and its requirements and repeat rule. Every reward is a Claim the player collects. A step's key is minted once from
 * its words and never renamed.
 *
 * <p>The Quest Tool opens it on what it points at: a character becomes the giver, a creature a "kill" step, a block a
 * "break" step, an item on the ground a "pick up" step. World thread, as every wizard method.
 */
public final class QuestWizard implements AuthoringWizard {

    /** The wizard's and its tool's id: its author node is {@code ziggfreedcommon.author.quest}. */
    public static final String ID = "Quest";

    /** How many steps the editor shows. */
    static final int STEP_SLOTS = 5;

    /** How many reward rows the editor shows. */
    static final int REWARD_SLOTS = 3;

    static final int PAGE_QUEST = 0;
    static final int PAGE_GIVER = 1;
    static final int PAGE_STEPS = 2;
    static final int PAGE_TARGETS = 3;
    static final int PAGE_REWARDS = 4;
    static final int PAGE_PARAMS = 5;
    static final int PAGE_RULES = 6;

    static final String EDIT = "edit";
    static final String TITLE = "title";
    static final String FLAVOR = "flavor";
    static final String GIVER = "giver";
    static final String RETURN = "returnToGiver";
    static final String NEW_GIVER = "newGiver";
    static final String GIVER_NAME = "giverName";
    static final String GIVER_SPOT = "giverSpot";
    static final String SEQUENTIAL = "sequential";
    static final String AUTO_TRACK = "autoTrack";
    static final String REQ_QUEST = "reqQuest";
    static final String REQ_PERMISSION = "reqPermission";
    static final String REQ_FACTOR = "reqFactor";
    static final String REQ_PARAM = "reqParam";
    static final String REQ_MIN = "reqMin";
    static final String REPEAT = "repeat";
    static final String REPEAT_HOURS = "repeatHours";
    static final String REPEAT_RESET = "repeatReset";
    static final String REPEAT_MAX = "repeatMax";

    /** No row: the new giver's id (its role's, its placement's and its character's), minted once for the session. */
    static final String NEW_GIVER_ID = "newGiverId";

    /** No row, per step slot: the kind its key was saved under, so a changed kind takes a new key. */
    static final String KEY_KIND = "keyKind";

    private static final String KILL = "KILL_ENTITY";
    private static final String BREAK = "BREAK_BLOCK";
    private static final String PICK_UP = "PICKUP_ITEM";

    private static final String[] MATCH_MODES = {"", "EXACT", "CONTAINS", "PREFIX"};
    private static final String[] PERIODS = {"", QuestAsset.Repeat.Reset.PERIOD_DAILY,
            QuestAsset.Repeat.Reset.PERIOD_WEEKLY};
    private static final String[] FLAGS = {"", "true", "false"};

    /** Every reward slot's field ({@link #reward}). */
    private static final Pattern REWARD_FIELD = Pattern.compile("r\\d+\\..*");

    @Nonnull private final PlayerRef admin;

    /** The saved quest last chosen to open, so a choice is read once. */
    @Nullable private String attempted;
    /** The file this session edits (the saved quest opened, or what the last save wrote), or null for a new one. */
    @Nullable private JsonObject original;
    /** Why the chosen quest could not be opened, or null. */
    @Nullable private Message loadProblem;
    /** True once a saved quest was opened, so the session edits it. */
    private boolean opened;
    /** True when the file's requirements are more than the page shows, so a save leaves them as written. */
    private boolean requiresKept;
    /** True when the file's wait is not whole hours, so a save leaves it as written while the hours stay empty. */
    private boolean cooldownKept;
    /** Every step key the file holds or retires, lower-cased, which a new step's key never takes. */
    @Nonnull private Set<String> fileStepKeys = new HashSet<>();

    QuestWizard(@Nonnull AuthoringUse use) {
        this.admin = use.playerRef();
    }

    @Nonnull
    @Override
    public Message title() {
        return QuestEditorText.line("title");
    }

    @Override
    public boolean saves() {
        return true;
    }

    // ---------------------------------------------------------------- field ids

    /** A step slot's field: {@code s<n>.<part>}, {@code n} from 1. */
    @Nonnull
    static String step(int n, @Nonnull String part) {
        return "s" + n + "." + part;
    }

    /** A reward slot's field: {@code r<n>.<part>}, {@code n} from 1. */
    @Nonnull
    static String reward(int n, @Nonnull String part) {
        return "r" + n + "." + part;
    }

    /** A reward slot's parameter field: {@code r<n>.p.<Name>}. */
    @Nonnull
    static String rewardParam(int n, @Nonnull String name) {
        return reward(n, "p." + name);
    }

    // ---------------------------------------------------------------- the pages

    @Nonnull
    @Override
    public List<WizardStep> steps(@Nonnull WizardSession session) {
        sync(session);
        return List.of(questPage(session), giverPage(), stepsPage(), targetsPage(session), rewardsPage(),
                paramsPage(session), rulesPage());
    }

    @Nonnull
    private static WizardStep questPage(@Nonnull WizardSession session) {
        List<WizardField> fields = new ArrayList<>();
        if (session.id() == null && !packQuests().isEmpty()) {
            fields.add(picker(EDIT, "field.edit", Pickers.of(() -> packQuests().keySet()))
                    .withHint(QuestEditorText.key("hint.edit")));
        }
        fields.add(note("questNote", "note.quest"));
        fields.add(text(TITLE, "field.title"));
        fields.add(text(FLAVOR, "field.flavor").withHint(QuestEditorText.key("hint.flavor")));
        return new WizardStep(QuestEditorText.key("page.quest"), fields);
    }

    @Nonnull
    private static WizardStep giverPage() {
        return WizardStep.of(QuestEditorText.key("page.giver"),
                note("giverNote", "note.giver"),
                picker(GIVER, "field.giver", QuestEditorPicks.characters())
                        .fromWorld(QuestEditorPicks::characterOf)
                        .withHint(QuestEditorText.key("hint.giver")),
                toggle(RETURN, "field.return"),
                header("newGiverHeader", "header.new_giver"),
                note("newGiverNote", "note.new_giver"),
                toggle(NEW_GIVER, "field.new_giver"),
                text(GIVER_NAME, "field.giver_name").withHint(QuestEditorText.key("hint.giver_name")),
                text(GIVER_SPOT, "field.giver_spot").fromWorld(WorldPick::position)
                        .withHint(QuestEditorText.key("hint.giver_spot")));
    }

    @Nonnull
    private static WizardStep stepsPage() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(note("stepsNote", "note.steps"));
        for (int n = 1; n <= STEP_SLOTS; n++) {
            fields.add(own(FieldSpec.header(step(n, "head"), step(n, "head")), QuestEditorText.line("steps.slot", n)));
            fields.add(picker(step(n, "kind"), "field.step_kind", QuestEditorPicks.objectiveKinds()));
            fields.add(integer(step(n, "amount"), "field.step_amount"));
        }
        fields.add(header("flowHeader", "header.flow"));
        fields.add(toggle(SEQUENTIAL, "field.sequential"));
        fields.add(toggle(AUTO_TRACK, "field.auto_track"));
        return new WizardStep(QuestEditorText.key("page.steps"), fields);
    }

    @Nonnull
    private static WizardStep targetsPage(@Nonnull WizardSession session) {
        List<WizardField> fields = new ArrayList<>();
        fields.add(note("targetsNote", "note.targets"));
        boolean any = false;
        for (int n = 1; n <= STEP_SLOTS; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            any = true;
            fields.add(own(FieldSpec.header(step(n, "thead"), step(n, "thead")),
                    QuestEditorText.line("targets.slot", n, Msg.raw(kind))));
            String id = step(n, "target");
            Pickers.Source listed = QuestEditorPicks.targets(kind);
            WizardField target = (listed != null ? WizardField.picker(id, id, id, listed)
                    : WizardField.of(FieldSpec.text(id, id, id))).withLabel(QuestEditorText.line("field.target", n));
            Function<WorldPick, String> reading = QuestEditorPicks.reading(kind);
            if (reading != null) {
                target = target.fromWorld(reading);
            }
            String hint = QuestEditorPicks.namesSpot(kind) ? "hint.target_spot"
                    : QuestEditorPicks.namesPlace(kind) ? "hint.target_place" : "hint.target";
            fields.add(target.withHint(QuestEditorText.key(hint)));
            fields.add(dropdown(step(n, "match"), "field.match", MATCH_MODES));
            fields.add(text(step(n, "line"), "field.line"));
        }
        if (!any) {
            fields.add(note("targetsNone", "note.targets_none"));
        }
        return new WizardStep(QuestEditorText.key("page.targets"), fields);
    }

    @Nonnull
    private static WizardStep rewardsPage() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(note("rewardsNote", "note.rewards"));
        for (int n = 1; n <= REWARD_SLOTS; n++) {
            fields.add(own(FieldSpec.header(reward(n, "head"), reward(n, "head")),
                    QuestEditorText.line("rewards.slot", n)));
            fields.add(picker(reward(n, "kind"), "field.reward_kind", QuestEditorPicks.rewardKinds()));
        }
        return new WizardStep(QuestEditorText.key("page.rewards"), fields);
    }

    @Nonnull
    private static WizardStep paramsPage(@Nonnull WizardSession session) {
        List<WizardField> fields = new ArrayList<>();
        boolean any = false;
        for (int n = 1; n <= REWARD_SLOTS; n++) {
            String kind = rewardKindOf(session, n);
            if (kind == null) {
                continue;
            }
            any = true;
            fields.add(own(FieldSpec.header(reward(n, "phead"), reward(n, "phead")),
                    QuestEditorText.line("params.slot", n, Msg.raw(kind))));
            RewardParams described = described(kind);
            if (described == null) {
                fields.add(text(reward(n, "params"), "field.params_free")
                        .withHint(QuestEditorText.key("hint.params_free")));
            } else if (described.params().isEmpty()) {
                fields.add(note(reward(n, "pnone"), "note.params_none"));
            } else {
                for (RewardParam param : described.params()) {
                    fields.add(paramField(n, param));
                }
            }
        }
        if (!any) {
            fields.add(note("paramsNone", "note.params_empty"));
        }
        return new WizardStep(QuestEditorText.key("page.params"), fields);
    }

    /** One parameter of a reward kind's description, as the row its type takes, labelled with its name. */
    @Nonnull
    private static WizardField paramField(int n, @Nonnull RewardParam param) {
        String id = rewardParam(n, param.name());
        WizardField field = switch (param.type()) {
            case INTEGER -> WizardField.of(FieldSpec.integer(id, id, id));
            case NUMBER -> WizardField.of(FieldSpec.number(id, id, id));
            case BOOLEAN -> WizardField.of(FieldSpec.dropdown(id, id, id, FLAGS));
            case TEXT -> {
                Pickers.Source listed = QuestEditorPicks.of(param.pick());
                yield listed != null ? WizardField.picker(id, id, id, listed) : WizardField.of(FieldSpec.text(id, id, id));
            }
        };
        return field.withLabel(QuestEditorText.line(param.required() ? "field.param_required" : "field.param",
                Msg.raw(param.name())));
    }

    @Nonnull
    private WizardStep rulesPage() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(header("requiresHeader", "header.requires"));
        if (requiresKept) {
            fields.add(note("requiresKept", "note.requires_kept"));
        } else {
            fields.add(picker(REQ_QUEST, "field.req_quest", QuestEditorPicks.quests()));
            fields.add(text(REQ_PERMISSION, "field.req_permission"));
            fields.add(picker(REQ_FACTOR, "field.req_factor", QuestEditorPicks.factors()));
            fields.add(text(REQ_PARAM, "field.req_param"));
            fields.add(number(REQ_MIN, "field.req_min"));
        }
        fields.add(header("repeatHeader", "header.repeat"));
        fields.add(toggle(REPEAT, "field.repeat"));
        fields.add(integer(REPEAT_HOURS, "field.repeat_hours").withHint(QuestEditorText.key("hint.repeat_hours")));
        fields.add(dropdown(REPEAT_RESET, "field.repeat_reset", PERIODS));
        fields.add(integer(REPEAT_MAX, "field.repeat_max").withHint(QuestEditorText.key("hint.repeat_max")));
        return new WizardStep(QuestEditorText.key("page.rules"), fields);
    }

    // ---------------------------------------------------------------- opening

    /** Seed the defaults, then what the Quest Tool pointed at. */
    @Override
    public void begin(@Nonnull WizardSession session, @Nonnull AuthoringUse use) {
        session.put(AUTO_TRACK, "on");
        session.put(SEQUENTIAL, "on");
        for (int n = 1; n <= STEP_SLOTS; n++) {
            session.put(step(n, "amount"), "1");
        }
        WorldPick pick = use.pick();
        if (pick.hasEntity()) {
            String character = isCharacter(pick) ? QuestEditorPicks.characterOf(pick) : null;
            if (character != null) {
                session.put(GIVER, character);
            } else if (pick.npcRole() != null) {
                seedStep(session, KILL, pick.npcRole());
            } else if (pick.droppedItemId() != null) {
                seedStep(session, PICK_UP, pick.droppedItemId());
            }
        } else if (pick.hasBlock()) {
            seedStep(session, BREAK, pick.blockId());
        }
    }

    private static void seedStep(@Nonnull WizardSession session, @Nonnull String kind, @Nullable String target) {
        session.put(step(1, "kind"), kind);
        session.put(step(1, "target"), target);
    }

    /** True for an entity that is a character (a placed one, or one a character file names), not a creature. */
    private static boolean isCharacter(@Nonnull WorldPick pick) {
        Ref<EntityStore> entity = pick.entity();
        if (entity == null || !entity.isValid()) {
            return false;
        }
        try {
            ComponentType<EntityStore, PlacedNpcComponent> placed = PlacedNpcComponent.getComponentType();
            if (placed != null && entity.getStore().getComponent(entity, placed) != null) {
                return true;
            }
        } catch (Throwable t) {
            SafeLog.fine("[quest-editor] the picked entity's placement could not be read: " + t.getMessage());
        }
        String id = QuestEditorPicks.characterOf(pick);
        return id != null && NpcIdentities.allDeclaredNpcIds().stream().anyMatch(id::equalsIgnoreCase);
    }

    /** A picked character stands in for a new giver; a picked spot is where a new one stands. */
    @Override
    public void picked(@Nonnull WizardSession session, @Nonnull String fieldId, @Nonnull WorldPick pick) {
        if (GIVER.equals(fieldId)) {
            session.put(NEW_GIVER, "");
        } else if (GIVER_SPOT.equals(fieldId)) {
            session.put(NEW_GIVER, "on");
        }
    }

    /**
     * Open the saved quest the admin chose, once: its file is read from the server content pack and every page filled
     * from it, and the session keeps its id. A quest built on another ({@code Parent}) is not opened here.
     */
    private void sync(@Nonnull WizardSession session) {
        if (session.id() != null || original != null) {
            return;
        }
        String chosen = session.value(EDIT).trim();
        if (chosen.isEmpty()) {
            attempted = null;
            loadProblem = null;
            return;
        }
        if (chosen.equals(attempted)) {
            return;
        }
        attempted = chosen;
        loadProblem = null;
        Path file = null;
        String fileId = null;
        for (Map.Entry<String, Path> e : packQuests().entrySet()) {
            if (e.getKey().equalsIgnoreCase(chosen)) {
                fileId = e.getKey();
                file = e.getValue();
            }
        }
        if (file == null || fileId == null) {
            loadProblem = QuestEditorText.line("load.missing", Msg.raw(chosen));
            return;
        }
        JsonObject doc;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("not a JSON object");
            }
            doc = parsed.getAsJsonObject();
        } catch (Throwable t) {
            SafeLog.warn("[quest-editor] the saved quest " + file + " could not be read: " + t.getMessage());
            loadProblem = QuestEditorText.line("load.unreadable", Msg.raw(chosen));
            return;
        }
        QuestDraft.Loaded loaded = QuestDraft.read(doc, STEP_SLOTS, REWARD_SLOTS);
        if (loaded.inherits()) {
            loadProblem = QuestEditorText.line("load.inherits", Msg.raw(chosen));
            return;
        }
        session.keepId(fileId);
        original = doc;
        opened = true;
        fill(session, loaded);
    }

    /** Every page's values from a saved quest. */
    private void fill(@Nonnull WizardSession session, @Nonnull QuestDraft.Loaded loaded) {
        fileStepKeys.addAll(QuestDraft.taken(loaded));
        requiresKept = loaded.requires() == null;
        cooldownKept = loaded.repeat().cooldownHours() == QuestDraft.KEEP_COOLDOWN;

        session.put(TITLE, words(loaded.titleKey(), loaded.displayName()));
        session.put(FLAVOR, words(loaded.flavorKey(), null));
        session.put(GIVER, loaded.giver());
        session.put(NEW_GIVER, "");
        flag(session, RETURN, loaded.returnToGiver());
        flag(session, SEQUENTIAL, loaded.sequential());
        flag(session, AUTO_TRACK, loaded.autoTrack());

        for (int n = 1; n <= STEP_SLOTS; n++) {
            for (String part : List.of("kind", "target", "match", "line", "key", "keep", KEY_KIND)) {
                session.put(step(n, part), null);
            }
            session.put(step(n, "amount"), "1");
        }
        for (int i = 0; i < loaded.steps().size(); i++) {
            QuestForm.Step step = loaded.steps().get(i);
            int n = i + 1;
            session.put(step(n, "key"), step.key());
            session.put(step(n, KEY_KIND), step.kind());
            session.put(step(n, "kind"), step.kind());
            session.put(step(n, "amount"), Long.toString(step.amount()));
            session.put(step(n, "target"), step.target());
            session.put(step(n, "match"), step.matchMode());
            String line = words(step.textKey(), null);
            if (!line.isEmpty()) {
                session.put(step(n, "line"), line);
            } else {
                session.put(step(n, "keep"), step.textKey());
            }
        }

        seedRewards(session, loaded.rewards());

        QuestForm.Requirements requires = loaded.requires();
        if (requires != null) {
            session.put(REQ_QUEST, requires.quest());
            session.put(REQ_PERMISSION, requires.permission());
            session.put(REQ_FACTOR, requires.factor());
            session.put(REQ_PARAM, requires.param());
            session.put(REQ_MIN, requires.min() == null ? null : number(requires.min()));
        }
        QuestForm.Repeat repeat = loaded.repeat();
        flag(session, REPEAT, repeat.on());
        session.put(REPEAT_HOURS, repeat.cooldownHours() > 0 ? Integer.toString(repeat.cooldownHours()) : null);
        session.put(REPEAT_RESET, repeat.period());
        session.put(REPEAT_MAX, repeat.maxCompletions() > 0 ? Integer.toString(repeat.maxCompletions()) : null);
    }

    /**
     * The reward slots from a file's Claim rows, each slot the row at its place: at an open, and again after every
     * save, since a save closes up an emptied slot's row and the slots must name the rows the next save writes over.
     */
    private static void seedRewards(@Nonnull WizardSession session, @Nonnull List<QuestForm.Reward> rewards) {
        for (String id : new ArrayList<>(session.values().keySet())) {
            if (REWARD_FIELD.matcher(id).matches()) {
                session.put(id, null);
            }
        }
        for (QuestForm.Reward reward : rewards) {
            int n = reward.slot() + 1;
            session.put(reward(n, "kind"), reward.kind());
            RewardParams described = described(reward.kind());
            if (described == null) {
                session.put(reward(n, "params"), freeText(reward.params()));
                continue;
            }
            for (RewardParam param : described.params()) {
                for (Map.Entry<String, String> e : reward.params().entrySet()) {
                    if (e.getKey().equalsIgnoreCase(param.name())) {
                        session.put(rewardParam(n, param.name()), e.getValue());
                    }
                }
            }
        }
    }

    /** The English a key holds: the words this pack saved, else what the server loaded, else {@code fallback}. */
    @Nonnull
    private static String words(@Nullable String key, @Nullable String fallback) {
        if (key != null && !key.isBlank()) {
            String saved = ServerContent.text(key);
            if (saved != null) {
                return saved;
            }
            String loaded = LangCatalog.value(key);
            if (loaded != null) {
                return loaded;
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static void flag(@Nonnull WizardSession session, @Nonnull String id, boolean on) {
        session.put(id, on ? "on" : "");
    }

    // ---------------------------------------------------------------- the preview

    @Nonnull
    @Override
    public List<Message> preview(@Nonnull WizardSession session) {
        sync(session);
        List<Message> lines = new ArrayList<>();
        if (loadProblem != null) {
            lines.add(loadProblem);
        }
        String title = session.value(TITLE).trim();
        lines.add(Msg.bold(title.isEmpty() ? QuestEditorText.line("preview.untitled") : Msg.raw(title)));
        String id = session.id();
        lines.add(id == null ? QuestEditorText.line("preview.new")
                : QuestEditorText.line(opened ? "preview.editing" : "preview.saved_as", Msg.raw(id)));

        String giver = session.value(GIVER).trim();
        boolean newGiver = session.isOn(NEW_GIVER);
        if (newGiver) {
            String name = session.value(GIVER_NAME).trim();
            lines.add(name.isEmpty() ? QuestEditorText.line("preview.giver_new_unnamed")
                    : QuestEditorText.line("preview.giver_new", Msg.raw(name)));
        } else if (!giver.isEmpty()) {
            lines.add(QuestEditorText.line("preview.giver", Msg.raw(giver)));
            if (NpcIdentities.allDeclaredNpcIds().stream().noneMatch(giver::equalsIgnoreCase)) {
                lines.add(QuestEditorText.line("preview.giver_unplaced", Msg.raw(giver)));
            }
        } else {
            lines.add(QuestEditorText.line("preview.book"));
        }
        if (session.isOn(RETURN) && (newGiver || !giver.isEmpty())) {
            lines.add(QuestEditorText.line("preview.return"));
        }

        int steps = 0;
        for (int n = 1; n <= STEP_SLOTS; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            steps++;
            String target = session.value(step(n, "target")).trim();
            Long amount = amountOf(session, n);
            long count = amount == null ? 1L : amount;
            lines.add(target.isEmpty() ? QuestEditorText.line("preview.step_any", n, Msg.raw(kind), count)
                    : QuestEditorText.line("preview.step", n, Msg.raw(kind), Msg.raw(target), count));
        }
        if (steps == 0) {
            lines.add(QuestEditorText.line("preview.no_steps"));
        }

        int rewards = 0;
        for (int n = 1; n <= REWARD_SLOTS; n++) {
            String kind = rewardKindOf(session, n);
            if (kind == null) {
                continue;
            }
            rewards++;
            String params = freeText(rewardParams(session, n, kind));
            lines.add(params.isEmpty() ? QuestEditorText.line("preview.reward_bare", n, Msg.raw(kind))
                    : QuestEditorText.line("preview.reward", n, Msg.raw(kind), Msg.raw(params)));
        }
        lines.add(QuestEditorText.line(rewards == 0 ? "preview.no_rewards" : "preview.claim"));

        previewRequires(session, lines);
        previewRepeat(session, lines);
        if (session.step() == PAGE_PARAMS) {
            previewKinds(session, lines);
        }
        return lines;
    }

    private void previewRequires(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        if (requiresKept) {
            lines.add(QuestEditorText.line("preview.requires_kept"));
            return;
        }
        String quest = session.value(REQ_QUEST).trim();
        String permission = session.value(REQ_PERMISSION).trim();
        String factor = session.value(REQ_FACTOR).trim();
        if (!quest.isEmpty()) {
            lines.add(QuestEditorText.line("preview.req_quest", Msg.raw(quest)));
        }
        if (!permission.isEmpty()) {
            lines.add(QuestEditorText.line("preview.req_permission", Msg.raw(permission)));
        }
        if (!factor.isEmpty()) {
            String param = session.value(REQ_PARAM).trim();
            String min = session.value(REQ_MIN).trim();
            lines.add(QuestEditorText.line("preview.req_factor", Msg.raw(factor), Msg.raw(param),
                    Msg.raw(min.isEmpty() ? "1" : min)));
        }
        if (quest.isEmpty() && permission.isEmpty() && factor.isEmpty()) {
            lines.add(QuestEditorText.line("preview.req_none"));
        }
    }

    private static void previewRepeat(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        if (!session.isOn(REPEAT)) {
            lines.add(QuestEditorText.line("preview.once"));
            return;
        }
        lines.add(QuestEditorText.line("preview.repeat"));
        Integer hours = wholeOf(session.value(REPEAT_HOURS));
        if (hours != null && hours > 0) {
            lines.add(QuestEditorText.line("preview.repeat_wait", hours));
        }
        String period = session.value(REPEAT_RESET).trim();
        if (!period.isEmpty()) {
            lines.add(QuestEditorText.line("preview.repeat_period", Msg.raw(period)));
        }
        Integer max = wholeOf(session.value(REPEAT_MAX));
        if (max != null && max > 0) {
            lines.add(QuestEditorText.line("preview.repeat_max", max));
        }
    }

    /** On the reward details page: what each chosen kind pays and what each parameter means, as its owner says. */
    private static void previewKinds(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        for (int n = 1; n <= REWARD_SLOTS; n++) {
            String kind = rewardKindOf(session, n);
            RewardParams described = kind == null ? null : described(kind);
            if (described == null) {
                continue;
            }
            String sentence = described.documentation();
            lines.add(QuestEditorText.line("preview.kind_doc", Msg.raw(kind),
                    Msg.raw(sentence == null ? "" : sentence)));
            for (RewardParam param : described.params()) {
                if (param.documentation() != null && !param.documentation().isBlank()) {
                    lines.add(QuestEditorText.line("preview.param_doc", Msg.raw(param.name()),
                            Msg.raw(param.documentation())));
                }
            }
        }
    }

    // ---------------------------------------------------------------- the checks

    @Nullable
    @Override
    public Message check(@Nonnull WizardSession session, int step) {
        sync(session);
        return switch (step) {
            case PAGE_QUEST -> checkQuest(session);
            case PAGE_GIVER -> checkGiver(session);
            case PAGE_STEPS -> checkSteps(session);
            case PAGE_TARGETS -> checkTargets(session);
            case PAGE_PARAMS -> checkParams(session);
            case PAGE_RULES -> checkRules(session);
            default -> null;
        };
    }

    @Nullable
    private Message checkQuest(@Nonnull WizardSession session) {
        if (loadProblem != null && session.id() == null) {
            return loadProblem;
        }
        if (session.value(TITLE).isBlank()) {
            return QuestEditorText.line("check.title");
        }
        return hasBackslash(session.value(TITLE)) || hasBackslash(session.value(FLAVOR))
                ? QuestEditorText.line("check.backslash") : null;
    }

    @Nullable
    private static Message checkGiver(@Nonnull WizardSession session) {
        if (!session.isOn(NEW_GIVER)) {
            return null;
        }
        String name = session.value(GIVER_NAME);
        if (name.isBlank()) {
            return QuestEditorText.line("check.giver_name");
        }
        if (hasBackslash(name)) {
            return QuestEditorText.line("check.backslash");
        }
        String spot = session.value(GIVER_SPOT);
        return !spot.isBlank() && QuestGivers.cell(spot) == null ? QuestEditorText.line("check.giver_spot") : null;
    }

    @Nullable
    private static Message checkSteps(@Nonnull WizardSession session) {
        boolean any = false;
        for (int n = 1; n <= STEP_SLOTS; n++) {
            if (kindOf(session, n) == null) {
                continue;
            }
            any = true;
            if (amountOf(session, n) == null) {
                return QuestEditorText.line("check.amount", n);
            }
        }
        return any ? null : QuestEditorText.line("check.steps");
    }

    @Nullable
    private static Message checkTargets(@Nonnull WizardSession session) {
        for (int n = 1; n <= STEP_SLOTS; n++) {
            String kind = kindOf(session, n);
            if (kind == null) {
                continue;
            }
            String target = session.value(step(n, "target")).trim();
            if (QuestEditorPicks.namesSpot(kind) && !QuestEditorPicks.isSpot(target)) {
                return QuestEditorText.line("check.target_spot", n);
            }
            if (QuestEditorPicks.namesPlace(kind) && target.isEmpty()) {
                return QuestEditorText.line("check.target_place", n);
            }
            if (hasBackslash(session.value(step(n, "line")))) {
                return QuestEditorText.line("check.backslash");
            }
        }
        return null;
    }

    @Nullable
    private static Message checkParams(@Nonnull WizardSession session) {
        for (int n = 1; n <= REWARD_SLOTS; n++) {
            String kind = rewardKindOf(session, n);
            if (kind == null) {
                continue;
            }
            RewardParams described = described(kind);
            if (described == null) {
                if (freeParams(session.value(reward(n, "params"))) == null) {
                    return QuestEditorText.line("check.params_free", n);
                }
                continue;
            }
            for (RewardParam param : described.params()) {
                String value = session.value(rewardParam(n, param.name())).trim();
                if (value.isEmpty()) {
                    if (param.required()) {
                        return QuestEditorText.line("check.param_required", n, Msg.raw(param.name()));
                    }
                    continue;
                }
                boolean reads = switch (param.type()) {
                    case INTEGER -> wholeLong(value) != null;
                    case NUMBER -> decimal(value) != null;
                    default -> true;
                };
                if (!reads) {
                    return QuestEditorText.line("check.param_number", n, Msg.raw(param.name()));
                }
            }
        }
        return null;
    }

    @Nullable
    private Message checkRules(@Nonnull WizardSession session) {
        if (!requiresKept && !session.value(REQ_FACTOR).isBlank() && !session.value(REQ_MIN).isBlank()
                && decimal(session.value(REQ_MIN)) == null) {
            return QuestEditorText.line("check.req_min");
        }
        if (!session.value(REPEAT_HOURS).isBlank() && atLeastZero(session.value(REPEAT_HOURS)) == null) {
            return QuestEditorText.line("check.repeat_hours");
        }
        if (!session.value(REPEAT_MAX).isBlank() && atLeastZero(session.value(REPEAT_MAX)) == null) {
            return QuestEditorText.line("check.repeat_max");
        }
        return null;
    }

    // ---------------------------------------------------------------- the save

    @Nonnull
    @Override
    public List<ServerContent.Result> save(@Nonnull WizardSession session) {
        sync(session);
        AssetStore<String, QuestAsset, DefaultAssetMap<String, QuestAsset>> store = questStore();
        String title = session.value(TITLE).trim();
        String id = session.idOr(() -> ServerContent.mintId(store, title));

        Map<String, String> texts = new LinkedHashMap<>();
        String titleKey = ServerContent.textKey(store, id, "title");
        texts.put(titleKey, title);
        String flavor = session.value(FLAVOR).trim();
        String flavorKey = null;
        if (!flavor.isEmpty()) {
            String key = ServerContent.textKey(store, id, "flavor");
            texts.put(key, flavor);
            flavorKey = ServerContent.storedKey(key);
        }

        Set<String> taken = new HashSet<>(fileStepKeys);
        for (int n = 1; n <= STEP_SLOTS; n++) {
            String key = session.value(step(n, "key")).trim();
            if (!key.isEmpty()) {
                taken.add(key.toLowerCase(Locale.ROOT));
            }
        }
        List<QuestForm.Step> steps = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int n = 1; n <= STEP_SLOTS; n++) {
            String kind = kindOf(session, n);
            String saved = blankToNull(session.value(step(n, "key")));
            if (kind == null) {
                if (saved != null) {
                    // An emptied step leaves the file and its key is retired; the slot starts afresh.
                    dropped.add(saved);
                    forgetKey(session, n);
                }
                continue;
            }
            String target = session.value(step(n, "target")).trim();
            String line = session.value(step(n, "line")).trim();
            String key = QuestDraft.keyFor(saved, blankToNull(session.value(step(n, KEY_KIND))), kind,
                    line.isEmpty() ? kind + " " + target : line, taken::contains);
            String replaces = null;
            if (saved != null && !saved.equals(key)) {
                // A changed kind is a new step: it takes a new key in the old one's place, which is retired, so no
                // player's progress on the old step carries onto it.
                replaces = saved;
                dropped.add(saved);
                session.put(step(n, "keep"), null);
            }
            taken.add(key.toLowerCase(Locale.ROOT));
            session.put(step(n, "key"), key);
            session.put(step(n, KEY_KIND), kind);
            String textKey;
            if (!line.isEmpty()) {
                String lineKey = ServerContent.textKey(store, id, "step." + key);
                texts.put(lineKey, line);
                textKey = ServerContent.storedKey(lineKey);
            } else {
                textKey = blankToNull(session.value(step(n, "keep")));
            }
            Long amount = amountOf(session, n);
            steps.add(new QuestForm.Step(key, kind, blankToNull(target), amount == null ? 1L : amount,
                    blankToNull(session.value(step(n, "match"))), textKey, replaces));
        }

        List<QuestForm.Reward> rewards = new ArrayList<>();
        for (int n = 1; n <= REWARD_SLOTS; n++) {
            String kind = rewardKindOf(session, n);
            if (kind == null) {
                continue;
            }
            RewardParams described = described(kind);
            if (described == null) {
                Map<String, String> free = freeParams(session.value(reward(n, "params")));
                rewards.add(new QuestForm.Reward(n - 1, kind, free == null ? Map.of() : free, Set.of(), true));
                continue;
            }
            Set<String> managed = new LinkedHashSet<>();
            for (RewardParam param : described.params()) {
                managed.add(param.name());
            }
            rewards.add(new QuestForm.Reward(n - 1, kind, rewardParams(session, n, kind), managed, false));
        }

        QuestForm.Requirements requires = null;
        if (!requiresKept) {
            String factor = blankToNull(session.value(REQ_FACTOR));
            requires = new QuestForm.Requirements(blankToNull(session.value(REQ_QUEST)),
                    blankToNull(session.value(REQ_PERMISSION)), factor,
                    factor == null ? null : blankToNull(session.value(REQ_PARAM)),
                    factor == null ? null : decimal(session.value(REQ_MIN)));
        }
        Integer hours = atLeastZero(session.value(REPEAT_HOURS));
        Integer max = atLeastZero(session.value(REPEAT_MAX));
        QuestForm.Repeat repeat = new QuestForm.Repeat(session.isOn(REPEAT),
                hours != null ? hours : cooldownKept ? QuestDraft.KEEP_COOLDOWN : 0,
                blankToNull(session.value(REPEAT_RESET)), max == null ? 0 : max);

        boolean newGiver = session.isOn(NEW_GIVER);
        String giver;
        String giverNameKey = null;
        if (newGiver) {
            String giverName = session.value(GIVER_NAME).trim();
            giver = blankToNull(session.value(NEW_GIVER_ID));
            if (giver == null) {
                giver = QuestGivers.mintId(giverName);
                session.put(NEW_GIVER_ID, giver);
            }
            String nameKey = ServerContent.textKey(QuestGivers.NAME_STORE, giver, "name");
            texts.put(nameKey, giverName);
            giverNameKey = ServerContent.storedKey(nameKey);
        } else {
            giver = blankToNull(session.value(GIVER));
        }

        QuestForm form = new QuestForm(ServerContent.storedKey(titleKey), flavorKey, giver, session.isOn(RETURN),
                session.isOn(SEQUENTIAL), session.isOn(AUTO_TRACK), steps, dropped, REWARD_SLOTS, rewards, requires,
                repeat);
        JsonObject doc = QuestDraft.write(form, original);
        original = doc;
        for (QuestForm.Step written : steps) {
            fileStepKeys.add(written.key().toLowerCase(Locale.ROOT));
        }
        for (String retired : dropped) {
            fileStepKeys.add(retired.toLowerCase(Locale.ROOT));
        }
        // The save closes up an emptied reward's row, so the slots name the rows as the file now holds them.
        seedRewards(session, QuestDraft.read(doc, STEP_SLOTS, REWARD_SLOTS).rewards());

        List<ServerContent.Result> results = new ArrayList<>();
        results.add(ServerContent.setTexts(texts));
        ServerContent.Result role = null;
        if (newGiver && giverNameKey != null && !QuestGivers.placed(giver)) {
            role = ServerContent.writeRole(giver, QuestGivers.roleJson(giverNameKey));
            results.add(role);
        }
        ServerContent.Result quest = ServerContent.write(store, id, doc.toString());
        results.add(quest);
        if (role != null) {
            results.add(QuestGivers.placeAfter(role, quest, admin, giver, session.value(GIVER_SPOT)));
        }
        return results;
    }

    /** Step slot {@code n} holds no saved key any more: its next step mints a new one. */
    private static void forgetKey(@Nonnull WizardSession session, int n) {
        session.put(step(n, "key"), null);
        session.put(step(n, KEY_KIND), null);
        session.put(step(n, "keep"), null);
    }

    // ---------------------------------------------------------------- reads

    /** The quest store the engine registered, which the server content pack writes into. */
    @Nonnull
    static AssetStore<String, QuestAsset, DefaultAssetMap<String, QuestAsset>> questStore() {
        return AssetRegistry.getAssetStore(QuestAsset.class);
    }

    /** The quests the server content pack holds, by id, each to its file: the ones this editor can open. */
    @Nonnull
    private static Map<String, Path> packQuests() {
        try {
            Map<String, Path> paths = questStore().getAssetMap().getPathMap(ServerContent.packId());
            return paths == null ? Map.of() : paths;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /** Step {@code n}'s kind, or null for an empty slot. */
    @Nullable
    private static String kindOf(@Nonnull WizardSession session, int n) {
        return blankToNull(session.value(step(n, "kind")));
    }

    /** Reward {@code n}'s kind, or null for an empty slot. */
    @Nullable
    private static String rewardKindOf(@Nonnull WizardSession session, int n) {
        return blankToNull(session.value(reward(n, "kind")));
    }

    /** Step {@code n}'s count, a whole number of at least 1, or null when it reads as none. */
    @Nullable
    private static Long amountOf(@Nonnull WizardSession session, int n) {
        Long amount = wholeLong(session.value(step(n, "amount")));
        return amount == null || amount < 1L ? null : amount;
    }

    /** Reward {@code n}'s parameters as typed, by the names its kind describes; blanks left out. */
    @Nonnull
    private static Map<String, String> rewardParams(@Nonnull WizardSession session, int n, @Nonnull String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        RewardParams described = described(kind);
        if (described == null) {
            Map<String, String> free = freeParams(session.value(reward(n, "params")));
            return free == null ? out : free;
        }
        for (RewardParam param : described.params()) {
            String value = session.value(rewardParam(n, param.name())).trim();
            if (!value.isEmpty()) {
                out.put(param.name(), value);
            }
        }
        return out;
    }

    /** What reward kind {@code kind}'s rewards may name, as its owner described it; null when undescribed. */
    @Nullable
    private static RewardParams described(@Nonnull String kind) {
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
    private static String freeText(@Nonnull Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(", ", parts);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean hasBackslash(@Nullable String value) {
        return value != null && value.indexOf('\\') >= 0;
    }

    @Nullable
    private static Long wholeLong(@Nullable String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Integer wholeOf(@Nullable String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A whole number of at least 0, or null. */
    @Nullable
    private static Integer atLeastZero(@Nullable String value) {
        Integer whole = wholeOf(value);
        return whole == null || whole < 0 ? null : whole;
    }

    @Nullable
    private static Double decimal(@Nullable String value) {
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
    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : Double.toString(value);
    }

    // ---------------------------------------------------------------- rows

    @Nonnull
    private static WizardField text(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.text(id, id, QuestEditorText.key(labelKey)));
    }

    @Nonnull
    private static WizardField integer(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.integer(id, id, QuestEditorText.key(labelKey)));
    }

    @Nonnull
    private static WizardField number(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.number(id, id, QuestEditorText.key(labelKey)));
    }

    @Nonnull
    private static WizardField toggle(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.toggle(id, QuestEditorText.key(labelKey)));
    }

    @Nonnull
    private static WizardField note(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.note(id, QuestEditorText.key(labelKey)));
    }

    @Nonnull
    private static WizardField header(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.header(id, QuestEditorText.key(labelKey)));
    }

    @Nonnull
    private static WizardField dropdown(@Nonnull String id, @Nonnull String labelKey, @Nonnull String[] values) {
        return WizardField.of(FieldSpec.dropdown(id, id, QuestEditorText.key(labelKey), values));
    }

    @Nonnull
    private static WizardField picker(@Nonnull String id, @Nonnull String labelKey, @Nonnull Pickers.Source source) {
        return WizardField.picker(id, id, QuestEditorText.key(labelKey), source);
    }

    /** A row labelled with words built as it paints (its spec's label key is its field id). */
    @Nonnull
    private static WizardField own(@Nonnull FieldSpec spec, @Nonnull Message words) {
        return WizardField.of(spec).withLabel(words);
    }
}
