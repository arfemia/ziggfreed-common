package com.ziggfreed.common.loot.reward;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One reward, as far as the engine is concerned: a KIND and a bag of parameters. The engine hands
 * both to whatever handler is registered for that kind and looks no further.
 *
 * <p>That is the whole point. A reward's real schema belongs to whoever defines the kind - an item
 * grant, a currency payout, a console command, a title - and pinning a fixed field set here would
 * force every consumer's rewards through one mod's idea of what a reward is. Parameters are strings
 * because that is the one representation every authoring format and every handler can agree on; the
 * typed readers below do the interpreting, and a bad value falls back rather than throwing.
 *
 * <p>Immutable. Parameter keys are matched case-insensitively so an authoring layer using PascalCase
 * keys and a handler reading lower-case ones do not have to agree first.
 */
public final class RewardSpec {

    private final String kind;
    private final Map<String, String> params;

    private RewardSpec(@Nonnull String kind, @Nonnull Map<String, String> params) {
        this.kind = kind.trim();
        Map<String, String> lowered = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (key != null && value != null) {
                lowered.put(key.trim().toLowerCase(Locale.ROOT), value);
            }
        });
        this.params = Map.copyOf(lowered);
    }

    /** A reward of {@code kind} with no parameters. */
    @Nonnull
    public static RewardSpec of(@Nonnull String kind) {
        return new RewardSpec(kind, Map.of());
    }

    /** A reward of {@code kind} carrying {@code params}. */
    @Nonnull
    public static RewardSpec of(@Nonnull String kind, @Nonnull Map<String, String> params) {
        return new RewardSpec(kind, params);
    }

    /** A reward of {@code kind} with one parameter, the common shape. */
    @Nonnull
    public static RewardSpec of(@Nonnull String kind, @Nonnull String key, @Nonnull String value) {
        return new RewardSpec(kind, Map.of(key, value));
    }

    /** Which registered handler interprets this. */
    @Nonnull
    public String kind() {
        return kind;
    }

    /** Every parameter, keys lower-cased. */
    @Nonnull
    public Map<String, String> params() {
        return params;
    }

    /** A parameter's raw value, or null when unset. */
    @Nullable
    public String param(@Nonnull String key) {
        return params.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** A parameter's raw value, or {@code fallback} when unset. */
    @Nonnull
    public String paramOr(@Nonnull String key, @Nonnull String fallback) {
        String value = param(key);
        return value != null ? value : fallback;
    }

    /**
     * A parameter read as a whole number, or {@code fallback} when unset or unparseable.
     *
     * <p>A decimal that names a whole number reads as that number: authoring formats are hand-written
     * JSON, so {@code "Count": 5.0} happens, and every layer has to agree it means five. Reading it
     * strictly here while a preview elsewhere read it loosely is how a player gets shown five of
     * something and handed one.
     */
    public long longParam(@Nonnull String key, long fallback) {
        String value = param(key);
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(trimmed);
            } catch (NumberFormatException e2) {
                return fallback;
            }
        }
    }

    /** A parameter read as a decimal, or {@code fallback} when unset or unparseable. */
    public double doubleParam(@Nonnull String key, double fallback) {
        String value = param(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A parameter read as a flag ({@code "true"} case-insensitive), or {@code fallback} when unset. */
    public boolean flagParam(@Nonnull String key, boolean fallback) {
        String value = param(key);
        return value != null ? Boolean.parseBoolean(value.trim()) : fallback;
    }

    /** A copy with one parameter added or replaced. */
    @Nonnull
    public RewardSpec with(@Nonnull String key, @Nonnull String value) {
        Map<String, String> merged = new LinkedHashMap<>(params);
        merged.put(key.trim().toLowerCase(Locale.ROOT), value);
        return new RewardSpec(kind, merged);
    }

    @Override
    public String toString() {
        return "RewardSpec[" + kind + " " + params + "]";
    }
}
