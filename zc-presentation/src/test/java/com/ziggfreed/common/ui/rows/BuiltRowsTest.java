package com.ziggfreed.common.ui.rows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The record a page keeps of what its last full build drew, and the two answers every
 * scroll-preserving partial update depends on: the index a row is really at, and whether that row
 * has moved out from under the group it was drawn beneath.
 */
class BuiltRowsTest {

    /** A heading holds a row index of its own, so the rows after it are not off by one. */
    @Test
    void headingsOccupyAnIndex() {
        BuiltRows rows = new BuiltRows();
        rows.addHeader();
        rows.add("Quest_A", "AVAILABLE");
        rows.addHeader();
        rows.add("Quest_B", "ACTIVE");

        assertEquals(4, rows.size());
        assertEquals(1, rows.indexOf("Quest_A"));
        assertEquals(3, rows.indexOf("Quest_B"));
    }

    /** A row the last build never drew answers -1, which every caller reads as "reopen instead". */
    @Test
    void unknownRowIsNotAddressable() {
        BuiltRows rows = new BuiltRows();
        rows.add("Quest_A", "AVAILABLE");

        assertEquals(-1, rows.indexOf("Quest_Z"));
        assertEquals(-1, rows.indexOf(null));
        assertEquals(-1, rows.indexOf("  "));
        assertNull(rows.groupOf("Quest_Z"));
    }

    /** A heading answers to no id, so an empty lookup can never address one. */
    @Test
    void headingsAnswerToNoId() {
        BuiltRows rows = new BuiltRows();
        rows.addHeader();

        assertEquals(-1, rows.indexOf(""));
    }

    /** Ids match the family's way: case and surrounding space are not part of an id. */
    @Test
    void idsMatchLoosely() {
        BuiltRows rows = new BuiltRows();
        rows.add("Quest_A", "AVAILABLE");

        assertEquals(0, rows.indexOf("quest_a"));
        assertEquals(0, rows.indexOf(" Quest_A "));
        assertFalse(rows.moved("QUEST_A", "available"));
    }

    /** The whole point: the group it was BUILT under against the group it belongs to now. */
    @Test
    void movedReportsAChangedGroup() {
        BuiltRows rows = new BuiltRows();
        rows.add("Quest_A", "AVAILABLE");

        assertFalse(rows.moved("Quest_A", "AVAILABLE"));
        assertTrue(rows.moved("Quest_A", "ACTIVE"));
        assertEquals("AVAILABLE", rows.groupOf("Quest_A"));
    }

    /** A row that was never drawn counts as moved, so the caller rebuilds rather than guessing. */
    @Test
    void anUndrawnRowCountsAsMoved() {
        BuiltRows rows = new BuiltRows();
        rows.add("Quest_A", "AVAILABLE");

        assertTrue(rows.moved("Quest_Z", "AVAILABLE"));
    }

    /** A list with no groups records its rows all the same, and none of them ever reads as moved. */
    @Test
    void aGrouplessListNeverMoves() {
        BuiltRows rows = new BuiltRows();
        rows.add("Offer_A", null);

        assertEquals(0, rows.indexOf("Offer_A"));
        assertFalse(rows.moved("Offer_A", null));
    }

    /** A fresh build starts from nothing, or the previous build's rows would answer for it. */
    @Test
    void clearForgetsTheLastBuild() {
        BuiltRows rows = new BuiltRows();
        rows.add("Quest_A", "AVAILABLE");
        rows.clear();

        assertEquals(0, rows.size());
        assertEquals(-1, rows.indexOf("Quest_A"));
        assertTrue(rows.moved("Quest_A", "AVAILABLE"));
    }
}
