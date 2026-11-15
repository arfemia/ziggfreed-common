package com.ziggfreed.common.objectives.authoring;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.authoring.AuthoringUse;
import com.ziggfreed.common.authoring.AuthoringWizard;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.authoring.WizardField;
import com.ziggfreed.common.authoring.WizardSession;
import com.ziggfreed.common.authoring.WizardStep;
import com.ziggfreed.common.authoring.WorldPick;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.title.TitleAsset;
import com.ziggfreed.common.objectives.title.TitleText;
import com.ziggfreed.common.progress.ConventionKeys;
import com.ziggfreed.common.util.SafeLog;

/**
 * The achievement editor, on the authoring wizard frame. It makes an achievement, or opens one the server content
 * pack holds or one a pack ships, and saves it into the server content pack through {@link ServerContent}: the words
 * typed for it (each a language key), a new category's file, a new title's file, then the achievement file
 * ({@link AchievementDraft}), in that order, so the title row never names a title that has not loaded.
 *
 * <p>Seven pages: the achievement's name and words (or a saved one to open); its category (picked, or made on the
 * spot), subcategory, points and whether it is in circulation; its criteria's kinds and counts, from the live
 * objective kinds; each criterion's target, how it matches and its line ({@link ObjectiveSlotPages}); its title (none,
 * one that exists, or one made on the spot with its name, flavour and where it sits around a player's name); its
 * rewards' kinds; and each reward's parameters ({@link RewardSlotPages}). Every reward is a Claim the player collects;
 * the title is the one row that lands the moment it is earned. A criterion's key is minted once and never renamed.
 *
 * <p><b>Shipped achievements.</b> Saving one a pack ships writes a copy of the same id into the server content pack,
 * started from the shipped file (its {@code Requires}, {@code Meta} and words carry over), which the store serves over
 * the shipped one from then on. Words left as they were keep their shipped key and its translations; words retyped
 * become a server key. A server copy can be taken away again, which brings the shipped one back.
 *
 * <p>The Achievement Tool opens it on what it points at: a block becomes a "break" criterion, a creature a "kill"
 * one, an item on the ground a "pick up" one and a character a "talk to" one. World thread, as every wizard method.
 */
public final class AchievementWizard implements AuthoringWizard {

    /** The wizard's and its tool's id: its author node is {@code ziggfreedcommon.author.achievement}. */
    public static final String ID = "Achievement";

    /** How many criteria the editor shows. */
    static final int CRITERION_SLOTS = 5;

    /** How many reward rows the editor shows. */
    static final int REWARD_SLOTS = 3;

    static final int PAGE_ACHIEVEMENT = 0;
    static final int PAGE_LISTING = 1;
    static final int PAGE_CRITERIA = 2;
    static final int PAGE_DETAILS = 3;
    static final int PAGE_TITLE = 4;
    static final int PAGE_REWARDS = 5;
    static final int PAGE_PARAMS = 6;

    static final String EDIT = "edit";
    static final String EDIT_SHIPPED = "editShipped";
    static final String TITLE = "title";
    static final String FLAVOR = "flavor";
    static final String CATEGORY = "category";
    static final String NEW_CATEGORY = "newCategory";
    static final String CATEGORY_NAME = "categoryName";
    static final String SUBCATEGORY = "subcategory";
    static final String POINTS = "points";
    static final String ENABLED = "enabled";
    static final String TITLE_PICK = "titlePick";
    static final String NEW_TITLE = "newTitle";
    static final String TITLE_NAME = "titleName";
    static final String TITLE_FLAVOR = "titleFlavor";
    static final String TITLE_PLACE = "titlePlace";
    static final String RESTORE = "restore";

    /** No row: the new category's id, minted once for the session. */
    static final String NEW_CATEGORY_ID = "newCategoryId";

    /** No row: the new title's id, minted once for the session. */
    static final String NEW_TITLE_ID = "newTitleId";

    /** No row, per criterion slot: the {@code TextKey} its file holds, and the words that key showed. */
    static final String LINE_KEY = "lineKey";
    static final String LINE_SHOWN = "lineShown";

    private static final String KILL = "KILL_ENTITY";
    private static final String BREAK = "BREAK_BLOCK";
    private static final String PICK_UP = "PICKUP_ITEM";
    private static final String TALK = "TALK_TO_NPC";

