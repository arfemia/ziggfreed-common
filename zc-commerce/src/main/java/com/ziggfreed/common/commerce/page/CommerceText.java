package com.ziggfreed.common.commerce.page;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.util.NumberFormatter;
import com.ziggfreed.common.util.PeriodMath;

/**
 * How authored content reads on a commerce screen, and how long is left on a clock.
 *
 * <p>Pure - strings and numbers in, {@link Message}s out - so what a shelf title will say and what a
 * countdown will read are both assertable with no server standing.
 *
 * <h2>The title-argument seam, and why it exists</h2>
 *
 * <p>A generated family writes one file per row, and the thing that varies is usually a NAME:
 * {@code "TextArgs": { "Title": ["MINING"] }} against a line reading {@code "{0} experience"}. That
 * argument is an ID, not a word - so passing it through renders {@code MINING experience} in every
 * language, which is a shipped-content bug rather than a translation gap. Nothing in this library
 * can know that {@code MINING} is a skill, so {@link ArgResolver} is where the consumer that DOES
 * know says so, once, and every generated row in every locale reads correctly from then on.
 *
 * <p>The resolver is asked about EVERY authored argument rather than only the {@code @}-prefixed
 * sentinels, because a generated id is written bare - it is the value the generator substituted, not
 * a sentinel an author typed. It answers null for anything it does not recognise, and an unanswered
 * argument is passed through exactly as authored: that is how an author finds out they wrote
 * something nothing provides, where a blank would read as a broken translation.
 */
public final class CommerceText {

    private CommerceText() {
    }

    /**
     * Where an authored text argument's VALUE comes from.
     *
     * <p>Deliberately a function rather than a registry: a consumer answers the arguments it knows
     * and returns null for the rest, so the vocabulary grows without this library enumerating it. An
     * answer that is itself localized MUST be returned as a nested {@link Message}, never as an
     * already-resolved String, or it renders in the server's language instead of the player's.
     */
    @FunctionalInterface
    public interface ArgResolver {

        /** What {@code authored} means, or null to leave it exactly as it was written. */
        @Nullable
        Object resolve(@Nonnull String authored);
    }

    /** Nobody knows what any argument means, so every one reads as the literal an author typed. */
    public static final ArgResolver RAW_ARGS = authored -> null;

    // ==================== authored text ====================

    /**
     * What this content is CALLED: its authored title key bound to its own arguments, else the plain
     * display name an author typed, else {@code fallback}.
     */
    @Nonnull
    public static Message title(@Nullable ContentTextAsset text, @Nullable ArgResolver resolver,
            @Nonnull Message fallback) {
        if (text == null) {
            return fallback;
        }
        String key = trimToNull(text.getTitleKey());
        if (key != null) {
            return Msg.key(key, args(text.titleArgs(), resolver));
        }
        String display = trimToNull(text.getDisplayName());
        return display != null ? Msg.raw(display) : fallback;
    }

    /**
     * The line UNDER the title, or null when there is none - which is the show/hide signal, since a
     * blank paragraph reads as a rendering failure rather than as content with nothing to say.
     */
    @Nullable
    public static Message flavor(@Nullable ContentTextAsset text, @Nullable ArgResolver resolver) {
        if (text == null) {
            return null;
        }
        String key = trimToNull(text.getFlavorKey());
        return key == null ? null : Msg.key(key, args(text.flavorArgs(), resolver));
    }

    /**
     * Authored arguments as a localization template binds them: each one offered to the resolver
     * first, {@code @amount} answered with a grouped number when nothing else did, and anything
     * still unanswered passed through as the literal it was written as.
     */
    @Nonnull
    public static Object[] args(@Nullable List<String> authored, @Nullable ArgResolver resolver) {
        return args(authored, resolver, 0L);
    }

    /** {@link #args(List, ArgResolver)} with the number {@code @amount} stands for. */
    @Nonnull
    public static Object[] args(@Nullable List<String> authored, @Nullable ArgResolver resolver,
            long amount) {
        if (authored == null || authored.isEmpty()) {
            return new Object[0];
        }
        Object[] out = new Object[authored.size()];
        for (int i = 0; i < authored.size(); i++) {
            String token = authored.get(i) == null ? "" : authored.get(i);
            out[i] = resolve(token, resolver, amount);
        }
        return out;
    }

    @Nonnull
    private static Object resolve(@Nonnull String token, @Nullable ArgResolver resolver, long amount) {
        if (resolver != null && !token.isEmpty()) {
            try {
                Object answer = resolver.resolve(token);
                if (answer != null) {
                    return answer;
                }
            } catch (Throwable ignored) {
                // A consumer's naming failing costs this argument's word, never the whole line.
            }
        }
        if (ContentTextAsset.ARG_AMOUNT.equals(token)) {
            return NumberFormatter.grouped(amount);
        }
        return token;
    }

    // ==================== the clock ====================

    /**
     * How long until a rotation turns over, in the coarsest two units that still say something:
     * {@code "2d 6h"}, {@code "6h 14m"}, {@code "14m 03s"}, {@code "43s"}.
     *
     * <p>Two units rather than one because a bare {@code "2d"} hides whether that is two days or
     * nearly three, and rather than four because nobody reading a shop header cares about the
     * seconds inside a week. A period already over, or one that never turns over at all, reads as
     * {@code "-"}: a countdown to nothing is worse than an honest dash.
     */
    @Nonnull
    public static String countdown(long remainingMs) {
        if (remainingMs <= 0L || remainingMs == Long.MAX_VALUE) {
            return "-";
        }
        long days = remainingMs / PeriodMath.DAY_MS;
        long hours = (remainingMs % PeriodMath.DAY_MS) / PeriodMath.HOUR_MS;
        long minutes = (remainingMs % PeriodMath.HOUR_MS) / PeriodMath.MINUTE_MS;
        long seconds = (remainingMs % PeriodMath.MINUTE_MS) / PeriodMath.SECOND_MS;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + pad(seconds) + "s";
        }
        return seconds + "s";
    }

    /** {@link #countdown(long)} as untranslated data, which is what a countdown is. */
    @Nonnull
    public static Message countdownMessage(long remainingMs) {
        return Msg.raw(countdown(remainingMs));
    }

    @Nonnull
    private static String pad(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    // ==================== small shared bits ====================

    /** The case-insensitive form ids are compared by, matching every other id in this library. */
    @Nonnull
    public static String normalize(@Nullable String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** True when both ids name the same thing, however either was written. */
    public static boolean sameId(@Nullable String a, @Nullable String b) {
        return !normalize(a).isEmpty() && normalize(a).equals(normalize(b));
    }

    @Nullable
    static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
