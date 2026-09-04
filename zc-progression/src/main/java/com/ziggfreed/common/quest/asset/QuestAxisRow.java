package com.ziggfreed.common.quest.asset;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonPrimitive;

/**
 * One row a {@link QuestValueEnumerator} answers with: either a single value for the axis's own
 * token, or several tokens bound together.
 *
 * <pre>{@code
 * QuestAxisRow.of("copper")                                   // binds {material}
 * QuestAxisRow.builder().put("material", "copper")
 *                       .put("tier", 2)
 *                       .put("named", true).build()           // binds three tokens at once
 * }</pre>
 *
 * <p>Values keep their TYPE. A whole authored value of {@code "{tier}"} becomes the number 2, not
 * the string "2", so a token can fill a numeric field; the same token written inside a longer
 * string ({@code "quest.gather.tier{tier}"}) is spliced in as text. Binding several tokens in one
 * row is what keeps a generated set honest: a row is one real thing, so a combination that should
 * never exist cannot be produced by accident.
 */
public final class QuestAxisRow {

    @Nullable private final JsonPrimitive value;
    private final Map<String, JsonPrimitive> bindings;

    private QuestAxisRow(@Nullable JsonPrimitive value, @Nonnull Map<String, JsonPrimitive> bindings) {
        this.value = value;
        this.bindings = Map.copyOf(bindings);
    }

    /** A row that binds the axis's own token to one string. */
    @Nonnull
    public static QuestAxisRow of(@Nonnull String value) {
        return new QuestAxisRow(new JsonPrimitive(value), Map.of());
    }

    /** A row that binds several named tokens, all as strings. */
    @Nonnull
    public static QuestAxisRow of(@Nonnull Map<String, String> bindings) {
        Builder builder = builder();
        bindings.forEach(builder::put);
        return builder.build();
    }

    /** True when the row carries no binding at all, so it produces nothing. */
    public boolean isEmpty() {
        return value == null && bindings.isEmpty();
    }

    /**
     * The tokens this row binds, given the axis it belongs to. A single-value row binds
     * {@code axisToken}; named bindings are added on top and win a collision, so a row can override
     * the axis's own token deliberately.
     */
    @Nonnull
    Map<String, JsonPrimitive> bind(@Nullable String axisToken) {
        Map<String, JsonPrimitive> out = new LinkedHashMap<>();
        if (value != null && axisToken != null && !axisToken.isBlank()) {
            out.put(axisToken.trim(), value);
        }
        out.putAll(bindings);
        return out;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Assembles a multi-token row, one typed binding at a time. */
    public static final class Builder {

        private final Map<String, JsonPrimitive> bindings = new LinkedHashMap<>();
        @Nullable private JsonPrimitive value;

        private Builder() {
        }

        /**
         * The value the axis's OWN token binds to, beside the named bindings: a row that is one
         * thing (an encounter id) carrying a few facts about it (its name key) under names of
         * their own.
         */
        @Nonnull
        public Builder value(@Nonnull String value) {
            this.value = new JsonPrimitive(value);
            return this;
        }

        @Nonnull
        public Builder put(@Nonnull String token, @Nonnull String value) {
            bindings.put(token.trim(), new JsonPrimitive(value));
            return this;
        }

        @Nonnull
        public Builder put(@Nonnull String token, long value) {
            bindings.put(token.trim(), new JsonPrimitive(value));
            return this;
        }

        @Nonnull
        public Builder put(@Nonnull String token, double value) {
            bindings.put(token.trim(), new JsonPrimitive(value));
            return this;
        }

        @Nonnull
        public Builder put(@Nonnull String token, boolean value) {
            bindings.put(token.trim(), new JsonPrimitive(value));
            return this;
        }

        @Nonnull
        public QuestAxisRow build() {
            return new QuestAxisRow(value, bindings);
        }
    }
}