    private static final String[] PLACES = {AchievementDraft.TitlePlace.AFTER.name(),
            AchievementDraft.TitlePlace.COMMA.name(), AchievementDraft.TitlePlace.BEFORE.name()};

    private static final EditorWords W = AchievementEditorText.WORDS;

    /** The criterion slots: {@code c<n>.<part>}. */
    private static final ObjectiveSlotPages CRITERIA = new ObjectiveSlotPages(W, CRITERION_SLOTS, "c",
            List.of(LINE_KEY, LINE_SHOWN));

    /** The reward slots: {@code r<n>.<part>}. */
    private static final RewardSlotPages REWARDS = new RewardSlotPages(W, REWARD_SLOTS,
            AchievementEditorPicks.rewardKinds());

    /** The saved achievement last chosen to open, so a choice is read once. */
    @Nullable private String attempted;
    /** The file this session edits (the one opened, or what the last save wrote), or null for a new one. */
    @Nullable private JsonObject original;
    /** Why the chosen achievement could not be opened, or null. */
    @Nullable private Message loadProblem;
    /** True once a saved achievement was opened, so the session edits it. */
    private boolean opened;
    /** Every criterion key the file holds or retires, lower-cased, which a new criterion's key never takes. */
    @Nonnull private final Set<String> fileCriterionKeys = new HashSet<>();
    /** The file's {@code Text.TitleKey} (null when the convention line names it) and the words it showed. */
    @Nullable private String fileTitleKey;
    @Nonnull private String shownTitle = "";
    /** The file's {@code Text.FlavorKey} and the words it showed. */
    @Nullable private String fileFlavorKey;
    @Nonnull private String shownFlavor = "";

    AchievementWizard(@Nonnull AuthoringUse use) {
    }

    @Nonnull
    @Override
    public Message title() {
        return AchievementEditorText.line("title");
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
        return List.of(achievementPage(session), listingPage(), criteriaPage(), CRITERIA.targetsPage(session),
                titlePage(), REWARDS.kindsPage(), REWARDS.paramsPage(session, restoreRows(session)));
    }

    @Nonnull
    private static WizardStep achievementPage(@Nonnull WizardSession session) {
        List<WizardField> fields = new ArrayList<>();
        if (session.id() == null) {
            if (!AchievementEditorPicks.serverAchievements().isEmpty()) {
                fields.add(W.picker(EDIT, "field.edit", Pickers.of(AchievementEditorPicks::serverAchievements))
                        .withHint(W.key("hint.edit")));
            }
            if (!AchievementEditorPicks.shippedAchievements().isEmpty()) {
                fields.add(W.picker(EDIT_SHIPPED, "field.edit_shipped",
                        Pickers.of(AchievementEditorPicks::shippedAchievements)).withHint(W.key("hint.edit_shipped")));
            }
        }
        fields.add(W.note("achievementNote", "note.achievement"));
        fields.add(W.text(TITLE, "field.title"));
        fields.add(W.text(FLAVOR, "field.flavor").withHint(W.key("hint.flavor")));
        return new WizardStep(W.key("page.achievement"), fields);
    }

    @Nonnull
    private static WizardStep listingPage() {
        return WizardStep.of(W.key("page.listing"),
                W.note("listingNote", "note.listing"),
                W.picker(CATEGORY, "field.category", AchievementEditorPicks.categoryList())
                        .withHint(W.key("hint.category")),
                W.header("newCategoryHeader", "header.new_category"),
                W.toggle(NEW_CATEGORY, "field.new_category"),
                W.text(CATEGORY_NAME, "field.category_name").withHint(W.key("hint.category_name")),
                W.header("worthHeader", "header.worth"),
                W.text(SUBCATEGORY, "field.subcategory").withHint(W.key("hint.subcategory")),
                W.integer(POINTS, "field.points").withHint(W.key("hint.points")),
                W.note("enabledNote", "note.enabled"),
                W.toggle(ENABLED, "field.enabled"));
    }

    @Nonnull
    private static WizardStep criteriaPage() {
        return new WizardStep(W.key("page.criteria"), CRITERIA.kindRows());
    }

