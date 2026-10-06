package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

/**
 * What a build draws and what a click reaches: a section with no row showing draws nothing, a rule that
 * throws hides only its own row, and a click resolves only to a row of the kind it claims that still
 * shows, so a stale, malformed or out-of-range token is the page's to answer.
 */
class SettingsPlanTest {

    private static final SettingsRow.Toggle ON = new SettingsRow.Toggle() {
        @Override
        public boolean on(@Nonnull SettingsViewer viewer) {
            return true;
        }

        @Override
        public boolean set(@Nonnull SettingsViewer viewer, boolean on) {
            return true;
        }
    };

    @Nonnull
    private static SettingsRow heading(@Nonnull String id, @Nonnull Predicate<SettingsViewer> visible) {
        return SettingsRow.heading(id, Message.raw(id), visible);
    }

    @Nonnull
    private static SettingsRow toggle(@Nonnull String id, boolean visible) {
        return SettingsRow.toggle(id, Message.raw(id), null, viewer -> visible, ON);
    }

    @Test
    void aSectionWithNothingShowingDrawsNothing() {
        SettingsSection empty = new SettingsSection("empty", Message.raw("e"), List.of(heading("a", v -> false)));
        SettingsSection full = new SettingsSection("full", Message.raw("f"), List.of(heading("b", v -> true)));

        assertEquals(List.of(full), SettingsPlan.drawable(List.of(empty, full), SettingsViewer.NOBODY));
    }

    @Test
    void aRuleThatThrowsHidesOnlyItsOwnRow() {
        SettingsRow broken = heading("broken", v -> {
            throw new IllegalStateException("boom");
        });
        SettingsSection section = new SettingsSection("s", Message.raw("s"), List.of(broken, heading("ok", v -> true)));

        assertFalse(SettingsPlan.visible(broken, SettingsViewer.NOBODY));
        assertEquals(List.of(section), SettingsPlan.drawable(List.of(section), SettingsViewer.NOBODY));
    }

    @Test
    void aClickIsResolvedOnlyToAShownRowOfItsKind() {
        List<SettingsRow> drawn = List.of(heading("h", v -> true), toggle("shown", true), toggle("hidden", false));

        assertSame(drawn.get(1), SettingsPlan.clicked(drawn, "1", SettingsRow.Kind.TOGGLE, SettingsViewer.NOBODY));
        for (String token : Arrays.asList("0", "2", "3", "-1", "x", "", " 1x", null)) {
            assertNull(SettingsPlan.clicked(drawn, token, SettingsRow.Kind.TOGGLE, SettingsViewer.NOBODY),
                    "token '" + token + "' reaches no switch that shows");
        }
        assertNull(SettingsPlan.clicked(drawn, "1", SettingsRow.Kind.CHOICE, SettingsViewer.NOBODY),
                "a click claiming another kind reaches nothing");
    }
}
