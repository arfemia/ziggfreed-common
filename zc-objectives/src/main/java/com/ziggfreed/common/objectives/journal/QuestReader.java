package com.ziggfreed.common.objectives.journal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.book.BookVerbs;
import com.ziggfreed.common.objectives.questlist.CharacterQuestListing;
import com.ziggfreed.common.objectives.render.QuestCadenceBadge;
import com.ziggfreed.common.progress.CategoryNames;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionIcons;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.quest.LockReasons;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.quest.asset.QuestCategoryAsset;
import com.ziggfreed.common.quest.asset.QuestCategoryConfig;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.KitText;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.util.SafeLog;

/**
 * A quest and the engine's answers about it, read into the kit's records: the section it belongs in, its list row,
 * its page, the whole journal. Pure over the engine, the subject and the consumer's {@link QuestPresentation}, so
 * the book and the NPC quest page read a quest the same way and one fix lands in both.
 *
 * <p>A reader is a snapshot: it remembers each quest's status and accept answer the first time it is asked, so one
 * build reads one consistent log. Make a fresh reader after a verb changes anything.
 *
 * <p><b>What lists.</b> Every quest the catalogue offers ({@link Quest#available()}) that the player has started or
 * may see; a board-managed quest only while it is carried or waiting to be collected (offering it and showing it
 * finished are the board's job). <b>The page.</b> The quest's picture, title and meta; the board's pills or the
 * quest's tags; the Track toggle on a carried quest; the step tally; the reading paragraph; Requirements before it is
 * taken, Objectives (a later order group under its own "Step N" heading, ticks done, current and locked, a hidden
 * locked step left out), Rewards (what waits to be collected wears a pill) and any consumer blocks; the action bar
 * ({@link QuestActions}) and its hint.
 *
 * <p><b>Where it stands.</b> A row and a page read the same in the book and at a character, except for what the place
 * decides: a finished quest that cannot be collected where the player stands ({@link #collectsElsewhere}) reads
 * Elsewhere rather than a gold Collect, and its hint names the character it is collected from.
 */
public final class QuestReader {

    /** This library's lang prefix and the journal's domain ({@code ziggfreedcommon.journal.lang}). */
    public static final String PREFIX = "ziggfreedcommon.";
    public static final String DOMAIN = "journal.";

    /** The block ids a page carries, in order: before it is taken, its steps, a later step, what it pays. */
    public static final String BLOCK_REQUIREMENTS = "requirements";
    public static final String BLOCK_OBJECTIVES = "objectives";
    public static final String BLOCK_STEP = "step";
    public static final String BLOCK_REWARDS = "rewards";

    @Nonnull private final QuestEngine engine;
    @Nonnull private final Subject subject;
    @Nonnull private final QuestPresentation presentation;
    @Nullable private final UUID viewer;
    private final long nowMs;

    private final Map<String, QuestStatus> statuses = new HashMap<>();
    private final Map<String, Boolean> acceptable = new HashMap<>();
    @Nullable private List<Quest> listed;

    private QuestReader(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull QuestPresentation presentation, @Nullable UUID viewer, long nowMs) {
        this.engine = engine;
        this.subject = subject;
        this.presentation = presentation;
        this.viewer = viewer;
        this.nowMs = nowMs;
    }

    /** A reader for {@code subject}'s quests in {@code engine}, as {@code presentation} says they read. */
    @Nonnull
    public static QuestReader of(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull QuestPresentation presentation, @Nullable UUID viewer, long nowMs) {
        return new QuestReader(engine, subject, presentation, viewer, nowMs);
    }

    /** A key of {@code ziggfreedcommon.journal.lang}, without its prefix. */
    @Nonnull
    public static Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    @Nonnull
    public QuestEngine engine() {
        return engine;
    }

    @Nonnull
    public Subject subject() {
        return subject;
    }

    @Nonnull
    public QuestPresentation presentation() {
        return presentation;
    }

    @Nullable
    public UUID viewer() {
        return viewer;
    }

    public long nowMs() {
        return nowMs;
    }

    // ==================== what a quest is ====================

