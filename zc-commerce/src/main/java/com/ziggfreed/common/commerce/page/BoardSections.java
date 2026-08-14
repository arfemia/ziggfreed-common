package com.ziggfreed.common.commerce.page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.QuestStatus;

/**
 * Where each contract sits on a board, and in what order the board reads. Pure: no engine, no store,
 * no page - which is what makes the ordering assertable, since it is the part a player notices
 * immediately and the part a refactor breaks silently.
 *
 * <p>The runs are ordered by WHAT THE PLAYER CAN DO ABOUT THEM, most actionable first: a reward
 * waiting to be taken, then a delivery this board is the destination for, then everything else being
 * carried, then what can be taken on, then what is visible but out of reach, then what is already
 * spent for this rotation, then what is finished.
 *
 * <p><b>A contract spent this period is DRAWN, not hidden.</b> A daily that disappears the moment it
 * is finished reads as content having been taken away; one sitting in its own run with the line
 * saying it comes back when the board turns over reads as a rotation, which is what it is.
 */
public final class BoardSections {

    /** A lifecycle run, declared in the order it renders. */
    public enum Section {

        /** Finished; the reward is waiting to be collected here. */
        READY,

        /** Being carried, and this board is where the outstanding delivery resolves. */
        TURN_IN,

        /** Being carried, with its next step out in the world. */
        ACTIVE,

        /** On the board and takeable right now. */
        AVAILABLE,

        /** On the board but out of reach: a gate refuses it. */
        LOCKED,

        /** Already completed inside this rotation; it comes back when the board turns over. */
        SPENT,

        /** Finished and collected. */
        DONE
    }

    /** One row of the board, as far as the ordering is concerned. */
    public record Entry(@Nonnull String bountyId, @Nonnull Section section, int order) {

        /** An entry with no ordering opinion of its own. */
        @Nonnull
        public static Entry of(@Nonnull String bountyId, @Nonnull Section section) {
            return new Entry(bountyId, section, 0);
        }
    }

    private BoardSections() {
    }

    /**
     * Which run a contract belongs in.
     *
     * <p>Every place-aware answer is asked of the ENGINE by the caller and passed in as a plain
     * boolean, so this stays a decision table with nothing to look up: which board the player is
     * standing at is the page's business, and what that means for the list is this one's.
     *
     * @param status        what the contract effectively is for this player
     * @param acceptable    whether the board's own accept check passes right now; only consulted for
     *                      an unstarted contract, since a carried one cannot be taken again either way
     * @param readyHere     whether this board is where the outstanding delivery resolves
     * @param collectHere   whether a FINISHED contract may be collected here; a bounty is bound to
     *                      the board it was taken at, so this is false at a different board
     * @param spentThisPeriod whether it was completed inside the CURRENT rotation period, which locks
     *                      it until the board turns over whatever its own cooldown says
     */
    @Nonnull
    public static Section classify(@Nonnull QuestStatus status, boolean acceptable, boolean readyHere,
            boolean collectHere, boolean spentThisPeriod) {
        return switch (status) {
            case COMPLETED_UNCLAIMED -> collectHere ? Section.READY : Section.ACTIVE;
            case ACTIVE -> readyHere ? Section.TURN_IN : Section.ACTIVE;
            case NOT_STARTED -> spentThisPeriod ? Section.SPENT
                    : (acceptable ? Section.AVAILABLE : Section.LOCKED);
            case ON_COOLDOWN -> Section.SPENT;
            case COMPLETED -> spentThisPeriod ? Section.SPENT : Section.DONE;
        };
    }

    /**
     * The board in the order it renders: by run, then by the content's own order, then by id so a
     * restart cannot reshuffle it.
     */
    @Nonnull
    public static List<Entry> sort(@Nonnull List<Entry> entries) {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingInt((Entry e) -> e.section().ordinal())
                .thenComparingInt(Entry::order)
                .thenComparing(Entry::bountyId));
        return ordered;
    }

    /** Just the ids of {@link #sort}, which is what the page walks. */
    @Nonnull
    public static List<String> sortedIds(@Nonnull List<Entry> entries) {
        List<String> ids = new ArrayList<>();
        for (Entry entry : sort(entries)) {
            ids.add(entry.bountyId());
        }
        return ids;
    }

    /**
     * Which contract the detail panel opens on: the one the page was opened AT when it is on the
     * board, else whatever was already selected while it is still there, else the first row.
     *
     * <p>The order matters in both directions. A deep link must beat a stale selection, or opening a
     * board from somewhere naming a contract lands on whatever the player last clicked; and a
     * surviving selection must beat the first row, or every refresh after an accept would jump them
     * back to the top.
     */
    @Nullable
    public static String select(@Nonnull List<String> orderedIds, @Nullable String openedAt,
            @Nullable String currentSelection) {
        String deepLink = firstMatch(orderedIds, openedAt);
        if (deepLink != null) {
            return deepLink;
        }
        String surviving = firstMatch(orderedIds, currentSelection);
        if (surviving != null) {
            return surviving;
        }
        return orderedIds.isEmpty() ? null : orderedIds.get(0);
    }

    @Nullable
    private static String firstMatch(@Nonnull List<String> orderedIds, @Nullable String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return null;
        }
        for (String id : orderedIds) {
            if (CommerceText.sameId(id, wanted)) {
                return id;
            }
        }
        return null;
    }
}
