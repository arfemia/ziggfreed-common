package com.ziggfreed.common.i18n;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A minimal, mod-agnostic factory for building Hytale {@link Message} values that the
 * <b>client</b> resolves against the locale the player picked in-game - the same
 * client-side-localization model the MMO Skill Tree's own {@code i18n.Msg} pioneered,
 * lifted here (namespace-parameterized) so any consumer mod builds localized Messages
 * without re-deriving the arg-binding rules. The server never reads, caches, or persists
 * a player's locale; it emits keys + params and the client renders them.
 *
 * <p>Unlike a per-mod {@code Msg}, {@link #tr} takes the caller's OWN key prefix (e.g.
 * {@code "rpgstations."}) as an explicit argument - this class carries no fixed
 * namespace of its own, so several consumer mods can share one implementation. A
 * consumer that wants a prefix-free call site wraps this with its own tiny facade
 * (mirroring the MMO's {@code i18n.Msg}, which stays untouched and does NOT route
 * through here - no churn for an existing, working call site).
 *
 * <p><b>The arg distinction is the whole correctness story</b> (the invariant carried
 * over from the MMO): an arg that is itself localized (a name, a nested template) MUST
 * be passed as a nested {@link Message} so it renders in the <i>client's</i> locale;
 * pre-resolving it to a String would freeze it in one language. Anything that is
 * genuinely data (a formatted number, a raw asset id, a player name) is passed as a
 * String / scalar and becomes a flat param. {@link #tr}/{@link #key} bind
 * {@code {0},{1},...} placeholders to params {@code "0","1",...}: a {@link Message} arg
 * becomes a nested param, a number becomes a scalar param, anything else a raw string
 * param.
 */
public final class Msg {

    /**
     * The locale-neutral glue key ({@code "{0}{1}"}) backing {@link #cat}, shipped by this
     * jar in every locale it carries (identical value everywhere - it is punctuation-free
     * concatenation, not content).
     */
    private static final String CAT_KEY = "common.fmt.cat";

    private Msg() {
    }

    /**
     * A translation {@link Message} for {@code prefix + key} (the caller supplies its own
     * namespace prefix, e.g. {@code "rpgstations."}), with {@code {0},{1},...} bound from
     * {@code args}. A {@link Message} arg becomes a nested param (resolved client-side in
     * its own locale); a number becomes a scalar param; anything else becomes a raw string
     * param.
     */
    @Nonnull
    public static Message tr(@Nonnull String prefix, @Nonnull String key, @Nonnull Object... args) {
        return key(prefix + key, args);
    }

    /**
     * A translation {@link Message} for a fully-qualified key (no prefix applied) - used
     * for another mod's / the game's own native namespaces so a name resolves in the
     * client's locale natively.
     */
    @Nonnull
    public static Message key(@Nonnull String fullKey, @Nonnull Object... args) {
        Message m = Message.translation(fullKey);
        bindAll(m, args);
        return m;
    }

    /** Raw, untranslated text - formatted numbers, asset ids, player names. Never a localized name. */
    @Nonnull
    public static Message raw(@Nullable String text) {
        return Message.raw(text != null ? text : "");
    }

    /**
     * {@link #tr} using NAMED params ({@code {name}} tokens) instead of positional
     * {@code {0},{1},...} - for a lang value authored with named placeholders. Same
     * per-arg binding as {@link #tr}.
     */
    @Nonnull
    public static Message trNamed(@Nonnull String prefix, @Nonnull String key, @Nonnull Map<String, Object> namedArgs) {
        return keyNamed(prefix + key, namedArgs);
    }

    /** {@link #key} with NAMED params instead of positional ones, for a fully-qualified key. */
    @Nonnull
    public static Message keyNamed(@Nonnull String fullKey, @Nonnull Map<String, Object> namedArgs) {
        Message m = Message.translation(fullKey);
        for (Map.Entry<String, Object> e : namedArgs.entrySet()) {
            bind(m, e.getKey(), e.getValue());
        }
        return m;
    }

    /**
     * Concatenate parts in order (locale-neutral glue: punctuation, spacing, lists).
     *
     * <p><b>Never pass a {@code join} result as a {@link #tr} param</b> (it carries
     * neither {@code rawText} nor {@code messageId} so it renders blank when nested);
     * compose it at the outer level, or use {@link #cat} when the composite itself must
     * flow into a param position.
     */
    @Nonnull
    public static Message join(@Nonnull Message... parts) {
        return Message.join(parts);
    }

    /**
     * Concatenate parts in order, like {@link #join}, but the result renders correctly
     * BOTH placed directly in a message tree AND nested as a {@link #tr}/{@link #key}
     * param. Left-folds the parts pairwise into nested {@link #key}({@link #CAT_KEY}, a, b)
     * nodes, so every fold node carries a {@code messageId} and resolves per the engine's
     * recursive param-substitution contract (unlike a bare {@link #join} result, which
     * carries neither and renders empty as a nested param).
     *
     * <p>Zero parts return an empty raw {@link Message}; one part is returned unchanged.
     */
    @Nonnull
    public static Message cat(@Nonnull Message... parts) {
        if (parts.length == 0) {
            return Message.raw("");
        }
        Message acc = parts[0];
        for (int i = 1; i < parts.length; i++) {
            acc = key(CAT_KEY, acc, parts[i]);
        }
        return acc;
    }

    /** Bold wrapper (fluent; a call site need not import the engine {@link Message} type just for style). */
    @Nonnull
    public static Message bold(@Nonnull Message m) {
        return m.bold(true);
    }

    /** Color wrapper for a hex string (e.g. {@code "#FFAA00"}). */
    @Nonnull
    public static Message color(@Nonnull Message m, @Nonnull String hex) {
        return m.color(hex);
    }

    private static void bindAll(@Nonnull Message m, @Nonnull Object... args) {
        for (int i = 0; i < args.length; i++) {
            bind(m, Integer.toString(i), args[i]);
        }
    }

    private static void bind(@Nonnull Message m, @Nonnull String key, @Nullable Object arg) {
        switch (arg) {
            case null -> m.param(key, Message.raw(""));
            case Message msg -> m.param(key, msg);
            case Integer i -> m.param(key, (int) i);
            case Long l -> m.param(key, (long) l);
            case Double d -> m.param(key, (double) d);
            case Float f -> m.param(key, (float) f);
            case Boolean b -> m.param(key, (boolean) b);
            // A String (or any other) arg is wrapped as a NESTED raw Message via the
            // param(key, Message) overload, NOT the bare param(key, String) overload -
            // the bare String overload emits a StringParamValue the client cannot
            // deserialize when the host property is a plain-String sink; a nested raw
            // Message routes through messageParams and always resolves.
            default -> m.param(key, Message.raw(arg.toString()));
        }
    }
}
