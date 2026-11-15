package com.ziggfreed.common.objectives.book;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.npc.NpcNames;
import com.ziggfreed.common.objectives.journal.QuestVerbs;
import com.ziggfreed.common.objectives.render.ClaimToasts;
import com.ziggfreed.common.progress.ClaimAttempt;
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
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * The book's verbs, acting for the context's player over THE shared progression runtime: every mutating call
 * runs inside the registered {@link ProgressionCallScope}, so a quest accepted here fires exactly what the
 * owning mod's own menu would have fired, and the consumer's seams ({@link ObjectiveBookDeps}: the accept and
 * abandon announcements, the milestone claim) speak as they always have.
 *
 * <p>A quest's completion, its waiting to be collected and its collect are announced by the engine's notices
 * alone ({@code Quest_Completed}, {@code Quest_Parked}, {@code Quest_Claimed}), drawn into the open book as they
 * fire; no verb here raises a toast of its own for any of them, so one press is one toast on every surface.
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
        // The giver is the accept site, as the engine's own auto-accept pass records it; a quest with no giver
        // records none.
        QuestVerbs.Accepted accepted = QuestVerbs.acceptAt(subject, quest, quest.npcViewId());
        if (!accepted.accepted()) {
            return false;
        }
        announceAccepted(ctx.deps(), quest, accepted, ctx.store(), ctx.ref(), ctx.player(), ctx::toast);
        return true;
    }

    /**
     * The ONE accept announcement, for the book and the library's conversation binding alike. An accept that
     * finished the quest at once ({@link QuestVerbs.Accepted#finished}) says nothing here: the engine's completion
     * notice ({@code Quest_Completed} or {@code Quest_Parked}) is its one announcement on every surface. Otherwise
     * the consumer's {@link ObjectiveBookDeps.ActionFeedback} hears it, and only where no consumer filled that seam
     * does the library's own "accepted" line go to {@code toast}: a filled seam owns the announcement, and two
     * toasts for one accept would double-report it. Guarded: a consumer's feedback failing costs its own moment.
     */
    public static void announceAccepted(@Nonnull ObjectiveBookDeps deps, @Nonnull Quest quest,
            @Nonnull QuestVerbs.Accepted accepted, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull Consumer<ToastSpec> toast) {
        if (!accepted.accepted() || accepted.finished()) {
            return;
        }
        try {
            deps.actionFeedback().accepted(quest, store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the accept announcement for '" + quest.id() + "' failed: " + t.getMessage());
        }
        if (!deps.announcesActions()) {
            toast.accept(ToastSpec.of(ToastKind.SUCCESS, BookContext.libraryText("book.toast.accepted")));
        }
    }

    /**
     * Collect a finished quest's rewards through the engine's own claim. A refusal is answered off the engine's
     * reason ({@link ClaimToasts#refusedToast}): rewards that will not fit say make room, a site-bound quest says
     * where to collect it. A collect that paid raises nothing here: the engine's {@code Quest_Claimed} notice,
     * drawn into the open book as the claim lands, names the quest, lists what this press handed over and plays
     * the chest, the one announcement of a collect on every surface.
     */
    public boolean collect(@Nonnull Quest quest) {
        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ctx.questSubject();
        if (subject == null || engine.status(subject, quest) != QuestStatus.COMPLETED_UNCLAIMED) {
            return false;
        }
        ClaimAttempt claimed = ProgressionRuntime.questScope().around(subject, s -> engine.attemptClaim(s, quest));
        if (ClaimAttempt.paidOf(claimed) == null) {
            // The engine's own refusal picks the line: rewards that will not fit say make room, a quest the engine
            // still binds to a site says where to collect it (a site that is gone binds nothing), anything else
            // says there is nothing to collect.
            ctx.toast(ClaimToasts.refusedToast(claimed));
            return false;
        }
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
            // Counts are data; the item name is a nested client-resolved Message. A hand-in that finished the
            // quest raises nothing here: its completion notice (Quest_Completed or Quest_Parked), drawn into the
            // book as it fires, is the one announcement of that press.
            if (engine.status(subject, quest) == QuestStatus.ACTIVE) {
                ctx.toast(ToastSpec.of(ToastKind.SUCCESS, ctx.text("book.toast.turn_in", handed, required, itemName)));
            }
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
        ClaimAttempt claimed = ProgressionRuntime.achievementScope()
                .around(subject, s -> engine.attemptClaim(s, achievement));
        RewardGrants.GrantOutcome paid = ClaimAttempt.paidOf(claimed);
        if (paid == null) {
            // Rewards that will not fit say make room; anything else says there is nothing to collect.
            ctx.toast(ClaimToasts.refusedToast(claimed));
            return false;
        }
        // The rows are what the claim just handed over; a rolled table lists what it rolled.
        ctx.toast(rewardToast(ctx.text("book.achievements.claim_success"), paid.receipt()));
        return true;
    }

    /**
     * Collect the points milestone at {@code threshold}, through the claim seam (the engine's own collect unless a
     * consumer filled it). No engine moment announces a rung's collect, so the success toast carries the chest
     * every collect plays ({@link ToastSounds#COLLECT}).
     */
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
            case SUCCESS -> ctx.toast(rewardToast(ctx.text("book.achievements.claim_success"), result.rowsOr(authored))
                    .withSound(ToastSounds.COLLECT));
            case INVENTORY_FULL -> ctx.toast(ClaimToasts.noRoomToast());
            case NOT_READY -> ctx.toast(ClaimToasts.nothingToCollectToast());
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
    @Nonnull
    private ToastSpec rewardToast(@Nonnull Message headline, @Nonnull List<RewardSpec> rewards) {
        return ClaimToasts.rewardToast(headline, rewards, ctx.deps().rewardChips(),
                dropped -> ctx.text("book.more", dropped));
    }
}
