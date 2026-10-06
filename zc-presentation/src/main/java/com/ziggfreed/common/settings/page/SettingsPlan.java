package com.ziggfreed.common.settings.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * What a build of the Settings tab draws and what a click reaches, worked out with no builder in hand so
 * a test reads it: a section draws when any of its rows shows, a rule that throws hides its own row (one
 * line per row), and a click token resolves only to a drawn row of the kind it claims that still shows.
 */
final class SettingsPlan {

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SettingsPlan() {
    }

    /** Does {@code row} show for {@code viewer}? A rule that throws hides it. */
    static boolean visible(@Nonnull SettingsRow row, @Nonnull SettingsViewer viewer) {
        try {
            return row.visible().test(viewer);
        } catch (Throwable t) {
            if (WARNED.add(row.id())) {
                SafeLog.warn("[settings] the '" + row.id() + "' row's rule failed, so it is hidden: " + t.getMessage());
            }
            return false;
        }
    }

    /** The sections a build draws: those with a row showing, in order. */
    @Nonnull
    static List<SettingsSection> drawable(@Nonnull List<SettingsSection> sections, @Nonnull SettingsViewer viewer) {
        List<SettingsSection> out = new ArrayList<>();
        for (SettingsSection section : sections) {
            for (SettingsRow row : section.rows()) {
                if (visible(row, viewer)) {
                    out.add(section);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The drawn row a click names, or null when the token is no number, out of range, of another kind, or a
     * row that no longer shows (a parent switched off since the build). A null is the page's to answer.
     */
    @Nullable
    static SettingsRow clicked(@Nonnull List<SettingsRow> drawn, @Nullable String token,
            @Nonnull SettingsRow.Kind kind, @Nonnull SettingsViewer viewer) {
        if (token == null) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 0 || index >= drawn.size()) {
            return null;
        }
        SettingsRow row = drawn.get(index);
        return row.kind() == kind && visible(row, viewer) ? row : null;
    }
}