    /** What {@code q} effectively is for the subject. */
    @Nonnull
    public QuestStatus status(@Nonnull Quest q) {
        return statuses.computeIfAbsent(q.id(), id -> engine.status(subject, q));
    }

    /** Whether the accept gate passes now (an unstarted quest only; anything else reads false). */
    public boolean acceptable(@Nonnull Quest q) {
        if (status(q) != QuestStatus.NOT_STARTED) {
            return false;
        }
        return acceptable.computeIfAbsent(q.id(), id -> engine.canAccept(subject, q).allowed());
    }

    /** Whether the subject tracks {@code q}. */
    public boolean tracked(@Nonnull Quest q) {
        return engine.tracked(subject).contains(q.id());
    }

    /** The journal section {@code q} belongs in. */
    @Nonnull
    public QuestSection sectionOf(@Nonnull Quest q) {
        return QuestSection.of(status(q), acceptable(q));
    }

    /** Every quest the journal lists, before any filter, in catalogue order. */
    @Nonnull
    public List<Quest> listed() {
        if (listed != null) {
            return listed;
        }
        List<Quest> out = new ArrayList<>();
        for (Quest quest : engine.quests()) {
            if (quest == null || !quest.available()) {
                continue;
            }
            QuestStatus status = status(quest);
            if (status == QuestStatus.NOT_STARTED && !engine.isVisible(subject, quest)) {
                continue;
            }
            if (presentation.managed(quest) && !carried(status)) {
                continue;
            }
            out.add(quest);
        }
        listed = List.copyOf(out);
        return listed;
    }

    // ==================== rows ====================

    /** {@code q}'s standard list row, as the book reads it. */
    @Nonnull
    public LedgerRow row(@Nonnull Quest q) {
        return row(q, null);
    }

    /** {@code q}'s standard list row; with {@code here}, as it reads at that character (the NPC quest page). */
    @Nonnull
    public LedgerRow row(@Nonnull Quest q, @Nullable CharacterQuestListing here) {
        return read(q, here, false);
    }

    /** {@code q}'s compact row (an overview strip, the Skills page panel), as the book reads it: no meta line. */
    @Nonnull
    public LedgerRow compactRow(@Nonnull Quest q) {
        return read(q, null, true);
    }

    /**
     * A row: its section's tone and state word, except that a finished quest that cannot be collected where the
     * player stands reads Elsewhere in the neutral tone, since there is nothing to press here and nothing refuses it.
     */
    @Nonnull
    private LedgerRow read(@Nonnull Quest q, @Nullable CharacterQuestListing here, boolean compact) {
        QuestStatus status = status(q);
        QuestSection section = sectionOf(q);
        QuestEngine.ObjectiveTally tally = carried(status) ? engine.tally(subject, q) : null;
        Message value = tally == null ? null : KitText.count(tally.completed(), tally.total());
        Progress progress = tally != null && status == QuestStatus.ACTIVE
                ? new Progress(tally.completed(), tally.total()) : null;
        boolean elsewhere = collectsElsewhere(q, here);
        return new LedgerRow(q.id(), title(q), compact ? null : meta(q), picture(q),
                elsewhere ? Tone.NEUTRAL : section.tone(),
                elsewhere ? text("state.elsewhere") : stateWord(q, section), value, progress,
                tracked(q) ? Mark.TRACKED : Mark.NONE, section == QuestSection.COMPLETED);
    }

    /**
     * Whether {@code q} is finished and waits to be collected somewhere other than where the player stands: at
     * {@code here}, a character none of whose ids it may be collected under; in the book, a quest collected only at
     * its site ({@link QuestActions#collectableHere}). Such a quest reads Elsewhere, offers no Collect, and its hint
     * says where it is collected.
     */
    public boolean collectsElsewhere(@Nonnull Quest q, @Nullable CharacterQuestListing here) {
        return status(q) == QuestStatus.COMPLETED_UNCLAIMED && !QuestActions.collectableHere(q, here);
    }

    /** The state word a row wears, in its section's tone. */
    @Nonnull
    private Message stateWord(@Nonnull Quest q, @Nonnull QuestSection section) {
        return switch (section) {
            case READY -> text("state.collect");
            case IN_PROGRESS -> text("state.progress");
            case AVAILABLE -> text("state.available");
            case NOT_YET -> text("state.locked");
            case WAITING -> text("state.back_in", waitLine(q));
            case COMPLETED -> text("state.done");
        };
    }

