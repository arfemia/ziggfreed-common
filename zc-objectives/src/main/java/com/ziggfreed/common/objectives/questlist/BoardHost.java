package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.event.BoardEvents;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.BountyAssetRef;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.page.CommerceChips;
import com.ziggfreed.common.commerce.page.CommercePages;
import com.ziggfreed.common.commerce.page.CommerceText;
import com.ziggfreed.common.commerce.page.ConfirmArm;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.LockReasons;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * What the quest page adds when its host is a BOARD ({@link ZigNpcQuestPage#atBoard}, the page
 * {@code CommercePages.openBoard} opens): the board's header, Reroll on a contract still on the board, and the
 * board's own Where. Everything else is the character's page: the board answers to its own id alone, so
 * {@link CharacterQuestListing} lists what it has posted (the shared draw with the player's rerolls) and what was taken
 * there, by the board's own rules, and the buttons are the same Accept, Hand in, Collect, Track and Abandon.
 *
 * <p>The board is read live on every build and every press, never held, so a reload, a switch thrown or a season
 * ending is what the next press meets.
 */
final class BoardHost {

    /** The verb a press of Reroll means, in the action bar's Secondary slot. */
    static final String REROLL = "reroll";

    /** A posting still on the board, what the board says about rerolling it now, and how many rerolls are left. */
    record Reroll(@Nonnull CharacterQuestListing.Posting posting, @Nonnull RerollSpec spec,
            @Nonnull BoardEngine.BoardCheck check, int left) {
    }

    /** What a press of Reroll did: the toast it reads with (none when it simply swapped) and what now sits there. */
    record Rerolled(@Nullable ToastKind kind, @Nullable Message line, @Nullable String newId) {
    }

    @Nonnull private final String boardId;

    BoardHost(@Nonnull String boardId) {
        this.boardId = boardId;
    }

    /** The id this page stands at: the board's own, as it declares it. */
    @Nonnull
    String boardId() {
        return boardId;
    }

    /** The board now, or null when nothing answers to its id (or nothing is loaded yet). */
    @Nullable
    BoardAssetSpec board() {
        try {
            return CommerceCatalogs.boards().board(boardId);
        } catch (Throwable notLoadedYet) {
            return null;
        }
    }

    // ==================== opening ====================

    /**
     * The id a board declares, however {@code boardId} was written, so what the page answers to is the id a contract
     * taken there records; the id as given when nothing answers to it.
     */
    @Nonnull
    static String declaredId(@Nonnull String boardId) {
        String id = boardId.trim();
        try {
            BoardAssetSpec board = CommerceCatalogs.boards().board(id);
            return board == null ? id : board.boardId();
        } catch (Throwable notLoadedYet) {
            return id;
        }
    }

    /**
     * The quest id a deep link's contract is published under, however it was written; the id as given when no board
     * posts one by it, and null for none.
     */
    @Nullable
    static String contractId(@Nullable String bountyId) {
        if (bountyId == null || bountyId.isBlank()) {
            return null;
        }
        String id = bountyId.trim();
        try {
            BountyAssetRef contract = CommerceCatalogs.boards().bounty(id);
            return contract == null ? id : contract.bountyId();
        } catch (Throwable notLoadedYet) {
            return id;
        }
    }

    // ==================== the header ====================

    /**
     * Whether the board is open where the player stands; the line the page shows in place of its list when it is not
     * (a board nothing answers to, or one switched off, out of season or not in this world), null when it is.
     */
    @Nullable
    static Message closedLine(@Nullable BoardAssetSpec board, @Nonnull LoadedWorld viewer) {
        if (board == null) {
            return HostedBoards.boardText("board.empty.unknown");
        }
        return board.asset().isAvailableIn(viewer) ? null : HostedBoards.boardText("board.empty.closed");
    }

    /**
     * Paint the board's header: its title as the host's name, its picture beside it, its line under it, and, while
     * it is open and turns over, the one clock line "New contracts in {0}". A board nothing answers to reads as the
     * library's word for a board.
     */
    static void paintHeader(@Nonnull UICommandBuilder cmd, @Nullable BoardAssetSpec board, boolean open, long nowMs) {
        BoardAsset asset = board == null ? null : board.asset();
        CommerceText.ArgResolver args = titleArgs();
        cmd.set(NpcQuestPagePlan.HEADER + ".TextSpans", CommerceText.title(asset == null ? null : asset.getText(),
                args, HostedBoards.boardText("board.title")));
        boolean drawn = asset != null
                && IconRenderer.applyPlainIcon(cmd, NpcQuestPagePlan.HOST_PICTURE, asset.getIcon(), null);
        cmd.set(NpcQuestPagePlan.HOST_PICTURE + ".Visible", drawn);
        optional(cmd, NpcQuestPagePlan.HOST_LINE,
                asset == null ? null : CommerceText.flavor(asset.getText(), args));
        paintClock(cmd, board, open, nowMs);
    }

    /**
     * The header's one clock line, "New contracts in {0}", while the board is open and turns over; hidden otherwise.
     * Painted with the header and again with every in-place repaint, so a page left open keeps the right time.
     */
    static void paintClock(@Nonnull UICommandBuilder cmd, @Nullable BoardAssetSpec board, boolean open, long nowMs) {
        optional(cmd, NpcQuestPagePlan.HOST_CLOCK, open && board != null && HostedBoards.turnsOver(board)
                ? HostedBoards.boardText("board.refresh_in", LockReasons.waitLine(board.rotation().millisUntilNext(nowMs)))
                : null);
    }

    // ==================== the Where gate ====================

    /**
     * What a press on this page meets when its board is not in the presser's world ({@code existsIn}): the board's
     * own words for a board that is not there, as a switched-off board refuses; null when the press may go ahead.
     */
    @Nullable
    Message pressRefusal(@Nonnull LoadedWorld viewer) {
        BoardAssetSpec board = board();
        return board != null && board.asset().existsIn(viewer) ? null
                : HostedBoards.refusalLine(BoardEngine.REASON_DISABLED);
    }

    // ==================== reroll ====================

    /**
     * Reroll's offer for {@code quest} on {@code listing}: a posting the player is not carrying, Available or Locked
     * (a contract done this rotation is no slot to change, and one taken cannot be rerolled from under the player),
     * on a board that offers rerolls, with the board's own probe ({@code canReroll}) and the rerolls left this
     * rotation (-1 when uncapped). Null when there is nothing to reroll.
     */
    @Nullable
    static Reroll offer(@Nonnull CharacterQuestListing listing, @Nonnull Quest quest, @Nonnull Subject subject) {
        CharacterQuestListing.Posting posting = listing.postingOf(quest.id());
        RerollSpec spec = posting == null ? null : posting.board().reroll();
        if (spec == null) {
            return null;
        }
        Section section = listing.sectionOf(quest);
        if (section != Section.AVAILABLE && section != Section.LOCKED) {
            return null;
        }
        BoardEngine.BoardCheck check = CommerceEngines.boards().canReroll(subject, posting.board(),
                CommerceCatalogs.boards().pool(), posting.position(), listing.nowMs());
        return new Reroll(posting, spec, check, spec.remaining(spent(subject, posting.board(), listing.nowMs())));
    }

    /** The key a paid reroll's two presses are armed under: the contract the panel shows. */
    @Nonnull
    static String armKey(@Nonnull Reroll offer) {
        return REROLL + ":" + offer.posting().contract().bountyId();
    }

    /**
     * {@code view} with Reroll in its Secondary slot (Confirm while a paid one is armed) and the reroll's terms as its
     * hint: the price and the rerolls left. A reroll the board refuses (the rotation's rerolls spent, nothing
     * different to swap in) shows a locked button and the board's reason in the hint.
     */
    @Nonnull
    static DetailView withReroll(@Nonnull DetailView view, @Nonnull Reroll offer, boolean armed) {
        boolean allowed = offer.check().ok();
        Message label = HostedBoards.boardText(allowed && armed ? "action.confirm" : "board.action.reroll");
        Message hint = allowed ? terms(offer) : HostedBoards.refusalLine(offer.check().reason());
        List<DetailAction> actions = new ArrayList<>();
        for (DetailAction action : view.actions()) {
            if (action.slot() != ActionSlot.SECONDARY) {
                actions.add(action);
            }
        }
        actions.add(new DetailAction(ActionSlot.SECONDARY, label, ActionLook.NORMAL, REROLL, null, allowed, null));
        return new DetailView(view.picture(), view.title(), view.meta(), view.subMeta(), view.badges(), view.toggle(),
                view.progress(), view.progressLabel(), view.lead(), view.blocks(), actions,
                hint != null ? hint : view.hint());
    }

    /**
     * Press Reroll: the board's probe first, so a press that could change nothing refuses before anything is
     * charged; a paid reroll takes two presses ({@code arm}), the first saying the price; then the board's own
     * reroll at the posting's position, against the clock the posting was drawn at, inside the progression call
     * scope, announced as the native rerolled event. Answers the toast it reads with and the contract that now sits
     * there.
     */
    @Nonnull
    static Rerolled reroll(@Nonnull Subject subject, @Nonnull UUID playerId, @Nonnull Reroll offer,
            @Nonnull ConfirmArm arm, long nowMs) {
        CharacterQuestListing.Posting posting = offer.posting();
        BoardAssetSpec board = posting.board();
        BoardEngine engine = CommerceEngines.boards();
        BoardEngine.BoardCheck probe = engine.canReroll(subject, board, CommerceCatalogs.boards().pool(),
                posting.position(), nowMs);
        if (!probe.ok()) {
            return new Rerolled(ToastKind.ERROR, HostedBoards.refusalLine(probe.reason()), null);
        }
        Cost price = offer.spec().cost();
        if (!price.isFree() && !arm.confirm(armKey(offer), nowMs)) {
            return new Rerolled(ToastKind.WARNING, HostedBoards.boardText("board.toast.reroll_confirm",
                    priceLine(price)), null);
        }
        // Inside the registered progression scope, as every other press on the page, so the owning mod hears a
        // reroll made here as it hears one made anywhere else.
        BoardEngine.RerollResult result = ProgressionRuntime.questScope().around(subject,
                s -> engine.reroll(s, board, CommerceCatalogs.boards().pool(), posting.position(), nowMs));
        if (result == null || !result.ok()) {
            return new Rerolled(ToastKind.ERROR, HostedBoards.refusalLine(result == null ? null : result.reason()),
                    null);
        }
        BoardEvents.fireRerolled(board.boardId(), playerId, result.position(), result.replacedId(),
                result.newId() == null ? "" : result.newId());
        return new Rerolled(null, null, result.newId());
    }

    /**
     * The reroll's terms while one is allowed: what it costs (the price's own words, as its chip reads) and how many
     * are left this rotation; a free reroll says only how many are left, and a free, uncapped one says nothing.
     */
    @Nullable
    private static Message terms(@Nonnull Reroll offer) {
        Cost price = offer.spec().cost();
        int left = offer.left();
        if (price.isFree()) {
            return left < 0 ? null : HostedBoards.boardText("board.reroll.free_left", left);
        }
        Message cost = priceLine(price);
        return left < 0 ? HostedBoards.boardText("board.reroll.costs", cost)
                : HostedBoards.boardText("board.reroll.costs_left", cost, left);
    }

    /** How many rerolls the player has spent on {@code board} this rotation; nothing when the store cannot say. */
    private static int spent(@Nonnull Subject subject, @Nonnull BoardAssetSpec board, long nowMs) {
        try {
            return CommerceStores.get().rerollsSpent(subject, board.boardId(), board.rotation().periodIndex(nowMs));
        } catch (Throwable t) {
            SafeLog.warn("[questlist] the rerolls spent on '" + board.boardId() + "' could not be read", t);
            return 0;
        }
    }

    /**
     * A price as the phrase its chip reads ({@link CommerceChips#amountAndName}), for the hint and the line asking a
     * player to confirm paying it: its first wallet, as the price leads with it.
     */
    @Nonnull
    private static Message priceLine(@Nonnull Cost price) {
        String primary = price.primaryCurrencyId();
        if (primary == null) {
            return Msg.raw("");
        }
        return CommerceChips.amountAndName(CommerceDefaults.currencyEngine(), primary, price.amountOf(primary),
                CommercePages.resolvedDeps().currencyNames());
    }

    @Nonnull
    private static CommerceText.ArgResolver titleArgs() {
        return CommercePages.resolvedDeps().titleArgs();
    }

    /** A label shown with {@code text}, or hidden when there is none; both ways, so a repaint clears the last one. */
    private static void optional(@Nonnull UICommandBuilder cmd, @Nonnull String label, @Nullable Message text) {
        if (text != null) {
            cmd.set(label + ".TextSpans", text);
        }
        cmd.set(label + ".Visible", text != null);
    }
}
