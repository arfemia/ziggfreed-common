package com.ziggfreed.common.objectives.book;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.questlist.NpcQuestPageDeps;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about {@link ObjectiveBookPage} without owning it.
 *
 * <p>Every seam here has a DEFAULT that leaves the book fully working on a bare server: the
 * catalogue, the subject and the display text all come from the shared progression runtime, so a
 * consumer fills a seam only to say something the library genuinely cannot know - its
 * board-managed quests, who claimed a server-first, its points-milestone ladder. The book's frame
 * paint and its rail are the shared menu's, said through {@code ZigMenu.consumer}; a consumer's
 * statistics reach the book as a {@code LedgerContributions.STATISTICS} source.
 *
 * <ul>
 *   <li>{@link ExtHandler} - answers an {@code ext} click the book receives.</li>
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
 *   <li>{@link DetailBlockSource} - extra blocks after a quest's or an achievement's own on its page;
 *       default none.</li>
 *   <li>{@link SeenMarks} - when the player last opened each achievement category, so a tile can say
 *       something new was earned there; default the library's own, kept in the player's progress
 *       component ({@link ComponentSeenMarks}); {@link SeenMarks#NONE} turns the marks off.</li>
 * </ul>
 *
 * <p>Immutable; build one at setup and register a supplier via
 * {@link ObjectiveBookPages#deps(java.util.function.Supplier)}.
 */
public final class ObjectiveBookDeps {

    /**
     * Answers an {@code ext} click the book receives. Return true when the handler took the screen
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

    /**
     * What a quest tag chip says; the colour comes from the shared deterministic table. A source
     * answers null for a tag it has no word for, and the chip then reads the tidied raw tag, so a
     * consumer says only what it knows rather than re-spelling the library's own fallback.
     */
    @FunctionalInterface
    public interface TagLabelSource {

        @Nullable
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

    /**
     * What pressing a milestone's Claim button did AND what it handed over. {@code receipt} is what
     * the payout actually put in the player's hands (a rolled table as the items it produced, an
     * empty roll as nothing), or null when the fill answered only the outcome; the toast then lists
     * the rung as authored, through {@link #rowsOr}.
     */
    public record MilestoneClaimResult(@Nonnull MilestoneClaimOutcome outcome,
                                       @Nullable List<RewardSpec> receipt) {

        public MilestoneClaimResult {
            receipt = receipt == null ? null : List.copyOf(receipt);
        }

        /** An outcome with no word on what was handed over. */
        @Nonnull
        public static MilestoneClaimResult of(@Nonnull MilestoneClaimOutcome outcome) {
            return new MilestoneClaimResult(outcome, null);
        }

        /** A successful claim that handed over exactly {@code receipt}. */
        @Nonnull
        public static MilestoneClaimResult paid(@Nonnull List<RewardSpec> receipt) {
            return new MilestoneClaimResult(MilestoneClaimOutcome.SUCCESS, receipt);
        }

        /**
         * What a toast raised after this claim lists: the receipt when the fill answered one, else
         * {@code authored}, the rung's own list, resolved by the caller BEFORE the claim so which
         * rewards this press paid is still readable once the rung reads claimed.
         */
        @Nonnull
        public List<RewardSpec> rowsOr(@Nonnull List<RewardSpec> authored) {
            return receipt != null ? receipt : authored;
        }
    }

    /**
     * Claims one milestone's waiting rewards. A fill implements {@link #claim} for the outcome
     * alone, or overrides {@link #tryClaim} as well to say what the claim actually handed over.
     */
    @FunctionalInterface
    public interface MilestoneClaim {

        @Nonnull
        MilestoneClaimOutcome claim(int threshold, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player);

        /**
         * {@link #claim} answering WHAT it paid beside the outcome. The default runs the claim and
         * reports no receipt, so a fill that only answers the outcome keeps working and the book
         * lists the rung as authored; a fill holding the payout's receipt overrides this so a
         * rolled table lists the items it produced.
         */
        @Nonnull
        default MilestoneClaimResult tryClaim(int threshold, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
            return MilestoneClaimResult.of(claim(threshold, store, ref, player));
        }
    }

    /**
     * What announces a quest accepted or abandoned from the book. A FILLED seam owns the
     * announcement outright and the book's own accepted/abandoned toasts stand down, so one
     * action is one toast however the consumer words it; unfilled, the book's toasts are the
     * announcement.
     *
     * <p>The book asks the five-argument {@link #accepted(Quest, Store, Ref, Player,
     * RewardGrants.GrantOutcome)}, carrying what the settle right behind the accept paid when the
     * quest finished the instant it was taken; its default hands off to the four-argument form, so
     * a fill that does not care about the payout implements that one and hears every accept.
     */
    public interface ActionFeedback {

        default void accepted(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        }

        /**
         * A quest was accepted, and {@code settled} is what the settle right behind the accept
         * paid - non-null only when a standing value already met every step and the quest paid out
         * on the spot, so a completion toast raised here lists what was actually handed over. Null
         * for an ordinary accept, and for one that finished but parked for collecting.
         */
        default void accepted(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull Player player,
                @Nullable RewardGrants.GrantOutcome settled) {
            accepted(quest, store, ref, player);
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

    /** No consumer controls, so an unexpected ext click is answered by the book alone. */
    public static final ExtHandler NO_EXT = (extId, store, ref, player) -> false;

    /** No board anywhere, so every quest is the log's own. */
    public static final BoardManagedQuests NO_BOARDS = quest -> false;

    /** The generic gate reading: prerequisite quests, and nothing a factor vocabulary must name. */
    public static final RequirementText GENERIC_REQUIREMENTS = ObjectiveBookDeps::genericRequirementLine;

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

    /** The marks a deps whose builder named none carries ({@link #libraryMarks}). */
    @Nonnull
    private static volatile SeenMarks libraryMarks = SeenMarks.NONE;

    /** Everything at its library default: a book that works on a server running nothing else. */
    public static final ObjectiveBookDeps DEFAULTS = builder().build();

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
    @Nonnull private final DetailBlockSource detailBlocks;
    /** The builder's marks, or null for the library's own ({@link #libraryMarks}), read at each use. */
    @Nullable private final SeenMarks seen;

    private ObjectiveBookDeps(@Nonnull Builder builder) {
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
        this.detailBlocks = builder.detailBlocks;
        this.seen = builder.seen;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Install the library's own seen marks ({@link ComponentSeenMarks}, from {@link ObjectiveBookBootstrap}): the
     * marks every deps carries whose builder named none. Null goes back to {@link SeenMarks#NONE}.
     */
    public static void libraryMarks(@Nullable SeenMarks marks) {
        libraryMarks = marks != null ? marks : SeenMarks.NONE;
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

    @Nonnull
    public DetailBlockSource detailBlocks() {
        return detailBlocks;
    }

    @Nonnull
    public SeenMarks seen() {
        SeenMarks own = seen;
        return own != null ? own : libraryMarks;
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

    /** A tag chip's label, guarded: a seam that declines or throws falls to the tidied raw tag. */
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

    /** The consumer's extra blocks on a quest's page, guarded: a throwing or null answer adds none. */
    @Nonnull
    public List<DetailBlock> detailBlocksGuarded(@Nonnull Quest quest, @Nullable Subject subject) {
        try {
            return blocksOrNone(detailBlocks.quest(quest, subject));
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's detail blocks failed for a quest: " + t.getMessage());
            return List.of();
        }
    }

    /** The consumer's extra blocks on an achievement's page, guarded: a throwing or null answer adds none. */
    @Nonnull
    public List<DetailBlock> detailBlocksGuarded(@Nonnull Achievement achievement, @Nullable Subject subject) {
        try {
            return blocksOrNone(detailBlocks.achievement(achievement, subject));
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's detail blocks failed for an achievement: " + t.getMessage());
            return List.of();
        }
    }

    @Nonnull
    private static List<DetailBlock> blocksOrNone(@Nullable List<DetailBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<DetailBlock> kept = new ArrayList<>(blocks.size());
        for (DetailBlock block : blocks) {
            if (block != null) {
                kept.add(block);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * When {@code subject} last opened {@code category}, guarded: a throwing mark reads as seen just now
     * ({@link Long#MAX_VALUE}), so a failing marker never lights a tile.
     */
    public long seenAtGuarded(@Nullable Subject subject, @Nonnull String category) {
        try {
            return seen().seenAt(subject, category);
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    /** Record that {@code subject} opened {@code category}, guarded: a throwing mark costs only itself. */
    public void markSeenGuarded(@Nullable Subject subject, @Nonnull String category, long nowMs) {
        try {
            seen().markSeen(subject, category, nowMs);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's seen marks failed: " + t.getMessage());
        }
    }

    /**
     * The library's default requirements reading: the gate's prerequisite quests and permission leaf,
     * comma-joined, {@code AnyOf} alternatives bracketed. A {@code Not} group is deliberately never shown:
     * printing what must NOT be true reads as an instruction to go and do the thing that keeps the quest shut.
     * Null when nothing displayable is asked.
     */
    @Nullable
    static Message genericRequirementLine(@Nonnull Quest quest) {
        GateSpec requires = quest.requires();
        if (requires == null || requires.isEmpty()) {
            return null;
        }
        List<Message> items = new ArrayList<>(clauseItems(requires));
        for (GateClause clause : requires.allOfOrEmpty()) {
            if (clause != null) {
                items.addAll(clauseItems(clause));
            }
        }
        for (GateClause clause : requires.anyOfOrEmpty()) {
            if (clause == null) {
                continue;
            }
            List<Message> alternatives = clauseItems(clause);
            if (alternatives.isEmpty()) {
                continue;
            }
            Message joined = joinWithCommas(alternatives);
            items.add(alternatives.size() > 1 ? Msg.cat(Msg.raw("("), joined, Msg.raw(")")) : joined);
        }
        if (items.isEmpty()) {
            return null;
        }
        // The joined list rides as a PARAM, so it is built with the param-safe composite.
        return Msg.key("ziggfreedcommon.progression.book.quests.requires", joinWithCommas(items));
    }

    /** Every displayable requirement ONE clause asks for. */
    @Nonnull
    private static List<Message> clauseItems(@Nonnull GateClause clause) {
        List<Message> items = new ArrayList<>();
        for (String questId : clause.questsOrEmpty()) {
            if (questId == null || questId.isBlank()) {
                continue;
            }
            Message name = ProgressionTexts.title(questId);
            items.add(Msg.key("ziggfreedcommon.progression.book.quests.req.quest",
                    name != null ? name : Msg.raw(questId)));
        }
        String permission = clause.getPermission();
        if (permission != null && !permission.isBlank()) {
            items.add(Msg.key("ziggfreedcommon.progression.book.quests.req.permission", permission));
        }
        return items;
    }

    @Nonnull
    private static Message joinWithCommas(@Nonnull List<Message> parts) {
        List<Message> out = new ArrayList<>(parts.size() * 2);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.add(Msg.raw(", "));
            }
            out.add(parts.get(i));
        }
        return Msg.cat(out.toArray(new Message[0]));
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
        @Nonnull private DetailBlockSource detailBlocks = DetailBlockSource.NONE;
        @Nullable private SeenMarks seen;

        private Builder() {
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
        public Builder detailBlocks(@Nullable DetailBlockSource value) {
            this.detailBlocks = value != null ? value : DetailBlockSource.NONE;
            return this;
        }

        /** The book's seen marks; null (the default) carries the library's own ({@link ObjectiveBookDeps#libraryMarks(SeenMarks)}). */
        @Nonnull
        public Builder seen(@Nullable SeenMarks value) {
            this.seen = value;
            return this;
        }

        @Nonnull
        public ObjectiveBookDeps build() {
            return new ObjectiveBookDeps(this);
        }
    }
}
