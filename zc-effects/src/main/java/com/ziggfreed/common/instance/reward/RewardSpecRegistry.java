package com.ziggfreed.common.instance.reward;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A process-wide registry that lets a CONSUMER contribute its own reward-spec KIND TOKEN to the
 * compact loot/reward grammar without common learning the consumer's domain. Common ships the two
 * built-in tokens ({@code item}, {@code currency}) hard-coded in the parsers; a consumer registers an
 * extra token (e.g. {@code xp}) that maps to an existing {@link InstanceReward.Kind} plus a pure id
 * rewrite and an optional display icon. The registry knows NOTHING about what the token means - it is a
 * token -&gt; (kind, id-transform, icon) table, so a domain concept (XP, mana, ...) never enters common.
 *
 * <p>A registered token parses positionally exactly like the built-ins:
 * {@code <token> <arg> <qty> [displayKey]} (and, in a pool, behind the leading {@code w}/{@code s}/gate
 * flags). The {@code arg} is fed through {@link Binding#idTransform} to produce the reward id (for a
 * {@link InstanceReward.Kind#COMMAND} token, a console-command template that may contain a
 * {@code {player}} / {@code {amount}} placeholder - the granter substitutes {@code {amount}} from the
 * rolled quantity, the consumer's sink substitutes {@code {player}}) and through
 * {@link Binding#iconResolver} to produce the results-chip icon item id.
 *
 * <p>Register at consumer plugin {@code setup()}; framework asset JSON is decoded on the later
 * {@code LoadedAssetsEvent}, so a token is always registered before any spec that uses it parses. An
 * unregistered token parses to {@code null} (the entry is skipped), so a spec authored for an absent
 * consumer simply drops - the "no phantom reward when the granting mod is absent" property. Registration
 * is last-wins and thread-safe.
 */
public final class RewardSpecRegistry {

    /** One token binding: the target reward kind + a pure id rewrite + an optional icon resolver. */
    public record Binding(@Nonnull InstanceReward.Kind kind, @Nonnull UnaryOperator<String> idTransform,
                          @Nonnull UnaryOperator<String> iconResolver) {
    }

    private static final Map<String, Binding> BINDINGS = new ConcurrentHashMap<>();

    private RewardSpecRegistry() {
    }

    /**
     * Register (or replace) a custom kind token, matched case-insensitively.
     *
     * @param token        the spec kind token (e.g. {@code "xp"}); must not be {@code item}/{@code currency}
     * @param kind         the underlying {@link InstanceReward.Kind} the token grants as
     * @param idTransform  arg -&gt; reward id (a command template for a {@code COMMAND} kind)
     * @param iconResolver arg -&gt; chip icon item id, or {@code null} for an icon-less chip
     */
    public static void register(@Nonnull String token, @Nonnull InstanceReward.Kind kind,
                                @Nonnull UnaryOperator<String> idTransform,
                                @Nonnull UnaryOperator<String> iconResolver) {
        BINDINGS.put(token.toLowerCase(Locale.ROOT), new Binding(kind, idTransform, iconResolver));
    }

    /** The binding for a token (case-insensitive), or {@code null} when unregistered. */
    @Nullable
    public static Binding lookup(@Nonnull String token) {
        return BINDINGS.get(token.toLowerCase(Locale.ROOT));
    }

    /** Test/reset hook: drop all registrations. */
    public static void clear() {
        BINDINGS.clear();
    }
}
