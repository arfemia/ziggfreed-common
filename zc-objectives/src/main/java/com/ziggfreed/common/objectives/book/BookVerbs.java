package com.ziggfreed.common.objectives.book;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.npc.NpcNames;
import com.ziggfreed.common.objectives.render.ClaimToasts;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * The book's verbs, acting for the context's player over THE shared progression runtime: every mutating call
 * runs inside the registered {@link ProgressionCallScope}, so a quest accepted here fires exactly what the
 * owning mod's own menu would have fired, and the consumer's seams ({@link ObjectiveBookDeps}: the claim
 * pre-check, the accept and abandon announcements, the milestone claim) speak as they always have.
 *
 * <p>A verb acts and raises its toast; it never answers the event. The shell answers a verb by reopening on
 * the same state (the selection rides it, so Accept, Abandon and Collect land back on the same row in its new
 * section); a tab that can show the result in place (a pin, a track, a claim that moves nothing) calls the verb
 * itself and sends its own partial. Each verb with nobody to act for, or no such quest or achievement, does
 * nothing and says so with its return value.
 */
public final class BookVerbs {

    @Nonnull private final BookContext ctx;

    BookVerbs(@Nonnull BookContext ctx) {
        this.ctx = ctx;
    }

    // ==================== quests ====================

    /**
     * The one state-dispatched quest button: Collect when the quest waits to be collected, else Accept. True
     * when the press changed the quest.
     */
    public boolean primary(@Nullable String questId) {
        Subject subject = ctx.questSubject();
        Quest quest = subject == null ? null : quest(questId);
        if (quest == null) {
            return false;
        }
        return ProgressionRuntime.quests().status(subject, quest) == QuestStatus.COMPLETED_UNCLAIMED
                ? collect(quest) : accept(quest);
    }

