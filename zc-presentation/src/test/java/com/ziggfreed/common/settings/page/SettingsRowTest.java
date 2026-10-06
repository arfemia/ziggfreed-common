package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.LocalizableString;

/**
 * A row carries exactly the parts its kind uses: a heading only its words, a toggle and a choice their
 * reading and writing, a tile its picture and what it opens; a row with no id is refused, and a section
 * keeps its own copy of the rows it was handed.
 */
class SettingsRowTest {

    private static final SettingsRow.Toggle TOGGLE = new SettingsRow.Toggle() {
        @Override
        public boolean on(SettingsViewer viewer) {
            return true;
        }

        @Override
        public boolean set(SettingsViewer viewer, boolean on) {
            return true;
        }
    };

    private static final SettingsRow.Choice CHOICE = new SettingsRow.Choice() {
        @Override
        public List<SettingsOption> options(SettingsViewer viewer) {
            return List.of(new SettingsOption("a", LocalizableString.fromString("A")));
        }

        @Override
        public String value(SettingsViewer viewer) {
            return "a";
        }

        @Override
        public boolean set(SettingsViewer viewer, String value) {
            return true;
        }
    };

    private static final SettingsRow.Tile TILE = new SettingsRow.Tile() {
        @Override
        public Message line(SettingsViewer viewer) {
            return Message.raw("line");
        }

        @Override
        public boolean open(SettingsViewer viewer) {
            return false;
        }
    };

    @Test
    void eachKindCarriesItsOwnParts() {
        SettingsRow heading = SettingsRow.heading("h", Message.raw("H"), viewer -> true);
        assertEquals(SettingsRow.Kind.HEADING, heading.kind());
        assertNull(heading.toggle());
        assertNull(heading.choice());
        assertNull(heading.tile());
        assertNull(heading.hint());

        SettingsRow toggle = SettingsRow.toggle("t", Message.raw("T"), Message.raw("hint"), viewer -> true, TOGGLE);
        assertEquals(SettingsRow.Kind.TOGGLE, toggle.kind());
        assertSame(TOGGLE, toggle.toggle());
        assertNull(toggle.choice());

        SettingsRow choice = SettingsRow.choice("c", Message.raw("C"), null, viewer -> true, CHOICE);
        assertEquals(SettingsRow.Kind.CHOICE, choice.kind());
        assertSame(CHOICE, choice.choice());

        SettingsRow tile = SettingsRow.tile("x", Message.raw("X"), "Deco_Map", viewer -> false, TILE);
        assertEquals(SettingsRow.Kind.TILE, tile.kind());
        assertSame(TILE, tile.tile());
        assertEquals("Deco_Map", tile.icon());
        assertEquals("x", tile.id());
    }

    @Test
    void aRowWithNoIdOrNoPartIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SettingsRow.heading(" ", Message.raw("H"), viewer -> true));
        assertThrows(NullPointerException.class,
                () -> SettingsRow.toggle("t", Message.raw("T"), null, viewer -> true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SettingsSection("", Message.raw("S"), List.of()));
    }

    @Test
    void aSectionKeepsItsOwnCopyOfItsRows() {
        ArrayList<SettingsRow> rows = new ArrayList<>();
        rows.add(SettingsRow.heading("h", Message.raw("H"), viewer -> true));
        SettingsSection section = new SettingsSection("s", Message.raw("S"), rows);
        rows.clear();

        assertEquals(1, section.rows().size());
    }
}
