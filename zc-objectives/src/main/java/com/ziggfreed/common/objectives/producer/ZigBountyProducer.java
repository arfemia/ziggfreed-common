package com.ziggfreed.common.objectives.producer;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.board.BoardStreak;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.commerce.CommerceStore;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.commerce.fold.BountyAssetRef;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.quest.event.QuestClaimedEvent;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a collected board contract into quest and achievement progress: {@code COMPLETE_BOUNTY} once per contract
 * collected, and {@code BOUNTY_STREAK}, the player's run of days with one collected, on the first of each day. The
 * one producer of both, so a contract counts once for every reader, whichever surface collected it.
 *
 * <p>{@code COMPLETE_BOUNTY}: Target the first board the contract names, Qualifier its grade there, Amount 1, through
 * the full dispatch; each further board it names goes out {@link DispatchOptions#TARGETED_ONLY TARGETED_ONLY}, so a
 * step naming that board counts it while a step naming none counts the contract once.
 *
 * <p>{@code BOUNTY_STREAK}: value-based, Amount the run's length. The run lives in the commerce store
 * ({@link CommerceStore#boardStreak}) and moves by {@link BoardStreak#after} on the UTC day of the collect: one more
 * after yesterday, back to 1 after a gap, and nothing at all (no moment) for a second contract the same day.
 *
 * <p>An EVENT-BUS listener at {@link EventPriority#LATE} on {@link QuestClaimedEvent}, like
 * {@link ZigQuestCompletionProducer}: the claim fires after the payout settled, so the dispatch runs on the player's
 * own world thread ({@link PlayerMomentDispatch#onPlayerThread}) with nothing left in flight. A quest no board posts
 * is not a contract and fires nothing here.
 */
public final class ZigBountyProducer {

    /** A board contract collected. */
    public static final String KIND = "COMPLETE_BOUNTY";

    /** The player's run of days with a board contract collected. */
    public static final String KIND_STREAK = "BOUNTY_STREAK";

    private static final String LABEL = "bounty";

    private static final long AMOUNT = 1L;

    private ZigBountyProducer() {
    }

    /** One board a contract is posted on, with its grade there (null when it has none). */
    record Credit(@Nonnull String board, @Nullable String grade) {
    }

    /** Listen for collected quests on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(EventPriority.LATE, QuestClaimedEvent.class,
                ZigBountyProducer::onClaimed);
    }

    /** Guarded whole: a producer that throws would take the claim's other listeners down with it. */
    static void onClaimed(@Nonnull QuestClaimedEvent event) {
        try {
            BountyAssetRef contract = contractOf(event.questId());
            if (contract == null) {
                return;
            }
            List<Credit> credits = creditsOf(contract);
            PlayerMomentDispatch.onPlayerThread(LABEL, event.playerId(), KIND,
                    (store, ref) -> credit(store, ref, credits));
        } catch (Throwable t) {
            SafeLog.warn("[progression] bounty progress failed", t);
        }
    }

    /** Every dispatch one collected contract makes, on the player's world thread. */
    private static void credit(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<Credit> credits) {
        Credit primary = credits.isEmpty() ? new Credit("", null) : credits.get(0);
        ProgressDispatch.fire(store, ref, null, KIND, primary.board(), primary.grade(), AMOUNT, null);
        for (int i = 1; i < credits.size(); i++) {
            Credit also = credits.get(i);
            ProgressDispatch.fire(store, ref, KIND, also.board(), also.grade(), AMOUNT, DispatchOptions.TARGETED_ONLY);
        }
        Subject subject = Subject.of(store.getComponent(ref, Player.getComponentType()));
        if (subject == null) {
            return;
        }
        CommerceStore commerce = CommerceStores.get();
        BoardStreak before = commerce.boardStreak(subject);
        BoardStreak after = before.after(BoardStreak.epochDayOf(System.currentTimeMillis()));
        if (after == before) {
            return;
        }
        commerce.setBoardStreak(subject, after);
        ProgressDispatch.fire(store, ref, null, KIND_STREAK, "", null, after.current(), null);
    }

    /**
     * Every board {@code contract} names, in the order it names them, each once, with its grade there: the first is
     * the one a surface naming exactly one board means. Pure over the contract's own asset.
     */
    @Nonnull
    static List<Credit> creditsOf(@Nonnull BountyAssetRef contract) {
        List<Credit> credits = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (BountyAsset.BoardMembership membership : contract.asset().boardMemberships()) {
            String board = membership.getBoard();
            if (board == null || board.isBlank() || seen.contains(board)) {
                continue;
            }
            seen.add(board);
            String grade = membership.getDifficulty();
            credits.add(new Credit(board, grade == null || grade.isBlank() ? null : grade));
        }
        return credits;
    }

    /** The contract posted under {@code questId}, or null when no board posts one (or nothing is loaded yet). */
    @Nullable
    private static BountyAssetRef contractOf(@Nullable String questId) {
        if (questId == null || questId.isBlank()) {
            return null;
        }
        try {
            return CommerceCatalogs.boards().bounty(questId);
        } catch (Throwable notLoadedYet) {
            return null;
        }
    }
}
