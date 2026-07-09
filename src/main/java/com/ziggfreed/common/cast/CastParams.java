package com.ziggfreed.common.cast;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Param-coercion helpers for a {@code Map<String, Object>} cast-params bag (the JSON
 * shape a codec / config hands an {@code onHit} or effect builder). Each helper reads
 * one key and coerces it to the expected type, returning a caller-supplied fallback
 * when the key is absent or of the wrong type. Semantics are lenient by design: a
 * mistyped value never throws, it just falls back.
 *
 * <p>Stateless, pure, no engine coupling. Lifted verbatim from the private helpers of
 * a consumer's on-hit builder registry so every builder shares one coercion authority.
 */
public final class CastParams {

    private CastParams() {}

    /**
     * The value at {@code key} as a {@link Number}, or {@code fallback} boxed as a
     * {@link Double} when the key is absent or not a {@code Number}.
     */
    public static Number numberOr(@Nonnull Map<String, Object> m, @Nonnull String key, double fallback) {
        Object v = m.get(key);
        return v instanceof Number ? (Number) v : (Double) fallback;
    }

    /**
     * The value at {@code key} as a {@link String}, or {@code fallback} (which may be
     * {@code null}) when the key is absent or not a {@code String}.
     */
    @Nullable
    public static String stringOr(@Nonnull Map<String, Object> m, @Nonnull String key, @Nullable String fallback) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : fallback;
    }

    /**
     * The value at {@code key} as a {@code boolean}, or {@code fallback} when the key
     * is absent or not a {@code Boolean}.
     */
    public static boolean boolOr(@Nonnull Map<String, Object> m, @Nonnull String key, boolean fallback) {
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }
}
