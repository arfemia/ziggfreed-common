package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.event.BoardEvents;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.commerce.page.CommerceRefusals;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * The boards a character HOSTS: every board whose {@code Hosts} names one of the ids the character answers to.
 * A character answers to those boards too ({@link #answerSet}), so whatever a player took at a board, or at the
 * character on the board's behalf, lists, hands in and collects at the character with no change to where it was
 * taken; and the character's quest page lists what each of those boards has posted
 * ({@link CharacterQuestListing#postingOf}).
 *
 * <p>The rules that decide are pure and asserted ({@link #answerSet(Set, Map)}, {@link #firstPoster}); the live
 * reads around them are guarded, so a board catalogue that is not up yet costs the boards, never the page.
 */
public final class HostedBoards {

    /** This library's lang namespace, and the commerce file's domain its refusal lines live under. */
    private static final String LIBRARY_PREFIX = "ziggfreedcommon.";
    private static final String COMMERCE_DOMAIN = "commerce.";

    private HostedBoards() {
    }

    // ==================== the answer set ====================

    /**
     * Every id a character answering to {@code own} answers to: its own ids, then each board whose {@code Hosts}
     * names any of them, read live off the loaded boards. A board switched off or out of season still answers, so
     * what a player carries from it still lists at its host; only its postings wait for it.
     */
    @Nonnull
    public static Set<String> answerSet(@Nonnull Set<String> own) {
        if (own.isEmpty()) {
            return own;
        }
        Map<String, List<String>> hostsByBoard = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, BoardAsset> entry : BoardConfig.getInstance().all().entrySet()) {
                BoardAsset board = entry.getValue();
                if (board != null && board.getId() != null) {
                    hostsByBoard.put(board.getId(), board.hostIds());
                }
            }
        } catch (Throwable notLoadedYet) {
            return own;
        }
        return answerSet(own, hostsByBoard);
    }

    /**
     * The pure rule behind {@link #answerSet(Set)}: {@code own}, then each board id of {@code hostsByBoard} whose
     * hosts name any id in {@code own}, matched however either was capitalized, in the map's order.
     */
    @Nonnull
    static Set<String> answerSet(@Nonnull Set<String> own, @Nonnull Map<String, List<String>> hostsByBoard) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : own) {
            if (id != null) {
                ids.add(id);
            }
        }
        Set<String> out = new LinkedHashSet<>(ids);
        for (Map.Entry<String, List<String>> board : hostsByBoard.entrySet()) {
            if (board.getKey() != null && namesAny(board.getValue(), ids)) {
                out.add(board.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static boolean namesAny(@Nullable List<String> hosts, @Nonnull Set<String> ids) {
        if (hosts == null) {
            return false;
        }
        for (String host : hosts) {
            for (String id : ids) {
                if (host != null && id != null && host.trim().equalsIgnoreCase(id.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== the boards among an answer set ====================

    /**
     * The boards among {@code ids} that are open where {@code viewer} stands ({@link BoardAsset#isAvailableIn}; with
     * no viewer, open on this server, {@link BoardAsset#isAvailable}), in the order boards list: by {@code Order},
     * then by id. Empty when none of the ids is a board.
     */
    @Nonnull
    public static List<BoardAssetSpec> openAmong(@Nonnull Collection<String> ids, @Nullable LoadedWorld viewer) {
        List<BoardAssetSpec> out = new ArrayList<>();
        try {
            Set<String> seen = new LinkedHashSet<>();
            for (String id : ids) {
                if (id == null || id.isBlank() || !seen.add(id.trim().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                BoardAssetSpec board = CommerceCatalogs.boards().board(id.trim());
                if (board != null && (viewer == null ? board.asset().isAvailable()
                        : board.asset().isAvailableIn(viewer))) {
                    out.add(board);
                }
            }
        } catch (Throwable notLoadedYet) {
            return List.of();
        }
        out.sort(Comparator.comparingInt((BoardAssetSpec board) -> board.asset().order())
                .thenComparing(BoardAssetSpec::boardId));
        return out;
    }

    /**
     * Each contract once, under the first board in {@code boards}' order that posts it: a contract two of a
     * character's boards post shows once, under the board that lists first. Keyed by the contract id lower-cased,
     * in the order the contracts first appear.
     */
    @Nonnull
    static <B> Map<String, B> firstPoster(@Nonnull List<B> boards, @Nonnull Function<B, List<String>> posted) {
        Map<String, B> out = new LinkedHashMap<>();
        for (B board : boards) {
            for (String contract : posted.apply(board)) {
                if (contract != null && !contract.isBlank()) {
                    out.putIfAbsent(contract.trim().toLowerCase(Locale.ROOT), board);
                }
            }
        }
        return out;
    }

    /** The wallets {@code boards} show in their headers, each once, in the boards' order. */
    @Nonnull
    public static List<String> currencies(@Nonnull List<BoardAssetSpec> boards) {
        Set<String> out = new LinkedHashSet<>();
        for (BoardAssetSpec board : boards) {
            out.addAll(board.currencies());
        }
        return new ArrayList<>(out);
    }

    // ==================== looking at a host's boards ====================

    /**
     * What looking at a board does, for every board a host page shows: a contract finished in a past period is
     * put back within reach (the lapse re-arm) and a turnover nobody has reported yet is announced. A page opening
     * on a host calls this; a reading that only lists (the mark over the host's head) never does. Each board is
     * guarded on its own.
     */
    public static void look(@Nonnull Subject subject, @Nonnull List<BoardAssetSpec> boards, long nowMs) {
        if (boards.isEmpty()) {
            return;
        }
        for (BoardAssetSpec board : boards) {
            try {
                CommerceEngines.boards().reArmLapsed(subject, board, CommerceCatalogs.boards().pool(), nowMs);
                BoardEvents.noticeRotation(board.boardId(), board.rotation().periodIndex(nowMs), nowMs);
            } catch (Throwable t) {
                SafeLog.warn("[questlist] looking at the board '" + board.boardId() + "' failed", t);
            }
        }
    }

    /**
     * A board's refusal token as the line it reads with on the board itself: commerce's own words for a board's
     * tokens ({@link CommerceRefusals}), the shared lock lines for everything else.
     */
    @Nonnull
    public static Message refusalLine(@Nullable String reason) {
        CommerceRefusals.Refusal refusal = CommerceRefusals.of(reason);
        return refusal.line() != null ? refusal.line() : boardText(refusal.key());
    }

    /** The line a contract taken off a board reads with, the board's own. */
    @Nonnull
    public static Message takenLine() {
        return boardText("board.toast.accepted");
    }

    /** A key of the commerce chrome ({@code ziggfreedcommon.commerce.lang}), the words a board reads with. */
    @Nonnull
    static Message boardText(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(LIBRARY_PREFIX, COMMERCE_DOMAIN + key, args);
    }

    /**
     * Whether {@code board} ever turns over: one with no {@code Rotation} keeps the same postings for good, so it has
     * no clock to show.
     */
    static boolean turnsOver(@Nonnull BoardAssetSpec board) {
        return board.rotation().periodLengthMs() != CommerceFold.NEVER.periodLengthMs();
    }

    // ==================== the character vocabulary ====================

    /**
     * The answer a board's {@code Hosts} is audited against: true when some placed character or identity overlay
     * answers to {@code npcId}, when a placement the mod gate kept off this server would have, or when a loaded NPC
     * role carries that name (a character with no overlay IS its role). False only for an id nothing can stand up.
     */
    @Nonnull
    public static Predicate<String> knownNpcIds() {
        return npcId -> {
            if (npcId == null || npcId.isBlank()) {
                return true;
            }
            String wanted = npcId.trim();
            for (String declared : NpcIdentities.allDeclaredNpcIds()) {
                if (declared.equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
            if (NpcIdentities.modGateRefusedNpcId(wanted)) {
                return true;
            }
            NPCPlugin npc = NPCPlugin.get();
            return npc == null || npc.hasRoleName(wanted);
        };
    }
}
