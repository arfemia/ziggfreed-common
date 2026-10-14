package com.ziggfreed.common.objectives.book;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.BountyAssetRef;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.page.CommerceLabels;
import com.ziggfreed.common.commerce.page.CommercePages;
import com.ziggfreed.common.commerce.page.CommerceText;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.ui.kit.ZigTokens;

/**
 * The library's own reading of a board's contracts ({@link ObjectiveBookDeps#BOARD_CONTRACTS}), the default every
 * quest surface reads, so a server running nothing but this library shows what a board says about its work: a
 * contract is board-managed, its pills are its board's word for its work (the board's {@code Text.PillKey}, else
 * the library's word for a contract) and its grade in the band's own colour (the board's {@code Grades} entry,
 * clamped to read on a row; a band with none reads on the plain pill), and on a contract the player carries it says
 * where the contract is handed in ({@code QuestReader.hint} asks it of a carried contract only).
 *
 * <p>The board a contract reads by is the first board it names that this server declares, as every surface naming
 * exactly one board means it. Every read is guarded: a catalogue not up yet reads as no contract at all.
 */
final class BoardContracts implements ObjectiveBookDeps.BoardManagedQuests {

    static final BoardContracts INSTANCE = new BoardContracts();

    /** This library's lang namespace. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** A pill with no fill of its own: the kit paints it as the plain pill. */
    private static final String NO_FILL = "";

    private BoardContracts() {
    }

    @Override
    public boolean managed(@Nonnull Quest quest) {
        return contract(quest.id()) != null;
    }

    @Override
    @Nonnull
    public List<ObjectiveBookDeps.Pill> pills(@Nonnull Quest quest) {
        BountyAssetRef contract = contract(quest.id());
        if (contract == null) {
            return List.of();
        }
        BoardAssetSpec board = boardOf(contract);
        List<ObjectiveBookDeps.Pill> pills = new ArrayList<>(2);
        pills.add(new ObjectiveBookDeps.Pill(pillWord(board), NO_FILL, NO_FILL));
        String band = CommerceText.normalize(board != null ? contract.difficultyOn(board.boardId())
                : firstBand(contract));
        if (!band.isEmpty()) {
            Message label = CommerceLabels.gradeOn(board == null ? null : board.boardId(), band,
                    CommercePages.resolvedDeps().titleArgs());
            String fill = CommerceLabels.gradeInk(board, band, NO_FILL);
            pills.add(new ObjectiveBookDeps.Pill(label, fill, fill.isEmpty() ? NO_FILL : ZigTokens.inkOn(fill)));
        }
        return pills;
    }

    @Override
    @Nullable
    public Message acceptHint(@Nonnull Quest quest) {
        return contract(quest.id()) == null ? null : Msg.tr(PREFIX, "journal.hint.board_contract");
    }

    // ==================== reading a contract ====================

    /** The contract posted under {@code questId}, or null when no board posts one (or nothing is loaded yet). */
    @Nullable
    private static BountyAssetRef contract(@Nullable String questId) {
        if (questId == null || questId.isBlank()) {
            return null;
        }
        try {
            return CommerceCatalogs.boards().bounty(questId);
        } catch (Throwable notLoadedYet) {
            return null;
        }
    }

    /** The first board {@code contract} names that this server declares, or null when it declares none of them. */
    @Nullable
    private static BoardAssetSpec boardOf(@Nonnull BountyAssetRef contract) {
        try {
            for (BountyAsset.BoardMembership membership : contract.asset().boardMemberships()) {
                String boardId = membership.getBoard();
                BoardAssetSpec board = boardId == null || boardId.isBlank() ? null
                        : CommerceCatalogs.boards().board(boardId);
                if (board != null) {
                    return board;
                }
            }
        } catch (Throwable notLoadedYet) {
            return null;
        }
        return null;
    }

    /** The band {@code contract} carries on the first board it names, or null. */
    @Nullable
    private static String firstBand(@Nonnull BountyAssetRef contract) {
        for (BountyAsset.BoardMembership membership : contract.asset().boardMemberships()) {
            if (membership.getBoard() != null && !membership.getBoard().isBlank()) {
                return membership.getDifficulty();
            }
        }
        return null;
    }

    /** The board's own word for its work ({@code Text.PillKey}), else the library's word for a contract. */
    @Nonnull
    private static Message pillWord(@Nullable BoardAssetSpec board) {
        String key = board == null ? null : board.asset().pillKey();
        return key != null ? ContentKeys.tr(key) : Msg.tr(PREFIX, "commerce.board.pill");
    }
}