    /**
     * Accept {@code quest}. A quest {@link #takenAtGiver taken at its giver} is refused with the hint naming
     * where (the NPC quest page is where it is legitimately accepted, so the engine's path stays open). A quest
     * that arms itself is taken here as the engine arms it, its giver recorded as the place it was taken, so a
     * hand-in or a collection that goes back there reads the same whichever way it reached the log.
     */
    public boolean accept(@Nonnull Quest quest) {
        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ctx.questSubject();
        if (subject == null) {
            return false;
        }
        if (takenAtGiver(quest)) {
            toast(ToastKind.INFO, giverHint(quest));
            return false;
        }
        if (!engine.canAccept(subject, quest).allowed()) {
            return false;
        }
        // What the settle right behind the accept paid, when a standing value already met every step: the
        // feedback seam lists that receipt rather than the authored promise.
        AtomicReference<RewardGrants.GrantOutcome> settled = new AtomicReference<>();
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.questScope().around(subject, s -> {
            // The giver is the accept site, as the engine's own auto-accept pass records it; a quest with no
            // giver records none, which is what the book has always passed.
            boolean accepted = engine.canAccept(s, quest).allowed() && engine.accept(s, quest, quest.npcViewId());
            if (accepted) {
                // Retroactive completions (a standing value already met) finish it at once.
                settled.set(engine.trySettle(s, quest));
            }
            return Boolean.valueOf(accepted);
        }));
        if (!ok) {
            return false;
        }
        try {
            ctx.deps().actionFeedback().accepted(quest, ctx.store(), ctx.ref(), ctx.player(), settled.get());
        } catch (Throwable ignored) {
            // A consumer's feedback failing costs its own moment, never the page.
        }
        if (!ctx.deps().announcesActions()) {
            // A filled feedback seam owns the announcement; two toasts for one accept would double-report it.
            toast(ToastKind.SUCCESS, ctx.text("book.toast.accepted"));
        }
        return true;
    }

    /**
     * Collect a finished quest's rewards. A consumer's pre-check may refuse first (an error toast, the engine
     * never asked); the engine refuses a placeless payout for a site-bound quest, said as where to collect it.
     */
    public boolean collect(@Nonnull Quest quest) {
        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ctx.questSubject();
        if (subject == null || engine.status(subject, quest) != QuestStatus.COMPLETED_UNCLAIMED) {
            return false;
        }
        Message refusal = ctx.deps().claimPreCheckGuarded(quest, ctx.store(), ctx.ref(), ctx.player());
        if (refusal != null) {
            toast(ToastKind.ERROR, refusal);
            return false;
        }
        RewardGrants.GrantOutcome paid = ProgressionRuntime.questScope().around(subject, s -> engine.tryClaim(s, quest));
        if (paid == null) {
            toast(ToastKind.ERROR, ctx.text(quest.turnInAt() != null
                    ? "book.quests.claim_at_site" : "book.toast.claim_failed"));
            return false;
        }
        // The rows are what THIS press handed over (the claim rewards' receipt, so a rolled table lists what it
        // produced), never the auto ones the quest settled with earlier: listing a payout twice reads as double.
        rewardToast(ctx.text("book.toast.quest_complete", questName(quest)), paid.receipt());
        return true;
    }

    /**
     * Hand items in for {@code objectiveId} (null: the quest's first step that can take a hand-in now). Returns
     * how many were handed in; 0 says why in a toast.
     */
    public int handIn(@Nonnull Quest quest, @Nullable String objectiveId) {
        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ctx.questSubject();
        if (subject == null) {
            return 0;
        }
        String objectiveKey = objectiveId;
        if (objectiveKey == null || objectiveKey.isBlank()) {
            ObjectiveDef first = engine.firstActiveTurnIn(subject, quest, null);
            objectiveKey = first == null ? null : first.id();
        }
        if (objectiveKey == null) {
            return 0;
        }
        String step = objectiveKey;
        Integer turnedIn = ProgressionRuntime.questScope().around(subject,
                s -> Integer.valueOf(engine.attemptTurnIn(s, quest, step)));
        int handed = turnedIn == null ? 0 : turnedIn.intValue();
        ObjectiveDef objective = quest.objective(step);
        Message itemName = objective == null || objective.target() == null
                ? Msg.raw("") : NativeNames.itemNameMsg(objective.target());
        int required = objective == null ? 0 : objective.amountAsInt();
        if (handed > 0) {
            // Counts are data; the item name is a nested client-resolved Message.
            toast(ToastKind.SUCCESS, ctx.text("book.toast.turn_in", handed, required, itemName));
        } else {
            ObjectiveProgressState progress = engine.progressOf(subject, quest.id(), step);
            int current = progress != null ? progress.current() : 0;
            toast(ToastKind.ERROR, ctx.text("book.quests.turn_in_fail", itemName, current, required));
        }
        return handed;
    }

    /** Abandon a quest (and stop tracking it). */
    public boolean abandon(@Nonnull Quest quest) {
        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ctx.questSubject();
        if (subject == null) {
            return false;
        }
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.questScope().around(subject, s -> {
            boolean abandoned = engine.abandon(s, quest.id());
            engine.untrack(s, quest.id());
            return Boolean.valueOf(abandoned);
        }));
        if (ok) {
            try {
                ctx.deps().actionFeedback().abandoned(quest, ctx.store(), ctx.ref(), ctx.player());
            } catch (Throwable ignored) {
                // A consumer's feedback failing costs its own moment, never the page.
            }
            if (!ctx.deps().announcesActions()) {
                toast(ToastKind.INFO, ctx.text("book.toast.abandoned"));
            }
        } else {
            toast(ToastKind.WARNING, ctx.text("book.toast.abandon_failed"));
        }
        return ok;
    }

    /** Track or untrack a quest; one over the cap is refused with its toast. Returns whether it is tracked now. */
    public boolean toggleTrack(@Nonnull Quest quest) {
        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ctx.questSubject();
        if (subject == null) {
            return false;
        }
        Message name = questName(quest);
        ProgressionCallScope scope = ProgressionRuntime.questScope();
        if (engine.tracked(subject).contains(quest.id())) {
            scope.around(subject, s -> Boolean.valueOf(engine.untrack(s, quest.id())));
            toast(ToastKind.INFO, ctx.text("book.toast.untracked", name));
            return false;
        }
        boolean ok = Boolean.TRUE.equals(scope.around(subject, s -> Boolean.valueOf(engine.track(s, quest.id()))));
        toast(ok ? ToastKind.INFO : ToastKind.ERROR, ok
                ? ctx.text("book.toast.tracked", name) : ctx.text("book.quests.track_cap", engine.maxTracked()));
        return ok;
    }

    // ==================== achievements ====================

    /** Pin or unpin an achievement; a pin over the cap is refused with its toast. Returns whether it is pinned now. */
    public boolean togglePin(@Nonnull String achievementId) {
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = ctx.achievementSubject();
        if (subject == null || achievementId.isBlank()) {
            return false;
        }
        String id = achievementId.trim();
        Achievement achievement = engine.achievement(id);
        Message name = achievement != null ? achievementName(achievement) : Msg.raw(id);
        try {
            ProgressionCallScope scope = ProgressionRuntime.achievementScope();
            if (engine.pinned(subject).contains(id)) {
                scope.around(subject, s -> Boolean.valueOf(engine.unpin(s, id)));
                toast(ToastKind.INFO, ctx.text("book.toast.unpinned", name));
                return false;
            }
            boolean pinned = Boolean.TRUE.equals(scope.around(subject, s -> Boolean.valueOf(engine.pin(s, id))));
            toast(pinned ? ToastKind.INFO : ToastKind.ERROR, pinned
                    ? ctx.text("book.toast.pinned", name)
                    : ctx.text("book.achievements.pin_cap", engine.maxPinned()));
            return pinned;
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's pin toggle failed: " + t.getMessage());
            return false;
        }
    }

    /** Collect an earned achievement's waiting rewards. */
    public boolean claim(@Nonnull Achievement achievement) {
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = ctx.achievementSubject();
        if (subject == null) {
            return false;
        }
        RewardGrants.GrantOutcome paid = ProgressionRuntime.achievementScope()
                .around(subject, s -> engine.tryClaim(s, achievement));
        if (paid == null) {
            toast(ToastKind.WARNING, ctx.text("book.toast.claim_failed"));
            return false;
        }
        // The rows are what the claim just handed over; a rolled table lists what it rolled.
        rewardToast(ctx.text("book.achievements.claim_success"), paid.receipt());
        return true;
    }

    /** Collect the consumer's points milestone at {@code threshold}, through its claim seam. */
    @Nonnull
    public ObjectiveBookDeps.MilestoneClaimOutcome claimMilestone(int threshold) {
        Subject subject = ctx.achievementSubject();
        if (subject == null || threshold <= 0) {
            return ObjectiveBookDeps.MilestoneClaimOutcome.NOT_READY;
        }
        // Resolved BEFORE the claim: afterwards the rung reads claimed, and with it which rewards this press
        // paid, the fallback rows for a fill that answers the outcome alone.
        List<RewardSpec> authored = List.of();
        for (ObjectiveBookDeps.MilestoneView view : ctx.deps().milestonesGuarded(ctx.store(), ctx.ref(), subject)) {
            if (view != null && view.threshold() == threshold) {
                authored = view.rewards();
                break;
            }
        }
        ObjectiveBookDeps.MilestoneClaimResult result;
        try {
            result = ctx.deps().milestoneClaim().tryClaim(threshold, ctx.store(), ctx.ref(), ctx.player());
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's milestone claim failed: " + t.getMessage());
            result = ObjectiveBookDeps.MilestoneClaimResult.of(ObjectiveBookDeps.MilestoneClaimOutcome.NOT_READY);
        }
        switch (result.outcome()) {
            case SUCCESS -> rewardToast(ctx.text("book.achievements.claim_success"), result.rowsOr(authored));
            case INVENTORY_FULL -> toast(ToastKind.ERROR, ctx.text("book.achievements.inventory_full"));
            case NOT_READY -> toast(ToastKind.WARNING, ctx.text("book.toast.claim_failed"));
        }
        return result.outcome();
    }

    // ==================== shared readings ====================

    /** The quest {@code id} names in the shared catalogue, or null. */
    @Nullable
    public static Quest quest(@Nullable String id) {
        return id == null || id.isBlank() ? null : ProgressionRuntime.quests().quest(id.trim());
    }

    /** The achievement {@code id} names in the shared catalogue, or null. */
    @Nullable
    public static Achievement achievement(@Nullable String id) {
        return id == null || id.isBlank() ? null : ProgressionRuntime.achievements().achievement(id.trim());
    }

    /**
     * Whether {@code quest} names a giver ({@link Quest#npcViewId()}): the character its hand-in or its collection
     * may go back to. Whether it is also TAKEN there is {@link #takenAtGiver}'s question, which the book asks
     * before offering Accept.
     */
    public static boolean giverBound(@Nonnull Quest quest) {
        return quest.npcViewId() != null;
    }

    /**
     * Whether {@code quest} is taken AT its giver, so the book never offers Accept for it: its page shows where to
     * go instead, and {@link #accept} refuses it. A quest that arms itself ({@link Quest#autoAccept()}) never is:
     * the engine puts it in the log rather than the giver handing it out, so its giver is only where it is handed
     * in, and a player who dropped it takes it back from the book like a quest with no giver. Everything else
     * (listing, objectives, rewards, hand-in, abandon) is untouched.
     */
    public static boolean takenAtGiver(@Nonnull Quest quest) {
        return giverBound(quest) && !quest.autoAccept();
    }

    /**
     * Where a quest {@link #takenAtGiver taken at its giver} is taken, naming the character when the placement
     * and identity assets can (the reading every other surface uses, so the hint and the nameplate never
     * disagree); the plain hint otherwise.
     */
    @Nonnull
    public static Message giverHint(@Nonnull Quest quest) {
        Message name = null;
        try {
            name = NpcNames.nameFor(quest.npcViewId());
        } catch (Throwable ignored) {
            // A naming walk failing costs the name, never the hint.
        }
        return name != null ? Msg.tr("ziggfreedcommon.", "progression.book.quests.giver_hint", name)
                : Msg.tr("ziggfreedcommon.", "progression.book.quests.giver_hint_plain");
    }

    @Nonnull
    private static Message questName(@Nonnull Quest quest) {
        return ProgressionTexts.titleOrUntitled(quest.id());
    }

    @Nonnull
    private static Message achievementName(@Nonnull Achievement achievement) {
        return ProgressionTexts.titleOrUntitled(achievement.id());
    }

    private void toast(@Nonnull ToastKind kind, @Nonnull Message message) {
        ctx.toast(ToastSpec.of(kind, message));
    }

    /** {@link ClaimToasts#rewardToast} through the consumer's chip source and the book's overflow line. */
    private void rewardToast(@Nonnull Message headline, @Nonnull List<RewardSpec> rewards) {
        ctx.toast(ClaimToasts.rewardToast(headline, rewards, ctx.deps().rewardChips(),
                dropped -> ctx.text("book.more", dropped)));
    }
}
