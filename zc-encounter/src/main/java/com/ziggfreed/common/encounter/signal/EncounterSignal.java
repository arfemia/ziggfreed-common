package com.ziggfreed.common.encounter.signal;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The reserved signal grammar a native encounter script speaks to this library:
 * {@code zc:<moment>[:<detail>]}, sent through the engine's own {@code SignalWorldEvent} action.
 *
 * <ul>
 *   <li>{@code zc:engaged} - the fight is on (beside the intro release);</li>
 *   <li>{@code zc:phase:<State>} - the fight entered the named phase (beside a ChangeTargetRole or
 *       State step);</li>
 *   <li>{@code zc:wave[:<label>]} - adds were summoned (beside a TriggerSpawners);</li>
 *   <li>{@code zc:defeated} - the subject is down (beside ClearEncounterBossBar);</li>
 *   <li>{@code zc:reset} - the script re-armed for the next run.</li>
 * </ul>
 *
 * <p>Any other {@code zc:<name>} is the author's own beat and surfaces as the generic signal event
 * with {@code detail} carrying everything after the prefix. Identity is NOT in the string: which
 * encounter signalled is read off the signalling entity, so a renamed script cannot desync from its
 * beats. The moment word is matched without regard to case; the detail keeps its case, because a
 * state name is case-sensitive to the engine.
 *
 * @param moment the reserved moment, or {@link Moment#CUSTOM}
 * @param detail what followed the moment word (a state name, a wave label, a custom beat), or null
 * @param raw    the id exactly as authored
 */
public record EncounterSignal(@Nonnull Moment moment, @Nullable String detail, @Nonnull String raw) {

    /** The prefix every framework signal carries. */
    public static final String PREFIX = "zc:";

    /** The reserved moment words, plus the catch-all. */
    public enum Moment {
        ENGAGED("engaged"),
        PHASE("phase"),
        WAVE("wave"),
        DEFEATED("defeated"),
        RESET("reset"),
        CUSTOM("");

        private final String word;

        Moment(@Nonnull String word) {
            this.word = word;
        }

        /** The moment word as authored after the prefix; empty for {@link #CUSTOM}. */
        @Nonnull
        public String word() {
            return word;
        }

        @Nullable
        static Moment ofWord(@Nonnull String word) {
            for (Moment m : values()) {
                if (m != CUSTOM && m.word.equals(word)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** True when {@code signalId} is addressed to this library at all. */
    public static boolean isFrameworkSignal(@Nullable String signalId) {
        return signalId != null && signalId.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /** Parse {@code signalId}, or null when it is not a {@code zc:} signal. */
    @Nullable
    public static EncounterSignal parse(@Nullable String signalId) {
        if (!isFrameworkSignal(signalId)) {
            return null;
        }
        String rest = signalId.substring(PREFIX.length()).trim();
        int colon = rest.indexOf(':');
        String word = (colon < 0 ? rest : rest.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String detail = colon < 0 ? null : rest.substring(colon + 1).trim();
        if (detail != null && detail.isEmpty()) {
            detail = null;
        }
        Moment moment = Moment.ofWord(word);
        if (moment == null) {
            return new EncounterSignal(Moment.CUSTOM, rest.isEmpty() ? null : rest, signalId);
        }
        return new EncounterSignal(moment, detail, signalId);
    }

    /** True for a reserved moment the framework itself reacts to. */
    public boolean isReserved() {
        return moment != Moment.CUSTOM;
    }

    /** The reserved id for a phase signal into {@code stateName}. */
    @Nonnull
    public static String phaseId(@Nonnull String stateName) {
        return PREFIX + Moment.PHASE.word() + ":" + stateName;
    }
}
