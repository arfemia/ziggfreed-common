package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.subject.Subject;

/**
 * The handler behind a {@link RewardKindAsset}: resolve the authored command template against the
 * reward's parameters and the receiving player, then run it as the server console.
 *
 * <p>It is the whole of what an authored kind can do, and that is deliberate. A command line is the
 * one capability every mod on every server already exposes, so a kind written as content reaches
 * anything a server owner could type - without this library learning a single thing about what the
 * command means.
 *
 * <h2>A parameter that was promised and not given refuses the payout</h2>
 *
 * <p>A {@code Required} parameter that the reward does not name (and that has no {@code Default})
 * makes {@link #grant} THROW, naming the parameter and the kind. Substituting an empty string instead
 * would run a command missing an argument, and what a half-written command does is between the
 * command and the server - it may do nothing, or it may do something nobody authored. Neither is
 * something to discover from a player report, so the refusal is loud and the reward is reported.
 *
 * <p>{@link #retryCommand} answers null in that case too: a reward that cannot say what it pays out
 * is not replayable either, so it is reported lost rather than parked in a queue that would never
 * deliver it.
 *
 * <h2>What a retry means here</h2>
 *
 * <p>Otherwise the retry IS the resolved line, because that line is exactly what the live payout
 * would have run. Nothing here can know whether running a given command twice pays twice, so an
 * owner writing a kind for something non-repeatable should say so in the file's {@code $Comment} -
 * the retry queue only ever replays what it was handed.
 *
 * <p>Immutable and cheap; the fold builds one per authored kind and registers it. The
 * {@link CommandRunner.Dispatcher} is a constructor argument so the whole class is unit-testable
 * with no live server.
 */
public final class CommandRewardKind implements RewardHandler {

    @Nonnull
    private final RewardKindAsset kind;

    @Nonnull
    private final CommandRunner.Dispatcher dispatcher;

    /** A handler running its command as the server console. */
    public CommandRewardKind(@Nonnull RewardKindAsset kind) {
        this(kind, CommandRunner.CONSOLE);
    }

    /** A handler running its command through {@code dispatcher} (a test, or a consumer's own policy). */
    public CommandRewardKind(@Nonnull RewardKindAsset kind, @Nonnull CommandRunner.Dispatcher dispatcher) {
        this.kind = kind;
        this.dispatcher = dispatcher;
    }

    /** The authored kind this handler pays out. */
    @Nonnull
    public RewardKindAsset kind() {
        return kind;
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        Map<String, String> placeholders = payablePlaceholders(spec, subject);
        List<String> failures = new ArrayList<>();
        boolean ran = CommandRunner.runWith(dispatcher, kind.getCommand(), placeholders, failures::add);
        if (!ran) {
            throw new IllegalStateException("reward kind '" + kind.authoredId() + "' could not run '"
                    + kind.getCommand() + "'" + (failures.isEmpty() ? "" : ": " + failures.get(0)));
        }
    }

    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        try {
            return resolve(spec, subject);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The command line this reward would run, fully substituted and with the {@code give} quantity
     * form corrected, or a THROW naming what the reward failed to say.
     *
     * <p>Public because a preview, an admin listing and the retry all want the same answer as the
     * grant, and re-deriving it anywhere else is how the two spellings start to disagree.
     */
    @Nonnull
    public String resolve(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        Map<String, String> placeholders = payablePlaceholders(spec, subject);
        List<String> resolved = CommandRunner.resolveAll(List.of(kind.getCommand()), placeholders);
        if (resolved.isEmpty()) {
            throw new IllegalStateException(
                    "reward kind '" + kind.authoredId() + "' resolved to an empty command line");
        }
        return resolved.get(0);
    }

    /**
     * The substitution map for a reward this kind CAN pay, or a THROW naming what stopped it. One
     * gate in front of both the live grant and the retry, so the two can never disagree about
     * whether a reward was payable or about what line it would have run.
     */
    @Nonnull
    private Map<String, String> payablePlaceholders(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        if (kind.isBlank()) {
            throw new IllegalStateException(
                    "reward kind '" + kind.authoredId() + "' names no command, so it pays out nothing");
        }
        List<String> missing = missingRequired(kind, spec);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("reward kind '" + kind.authoredId() + "' needs "
                    + String.join(", ", missing) + " and this reward did not name "
                    + (missing.size() == 1 ? "it" : "them"));
        }
        return placeholders(kind, spec, subject);
    }

