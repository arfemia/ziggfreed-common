package com.ziggfreed.common.party.board;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One notice on a party board, as its poster put it up: who, at which board (the dungeon the board is kept for and the
 * place it stands, each null for none), what for (the dungeon file of a depth or a daily the poster has opened, null
 * for anything), how many more they wanted then and the party size that fills it, up to two set notes (note ids, never
 * free text), when it went up, and the poster's party (null while they are still alone). Kept in memory only.
 */
public record PartyNotice(@Nonnull UUID poster, @Nullable String dungeon, @Nullable String place, @Nullable String aim,
        int wants, int goal, @Nonnull List<String> notes, long postedAt, @Nullable String partyId) {

    /** The most notes a notice carries. */
    public static final int MAX_NOTES = 2;

    /** The most players a notice asks for. */
    public static final int MAX_WANTS = 4;

    public PartyNotice {
        Objects.requireNonNull(poster, "poster");
        dungeon = blankToNull(dungeon);
        place = blankToNull(place);
        aim = blankToNull(aim);
        notes = notes == null ? List.of() : List.copyOf(notes.size() > MAX_NOTES ? notes.subList(0, MAX_NOTES) : notes);
    }

    /** How many more it wants with a party of {@code partySize} now: none once the party reaches its goal. */
    public int remaining(int partySize) {
        return Math.max(0, goal - partySize);
    }

    /** This notice filed under {@code party} (the poster's party, once they have one). */
    @Nonnull
    public PartyNotice withParty(@Nullable String party) {
        return new PartyNotice(poster, dungeon, place, aim, wants, goal, notes, postedAt, party);
    }

    /** Whether it is up on the board kept for {@code boardDungeon} (null: a board kept for none shows every notice). */
    public boolean onBoard(@Nullable String boardDungeon) {
        return boardDungeon == null || boardDungeon.isBlank() || boardDungeon.trim().equalsIgnoreCase(dungeon);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
