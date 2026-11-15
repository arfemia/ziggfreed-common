package com.ziggfreed.common.settings.page;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.settings.PlayerSettings;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's Combat card on the Settings tab: whether the player sees the word over their own critical hits
 * (R896; only the attacker ever sees it). The row shows only while the owner's switch over the word for everyone is
 * on, which the crit step's module owns (zc-entity's weak-spot settings, {@code CritText}), so the wiring root hands it
 * over ({@link #installOffered}); unfilled, the row shows, said once.
 */
public final class CombatSettings {

    public static final String ID = "combat";
    public static final String CRIT_WORD = "combat.critword";

    @Nullable
    private static volatile BooleanSupplier offered;

    private static final AtomicBoolean FALLBACK_SAID = new AtomicBoolean();

    private CombatSettings() {
    }

    /** Hand over whether the owner shows the critical hit word at all. Once, at setup; null removes it. */
    public static void installOffered(@Nullable BooleanSupplier rule) {
        offered = rule;
    }

    /** Whether the owner shows the word at all: the installed answer, else yes (said once). Never throws. */
    static boolean critWordOffered() {
        BooleanSupplier rule = offered;
        if (rule == null) {
            if (FALLBACK_SAID.compareAndSet(false, true)) {
                SafeLog.info("[settings] nothing says whether the critical hit word is on for this server, so its "
                        + "switch shows on the Settings tab");
            }
            return true;
        }
        try {
            return rule.getAsBoolean();
        } catch (Throwable t) {
            return true;
        }
    }

    /** The section as this server has it now. */
    @Nonnull
    public static SettingsSection section() {
        return new SettingsSection(ID, SettingsText.line("combat"), List.of(
                SettingsRow.toggle(CRIT_WORD, SettingsText.line("critword"), SettingsText.line("critword.hint"),
                        viewer -> critWordOffered(), CRIT_WORD_TOGGLE)));
    }

    /** The switch: the effective choice on, a press kept as the player's own. */
    private static final SettingsRow.Toggle CRIT_WORD_TOGGLE = new SettingsRow.Toggle() {
        @Override
        public boolean on(@Nonnull SettingsViewer viewer) {
            return PlayerSettings.critWord(viewer.playerRef());
        }

        @Override
        public boolean set(@Nonnull SettingsViewer viewer, boolean on) {
            PlayerRef playerRef = viewer.playerRef();
            if (!PlayerSettings.writable(playerRef)) {
                return false;
            }
            PlayerSettings.setCritWord(playerRef, on);
            return true;
        }
    };
}
