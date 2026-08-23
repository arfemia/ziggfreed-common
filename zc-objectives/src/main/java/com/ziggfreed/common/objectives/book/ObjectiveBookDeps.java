package com.ziggfreed.common.objectives.book;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.questlist.NpcQuestPageDeps;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about {@link ObjectiveBookPage} without owning it.
 *
 * <p>Every seam here has a DEFAULT that leaves the book fully working on a bare server: the
 * catalogue, the subject and the display text all come from the shared progression runtime, so a
 * consumer fills a seam only to say something the library genuinely cannot know - its own menu
 * rail, its board-managed quests, who claimed a server-first, its points-milestone ladder.
 *
 * <ul>
 *   <li>{@link NpcQuestPageDeps.PageTheme} - how the frame and its inner panels are painted; the
 *       default falls through to the theme registered on {@link ObjectiveBookPages}, else the
 *       plain append.</li>
 *   <li>{@link ChromePainter} (rail) - paints the whole left rail (branding, navigation) and may
 *       repaint the header region; unfilled, the rail column is hidden and the book reads clean on
 *       a bare server. A painted control binds back through {@link Chrome#bindExt} and the click
 *       lands on {@link ExtHandler}.</li>
 *   <li>{@link ChromePainter} (side panel) - paints the achievements tab's side column the same
 *       way; unfilled, that column hides on the achievements tab.</li>
 *   <li>{@link ExtHandler} - answers every {@code ext} click a painted control bound, whichever
 *       painter bound it: one channel for rail navigation, drill-downs, anything consumer-drawn.</li>
 *   <li>{@link BoardManagedQuests} - which quests a board manages, and how their rows read: the
 *       plumbing tags are suppressed, the pills say what the board says, accept is replaced by the
 *       at-the-board hint, and abandoning one forces a full repaint.</li>
 *   <li>{@link RequirementText} - the one-line "Requires: ..." a not-yet-started quest row shows.
 *       The default renders the generic parts of the gate (prerequisite quests); a consumer with
 *       its own factor vocabulary says the rest.</li>
 *   <li>{@link TagLabelSource} - what a quest tag chip says; the default tidies the raw tag.</li>
 *   <li>{@link NpcQuestPageDeps.RewardChipSource} - how ONE reward reads, layered over the shared
 *       generic reading exactly as on the NPC quest page.</li>
 *   <li>{@link FirstClaimSource} - who claimed a server-first achievement; unfilled, the badge
 *       shows without a claimant line.</li>
 *   <li>{@link MilestoneSource} / {@link MilestoneClaim} - the points-milestone ladder and its
 *       claim; unfilled, the milestones block and the third header stat hide.</li>
 *   <li>{@link ActionFeedback} - what announces a quest accepted or abandoned from the book, so
 *       those moments read exactly like the consumer's own menu. A filled seam OWNS the
 *       announcement and the book's own toasts stand down; unfilled, the book's toasts are the
 *       announcement.</li>
 *   <li>{@link QuestClaimPreCheck} - a last word before a quest's claim reaches the engine, for a
 *       refusal only the consumer can know about (a board that wants its own room check, a paused
 *       economy). Default nothing to refuse, so every claim reaches the engine unchanged.</li>
 * </ul>
 *
 * <p>Immutable; build one at setup and register a supplier via
 * {@link ObjectiveBookPages#deps(java.util.function.Supplier)}.
 */
public final class ObjectiveBookDeps {

    /**
     * The live page surfaces a painter or handler works against: the builders, the player, and the
     * one way a painted control binds a click back to the consumer.
     */
    public static final class Chrome {

        private final UICommandBuilder cmd;
        private final UIEventBuilder events;
        private final Store<EntityStore> store;
        private final Ref<EntityStore> ref;
        private final Player player;
        private final String activeTab;
        private final ExtBinder binder;

        Chrome(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull Player player, @Nonnull String activeTab, @Nonnull ExtBinder binder) {
            this.cmd = cmd;
            this.events = events;
            this.store = store;
            this.ref = ref;
            this.player = player;
            this.activeTab = activeTab;
            this.binder = binder;
        }

        @Nonnull
        public UICommandBuilder cmd() {
            return cmd;
        }

        @Nonnull
        public UIEventBuilder events() {
            return events;
        }

        @Nonnull
        public Store<EntityStore> store() {
            return store;
        }

        @Nonnull
        public Ref<EntityStore> ref() {
            return ref;
        }

        @Nonnull
        public Player player() {
            return player;
        }

        /** The tab being built ({@link ObjectiveBookPage#TAB_QUESTS} / {@link ObjectiveBookPage#TAB_ACHIEVEMENTS}). */
        @Nonnull
        public String activeTab() {
            return activeTab;
        }

        /**
         * Bind a painted control so its click reaches {@link ExtHandler#handle} carrying
         * {@code extId}. The page owns the wire format, so a consumer never composes an
         * {@code EventData} of its own and every painted control keeps the filter state alive.
         */
        public void bindExt(@Nonnull String selector, @Nonnull String extId) {
            binder.bindExt(selector, extId);
        }
    }

    /** The page-supplied half of {@link Chrome#bindExt}; never implemented by a consumer. */
    interface ExtBinder {

        void bindExt(@Nonnull String selector, @Nonnull String extId);
    }

    /**
     * Paints one deps-owned region (the left rail, or the achievements side column). Return true
     * when something was painted; false leaves the region hidden.
     */
    @FunctionalInterface
    public interface ChromePainter {

        boolean paint(@Nonnull Chrome chrome);
    }

    /**
     * Answers a click on a deps-painted control. Return true when the handler took the screen
     * (opened another page); false and the book stays up and answers the client itself.
     */
    @FunctionalInterface
    public interface ExtHandler {

        boolean handle(@Nonnull String extId, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player);
    }

    /** One substitute chip a board-managed quest row wears instead of its plumbing tags. */
    public record Pill(@Nonnull Message label, @Nonnull String background,
                       @Nonnull String textColor) {
    }

    /**
     * The quests a board manages, and the words their rows wear. A managed quest is listed only
     * while it is being carried or is ready to collect (never offered, never shown finished), its
     * plumbing tags are suppressed in favour of {@link #pills}, Accept is replaced by
     * {@link #acceptHint}, and abandoning one forces a full repaint so it cannot flash an Accept
     * button on the way out.
     */
    public interface BoardManagedQuests {

        /** Is this quest managed by a board rather than by the log? */
        boolean managed(@Nonnull Quest quest);

        /** The chips a managed quest's row wears; empty for none. */
        @Nonnull
        default List<Pill> pills(@Nonnull Quest quest) {
            return List.of();
        }

        /** The line shown where Accept would be; null for none. */
        @Nullable
        default Message acceptHint(@Nonnull Quest quest) {
            return null;
        }
    }

    /**
     * The one-line requirements reading a not-yet-started quest row shows, or null for none. The
     * page colours it by whether the quest is currently acceptable.
     */
    @FunctionalInterface
    public interface RequirementText {

        @Nullable
        Message lineFor(@Nonnull Quest quest);
    }

    /** What a quest tag chip says; the colour comes from the shared deterministic table. */
    @FunctionalInterface
    public interface TagLabelSource {

        @Nonnull
        Message labelOf(@Nonnull String tag);
    }

    /** Who claimed a server-first achievement, as the viewing player reads it. */
    public record FirstClaim(@Nonnull String claimantName, boolean self) {
    }

    /** The claim on one server-first achievement, or null while it is unclaimed / unknowable. */
    @FunctionalInterface
    public interface FirstClaimSource {

        @Nullable
        FirstClaim claimOf(@Nonnull String achievementId, @Nullable UUID viewer);
    }

    /**
     * One rung of the consumer's points-milestone ladder, ready to paint: the words are built by
     * whoever owns the ladder, the rewards render through the shared chip reading, and
     * {@code claimable} is true only when pressing Claim would actually pay.
     */
    public record MilestoneView(int threshold, @Nonnull Message title, @Nullable Message description,
                                @Nonnull List<RewardSpec> rewards, boolean unlocked, boolean claimed,
                                boolean claimable) {
    }

    /** The milestone ladder, ascending by threshold; empty hides the block and the header stat. */
    @FunctionalInterface
    public interface MilestoneSource {

        @Nonnull
        List<MilestoneView> milestones(@Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Subject subject);
    }

    /** What pressing a milestone's Claim button did. */
    public enum MilestoneClaimOutcome { SUCCESS, INVENTORY_FULL, NOT_READY }

    /** Claims one milestone's waiting rewards. */
    @FunctionalInterface
    public interface MilestoneClaim {

        @Nonnull
        MilestoneClaimOutcome claim(int threshold, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player);
    }

    /**
     * What announces a quest accepted or abandoned from the book. A FILLED seam owns the
     * announcement outright and the book's own accepted/abandoned toasts stand down, so one
     * action is one toast however the consumer words it; unfilled, the book's toasts are the
     * announcement.
     */
    public interface ActionFeedback {

        default void accepted(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        }

        default void abandoned(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        }
    }

    /**
     * A last word before a quest's claim reaches the engine. Returning a refusal STOPS the claim
     * before {@code engine.claim} is ever called, and the page shows it as an error toast; null lets
     * the claim proceed to the engine's own rules exactly as if nothing were registered.
     */
    @FunctionalInterface
    public interface QuestClaimPreCheck {

        @Nullable
        Message refusalFor(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player);
    }

    /**
     * The library's default frame paint: whatever theme was registered on
     * {@link ObjectiveBookPages#theme}, else the plain append - so a consumer that only ever
     * registered the one-call theme keeps its painted frame with no deps at all.
     */
    public static final NpcQuestPageDeps.PageTheme LEGACY_THEME =
            (cmd, template, frameSelectors) ->
                    ObjectiveBookPages.resolvedTheme().appendThemed(cmd, template, frameSelectors);

    /** Nothing painted, so the region hides. */
    public static final ChromePainter NO_CHROME = chrome -> false;

    /** No consumer controls, so an unexpected ext click is answered by the book alone. */
    public static final ExtHandler NO_EXT = (extId, store, ref, player) -> false;

    /** No board anywhere, so every quest is the log's own. */
    public static final BoardManagedQuests NO_BOARDS = quest -> false;

    /** The generic gate reading: prerequisite quests, and nothing a factor vocabulary must name. */
    public static final RequirementText GENERIC_REQUIREMENTS = BookQuestsTab::genericRequirementLine;

    /** The raw tag tidied up, the same contract free-string tags carry everywhere. */
    public static final TagLabelSource RAW_TAGS = tag -> Msg.raw(prettifyTag(tag));

    /** No consumer opinion about any reward; every chip takes the generic reading. */
    public static final NpcQuestPageDeps.RewardChipSource GENERIC_CHIPS = spec -> null;

    /** Nobody to name, so a server-first badge shows without a claimant line. */
    public static final FirstClaimSource NO_FIRST_CLAIMS = (achievementId, viewer) -> null;

    /** No ladder, so the milestones block and the third header stat hide. */
    public static final MilestoneSource NO_MILESTONES = (store, ref, subject) -> List.of();

    /** Nothing claimable where no ladder exists. */
    public static final MilestoneClaim NO_MILESTONE_CLAIM =
            (threshold, store, ref, player) -> MilestoneClaimOutcome.NOT_READY;

    /** Nothing beside the engines' own announcements. */
    public static final ActionFeedback NO_FEEDBACK = new ActionFeedback() {
    };

    /** Nothing to refuse, so every claim reaches the engine unchanged. */
    public static final QuestClaimPreCheck NO_CLAIM_PRECHECK = (quest, store, ref, player) -> null;

    /** Everything at its library default: a book that works on a server running nothing else. */
    public static final ObjectiveBookDeps DEFAULTS = builder().build();

    @Nonnull private final NpcQuestPageDeps.PageTheme theme;
    @Nonnull private final ChromePainter railPainter;
    @Nonnull private final ChromePainter sidePanelPainter;
    @Nonnull private final ExtHandler extHandler;
    @Nonnull private final BoardManagedQuests boardManaged;
    @Nonnull private final RequirementText requirementText;
    @Nonnull private final TagLabelSource tagLabels;
    @Nonnull private final NpcQuestPageDeps.RewardChipSource rewardChips;
    @Nonnull private final FirstClaimSource firstClaims;
    @Nonnull private final MilestoneSource milestones;
    @Nonnull private final MilestoneClaim milestoneClaim;
    @Nonnull private final ActionFeedback actionFeedback;
    @Nonnull private final QuestClaimPreCheck claimPreCheck;

    private ObjectiveBookDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.railPainter = builder.railPainter;
        this.sidePanelPainter = builder.sidePanelPainter;
        this.extHandler = builder.extHandler;
        this.boardManaged = builder.boardManaged;
        this.requirementText = builder.requirementText;
        this.tagLabels = builder.tagLabels;
        this.rewardChips = builder.rewardChips;
        this.firstClaims = builder.firstClaims;
        this.milestones = builder.milestones;
        this.milestoneClaim = builder.milestoneClaim;
        this.actionFeedback = builder.actionFeedback;
        this.claimPreCheck = builder.claimPreCheck;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public NpcQuestPageDeps.PageTheme theme() {
        return theme;
    }

    @Nonnull
    public ChromePainter railPainter() {
        return railPainter;
    }

    @Nonnull
    public ChromePainter sidePanelPainter() {
        return sidePanelPainter;
    }

    @Nonnull
    public ExtHandler extHandler() {
        return extHandler;
    }

    @Nonnull
    public BoardManagedQuests boardManaged() {
        return boardManaged;
    }

    @Nonnull
    public RequirementText requirementText() {
        return requirementText;
    }

    @Nonnull
    public TagLabelSource tagLabels() {
        return tagLabels;
    }

    @Nonnull
    public NpcQuestPageDeps.RewardChipSource rewardChips() {
        return rewardChips;
    }

    @Nonnull
    public FirstClaimSource firstClaims() {
        return firstClaims;
    }

    @Nonnull
    public MilestoneSource milestones() {
        return milestones;
    }

    @Nonnull
    public MilestoneClaim milestoneClaim() {
        return milestoneClaim;
    }

    @Nonnull
    public ActionFeedback actionFeedback() {
        return actionFeedback;
    }

    /** True when a consumer filled the accept/abandon seam and owns those announcements. */
    public boolean announcesActions() {
        return actionFeedback != NO_FEEDBACK;
    }

    @Nonnull
    public QuestClaimPreCheck claimPreCheck() {
        return claimPreCheck;
    }

    // ==================== guarded reads ====================

    /** Is {@code quest} board-managed, guarded: a throwing seam reads as "no". */
    public boolean managedGuarded(@Nonnull Quest quest) {
        try {
            return boardManaged.managed(quest);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The pills a managed row wears, guarded: a throwing seam costs the pills, not the row. */
    @Nonnull
    public List<Pill> pillsGuarded(@Nonnull Quest quest) {
        try {
            List<Pill> pills = boardManaged.pills(quest);
            return pills != null ? pills : List.of();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** The at-the-board hint, guarded; null for none. */
    @Nullable
    public Message acceptHintGuarded(@Nonnull Quest quest) {
        try {
            return boardManaged.acceptHint(quest);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The requirements line, guarded; null for none. */
    @Nullable
    public Message requirementLineGuarded(@Nonnull Quest quest) {
        try {
            return requirementText.lineFor(quest);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The claim pre-check's refusal, guarded: a throwing seam costs only its own answer, so the
     * claim reaches the engine exactly as it would with nothing registered.
     */
    @Nullable
    public Message claimPreCheckGuarded(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        try {
            return claimPreCheck.refusalFor(quest, store, ref, player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A tag chip's label, guarded: a throwing seam falls to the tidied raw tag. */
    @Nonnull
    public Message tagLabelGuarded(@Nonnull String tag) {
        try {
            Message label = tagLabels.labelOf(tag);
            if (label != null) {
                return label;
            }
        } catch (Throwable ignored) {
            // A consumer's label source failing costs the label, never the chip.
        }
        return Msg.raw(prettifyTag(tag));
    }

    /** A server-first claim, guarded; null while unclaimed or unknowable. */
    @Nullable
    public FirstClaim claimOfGuarded(@Nonnull String achievementId, @Nullable UUID viewer) {
        try {
            return firstClaims.claimOf(achievementId, viewer);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The milestone ladder, guarded: a throwing seam reads as no ladder. */
    @Nonnull
    public List<MilestoneView> milestonesGuarded(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Subject subject) {
        try {
            List<MilestoneView> views = milestones.milestones(store, ref, subject);
            return views != null ? views : List.of();
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's milestone source failed: " + t.getMessage());
            return List.of();
        }
    }

    /** Run one deps-owned painter, guarded: a throwing painter reads as nothing painted. */
    public boolean paintGuarded(@Nonnull ChromePainter painter, @Nonnull Chrome chrome,
            @Nonnull String what) {
        try {
            return painter.paint(chrome);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's " + what + " painter failed: " + t.getMessage());
            return false;
        }
    }

    /** {@code "wilds_side"} reads as {@code "Wilds Side"}: word breaks on {@code _-}, title case. */
    @Nonnull
    static String prettifyTag(@Nonnull String tag) {
        StringBuilder out = new StringBuilder(tag.length());
        boolean startWord = true;
        for (char c : tag.toCharArray()) {
            if (c == '_' || c == '-') {
                out.append(' ');
                startWord = true;
            } else {
                out.append(startWord ? Character.toUpperCase(c) : c);
                startWord = false;
            }
        }
        return out.toString();
    }

    /** Immutable-by-copy assembly; every knob defaults to the library's own answer. */
    public static final class Builder {

        @Nonnull private NpcQuestPageDeps.PageTheme theme = LEGACY_THEME;
        @Nonnull private ChromePainter railPainter = NO_CHROME;
        @Nonnull private ChromePainter sidePanelPainter = NO_CHROME;
        @Nonnull private ExtHandler extHandler = NO_EXT;
        @Nonnull private BoardManagedQuests boardManaged = NO_BOARDS;
        @Nonnull private RequirementText requirementText = GENERIC_REQUIREMENTS;
        @Nonnull private TagLabelSource tagLabels = RAW_TAGS;
        @Nonnull private NpcQuestPageDeps.RewardChipSource rewardChips = GENERIC_CHIPS;
        @Nonnull private FirstClaimSource firstClaims = NO_FIRST_CLAIMS;
        @Nonnull private MilestoneSource milestones = NO_MILESTONES;
        @Nonnull private MilestoneClaim milestoneClaim = NO_MILESTONE_CLAIM;
        @Nonnull private ActionFeedback actionFeedback = NO_FEEDBACK;
        @Nonnull private QuestClaimPreCheck claimPreCheck = NO_CLAIM_PRECHECK;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable NpcQuestPageDeps.PageTheme value) {
            this.theme = value != null ? value : LEGACY_THEME;
            return this;
        }

        @Nonnull
        public Builder railPainter(@Nullable ChromePainter value) {
            this.railPainter = value != null ? value : NO_CHROME;
            return this;
        }

        @Nonnull
        public Builder sidePanelPainter(@Nullable ChromePainter value) {
            this.sidePanelPainter = value != null ? value : NO_CHROME;
            return this;
        }

        @Nonnull
        public Builder extHandler(@Nullable ExtHandler value) {
            this.extHandler = value != null ? value : NO_EXT;
            return this;
        }

        @Nonnull
        public Builder boardManaged(@Nullable BoardManagedQuests value) {
            this.boardManaged = value != null ? value : NO_BOARDS;
            return this;
        }

        @Nonnull
        public Builder requirementText(@Nullable RequirementText value) {
            this.requirementText = value != null ? value : GENERIC_REQUIREMENTS;
            return this;
        }

        @Nonnull
        public Builder tagLabels(@Nullable TagLabelSource value) {
            this.tagLabels = value != null ? value : RAW_TAGS;
            return this;
        }

        @Nonnull
        public Builder rewardChips(@Nullable NpcQuestPageDeps.RewardChipSource value) {
            this.rewardChips = value != null ? value : GENERIC_CHIPS;
            return this;
        }

        @Nonnull
        public Builder firstClaims(@Nullable FirstClaimSource value) {
            this.firstClaims = value != null ? value : NO_FIRST_CLAIMS;
            return this;
        }

        @Nonnull
        public Builder milestones(@Nullable MilestoneSource value) {
            this.milestones = value != null ? value : NO_MILESTONES;
            return this;
        }

        @Nonnull
        public Builder milestoneClaim(@Nullable MilestoneClaim value) {
            this.milestoneClaim = value != null ? value : NO_MILESTONE_CLAIM;
            return this;
        }

        @Nonnull
        public Builder actionFeedback(@Nullable ActionFeedback value) {
            this.actionFeedback = value != null ? value : NO_FEEDBACK;
            return this;
        }

        @Nonnull
        public Builder claimPreCheck(@Nullable QuestClaimPreCheck value) {
            this.claimPreCheck = value != null ? value : NO_CLAIM_PRECHECK;
            return this;
        }

        @Nonnull
        public ObjectiveBookDeps build() {
            return new ObjectiveBookDeps(this);
        }
    }
}
