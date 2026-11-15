package com.ziggfreed.common.objectives.authoring;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
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
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.quest.asset.QuestAsset;
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
 * player reads ({@link ObjectiveSlotPages}); its rewards' kinds, from the live reward kinds; each reward's parameters,
 * from its kind's description ({@link RewardSlotPages}); and its requirements and repeat rule. Every reward is a Claim
 * the player collects. A step's key is minted once from its words and never renamed.
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

    private static final String KILL = "KILL_ENTITY";
    private static final String BREAK = "BREAK_BLOCK";
    private static final String PICK_UP = "PICKUP_ITEM";

    private static final String[] PERIODS = {"", QuestAsset.Repeat.Reset.PERIOD_DAILY,
            QuestAsset.Repeat.Reset.PERIOD_WEEKLY};

    private static final EditorWords W = QuestEditorText.WORDS;

    /** The step slots: {@code s<n>.<part>}. */
    private static final ObjectiveSlotPages STEPS = new ObjectiveSlotPages(W, STEP_SLOTS, "s", List.of());

    /** The reward slots: {@code r<n>.<part>}. */
    private static final RewardSlotPages REWARDS = new RewardSlotPages(W, REWARD_SLOTS, QuestEditorPicks.rewardKinds());

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

    // ---------------------------------------------------------------- the pages

    @Nonnull
    @Override
    public List<WizardStep> steps(@Nonnull WizardSession session) {
        sync(session);
        return List.of(questPage(session), giverPage(), stepsPage(), STEPS.targetsPage(session), REWARDS.kindsPage(),
                REWARDS.paramsPage(session, List.of()), rulesPage());
    }

    @Nonnull
    private static WizardStep questPage(@Nonnull WizardSession session) {
        List<WizardField> fields = new ArrayList<>();
        if (session.id() == null && !packQuests().isEmpty()) {
            fields.add(W.picker(EDIT, "field.edit", Pickers.of(() -> packQuests().keySet()))
                    .withHint(W.key("hint.edit")));
        }
        fields.add(W.note("questNote", "note.quest"));
        fields.add(W.text(TITLE, "field.title"));
        fields.add(W.text(FLAVOR, "field.flavor").withHint(W.key("hint.flavor")));
        return new WizardStep(W.key("page.quest"), fields);
    }

    @Nonnull
    private static WizardStep giverPage() {
        return WizardStep.of(W.key("page.giver"),
                W.note("giverNote", "note.giver"),
                W.picker(GIVER, "field.giver", QuestEditorPicks.characters())
                        .fromWorld(QuestEditorPicks::characterOf)
                        .withHint(W.key("hint.giver")),
                W.toggle(RETURN, "field.return"),
                W.header("newGiverHeader", "header.new_giver"),
                W.note("newGiverNote", "note.new_giver"),
                W.toggle(NEW_GIVER, "field.new_giver"),
                W.text(GIVER_NAME, "field.giver_name").withHint(W.key("hint.giver_name")),
                W.text(GIVER_SPOT, "field.giver_spot").fromWorld(WorldPick::position)
                        .withHint(W.key("hint.giver_spot")));
    }

    @Nonnull
    private static WizardStep stepsPage() {
        List<WizardField> fields = new ArrayList<>(STEPS.kindRows());
        fields.add(W.header("flowHeader", "header.flow"));
        fields.add(W.toggle(SEQUENTIAL, "field.sequential"));
        fields.add(W.toggle(AUTO_TRACK, "field.auto_track"));
        return new WizardStep(W.key("page.steps"), fields);
    }

    @Nonnull
    private WizardStep rulesPage() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(W.header("requiresHeader", "header.requires"));
        if (requiresKept) {
            fields.add(W.note("requiresKept", "note.requires_kept"));
        } else {
            fields.add(W.picker(REQ_QUEST, "field.req_quest", QuestEditorPicks.quests()));
            fields.add(W.text(REQ_PERMISSION, "field.req_permission"));
            fields.add(W.picker(REQ_FACTOR, "field.req_factor", QuestEditorPicks.factors()));
            fields.add(W.text(REQ_PARAM, "field.req_param"));
            fields.add(W.number(REQ_MIN, "field.req_min"));
        }
        fields.add(W.header("repeatHeader", "header.repeat"));
        fields.add(W.toggle(REPEAT, "field.repeat"));
        fields.add(W.integer(REPEAT_HOURS, "field.repeat_hours").withHint(W.key("hint.repeat_hours")));
        fields.add(W.dropdown(REPEAT_RESET, "field.repeat_reset", PERIODS));
        fields.add(W.integer(REPEAT_MAX, "field.repeat_max").withHint(W.key("hint.repeat_max")));
        return new WizardStep(W.key("page.rules"), fields);
    }

    // ---------------------------------------------------------------- opening

    /** Seed the defaults, then what the Quest Tool pointed at. */
    @Override
    public void begin(@Nonnull WizardSession session, @Nonnull AuthoringUse use) {
        session.put(AUTO_TRACK, "on");
        session.put(SEQUENTIAL, "on");
        STEPS.seedAmounts(session);
        WorldPick pick = use.pick();
        if (pick.hasEntity()) {
            String character = QuestEditorPicks.isCharacter(pick) ? QuestEditorPicks.characterOf(pick) : null;
            if (character != null) {
                session.put(GIVER, character);
            } else if (pick.npcRole() != null) {
                STEPS.seed(session, KILL, pick.npcRole());
            } else if (pick.droppedItemId() != null) {
                STEPS.seed(session, PICK_UP, pick.droppedItemId());
            }
        } else if (pick.hasBlock()) {
            STEPS.seed(session, BREAK, pick.blockId());
        }
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

        List<KeyedRows.Row> rows = new ArrayList<>();
        for (QuestForm.Step step : loaded.steps()) {
            rows.add(QuestDraft.row(step));
        }
        STEPS.fill(session, rows, key -> words(key, null));

        REWARDS.seed(session, loaded.rewards());

        QuestForm.Requirements requires = loaded.requires();
        if (requires != null) {
            session.put(REQ_QUEST, requires.quest());
            session.put(REQ_PERMISSION, requires.permission());
            session.put(REQ_FACTOR, requires.factor());
            session.put(REQ_PARAM, requires.param());
            session.put(REQ_MIN, requires.min() == null ? null : Typed.number(requires.min()));
        }
        QuestForm.Repeat repeat = loaded.repeat();
        flag(session, REPEAT, repeat.on());
        session.put(REPEAT_HOURS, repeat.cooldownHours() > 0 ? Integer.toString(repeat.cooldownHours()) : null);
        session.put(REPEAT_RESET, repeat.period());
        session.put(REPEAT_MAX, repeat.maxCompletions() > 0 ? Integer.toString(repeat.maxCompletions()) : null);
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

        STEPS.preview(session, lines);
        REWARDS.preview(session, lines);

        previewRequires(session, lines);
        previewRepeat(session, lines);
        if (session.step() == PAGE_PARAMS) {
            REWARDS.previewKinds(session, lines);
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
        Integer hours = Typed.wholeOf(session.value(REPEAT_HOURS));
        if (hours != null && hours > 0) {
            lines.add(QuestEditorText.line("preview.repeat_wait", hours));
        }
        String period = session.value(REPEAT_RESET).trim();
        if (!period.isEmpty()) {
            lines.add(QuestEditorText.line("preview.repeat_period", Msg.raw(period)));
        }
        Integer max = Typed.wholeOf(session.value(REPEAT_MAX));
        if (max != null && max > 0) {
            lines.add(QuestEditorText.line("preview.repeat_max", max));
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
            case PAGE_STEPS -> STEPS.checkKinds(session);
            case PAGE_TARGETS -> STEPS.checkTargets(session);
            case PAGE_PARAMS -> REWARDS.check(session);
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
        return Typed.hasBackslash(session.value(TITLE)) || Typed.hasBackslash(session.value(FLAVOR))
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
        if (Typed.hasBackslash(name)) {
            return QuestEditorText.line("check.backslash");
        }
        String spot = session.value(GIVER_SPOT);
        return !spot.isBlank() && QuestGivers.cell(spot) == null ? QuestEditorText.line("check.giver_spot") : null;
    }

    @Nullable
    private Message checkRules(@Nonnull WizardSession session) {
        if (!requiresKept && !session.value(REQ_FACTOR).isBlank() && !session.value(REQ_MIN).isBlank()
                && Typed.decimal(session.value(REQ_MIN)) == null) {
            return QuestEditorText.line("check.req_min");
        }
        if (!session.value(REPEAT_HOURS).isBlank() && Typed.atLeastZero(session.value(REPEAT_HOURS)) == null) {
            return QuestEditorText.line("check.repeat_hours");
        }
        if (!session.value(REPEAT_MAX).isBlank() && Typed.atLeastZero(session.value(REPEAT_MAX)) == null) {
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

        ObjectiveSlotPages.Collected collected = STEPS.collect(session, fileStepKeys, QuestDraft.STEPS,
                (n, key, line, replaced) -> {
                    if (!line.isEmpty()) {
                        String lineKey = ServerContent.textKey(store, id, "step." + key);
                        texts.put(lineKey, line);
                        return ServerContent.storedKey(lineKey);
                    }
                    return Typed.blankToNull(session.value(STEPS.field(n, "keep")));
                });
        List<QuestForm.Step> steps = new ArrayList<>();
        for (KeyedRows.Row row : collected.rows()) {
            steps.add(QuestDraft.step(row));
        }

        List<QuestForm.Reward> rewards = new ArrayList<>();
        for (ClaimSlots.Slot slot : REWARDS.collect(session)) {
            rewards.add(new QuestForm.Reward(slot.slot(), slot.kind(), slot.params(), slot.managed(), slot.whole()));
        }

        QuestForm.Requirements requires = null;
        if (!requiresKept) {
            String factor = Typed.blankToNull(session.value(REQ_FACTOR));
            requires = new QuestForm.Requirements(Typed.blankToNull(session.value(REQ_QUEST)),
                    Typed.blankToNull(session.value(REQ_PERMISSION)), factor,
                    factor == null ? null : Typed.blankToNull(session.value(REQ_PARAM)),
                    factor == null ? null : Typed.decimal(session.value(REQ_MIN)));
        }
        Integer hours = Typed.atLeastZero(session.value(REPEAT_HOURS));
        Integer max = Typed.atLeastZero(session.value(REPEAT_MAX));
        QuestForm.Repeat repeat = new QuestForm.Repeat(session.isOn(REPEAT),
                hours != null ? hours : cooldownKept ? QuestDraft.KEEP_COOLDOWN : 0,
                Typed.blankToNull(session.value(REPEAT_RESET)), max == null ? 0 : max);

        boolean newGiver = session.isOn(NEW_GIVER);
        String giver;
        String giverNameKey = null;
        if (newGiver) {
            String giverName = session.value(GIVER_NAME).trim();
            giver = Typed.blankToNull(session.value(NEW_GIVER_ID));
            if (giver == null) {
                giver = QuestGivers.mintId(giverName);
                session.put(NEW_GIVER_ID, giver);
            }
            String nameKey = ServerContent.textKey(QuestGivers.NAME_STORE, giver, "name");
            texts.put(nameKey, giverName);
            giverNameKey = ServerContent.storedKey(nameKey);
        } else {
            giver = Typed.blankToNull(session.value(GIVER));
        }

        QuestForm form = new QuestForm(ServerContent.storedKey(titleKey), flavorKey, giver, session.isOn(RETURN),
                session.isOn(SEQUENTIAL), session.isOn(AUTO_TRACK), steps, collected.dropped(), REWARD_SLOTS, rewards,
                requires, repeat);
        JsonObject doc = QuestDraft.write(form, original);
        original = doc;
        collected.remember(fileStepKeys);
        // The save closes up an emptied reward's row, so the slots name the rows as the file now holds them.
        REWARDS.seed(session, QuestDraft.read(doc, STEP_SLOTS, REWARD_SLOTS).rewards());

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
}