    // ==================== the substitution vocabulary ====================

    /**
     * What each placeholder in the template stands for: the two reserved ones, plus every DECLARED
     * parameter resolved to the reward's own value, else its default, else empty.
     *
     * <p>Only declared parameters are offered. A reward carrying a parameter the kind never declared
     * substitutes nothing, so a schema stays the one authority on what a kind reads and a stray
     * parameter shows up as a validator finding instead of quietly reaching a command line.
     */
    @Nonnull
    public static Map<String, String> placeholders(@Nonnull RewardKindAsset kind,
            @Nonnull RewardSpec spec, @Nonnull Subject subject) {
        Map<String, String> out = reserved(subject);
        for (String name : kind.paramsOrEmpty().keySet()) {
            if (name != null && !name.isBlank()) {
                out.put(name, kind.effectiveParam(spec, name));
            }
        }
        return out;
    }

    /**
     * The same vocabulary for a kind with NO declared schema: the reserved placeholders plus every
     * parameter the reward itself carries.
     *
     * <p>For the framework's own {@code Command} kind, where the template is written on the reward
     * rather than on a kind file, so there is no schema to declare anything and the reward's own
     * parameters are the whole of what it can mean. Shared with the declared form above so a
     * template reads the same either way; only where the parameter list comes from differs.
     */
    @Nonnull
    public static Map<String, String> placeholders(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        Map<String, String> out = reserved(subject);
        spec.params().forEach((name, value) -> {
            if (name != null && !name.isBlank()) {
                out.put(name, value == null ? "" : value);
            }
        });
        return out;
    }

    /** The placeholders every command template can use, whoever wrote it. */
    @Nonnull
    private static Map<String, String> reserved(@Nonnull Subject subject) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(RewardKindAsset.PLACEHOLDER_PLAYER, subject.name());
        out.put(RewardKindAsset.PLACEHOLDER_UUID, subject.id().toString());
        return out;
    }

    /**
     * Every {@code Required} parameter this reward does not answer for, in declaration order. Empty
     * when the reward is payable. A parameter with a {@code Default} is never missing - the default
     * IS the answer.
     */
    @Nonnull
    public static List<String> missingRequired(@Nonnull RewardKindAsset kind, @Nonnull RewardSpec spec) {
        List<String> missing = new ArrayList<>();
        kind.paramsOrEmpty().forEach((name, declaration) -> {
            if (name == null || name.isBlank() || declaration == null) {
                return;
            }
            if (declaration.isRequired() && !declaration.hasDefault() && spec.param(name) == null) {
                missing.add(name);
            }
        });
        return missing;
    }

    /**
     * The two RESERVED parameter names every reward entry may write whatever its kind declares:
     * {@link DeferredRewards#PARAM_NAME_KEY} and {@link DeferredRewards#PARAM_ICON}, the per-reward
     * presentation pair that BEATS the kind's own {@code Presentation} defaults. They are read by
     * the layer that draws a reward, never by a command line, so a kind has no reason to declare
     * them and an entry writing one is authoring a supported winning form, not a mistake. Held
     * lower-cased because {@link RewardSpec} lower-cases every parameter key it is given.
     */
    private static final Set<String> RESERVED_PRESENTATION_PARAMS = Set.of(
            DeferredRewards.PARAM_NAME_KEY.toLowerCase(Locale.ROOT),
            DeferredRewards.PARAM_ICON.toLowerCase(Locale.ROOT));

    /**
     * Every parameter this reward carries that the kind does not declare, in the order the reward
     * wrote them. Each one reaches no command line, which is exactly the silence a validator exists
     * to report - except the reserved presentation pair, which is read elsewhere by design (see
     * {@link #RESERVED_PRESENTATION_PARAMS}).
     */
    @Nonnull
    public static List<String> undeclaredParams(@Nonnull RewardKindAsset kind, @Nonnull RewardSpec spec) {
        List<String> unknown = new ArrayList<>();
        for (String written : spec.params().keySet()) {
            if (written == null || written.isBlank() || kind.declares(written)) {
                continue;
            }
            if (RESERVED_PRESENTATION_PARAMS.contains(written.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            unknown.add(written);
        }
        return unknown;
    }
}