    /** How long until {@code q} comes back, as a nested line (a spent calendar window included). */
    @Nonnull
    private Message waitLine(@Nonnull Quest q) {
        return LockReasons.waitLine(engine.offerableInMs(subject, q));
    }

    /**
     * The meta line: the category, how often it comes round, and who gives it; a board-managed quest's board words
     * in place of its giver. Null when there is nothing to say.
     */
    @Nullable
    private Message meta(@Nonnull Quest q) {
        List<Message> parts = new ArrayList<>();
        String category = q.category();
        if (category != null && !category.isBlank()) {
            parts.add(categoryName(category));
        }
        Message cadence = QuestCadenceBadge.label(q.repeat());
        if (cadence != null) {
            parts.add(cadence);
        }
        if (presentation.managed(q)) {
            for (Pill pill : presentation.pills(q)) {
                parts.add(pill.label());
            }
        } else {
            Message giver = presentation.npcName(q.npcViewId());
            if (giver != null) {
                parts.add(giver);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        List<Message> joined = new ArrayList<>();
        for (Message part : parts) {
            if (!joined.isEmpty()) {
                joined.add(Msg.raw(" - "));
            }
            joined.add(part);
        }
        return Msg.join(joined.toArray(new Message[0]));
    }

    // ==================== the page ====================

    /** {@code q}'s page as the book shows it. */
    @Nonnull
    public DetailView page(@Nonnull Quest q) {
        return page(q, null);
    }

    /** {@code q}'s page; with {@code here}, as it shows at that character (its actions and hint). */
    @Nonnull
    public DetailView page(@Nonnull Quest q, @Nullable CharacterQuestListing here) {
        QuestStatus status = status(q);
        QuestEngine.ObjectiveTally tally = carried(status) ? engine.tally(subject, q) : null;
        Progress progress = tally == null ? null : new Progress(tally.completed(), tally.total());
        Message progressLabel = tally == null ? null : text("progress.steps", tally.completed(), tally.total());

        List<DetailBlock> blocks = new ArrayList<>();
        DetailBlock requirements = requirements(q);
        if (requirements != null) {
            blocks.add(requirements);
        }
        blocks.addAll(objectiveBlocks(q));
        DetailBlock rewards = rewards(q);
        if (rewards != null) {
            blocks.add(rewards);
        }
        blocks.addAll(presentation.blocks(q, subject));

        return new DetailView(picture(q), title(q), meta(q), null, badges(q), toggle(q), progress, progressLabel,
                lead(q, status), blocks, QuestActions.of(q, this, here), hint(q, here));
    }

    /** A board's pills, else the quest's tags as plain pills. */
    @Nonnull
    private List<Pill> badges(@Nonnull Quest q) {
        if (presentation.managed(q)) {
            return presentation.pills(q);
        }
        List<Pill> pills = new ArrayList<>();
        for (String tag : q.tags()) {
            if (tag != null && !tag.isBlank()) {
                pills.add(Pill.of(presentation.tagLabel(tag), Tone.NEUTRAL));
            }
        }
        return pills;
    }

    /** Track or Untrack, on a carried quest, with the tooltip the book has always shown. */
    @Nullable
    private DetailToggle toggle(@Nonnull Quest q) {
        if (status(q) != QuestStatus.ACTIVE) {
            return null;
        }
        boolean on = tracked(q);
        return new DetailToggle(text(on ? "action.untrack" : "action.track"), on, QuestActions.TRACK,
                Msg.tr(PREFIX, "progression.book.tooltip.track"));
    }

    /** The narrative this quest reads with where it stands, else its flavor line. */
    @Nullable
    private static Message lead(@Nonnull Quest q, @Nonnull QuestStatus status) {
        Message lore = ProgressionTexts.lore(q.id(), switch (status) {
            case ACTIVE -> ContentTextAsset.Lore.STATE_ACTIVE;
            case COMPLETED, COMPLETED_UNCLAIMED -> ContentTextAsset.Lore.STATE_COMPLETE;
            case NOT_STARTED, ON_COOLDOWN -> ContentTextAsset.Lore.STATE_INCOMPLETE;
        });
        return lore != null ? lore : ProgressionTexts.flavor(q.id());
    }

    /**
     * Before the quest is taken: the consumer's reading, else every reason the gate refuses it. Ticked done when it
     * can be taken, locked when it cannot. Null for a carried or finished quest, or nothing to say.
     */
    @Nullable
    private DetailBlock requirements(@Nonnull Quest q) {
        if (status(q) != QuestStatus.NOT_STARTED) {
            return null;
        }
        boolean takeable = acceptable(q);
        List<Message> lines = new ArrayList<>();
        Message consumer = presentation.requirementLine(q);
        if (consumer != null) {
            lines.add(consumer);
        } else if (!takeable) {
            lines.addAll(LockReasons.lines(engine.canAccept(subject, q)));
        }
        if (lines.isEmpty()) {
            return null;
        }
        Tick tick = takeable ? Tick.DONE : Tick.LOCKED;
        List<DetailLine> out = new ArrayList<>();
        for (Message line : lines) {
            out.add(new DetailLine(Picture.NONE, line, null, null, tick, null, false));
        }
        return new DetailBlock(BLOCK_REQUIREMENTS, text("block.requirements"), null, out);
    }

    /**
     * The Objectives block, and one block per later order group under its own "Step N" heading. Which steps list is
     * the engine's answer ({@link QuestEngine#listedObjectives}): a quest that hides its locked steps while carried
     * draws neither the heading nor the lines of a group until it opens.
     */
    @Nonnull
    private List<DetailBlock> objectiveBlocks(@Nonnull Quest q) {
        List<ObjectiveDef> objectives = engine.listedObjectives(subject, q);
        if (objectives.isEmpty()) {
            return List.of();
        }
        QuestStatus status = status(q);
        Map<String, ObjectiveProgressState> progress = carried(status) ? engine.progressOf(subject, q.id()) : Map.of();
        boolean ordered = q.hasOrderedObjectives();

        List<DetailBlock> blocks = new ArrayList<>();
        String blockId = BLOCK_OBJECTIVES;
        Message label = text("block.objectives");
        Message meta = orderingHint(q);
        List<DetailLine> lines = new ArrayList<>();
        int lastOrder = -1;
        for (ObjectiveDef objective : objectives) {
            if (ordered && objective.order() > 0 && objective.order() != lastOrder) {
                if (lastOrder > 0) {
                    blocks.add(new DetailBlock(blockId, label, meta, lines));
                    blockId = BLOCK_STEP + objective.order();
                    label = text("block.step", objective.order());
                    meta = null;
                    lines = new ArrayList<>();
                }
                lastOrder = objective.order();
            }
            lines.add(objectiveLine(q, objective, status, progress));
        }
        blocks.add(new DetailBlock(blockId, label, meta, lines));
        return blocks;
    }

    /** How the steps are taken, for a quest with more than one: in order, in any order, or step by step. */
    @Nullable
    private static Message orderingHint(@Nonnull Quest q) {
        if (q.objectives().size() <= 1) {
            return null;
        }
        String key = q.hasOrderedObjectives() ? "mixed" : q.sequential() ? "sequential" : "any_order";
        return Msg.tr(PREFIX, "progression.book.quests.objectives." + key);
    }

    /** One step: its text, its count while carried, its picture and its tick. */
    @Nonnull
    private DetailLine objectiveLine(@Nonnull Quest q, @Nonnull ObjectiveDef objective, @Nonnull QuestStatus status,
            @Nonnull Map<String, ObjectiveProgressState> progress) {
        Message text = ProgressionTexts.objectiveOrUntitled(q.id(), objective.id());
        Picture picture = objectivePicture(q, objective);
        if (carried(status)) {
            ObjectiveProgressState state = progress.get(objective.id());
            int current = state != null ? state.current() : 0;
            int required = state != null ? state.required() : objective.amountAsInt();
            boolean done = state != null && state.isCompleted();
            Tick tick = done ? Tick.DONE
                    : engine.objectiveActive(subject, q, objective.id()) ? Tick.CURRENT : Tick.LOCKED;
            return new DetailLine(picture, text, KitText.count(current, required), null, tick, null, false);
        }
        Tick tick = status == QuestStatus.COMPLETED || status == QuestStatus.ON_COOLDOWN ? Tick.DONE : Tick.NONE;
        return new DetailLine(picture, text, null, null, tick, null, false);
    }

    /**
     * {@code q}'s Objectives lines in the order its page draws them, every block's lines one after another: what a
     * repaint after a hand-in reads, so it never walks the steps by an index a heading has shifted.
     */
    @Nonnull
    public List<DetailLine> objectiveLines(@Nonnull Quest q) {
        List<DetailLine> lines = new ArrayList<>();
        for (DetailBlock block : objectiveBlocks(q)) {
            lines.addAll(block.lines());
        }
        return lines;
    }

    /** What the quest pays, through the one shared chip reading; what waits to be collected wears a pill. */
    @Nullable
    private DetailBlock rewards(@Nonnull Quest q) {
        RewardChips.Source chips = presentation.rewardChips();
        List<DetailLine> lines = new ArrayList<>();
        addRewardLines(lines, q.autoRewards(), chips, null);
        Pill waiting = status(q) == QuestStatus.COMPLETED_UNCLAIMED ? Pill.of(text("reward.waiting"), Tone.COLLECT)
                : null;
        addRewardLines(lines, q.claimRewards(), chips, waiting);
        return lines.isEmpty() ? null : new DetailBlock(BLOCK_REWARDS, text("block.rewards"), null, lines);
    }

    private static void addRewardLines(@Nonnull List<DetailLine> lines, @Nonnull List<RewardSpec> rewards,
            @Nonnull RewardChips.Source chips, @Nullable Pill tag) {
        if (rewards.isEmpty()) {
            return;
        }
        List<RewardChip> read;
        try {
            read = RewardChips.chipsFor(rewards, chips);
        } catch (Throwable t) {
            SafeLog.warn("[progression] a quest's rewards could not be read: " + t.getMessage());
            return;
        }
        for (RewardChip chip : read) {
            Picture picture = chip.iconItemId() != null ? Picture.tooltipItem(chip.iconItemId())
                    : picture(chip.icon());
            lines.add(new DetailLine(picture, chip.label(), null, tag, Tick.NONE, null, false));
        }
    }

    /** The action bar's hint for {@code q}; with {@code here}, at that character. Null for none. */
    @Nullable
    public Message hint(@Nonnull Quest q, @Nullable CharacterQuestListing here) {
        QuestStatus status = status(q);
        if (presentation.managed(q) && (status == QuestStatus.NOT_STARTED || status == QuestStatus.ACTIVE)) {
            return presentation.acceptHint(q);
        }
        return switch (status) {
            case NOT_STARTED -> here != null ? null : notStartedHint(q);
            case ON_COOLDOWN -> text("state.back_in", waitLine(q));
            case COMPLETED_UNCLAIMED -> collectsElsewhere(q, here) ? whereToCollect(q) : null;
            case ACTIVE, COMPLETED -> null;
        };
    }

    /** Where a quest that is not collected here is collected: from its character by name, else the plain line. */
    @Nonnull
    private Message whereToCollect(@Nonnull Quest q) {
        String site = collectionSiteOf(q);
        Message name = site == null ? null : presentation.npcName(site);
        return name != null ? text("hint.collect_from", name) : text("hint.collect_at_site");
    }

    /**
     * The id {@code q} is collected at: the character its site names, or, for a quest collected wherever it was
     * taken, the place the player took it from. Null for a quest collected anywhere, or a place nothing recorded.
     */
    @Nullable
    private String collectionSiteOf(@Nonnull Quest q) {
        QuestTurnInSite site = q.turnInAt();
        if (site == null) {
            return null;
        }
        return site.isAcceptSite() ? engine.acceptSiteOf(subject, q.id()) : site.id();
    }

    /** Where to take a quest the book does not hand out, or why the log cannot take it now. */
    @Nullable
    private Message notStartedHint(@Nonnull Quest q) {
        if (BookVerbs.giverBound(q)) {
            Message name = presentation.npcName(q.npcViewId());
            return name != null ? text("hint.talk_to", name) : text("hint.talk_to_plain");
        }
        int max = engine.maxActive();
        if (!acceptable(q) && max > 0 && engine.logSlotsUsed(subject) >= max) {
            return text("hint.log_full", max);
        }
        return null;
    }

    // ==================== the journal ====================

    /**
     * The journal: {@code quests} filtered by category, tag, search and status, grouped into sections in order,
     * each in authored order then by name; a section with no quest is left out. With a status chosen or a search
     * typed, every section shown opens; otherwise each follows its default. A section keeps every row, and the kit
     * shows the first {@link LedgerSection#DEFAULT_CAP} with "Show N more".
     */
    @Nonnull
    public LedgerModel journal(@Nonnull List<Quest> quests, @Nonnull String status, @Nonnull String category,
            @Nonnull String tag, @Nonnull String search) {
        String needle = search.trim().toLowerCase(Locale.ROOT);
        boolean narrowed = !QuestSection.STATUS_ALL.equals(QuestSection.normalizeStatus(status)) || !needle.isEmpty();
        Map<QuestSection, List<Quest>> grouped = new EnumMap<>(QuestSection.class);
        for (Quest quest : quests) {
            if (!matchesCategory(quest, category) || !matchesTag(quest, tag)) {
                continue;
            }
            if (!needle.isEmpty() && !haystack(quest).contains(needle)) {
                continue;
            }
            QuestSection section = sectionOf(quest);
            if (section.shownFor(status)) {
                grouped.computeIfAbsent(section, s -> new ArrayList<>()).add(quest);
            }
        }
        List<LedgerSection> sections = new ArrayList<>();
        for (QuestSection section : QuestSection.values()) {
            List<Quest> members = grouped.get(section);
            if (members == null || members.isEmpty()) {
                continue;
            }
            Map<String, String> names = new HashMap<>();
            for (Quest quest : members) {
                names.put(quest.id(), flat(title(quest)));
            }
            members.sort(Comparator.comparingInt(Quest::listOrder)
                    .thenComparing((Quest quest) -> names.getOrDefault(quest.id(), ""), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Quest::id));
            List<LedgerRow> rows = new ArrayList<>();
            for (Quest quest : members) {
                rows.add(row(quest));
            }
            sections.add(new LedgerSection(section.id(), text(section.labelKey()), rows,
                    narrowed || section.openByDefault()));
        }
        return LedgerModel.of(sections);
    }

    private static boolean matchesCategory(@Nonnull Quest quest, @Nonnull String category) {
        return noFilter(category) || category.trim().equalsIgnoreCase(quest.category());
    }

    private static boolean matchesTag(@Nonnull Quest quest, @Nonnull String tag) {
        return noFilter(tag) || quest.hasTag(tag.trim());
    }

    private static boolean noFilter(@Nullable String value) {
        return value == null || value.isBlank() || QuestSection.STATUS_ALL.equalsIgnoreCase(value.trim());
    }

    /**
     * What a search matches: the title and flavor flattened to the server's language, and the raw tags. A String
     * test only; what a player reads stays client-resolved.
     */
    @Nonnull
    private static String haystack(@Nonnull Quest quest) {
        StringBuilder hay = new StringBuilder();
        hay.append(flat(ProgressionTexts.title(quest.id()))).append('\n');
        hay.append(flat(ProgressionTexts.flavor(quest.id()))).append('\n');
        for (String tag : quest.tags()) {
            hay.append(tag).append('\n');
        }
        return hay.toString().toLowerCase(Locale.ROOT);
    }

    /** The categories {@code quests} use (lower-cased), in the categories' authored order, then by id. */
    @Nonnull
    public List<String> categories(@Nonnull List<Quest> quests) {
        Set<String> seen = new LinkedHashSet<>();
        for (Quest quest : quests) {
            String category = quest.category();
            if (category != null && !category.isBlank()) {
                seen.add(category.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> out = new ArrayList<>(seen);
        out.sort(Comparator.comparingInt(QuestReader::categoryOrder).thenComparing(c -> c));
        return out;
    }

    /** The tags {@code quests} carry, in first-seen order; a board-managed quest's plumbing tags never count. */
    @Nonnull
    public List<String> tags(@Nonnull List<Quest> quests) {
        Set<String> seen = new LinkedHashSet<>();
        for (Quest quest : quests) {
            if (presentation.managed(quest)) {
                continue;
            }
            for (String tag : quest.tags()) {
                if (tag != null && !tag.isBlank()) {
                    seen.add(tag);
                }
            }
        }
        return List.copyOf(seen);
    }

    /** A category's name: its asset's title key, else {@code quest.category.<id>}, else the id as words. */
    @Nonnull
    public Message categoryName(@Nonnull String category) {
        return CategoryNames.questCategory(category, categoryAsset(category));
    }

    @Nullable
    private static QuestCategoryAsset categoryAsset(@Nonnull String category) {
        try {
            return QuestCategoryConfig.getInstance().category(category);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int categoryOrder(@Nonnull String category) {
        QuestCategoryAsset asset = categoryAsset(category);
        return asset == null ? Integer.MAX_VALUE : asset.orderOrLast();
    }

    // ==================== the header ====================

    /** The header's three stats: in progress (over the cap), to collect (gold when any), tracking (over the cap). */
    @Nonnull
    public List<Stat> stats() {
        int active = engine.activeCount(subject);
        int maxActive = engine.maxActive();
        int tracking = engine.trackedActive(subject).size();
        int maxTracked = engine.maxTracked();
        int collect = toCollect();
        return List.of(
                new Stat(maxActive > 0 ? KitText.count(active, maxActive) : Msg.num(active), text("stat.progress"),
                        Tone.NEUTRAL),
                new Stat(Msg.num(collect), text("stat.collect"), collect > 0 ? Tone.COLLECT : Tone.NEUTRAL),
                new Stat(maxTracked > 0 ? KitText.count(tracking, maxTracked) : Msg.num(tracking),
                        text("stat.tracking"), Tone.NEUTRAL));
    }

    /** The header's one-sentence summary of the log. */
    @Nonnull
    public Message subtitle() {
        return text("subtitle", engine.activeCount(subject), toCollect());
    }

    private int toCollect() {
        int count = 0;
        for (Quest quest : listed()) {
            if (status(quest) == QuestStatus.COMPLETED_UNCLAIMED) {
                count++;
            }
        }
        return count;
    }

    // ==================== shared readings ====================

    @Nonnull
    private static Message title(@Nonnull Quest q) {
        return ProgressionTexts.titleOrUntitled(q.id());
    }

    /** The quest's own picture, else its first step's, else its category's; none when nothing pictures it. */
    @Nonnull
    private Picture picture(@Nonnull Quest q) {
        if (q.icon() != null && !q.icon().isBlank()) {
            return Picture.item(q.icon());
        }
        if (!q.objectives().isEmpty()) {
            Picture first = objectivePicture(q, q.objectives().get(0));
            if (!first.isEmpty()) {
                return first;
            }
        }
        String category = q.category();
        QuestCategoryAsset asset = category == null ? null : categoryAsset(category);
        return asset == null ? Picture.NONE : Picture.item(asset.getIcon());
    }

    @Nonnull
    private static Picture objectivePicture(@Nonnull Quest q, @Nonnull ObjectiveDef objective) {
        try {
            return picture(ProgressionIcons.forObjective(q.id(), objective));
        } catch (Throwable t) {
            // A picture reading failing costs the picture, never the line.
            return Picture.NONE;
        }
    }

    @Nonnull
    private static Picture picture(@Nullable IconSpec icon) {
        return icon == null || icon.isEmpty() ? Picture.NONE : new Picture(icon.itemId(), icon.texturePath());
    }

    private static boolean carried(@Nonnull QuestStatus status) {
        return status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED;
    }

    @Nonnull
    private static String flat(@Nullable Message message) {
        if (message == null) {
            return "";
        }
        try {
            return UiText.flatten(message);
        } catch (Throwable t) {
            return "";
        }
    }
}
