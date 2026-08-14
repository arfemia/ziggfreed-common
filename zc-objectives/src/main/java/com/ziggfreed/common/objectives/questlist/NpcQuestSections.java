package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.QuestStatus;

/**
 * Where each quest sits on an NPC's list, and in what order the list reads. Pure: no engine, no
 * store, no page - which is what makes the ordering rules assertable, since they are the part a
 * player notices immediately and the part a refactor breaks silently.
 *
 * <p>The sections are ordered by WHAT THE PLAYER CAN DO ABOUT THEM, most actionable first: a reward
 * waiting to be taken, then a step this character is the destination for, then everything else being
 * carried, then what can be taken on, then what is visible but out of reach, then what is finished.
 * A quest waiting out a repeat cooldown reads as locked rather than vanishing, because a daily that
 * disappears between runs reads as content having been taken away.
 */
public final class NpcQuestSections {

    /** A lifecycle bucket, declared in the order it renders. */
    public enum Section {

        /** Finished; the reward is waiting to be collected. */
        READY,

        /** Being carried, and this character is where the outstanding step resolves. */
        TURN_IN,

        /** Being carried, with its next step somewhere else. */
        ACTIVE,

        /** Not started, and the player may take it right now. */
        AVAILABLE,

        /**
         * Finished, but its rewards belong somewhere else. A quest may name where it is collected,
         * and this character is not it - so the row says where the player stands rather than offering
         * a button that would refuse.
         */
        PARKED,

        /** Visible but out of reach: a gate refuses it, or a repeat is still on the clock. */
        LOCKED,

        /** Finished and collected. */
        DONE
    }

    /** One line of the list, as far as the ordering is concerned. */
    public record Entry(@Nonnull String questId, @Nonnull Section section, boolean highlighted,
                        int order) {

        /** An entry with no consumer-side ordering opinion. */
        @Nonnull
        public static Entry of(@Nonnull String questId, @Nonnull Section section, boolean highlighted) {
            return new Entry(questId, section, highlighted, 0);
        }
    }

    private NpcQuestSections() {
    }

    /**
     * Which bucket a quest belongs in.
     *
     * <p>The two place-aware answers are asked of the ENGINE by the caller and passed in as plain
     * booleans, so this stays a decision table with nothing to look up: which character the player is
     * standing at is the page's business, and what that means for the list is this one's.
     *
     * @param status         what the quest EFFECTIVELY is for this player (a repeatable off cooldown
     *                       reads as not started, which is what makes a daily re-offerable here)
     * @param acceptable     whether the accept gate passes right now; only consulted for an unstarted
     *                       quest, since a carried one cannot be accepted again either way
     * @param readyHere      whether this character is where the outstanding step resolves
     * @param collectHere    whether a FINISHED quest may be collected at this character; only
     *                       consulted for one waiting to be collected. A quest that names no
     *                       collection site is collectable everywhere, so this is true for the great
     *                       majority of content and false only where content said otherwise
     */
    @Nonnull
    public static Section classify(@Nonnull QuestStatus status, boolean acceptable, boolean readyHere,
            boolean collectHere) {
        return switch (status) {
            case COMPLETED_UNCLAIMED -> collectHere ? Section.READY : Section.PARKED;
            case ACTIVE -> readyHere ? Section.TURN_IN : Section.ACTIVE;
            case NOT_STARTED -> acceptable ? Section.AVAILABLE : Section.LOCKED;
            case ON_COOLDOWN -> Section.LOCKED;
            case COMPLETED -> Section.DONE;
        };
    }

    /**
     * The list in the order it renders: the HIGHLIGHTED quest first whatever its section, then by
     * section, then by the consumer's own order, then by id so a restart cannot reshuffle it.
     *
     * <p>The highlight pin is what makes a routed hand-in land on the quest it was routed for. There
     * is no scroll-to on a page, so pinning the row to the top is the whole of "take me to it": a
     * highlighted quest is always the first row and always the one the detail panel opens on.
     */
    @Nonnull
    public static List<Entry> sort(@Nonnull List<Entry> entries) {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing((Entry e) -> e.highlighted() ? 0 : 1)
                .thenComparingInt(e -> e.section().ordinal())
                .thenComparingInt(Entry::order)
                .thenComparing(Entry::questId));
        return ordered;
    }

    /** Just the ids of {@link #sort}, which is what the page walks. */
    @Nonnull
    public static List<String> sortedIds(@Nonnull List<Entry> entries) {
        List<String> ids = new ArrayList<>();
        for (Entry entry : sort(entries)) {
            ids.add(entry.questId());
        }
        return ids;
    }

    /**
     * Which quest the detail panel opens on: the one the page was ROUTED to when it is on this list,
     * else whatever was already selected while it is still on this list, else the first row, else
     * nothing at all.
     *
     * <p>The order matters in both directions. A routed highlight must beat a stale selection, or a
     * hand-in routed from a conversation opens on whatever the player last clicked; and a surviving
     * selection must beat the first row, or every refresh after an accept would jump the player back
     * to the top of the list.
     */
    @Nullable
    public static String select(@Nonnull List<String> orderedIds, @Nullable String highlightQuestId,
            @Nullable String currentSelection) {
        if (highlightQuestId != null && orderedIds.contains(highlightQuestId)) {
            return highlightQuestId;
        }
        if (currentSelection != null && orderedIds.contains(currentSelection)) {
            return currentSelection;
        }
        return orderedIds.isEmpty() ? null : orderedIds.get(0);
    }
}