    @Nonnull
    private static WizardStep titlePage() {
        return WizardStep.of(W.key("page.title"),
                W.note("titleNote", "note.title"),
                W.picker(TITLE_PICK, "field.title_pick", AchievementEditorPicks.titleList())
                        .withHint(W.key("hint.title_pick")),
                W.header("newTitleHeader", "header.new_title"),
                W.toggle(NEW_TITLE, "field.new_title"),
                W.text(TITLE_NAME, "field.title_name").withHint(W.key("hint.title_name")),
                W.text(TITLE_FLAVOR, "field.title_flavor").withHint(W.key("hint.title_flavor")),
                W.dropdown(TITLE_PLACE, "field.title_place", PLACES).withHint(W.key("hint.title_place")));
    }

    /** On a server copy of a shipped achievement: the switch that takes the copy away at Save. */
    @Nonnull
    private static List<WizardField> restoreRows(@Nonnull WizardSession session) {
        if (!AchievementEditorPicks.serverCopy(session.id())) {
            return List.of();
        }
        return List.of(W.header("restoreHeader", "header.restore"),
                W.note("restoreNote", "note.restore"),
                W.toggle(RESTORE, "field.restore"));
    }

    // ---------------------------------------------------------------- opening

    /** Seed the defaults, then what the Achievement Tool pointed at as the first criterion. */
    @Override
    public void begin(@Nonnull WizardSession session, @Nonnull AuthoringUse use) {
        CRITERIA.seedAmounts(session);
        session.put(POINTS, Integer.toString(AchievementAsset.Scoring.DEFAULT_POINTS));
        session.put(ENABLED, "on");
        session.put(TITLE_PLACE, AchievementDraft.TitlePlace.AFTER.name());
        WorldPick pick = use.pick();
        if (pick.hasEntity()) {
            String character = QuestEditorPicks.isCharacter(pick) ? QuestEditorPicks.characterOf(pick) : null;
            if (character != null) {
                CRITERIA.seed(session, TALK, character);
            } else if (pick.npcRole() != null) {
                CRITERIA.seed(session, KILL, pick.npcRole());
            } else if (pick.droppedItemId() != null) {
                CRITERIA.seed(session, PICK_UP, pick.droppedItemId());
            }
        } else if (pick.hasBlock()) {
            CRITERIA.seed(session, BREAK, pick.blockId());
        }
    }

    /**
     * Open the achievement the admin chose, once: the server content pack's own file, or the shipped one, is read and
     * every page filled from it, and the session keeps its id. A file built on another, a yearly one or a capstone is
     * not opened here, and neither is a shipped one whose id comes from its folder (its copy would take another id).
     */
    private void sync(@Nonnull WizardSession session) {
        if (session.id() != null || original != null) {
            return;
        }
        String chosen = session.value(EDIT).trim();
        boolean fromShipped = false;
        if (chosen.isEmpty()) {
            chosen = session.value(EDIT_SHIPPED).trim();
            fromShipped = true;
        }
        if (chosen.isEmpty()) {
            attempted = null;
            loadProblem = null;
            return;
        }
        String attempt = (fromShipped ? "shipped:" : "pack:") + chosen;
        if (attempt.equals(attempted)) {
            return;
        }
        attempted = attempt;
        loadProblem = null;
        String fileId = null;
        for (String id : fromShipped ? AchievementEditorPicks.shippedAchievements()
                : AchievementEditorPicks.serverAchievements()) {
            if (id.equalsIgnoreCase(chosen)) {
                fileId = id;
            }
        }
        if (fileId == null) {
            loadProblem = AchievementEditorText.line("load.missing", Msg.raw(chosen));
            return;
        }
        JsonObject doc = parse(fromShipped ? readShipped(fileId)
                : ServerContent.read(AchievementEditorPicks.achievements(), fileId));
        if (doc == null) {
            SafeLog.warn("[achievement-editor] the achievement " + fileId + " could not be read");
            loadProblem = AchievementEditorText.line("load.unreadable", Msg.raw(chosen));
            return;
        }
        if (fromShipped && !ownId(fileId)) {
            loadProblem = AchievementEditorText.line("load.nested", Msg.raw(chosen));
            return;
        }
        AchievementDraft.Loaded loaded = AchievementDraft.read(doc, CRITERION_SLOTS, REWARD_SLOTS);
        if (loaded.refusal() != null) {
            loadProblem = AchievementEditorText.line("load." + loaded.refusal().name().toLowerCase(Locale.ROOT),
                    Msg.raw(chosen));
            return;
        }
        session.keepId(fileId);
        original = doc;
        opened = true;
        fill(session, loaded, fileId);
    }

