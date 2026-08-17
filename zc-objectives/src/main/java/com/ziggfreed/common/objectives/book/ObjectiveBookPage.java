package com.ziggfreed.common.objectives.book;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.achievement.asset.AchievementCategoryConfig;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.AchievementGrouping.HeaderWalk;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Objective Book: a two-tab menu (quests, achievements) over THE shared progression runtime.
 *
 * <p>It reads the one merged catalogue, whoever authored each entry, and offers the minimal
 * lifecycle affordances a book can offer without a character in front of the player: take on an
 * offered quest, collect a finished one, and hand in a step that is not locked to somebody.
 * Achievements are always on, so they only ever need collecting.
 *
 * <p><b>It is not a second opinion about anybody's progress.</b> The engines, the subject and the
 * display text all come from the runtime, and every mutating call is wrapped in the registered
 * {@link com.ziggfreed.common.progress.runtime.ProgressionCallScope} - so a quest claimed here fires
 * exactly what the owning mod's own menu would have fired, down to the toast.
 *
 * <p>STATELESS across events, the same contract the shared leaderboard page keeps: every binding
 * round-trips the full next state, {@link #handleDataEvent} reopens the page with it, and every
 * exit path sends a response (a path that does not leaves the client spinning forever).
 */
public final class ObjectiveBookPage extends ToastablePage<ObjectiveBookEventData> {

    /** The quests tab id, also the default. */
    public static final String TAB_QUESTS = "quests";

    /** The achievements tab id. */
    public static final String TAB_ACHIEVEMENTS = "achievements";

    private static final String PAGE_TEMPLATE = "Pages/ZigObjectiveBookPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigObjectiveRow.ui";

    /** This library's own lang namespace; {@link Msg#tr} concatenates it with the key verbatim. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The domain segment every key on this page carries (the {@code ziggfreedcommon.progression.lang} file). */
    private static final String DOMAIN = "progression.";

    /** Step slots baked into {@code ZigObjectiveRow.ui}; anything past this is summarised. */
    private static final int MAX_STEPS = 4;

    /** A hard ceiling on rendered rows, so a very large catalogue cannot build an unbounded page. */
    private static final int MAX_ROWS = 200;

    /** Pixel width of a row's progress track, matching the row template's usable width. */
    private static final int BAR_WIDTH = 700;

    private static final int BAR_HEIGHT = 4;

    // Active/inactive tab contrast, the same treatment the shared leaderboard page uses.
    private static final String ACTIVE_TINT = "#5e86bd";
    private static final String ACTIVE_HOVER = "#6f97cf";
    private static final String ACTIVE_TEXT = "#ffffff";
    private static final String INACTIVE_TINT = "#2f3b49";
    private static final String INACTIVE_HOVER = "#445364";
    private static final String INACTIVE_TEXT = "#9fb0c2";
    private static final String HEADER_TINT = "#1e2a36";
    private static final String HEADER_TEXT = "#8696a8";
    // The category tier sits INSIDE a lifecycle heading, so it is drawn quieter than one: a row
    // template has no indent to give, and contrast is what is left to say "this is a sub-heading".
    private static final String SUBHEADER_TINT = "#18212b";
    private static final String SUBHEADER_TEXT = "#6c7c8e";

    /** Where a quest sits for this player, which is also the order the sections render in. */
    private enum QuestSection { ACTIVE, READY, AVAILABLE, LOCKED }

    /** Where an achievement sits for this player, which is also the order the sections render in. */
    private enum AchievementSection { READY, IN_PROGRESS, EARNED }

    /** One painted quest line: the engine's quest plus the text the runtime resolved for it. */
    private record QuestRow(@Nonnull Quest quest, @Nonnull Message name, @Nullable Message flavor,
                            @Nonnull QuestSection section, @Nullable String lockReason) {
    }

    /**
     * One achievement's row. {@code category} and {@code categoryRank} are read once, at build, and
     * carried on the row: they are what makes a list of forty achievements read as combat things
     * together and gathering things together, in the order the taxonomy declares, instead of
     * alphabetically, and each run of them carries a header naming the group. Content belonging to
     * no category, and content another mod folded (whose category this library cannot see), share
     * ONE bucket that reads after every named group rather than in front of them.
     */
    private record AchievementRow(@Nonnull Achievement achievement, @Nonnull Message name,
                                  @Nullable Message flavor, @Nonnull AchievementSection section,
                                  @Nonnull String category, int categoryRank) {
    }

    private final String tab;

    /** Open the book on the quests tab. */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef) {
        this(playerRef, TAB_QUESTS);
    }

    public ObjectiveBookPage(@Nonnull PlayerRef playerRef, @Nullable String tab) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction,
                ObjectiveBookEventData.CODEC);
        this.tab = TAB_ACHIEVEMENTS.equals(tab) ? TAB_ACHIEVEMENTS : TAB_QUESTS;
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));
        cmd.set("#Title.TextSpans", text("book.title"));

        boolean achievementsTab = TAB_ACHIEVEMENTS.equals(tab);
        ZigRichButton.text(cmd, "#TabQuests", text("book.tab.quests"));
        ZigRichButton.text(cmd, "#TabAchievements", text("book.tab.achievements"));
        bindTab(events, "#TabQuests", TAB_QUESTS);
        bindTab(events, "#TabAchievements", TAB_ACHIEVEMENTS);
        styleTab(cmd, "#TabQuests", !achievementsTab);
        styleTab(cmd, "#TabAchievements", achievementsTab);

        QuestEngine questEngine = ProgressionRuntime.quests();
        AchievementEngine achievementEngine = ProgressionRuntime.achievements();
        // The subject comes from the runtime, never built here: with somebody else's store active, a
        // subject carrying the wrong handle reads neutral and drops every write, so an accept or a
        // claim from this page would silently do nothing at all.
        Subject subject = subjectFor(store, ref, achievementsTab);
        if (subject == null) {
            cmd.set("#Summary.Visible", false);
            showEmpty(cmd, emptyText());
            renderToastInto(cmd);
            return;
        }

        // Both engines document self-heal as "whenever a surface opens": it is what settles a
        // standing-value step and what re-offers a repeatable whose cooldown has elapsed, so the
        // list below is read AFTER it rather than one open behind.
        selfHeal(questEngine, achievementEngine, subject);

        int rows = achievementsTab
                ? renderAchievements(cmd, events, subject, achievementEngine)
                : renderQuests(cmd, events, subject, questEngine);
        if (rows == 0) {
            showEmpty(cmd, emptyText());
        }
        renderToastInto(cmd);
    }

    private void selfHeal(@Nonnull QuestEngine questEngine,
                          @Nonnull AchievementEngine achievementEngine, @Nonnull Subject subject) {
        try {
            questEngine.selfHeal(subject);
            achievementEngine.selfHeal(subject);
        } catch (Throwable t) {
            SafeLog.warn("[progression] objective book self-heal failed", t);
        }
    }

    // ==================== quests ====================

    private int renderQuests(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                             @Nonnull Subject subject, @Nonnull QuestEngine engine) {
        cmd.set("#Summary.Visible", false);

        List<QuestRow> entries = new ArrayList<>();
        for (Quest quest : engine.quests()) {
            QuestSection section = sectionFor(subject, engine, quest);
            if (section == null) {
                continue;
            }
            String lockReason = section == QuestSection.LOCKED
                    ? engine.canAccept(subject, quest).firstReason() : null;
            entries.add(new QuestRow(quest,
                    nameOf(quest.id(), "book.quests.untitled"),
                    flavorOf(quest.id()), section, lockReason));
        }
        entries.sort(Comparator.comparingInt((QuestRow r) -> r.section().ordinal())
                .thenComparing(r -> r.quest().id()));

        int index = 0;
        HeaderWalk<QuestSection> walk = new HeaderWalk<>();
        for (QuestRow entry : entries) {
            boolean opensSection = walk.enterSection(entry.section());
            // The header and the row it heads are budgeted TOGETHER, so a list cut short by the row
            // ceiling never ends on a heading with nothing under it.
            if (index + (opensSection ? 1 : 0) >= MAX_ROWS) {
                break;
            }
            if (opensSection) {
                index = appendHeader(cmd, index, questSectionText(entry.section()),
                        HEADER_TINT, HEADER_TEXT);
            }
            index = appendQuestRow(cmd, events, index, subject, engine, entry);
        }
        return index;
    }

    /** Where this quest sits for this player, or null when it does not belong on the list at all. */
    @Nullable
    private static QuestSection sectionFor(@Nonnull Subject subject, @Nonnull QuestEngine engine,
                                           @Nonnull Quest quest) {
        QuestStatus status = engine.status(subject, quest);
        if (status == QuestStatus.ACTIVE) {
            return QuestSection.ACTIVE;
        }
        if (status == QuestStatus.COMPLETED_UNCLAIMED) {
            return QuestSection.READY;
        }
        if (status == QuestStatus.NOT_STARTED && engine.isVisible(subject, quest)) {
            return engine.canAccept(subject, quest).allowed()
                    ? QuestSection.AVAILABLE : QuestSection.LOCKED;
        }
        // A repeatable waiting out its clock is still the player's quest. Leaving it out entirely
        // would make a daily disappear from the book between runs, which reads as content having
        // been taken away rather than as a wait.
        return status == QuestStatus.ON_COOLDOWN ? QuestSection.LOCKED : null;
    }

    private int appendQuestRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                               int index, @Nonnull Subject subject, @Nonnull QuestEngine engine,
                               @Nonnull QuestRow entry) {
        String sel = appendRow(cmd, index);
        Quest quest = entry.quest();
        cmd.set(sel + " #Name.TextSpans", entry.name());
        setFlavor(cmd, sel, entry.flavor());

        switch (entry.section()) {
            case ACTIVE -> {
                QuestEngine.ObjectiveTally tally = engine.tally(subject, quest);
                setProgress(cmd, sel, tally.completed(), tally.total());
                paintBar(cmd, sel, tally.fraction());
                renderQuestSteps(cmd, sel, subject, engine, quest);
                if (engine.firstActiveTurnIn(subject, quest, null) != null) {
                    setAction(cmd, events, sel, text("book.action.turn_in"), "turn_in", quest.id());
                }
            }
            case READY -> {
                setStatus(cmd, sel, text("book.quests.section.ready"));
                setAction(cmd, events, sel, text("book.action.claim"), "claim", quest.id());
            }
            case AVAILABLE ->
                setAction(cmd, events, sel, text("book.action.accept"), "accept", quest.id());
            case LOCKED -> setStatus(cmd, sel, lockText(entry.lockReason()));
        }
        return index + 1;
    }

    /**
     * The CURRENT step only, so an ordered quest's list advances by itself as each step lands
     * rather than showing a wall of steps the player cannot work on yet.
     */
    private void renderQuestSteps(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                  @Nonnull Subject subject, @Nonnull QuestEngine engine,
                                  @Nonnull Quest quest) {
        List<ObjectiveDef> steps = engine.activeStepObjectives(subject, quest);
        int shown = Math.min(steps.size(), MAX_STEPS);
        for (int i = 0; i < shown; i++) {
            ObjectiveDef step = steps.get(i);
            Message line = objectiveText(quest.id(), step.id());
            cmd.set(sel + " #Step" + i + ".TextSpans",
                    line != null ? line : text("book.quests.step.untitled"));
            ObjectiveProgressState state = engine.progressOf(subject, quest.id(), step.id());
            int current = state != null ? state.current() : 0;
            int required = state != null ? state.required() : step.amountAsInt();
            cmd.set(sel + " #StepProgress" + i + ".TextSpans", text("book.progress", current, required));
            cmd.set(sel + " #StepRow" + i + ".Visible", true);
        }
        int overflow = steps.size() - shown;
        if (overflow > 0) {
            cmd.set(sel + " #StepMore.TextSpans", text("book.more", overflow));
            cmd.set(sel + " #StepMore.Visible", true);
        }
    }

    @Nonnull
    private Message questSectionText(@Nonnull QuestSection section) {
        return switch (section) {
            case ACTIVE -> text("book.quests.section.active");
            case READY -> text("book.quests.section.ready");
            case AVAILABLE -> text("book.quests.section.available");
            case LOCKED -> text("book.quests.section.locked");
        };
    }

    /**
     * The refusals this book can actually receive map to their own lines; anything else - a gate
     * evaluator's own reason, which belongs to whoever authored the gate - reads as the generic one
     * rather than leaking an internal token at a player.
     *
     * <p>The three with their own line are the three a player can do something about. A quest held
     * back by a spent calendar window or a spent lifetime cap reads as the generic line rather than
     * gaining two more keys nine translators would maintain; the count beside it is the real answer
     * there, and it is already on the row.
     */
    @Nonnull
    private Message lockText(@Nullable String reason) {
        if (QuestGates.REASON_UNAVAILABLE.equals(reason)) {
            return text("book.quests.lock.unavailable");
        }
        if (QuestGates.REASON_ON_COOLDOWN.equals(reason)) {
            return text("book.quests.lock.on_cooldown");
        }
        if (QuestGates.REASON_PREREQUISITES.equals(reason)) {
            return text("book.quests.lock.prerequisites");
        }
        return text("book.quests.lock.other");
    }

    // ==================== achievements ====================

    private int renderAchievements(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                   @Nonnull Subject subject, @Nonnull AchievementEngine engine) {
        int earned = engine.points(subject);
        cmd.set("#Summary.TextSpans",
                text("book.achievements.points", earned, earned + engine.pointsAvailable(subject)));
        cmd.set("#Summary.Visible", true);

        List<AchievementRow> entries = new ArrayList<>();
        for (Achievement achievement : engine.achievements()) {
            AchievementStatus status = engine.status(subject, achievement.id());
            AchievementSection section;
            if (status == AchievementStatus.UNLOCKED) {
                section = AchievementSection.READY;
            } else if (status == AchievementStatus.CLAIMED) {
                section = AchievementSection.EARNED;
            } else if (engine.isVisible(subject, achievement)) {
                section = AchievementSection.IN_PROGRESS;
            } else {
                continue;
            }
            String category = ProgressionDefaults.achievementCategory(achievement.id());
            entries.add(new AchievementRow(achievement,
                    nameOf(achievement.id(), "book.achievements.untitled"),
                    flavorOf(achievement.id()), section,
                    AchievementGrouping.bucketOf(category),
                    AchievementGrouping.rankOf(category,
                            ProgressionDefaults.categoryRank(category))));
        }
        entries.sort(Comparator.comparingInt((AchievementRow r) -> r.section().ordinal())
                .thenComparingInt(AchievementRow::categoryRank)
                .thenComparing(r -> r.category())
                .thenComparing(r -> r.achievement().id()));

        // TWO tiers of heading, the category one nested inside the lifecycle one. Both are drawn at
        // the first row that needs them and the rows are sorted to match, so a category with
        // nothing ready to collect simply has no header in the Ready to collect section.
        int index = 0;
        HeaderWalk<AchievementSection> walk = new HeaderWalk<>();
        for (AchievementRow entry : entries) {
            boolean opensSection = walk.enterSection(entry.section());
            boolean opensCategory = walk.enterCategory(entry.category());
            int headers = (opensSection ? 1 : 0) + (opensCategory ? 1 : 0);
            // Headers and the row they head are budgeted TOGETHER, so a list cut short by the row
            // ceiling never ends on a heading with nothing under it.
            if (index + headers >= MAX_ROWS) {
                break;
            }
            if (opensSection) {
                index = appendHeader(cmd, index, achievementSectionText(entry.section()),
                        HEADER_TINT, HEADER_TEXT);
            }
            if (opensCategory) {
                index = appendHeader(cmd, index, categoryText(entry.category()),
                        SUBHEADER_TINT, SUBHEADER_TEXT);
            }
            index = appendAchievementRow(cmd, events, index, subject, engine, entry);
        }
        return index;
    }

    /**
     * What one category's header says. The uncategorised bucket has a line of this page's own, since
     * there is no category word to translate or tidy up; every other bucket is named by the folded
     * taxonomy through {@link AchievementGrouping#label}.
     */
    @Nonnull
    private Message categoryText(@Nonnull String category) {
        if (AchievementGrouping.UNCATEGORISED.equals(category)) {
            return text("book.achievements.category.uncategorised");
        }
        return AchievementGrouping.label(category,
                AchievementCategoryConfig.getInstance().category(category));
    }

    private int appendAchievementRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                     int index, @Nonnull Subject subject,
                                     @Nonnull AchievementEngine engine,
                                     @Nonnull AchievementRow entry) {
        String sel = appendRow(cmd, index);
        Achievement achievement = entry.achievement();
        cmd.set(sel + " #Name.TextSpans", entry.name());
        setFlavor(cmd, sel, entry.flavor());
        setIcon(cmd, sel, ProgressionDefaults.achievementIcon(achievement.id()));

        switch (entry.section()) {
            case READY ->
                setAction(cmd, events, sel, text("book.action.claim"), "claim", achievement.id());
            case IN_PROGRESS -> {
                AchievementEngine.CriterionTally tally = engine.tally(subject, achievement);
                setProgress(cmd, sel, tally.completed(), tally.total());
                paintBar(cmd, sel, tally.fraction());
                renderCriteria(cmd, sel, subject, engine, achievement);
            }
            case EARNED -> setStatus(cmd, sel, text("book.achievements.status.earned"));
        }
        return index + 1;
    }

    private void renderCriteria(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                @Nonnull Subject subject, @Nonnull AchievementEngine engine,
                                @Nonnull Achievement achievement) {
        List<ObjectiveDef> criteria = achievement.criteria();
        int shown = Math.min(criteria.size(), MAX_STEPS);
        for (int i = 0; i < shown; i++) {
            // An achievement numbers its criteria, so the position IS the step id a text source is
            // asked for; a quest names its steps instead.
            Message line = objectiveText(achievement.id(), Integer.toString(i));
            cmd.set(sel + " #Step" + i + ".TextSpans",
                    line != null ? line : text("book.achievements.criterion.untitled"));
            ObjectiveProgressState state = engine.progressOf(subject, achievement, i);
            cmd.set(sel + " #StepProgress" + i + ".TextSpans",
                    text("book.progress", state.current(), state.required()));
            cmd.set(sel + " #StepRow" + i + ".Visible", true);
        }
        int overflow = criteria.size() - shown;
        if (overflow > 0) {
            cmd.set(sel + " #StepMore.TextSpans", text("book.more", overflow));
            cmd.set(sel + " #StepMore.Visible", true);
        }
    }

    @Nonnull
    private Message achievementSectionText(@Nonnull AchievementSection section) {
        return switch (section) {
            case READY -> text("book.achievements.section.ready");
            case IN_PROGRESS -> text("book.achievements.section.in_progress");
            case EARNED -> text("book.achievements.section.earned");
        };
    }

    // ==================== shared row painting ====================

    @Nonnull
    private static String appendRow(@Nonnull UICommandBuilder cmd, int index) {
        cmd.append("#List", ROW_TEMPLATE);
        return "#List[" + index + "]";
    }

    /**
     * A heading, rendered as a row with everything but its name left hidden. The two tiers differ
     * only in their colours, which is the whole of the nesting a fixed row template can express.
     */
    private static int appendHeader(@Nonnull UICommandBuilder cmd, int index, @Nonnull Message label,
                                    @Nonnull String tint, @Nonnull String textColor) {
        String sel = appendRow(cmd, index);
        cmd.set(sel + " #Name.TextSpans", label);
        cmd.set(sel + " #Name.Style.TextColor", textColor);
        cmd.set(sel + ".Background.Color", tint);
        return index + 1;
    }

    /**
     * What this content is called, asked of every registered text source in order, first non-null
     * winning. A merged catalogue is exactly where this matters: whoever folded an entry kept its
     * words, so without asking them half the list would render blank.
     */
    @Nonnull
    private Message nameOf(@Nonnull String contentId, @Nonnull String fallbackKey) {
        Message name = ask(source -> source.title(contentId));
        return name != null ? name : text(fallbackKey);
    }

    @Nullable
    private static Message flavorOf(@Nonnull String contentId) {
        return ask(source -> source.flavor(contentId));
    }

    @Nullable
    private static Message objectiveText(@Nonnull String contentId, @Nonnull String objectiveId) {
        return ask(source -> source.objective(contentId, objectiveId));
    }

    @Nullable
    private static Message ask(@Nonnull Function<ProgressionTextSource, Message> question) {
        for (ProgressionTextSource source : ProgressionRuntime.textSources()) {
            try {
                Message answer = question.apply(source);
                if (answer != null) {
                    return answer;
                }
            } catch (Throwable t) {
                SafeLog.warn("[progression] a text source failed while painting the book: "
                        + t.getMessage());
            }
        }
        return null;
    }

    private static void setFlavor(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                  @Nullable Message flavor) {
        if (flavor != null) {
            cmd.set(sel + " #Flavor.TextSpans", flavor);
            cmd.set(sel + " #Flavor.Visible", true);
        }
    }

    private static void setIcon(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                @Nullable String itemId) {
        if (itemId != null && !itemId.isBlank()) {
            cmd.set(sel + " #Icon.Slots", List.of(new ItemGridSlot(new ItemStack(itemId, 1))));
            cmd.set(sel + " #IconSlot.Visible", true);
        }
    }

    private void setProgress(@Nonnull UICommandBuilder cmd, @Nonnull String sel, int done, int total) {
        cmd.set(sel + " #Progress.TextSpans", text("book.progress", done, total));
        cmd.set(sel + " #Progress.Visible", true);
    }

    private static void setStatus(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                  @Nonnull Message status) {
        cmd.set(sel + " #Status.TextSpans", status);
        cmd.set(sel + " #Status.Visible", true);
    }

    private void setAction(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                           @Nonnull String sel, @Nonnull Message label,
                           @Nonnull String action, @Nonnull String id) {
        ZigRichButton.text(cmd, sel + " #Action", label);
        cmd.set(sel + " #Action.Visible", true);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Action",
                EventData.of("Action", action).append("Tab", tab).append("Id", id), false);
    }

    /** The bar is decoration over the count in {@code #Progress}, which is the real reading. */
    private void paintBar(@Nonnull UICommandBuilder cmd, @Nonnull String sel, double fraction) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * BAR_WIDTH);
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(filled));
        anchor.setHeight(Value.of(BAR_HEIGHT));
        cmd.setObject(sel + " #BarFill.Anchor", anchor);
        cmd.set(sel + " #BarTrack.Visible", true);
    }

    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull Message message) {
        cmd.set("#EmptyState.TextSpans", message);
        cmd.set("#EmptyState.Visible", true);
    }

    @Nonnull
    private Message emptyText() {
        return TAB_ACHIEVEMENTS.equals(tab) ? text("book.empty.achievements") : text("book.empty.quests");
    }

    private void bindTab(@Nonnull UIEventBuilder events, @Nonnull String selector,
                         @Nonnull String target) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", "tab").append("Tab", target).append("Id", ""), false);
    }

    private static void styleTab(@Nonnull UICommandBuilder cmd, @Nonnull String selector, boolean active) {
        String base = active ? ACTIVE_TINT : INACTIVE_TINT;
        UiRetint.retintButtonStates(cmd, selector, base,
                active ? ACTIVE_HOVER : INACTIVE_HOVER, base);
        ZigRichButton.color(cmd, selector, active ? ACTIVE_TEXT : INACTIVE_TEXT);
    }

    @Nonnull
    private Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull ObjectiveBookEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (data.action == null || "close".equals(data.action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        String nextTab = TAB_ACHIEVEMENTS.equals(data.tab) ? TAB_ACHIEVEMENTS
                : TAB_QUESTS.equals(data.tab) ? TAB_QUESTS : tab;
        if (!"tab".equals(data.action)) {
            act(ref, store, data, nextTab);
        }
        player.getPageManager().openCustomPage(ref, store, new ObjectiveBookPage(playerRef, nextTab));
    }

    /**
     * Perform the one engine call the pressed button stands for and toast the outcome. Every
     * failure path toasts too: a button that silently does nothing reads as a broken menu.
     */
    private void act(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                     @Nonnull ObjectiveBookEventData data, @Nonnull String nextTab) {
        String id = data.id;
        if (id == null || id.isBlank()) {
            return;
        }
        boolean achievementsTab = TAB_ACHIEVEMENTS.equals(nextTab);
        Subject subject = subjectFor(store, ref, achievementsTab);
        if (subject == null) {
            return;
        }
        try {
            if (achievementsTab) {
                actOnAchievement(subject, data.action, id);
            } else {
                actOnQuest(subject, data.action, id);
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] objective book action '" + data.action + "' failed", t);
            showToast(ToastKind.WARNING, text(failKeyFor(data.action)));
        }
    }

    /** The subject the ACTIVE store understands, for whichever side of the book is being read. */
    @Nullable
    private Subject subjectFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               boolean achievementsTab) {
        return achievementsTab
                ? ProgressionRuntime.subjects().achievementSubject(store, ref)
                : ProgressionRuntime.subjects().questSubject(store, ref);
    }

    /** Which "that did not work" line belongs to a pressed button. */
    @Nonnull
    private static String failKeyFor(@Nullable String action) {
        if ("claim".equals(action)) {
            return "book.toast.claim_failed";
        }
        if ("turn_in".equals(action)) {
            return "book.toast.turn_in_failed";
        }
        return "book.toast.accept_failed";
    }

    /**
     * Every mutating call goes through the registered scope, so a claim made from this book fires
     * exactly what the owning mod's own menu would have fired - its toast, its follow-on grants, its
     * bookkeeping. Without it a book-driven claim would pay out in silence.
     */
    private void actOnQuest(@Nonnull Subject subject, @Nullable String action, @Nonnull String id) {
        QuestEngine engine = ProgressionRuntime.quests();
        Quest quest = engine.quest(id);
        if (quest == null) {
            return;
        }
        ProgressionCallScope scope = ProgressionRuntime.questScope();
        if ("accept".equals(action)) {
            boolean ok = Boolean.TRUE.equals(scope.around(subject, s ->
                    Boolean.valueOf(engine.canAccept(s, quest).allowed() && engine.accept(s, quest))));
            toast(ok, "book.toast.accepted", "book.toast.accept_failed", ToastKind.SUCCESS);
        } else if ("claim".equals(action)) {
            boolean ok = Boolean.TRUE.equals(
                    scope.around(subject, s -> Boolean.valueOf(engine.claim(s, quest))));
            toast(ok, "book.toast.claimed", "book.toast.claim_failed", ToastKind.REWARD);
        } else if ("turn_in".equals(action)) {
            // The book has no character in front of the player, so the "somewhere unlocked" form is
            // the only one that can answer here; a hand-in locked to somebody is not offered.
            boolean ok = Boolean.TRUE.equals(scope.around(subject, s -> {
                ObjectiveDef step = engine.firstActiveTurnIn(s, quest, null);
                return Boolean.valueOf(step != null && engine.attemptTurnIn(s, quest, step.id()) > 0);
            }));
            toast(ok, "book.toast.turned_in", "book.toast.turn_in_failed", ToastKind.SUCCESS);
        }
    }

    private void actOnAchievement(@Nonnull Subject subject, @Nullable String action,
                                  @Nonnull String id) {
        if (!"claim".equals(action)) {
            return;
        }
        AchievementEngine engine = ProgressionRuntime.achievements();
        Achievement achievement = engine.achievement(id);
        if (achievement == null) {
            return;
        }
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.achievementScope()
                .around(subject, s -> Boolean.valueOf(engine.claim(s, achievement))));
        toast(ok, "book.toast.claimed", "book.toast.claim_failed", ToastKind.REWARD);
    }

    private void toast(boolean ok, @Nonnull String okKey, @Nonnull String failKey,
                       @Nonnull ToastKind okKind) {
        showToast(ok ? okKind : ToastKind.WARNING, text(ok ? okKey : failKey));
    }
}