    /** The shipped file the store serves for {@code id}, as written; null when it cannot be read. */
    @Nullable
    private static String readShipped(@Nonnull String id) {
        try {
            Path file = AchievementEditorPicks.achievements().getAssetMap().getPath(id);
            return file == null ? null : Files.readString(file, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            SafeLog.warn("[achievement-editor] the shipped achievement " + id + " could not be read: " + t.getMessage());
            return null;
        }
    }

    /**
     * True when the achievement's id is its file name alone, so a copy written beside the server content pack's
     * other achievements keeps it: a file under a {@code _}-marked folder takes that folder into its id.
     */
    private static boolean ownId(@Nonnull String fileId) {
        try {
            AchievementAsset asset = AchievementEditorPicks.achievements().getAssetMap().getAsset(fileId);
            return asset == null || asset.getId() == null || asset.getId().equalsIgnoreCase(fileId);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private static JsonObject parse(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Every page's values from a saved achievement. */
    private void fill(@Nonnull WizardSession session, @Nonnull AchievementDraft.Loaded loaded, @Nonnull String fileId) {
        fileCriterionKeys.addAll(AchievementDraft.taken(loaded));
        String lowerId = fileId.toLowerCase(Locale.ROOT);
        fileTitleKey = loaded.titleKey();
        shownTitle = fileTitleKey != null ? wordsOf(fileTitleKey)
                : firstWords(ConventionKeys.achievementTitle(lowerId), loaded.displayName());
        fileFlavorKey = loaded.flavorKey();
        shownFlavor = fileFlavorKey != null ? wordsOf(fileFlavorKey)
                : firstWords(ConventionKeys.achievementDescription(lowerId), null);

        session.put(TITLE, shownTitle);
        session.put(FLAVOR, shownFlavor);
        session.put(CATEGORY, loaded.category());
        session.put(NEW_CATEGORY, "");
        session.put(SUBCATEGORY, loaded.subcategory());
        session.put(POINTS, Integer.toString(loaded.points()));
        session.put(ENABLED, loaded.enabled() ? "on" : "");

        CRITERIA.fill(session, loaded.criteria(), AchievementWizard::wordsOf);
        for (int i = 0; i < loaded.criteria().size() && i < CRITERION_SLOTS; i++) {
            String key = loaded.criteria().get(i).textKey();
            session.put(CRITERIA.field(i + 1, LINE_KEY), key);
            session.put(CRITERIA.field(i + 1, LINE_SHOWN), key == null ? null : wordsOf(key));
        }

        session.put(TITLE_PICK, loaded.title());
        session.put(NEW_TITLE, "");
        REWARDS.seed(session, loaded.rewards());
    }

    /** The English a key holds: the words this pack saved, else what the server loaded under it; blank for none. */
    @Nonnull
    private static String wordsOf(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String saved = ServerContent.text(key);
        if (saved != null) {
            return saved;
        }
        String loaded = LangCatalog.value(ContentKeys.resolved(key));
        return loaded == null ? "" : loaded;
    }

    /** The words of the convention key {@code key}, else {@code fallback}, else blank. */
    @Nonnull
    private static String firstWords(@Nullable String key, @Nullable String fallback) {
        String words = wordsOf(key);
        return !words.isEmpty() ? words : fallback == null ? "" : fallback;
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
        lines.add(Msg.bold(title.isEmpty() ? AchievementEditorText.line("preview.untitled") : Msg.raw(title)));
        String id = session.id();
        lines.add(id == null ? AchievementEditorText.line("preview.new")
                : AchievementEditorText.line(opened ? "preview.editing" : "preview.saved_as", Msg.raw(id)));
        if (AchievementEditorPicks.shipped(id)) {
            lines.add(AchievementEditorText.line("preview.shipped"));
        } else if (AchievementEditorPicks.serverCopy(id)) {
            lines.add(AchievementEditorText.line("preview.server_copy"));
            if (session.isOn(RESTORE)) {
                lines.add(AchievementEditorText.line("preview.restore"));
                return lines;
            }
        }

        previewListing(session, lines);

        boolean any = false;
        for (int n = 1; n <= CRITERION_SLOTS; n++) {
            any |= CRITERIA.kindOf(session, n) != null;
        }
        if (any) {
            lines.add(AchievementEditorText.line("preview.all"));
        }
        CRITERIA.preview(session, lines);

        previewTitle(session, lines);
        REWARDS.preview(session, lines);

        if (opened) {
            lines.add(AchievementEditorText.line("preview.earned_rewards"));
            lines.add(AchievementEditorText.line("preview.earned_title"));
            lines.add(AchievementEditorText.line("preview.earned_kind"));
        }
        if (session.step() == PAGE_PARAMS) {
            REWARDS.previewKinds(session, lines);
        }
        return lines;
    }

    private static void previewListing(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        String subcategory = session.value(SUBCATEGORY).trim();
        if (session.isOn(NEW_CATEGORY)) {
            String name = session.value(CATEGORY_NAME).trim();
            lines.add(name.isEmpty() ? AchievementEditorText.line("preview.category_new_unnamed")
                    : AchievementEditorText.line("preview.category_new", Msg.raw(name)));
        } else {
            String category = session.value(CATEGORY).trim();
            lines.add(category.isEmpty() ? AchievementEditorText.line("preview.no_category")
                    : AchievementEditorText.line("preview.category", Msg.raw(category)));
        }
        if (!subcategory.isEmpty()) {
            lines.add(AchievementEditorText.line("preview.subcategory", Msg.raw(subcategory)));
        }
        Integer points = Typed.atLeastZero(session.value(POINTS));
        lines.add(AchievementEditorText.line("preview.points",
                points == null ? AchievementAsset.Scoring.DEFAULT_POINTS : points));
        if (!session.isOn(ENABLED)) {
            lines.add(AchievementEditorText.line("preview.out"));
        }
    }

    private static void previewTitle(@Nonnull WizardSession session, @Nonnull List<Message> lines) {
        if (session.isOn(NEW_TITLE)) {
            String name = session.value(TITLE_NAME).trim();
            if (name.isEmpty()) {
                lines.add(AchievementEditorText.line("preview.title_new_unnamed"));
            } else {
                lines.add(AchievementEditorText.line("preview.title_new", Msg.raw(name)));
                String place = AchievementDraft.TitlePlace.of(session.value(TITLE_PLACE)).name()
                        .toLowerCase(Locale.ROOT);
                lines.add(AchievementEditorText.line("preview.place_" + place, Msg.raw(name)));
            }
        } else {
            String picked = session.value(TITLE_PICK).trim();
            if (picked.isEmpty()) {
                lines.add(AchievementEditorText.line("preview.no_title"));
                return;
            }
            lines.add(AchievementEditorText.line("preview.title", TitleText.nameOf(picked)));
        }
        lines.add(AchievementEditorText.line("preview.title_lands"));
    }

    // ---------------------------------------------------------------- the checks

    @Nullable
    @Override
    public Message check(@Nonnull WizardSession session, int step) {
        sync(session);
        if (restoring(session)) {
            return null;
        }
        return switch (step) {
            case PAGE_ACHIEVEMENT -> checkAchievement(session);
            case PAGE_LISTING -> checkListing(session);
            case PAGE_CRITERIA -> CRITERIA.checkKinds(session);
            case PAGE_DETAILS -> CRITERIA.checkTargets(session);
            case PAGE_TITLE -> checkTitle(session);
            case PAGE_PARAMS -> REWARDS.check(session);
            default -> null;
        };
    }

    @Nullable
    private Message checkAchievement(@Nonnull WizardSession session) {
        if (loadProblem != null && session.id() == null) {
            return loadProblem;
        }
        if (session.value(TITLE).isBlank()) {
            return AchievementEditorText.line("check.title");
        }
        return Typed.hasBackslash(session.value(TITLE)) || Typed.hasBackslash(session.value(FLAVOR))
                ? AchievementEditorText.line("check.backslash") : null;
    }

    @Nullable
    private static Message checkListing(@Nonnull WizardSession session) {
        if (session.isOn(NEW_CATEGORY)) {
            String name = session.value(CATEGORY_NAME);
            if (name.isBlank()) {
                return AchievementEditorText.line("check.category_name");
            }
            if (Typed.hasBackslash(name)) {
                return AchievementEditorText.line("check.backslash");
            }
        } else if (session.value(CATEGORY).isBlank()) {
            return AchievementEditorText.line("check.category");
        }
        if (!session.value(POINTS).isBlank() && Typed.atLeastZero(session.value(POINTS)) == null) {
            return AchievementEditorText.line("check.points");
        }
        return null;
    }

    @Nullable
    private static Message checkTitle(@Nonnull WizardSession session) {
        if (!session.isOn(NEW_TITLE)) {
            return null;
        }
        if (!session.value(TITLE_PICK).isBlank()) {
            return AchievementEditorText.line("check.title_both");
        }
        String name = session.value(TITLE_NAME);
        if (name.isBlank()) {
            return AchievementEditorText.line("check.title_name");
        }
        // The name is written into the display line beside the player's name, where a brace would read as a slot.
        if (Typed.hasBackslash(name) || name.indexOf('{') >= 0 || name.indexOf('}') >= 0
                || Typed.hasBackslash(session.value(TITLE_FLAVOR))) {
            return AchievementEditorText.line("check.title_words");
        }
        return null;
    }

    /** True when Save takes the server copy away instead of writing it. */
    private static boolean restoring(@Nonnull WizardSession session) {
        return session.isOn(RESTORE) && AchievementEditorPicks.serverCopy(session.id());
    }

    // ---------------------------------------------------------------- the save

    @Nonnull
    @Override
    public List<ServerContent.Result> save(@Nonnull WizardSession session) {
        sync(session);
        AssetStore<String, AchievementAsset, DefaultAssetMap<String, AchievementAsset>> store =
                AchievementEditorPicks.achievements();
        if (restoring(session) && session.id() != null) {
            // The copy goes and the shipped achievement is served again; Save once more brings this copy back.
            session.put(RESTORE, "");
            return List.of(ServerContent.remove(store, session.id()));
        }
        String title = session.value(TITLE).trim();
        String id = session.idOr(() -> ServerContent.mintId(store, title));
        Map<String, String> texts = new LinkedHashMap<>();

        String flavor = session.value(FLAVOR).trim();
        String titleKey = keptKey(fileTitleKey, shownTitle, title, store, id, "title", texts);
        String flavorKey = keptKey(fileFlavorKey, shownFlavor, flavor, store, id, "flavor", texts);

        // A new category: its own file, named by a server key.
        AssetStore<String, AchievementCategoryAsset, DefaultAssetMap<String, AchievementCategoryAsset>> categories =
                AchievementEditorPicks.categories();
        String category;
        String categoryId = null;
        JsonObject categoryDoc = null;
        if (session.isOn(NEW_CATEGORY)) {
            String name = session.value(CATEGORY_NAME).trim();
            categoryId = Typed.blankToNull(session.value(NEW_CATEGORY_ID));
            if (categoryId == null) {
                Set<String> taken = new HashSet<>(AchievementEditorPicks.categoryIds());
                categoryId = ServerContent.mintId(name, candidate -> taken.contains(candidate.toLowerCase(Locale.ROOT)));
                session.put(NEW_CATEGORY_ID, categoryId);
            }
            String nameKey = ServerContent.textKey(categories, categoryId, "name");
            texts.put(nameKey, name);
            categoryDoc = AchievementDraft.categoryDocument(ServerContent.storedKey(nameKey),
                    parse(ServerContent.read(categories, categoryId)));
            category = categoryId.toLowerCase(Locale.ROOT);
        } else {
            category = Typed.blankToNull(session.value(CATEGORY));
        }

        // The words each criterion line now shows, recorded only once the save is queued: a save turned away before
        // its words were stored leaves the old ones, so a second try stores them again.
        Map<String, String> linesShown = new HashMap<>();
        ObjectiveSlotPages.Collected collected = CRITERIA.collect(session, fileCriterionKeys, AchievementDraft.CRITERIA,
                (n, key, line, replaced) -> {
                    String fileKey = replaced ? null : Typed.blankToNull(session.value(CRITERIA.field(n, LINE_KEY)));
                    String shown = replaced ? "" : session.value(CRITERIA.field(n, LINE_SHOWN));
                    String written = keptKey(fileKey, shown, line, store, id, "criterion." + key, texts);
                    session.put(CRITERIA.field(n, LINE_KEY), written);
                    linesShown.put(CRITERIA.field(n, LINE_SHOWN), written == null ? null : line.isEmpty() ? shown : line);
                    return written;
                });

        // A new title: its own file and words, and the line placing it around a player's name.
        AssetStore<String, TitleAsset, DefaultAssetMap<String, TitleAsset>> titles = AchievementEditorPicks.titles();
        String titleId;
        JsonObject titleDoc = null;
        String displayKey = null;
        String displayLine = null;
        if (session.isOn(NEW_TITLE)) {
            String name = session.value(TITLE_NAME).trim();
            titleId = Typed.blankToNull(session.value(NEW_TITLE_ID));
            if (titleId == null) {
                titleId = ServerContent.mintId(titles, name);
                session.put(NEW_TITLE_ID, titleId);
            }
            String nameKey = ServerContent.textKey(titles, titleId, "name");
            texts.put(nameKey, name);
            String titleFlavor = session.value(TITLE_FLAVOR).trim();
            String titleFlavorKey = null;
            if (!titleFlavor.isEmpty()) {
                String key = ServerContent.textKey(titles, titleId, "flavor");
                texts.put(key, titleFlavor);
                titleFlavorKey = ServerContent.storedKey(key);
            }
            displayKey = AchievementDraft.displayKey(titleId);
            displayLine = AchievementDraft.TitlePlace.of(session.value(TITLE_PLACE)).displayLine(name);
            if (displayLine != null) {
                texts.put(displayKey, displayLine);
            }
            titleDoc = AchievementDraft.titleDocument(ServerContent.storedKey(nameKey), titleFlavorKey,
                    parse(ServerContent.read(titles, titleId)));
        } else {
            titleId = Typed.blankToNull(session.value(TITLE_PICK));
        }

        Integer points = Typed.atLeastZero(session.value(POINTS));
        AchievementForm form = new AchievementForm(titleKey, flavorKey, category, subcategory(session),
                points == null ? AchievementAsset.Scoring.DEFAULT_POINTS : points, session.isOn(ENABLED),
                collected.rows(), collected.dropped(), REWARD_SLOTS, REWARDS.collect(session), titleId);
        JsonObject doc = AchievementDraft.write(form, original);
        original = doc;
        collected.remember(fileCriterionKeys);
        fileTitleKey = titleKey;
        fileFlavorKey = flavorKey;
        // The save closes up an emptied reward's row, so the slots name the rows as the file now holds them.
        REWARDS.seed(session, AchievementDraft.read(doc, CRITERION_SLOTS, REWARD_SLOTS).rewards());

        List<ServerContent.Result> results = new ArrayList<>();
        results.add(ServerContent.setTexts(texts));
        if (displayKey != null && displayLine == null && ServerContent.text(displayKey) != null) {
            // After a comma is the shared line: a display line this session saved earlier goes.
            results.add(ServerContent.removeText(displayKey));
        }
        if (categoryDoc != null) {
            results.add(ServerContent.write(categories, categoryId, categoryDoc.toString()));
        }
        if (titleDoc != null) {
            results.add(ServerContent.write(titles, titleId, titleDoc.toString()));
        }
        for (ServerContent.Result before : results) {
            if (before.refused()) {
                // Its words, category or title were turned away before anything was queued: the achievement
                // waits rather than land naming a key, a category or a title that is not there.
                return results;
            }
        }
        shownTitle = title;
        shownFlavor = flavor;
        linesShown.forEach(session::put);
        results.add(ServerContent.write(store, id, doc.toString()));
        return results;
    }

    /**
     * The key typed words save under ({@link AchievementDraft#keptKey}): the file's own key wherever it can be kept,
     * else the server key {@code store.<id>.<field>}.
     */
    @Nullable
    private static String keptKey(@Nullable String fileKey, @Nonnull String shown, @Nonnull String typed,
            @Nonnull AssetStore<?, ?, ?> store, @Nonnull String id, @Nonnull String field,
            @Nonnull Map<String, String> texts) {
        return AchievementDraft.keptKey(fileKey, shown, typed, key -> ServerContent.text(key) != null,
                () -> ServerContent.storedKey(ServerContent.textKey(store, id, field)), texts);
    }

    /**
     * The subcategory as typed: kept as the file wrote it while it reads the same, else an id made from the words
     * ({@code Stone Work} files under {@code stone_work}), lower-cased as content writes one; null for none.
     */
    @Nullable
    private String subcategory(@Nonnull WizardSession session) {
        String typed = Typed.blankToNull(session.value(SUBCATEGORY));
        if (typed == null) {
            return null;
        }
        String written = original == null ? null : AchievementDraft.read(original, 0, 0).subcategory();
        if (written != null && written.equalsIgnoreCase(typed)) {
            return written;
        }
        return ServerContent.mintId(typed, candidate -> false).toLowerCase(Locale.ROOT);
    }
}
