package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns the authored {@link RewardKindAsset}s into registered handlers on a
 * {@link RewardKindRegistry}, so a kind written as a file pays out exactly like one written in Java.
 *
 * <h2>It runs LAST, and JSON wins</h2>
 *
 * <p>Call this AFTER every Java registration a consumer makes, once the asset stores have loaded.
 * The order is the rule, not an accident: an authored kind whose id matches a Java-registered one
 * REPLACES it. A server owner who wants {@code Item} to mean something else on their server may say
 * so, and no mod gets to overrule a file the owner put there.
 *
 * <p>Which is also why it is LOUD. Every shadow logs one warning naming the file, and the returned
 * {@link Result} carries the same list for {@link RewardKindValidator#shadowed} to report in the
 * content audit. The warning matters because the swap is not free: a command-backed kind is a command
 * line and nothing else, so the shadowed kind's engine services go with it - the ask-first inventory
 * fit check that lets a payout say "come back with room" BEFORE charging a price, and any replay
 * richer than re-running the same line. An owner who reads the warning and keeps the file has made a
 * choice; one who never saw it has a bug they cannot find.
 *
 * <p>It is exactly ONE warning, which is why the registration goes through
 * {@link RewardKindRegistry#registerQuietly}: the registry would otherwise add its own generic
 * "two owners wanted this id" line above the one that actually explains the swap, and a reader who
 * sees two lines for one event learns less than one who sees the right one.
 *
 * <h2>An empty kind is skipped, never registered</h2>
 *
 * <p>A kind asset naming no command is left out entirely rather than registered as a handler that
 * does nothing. Shadowing a working kind with a dud is strictly worse than not shadowing at all.
 * There is one legitimate command-less shape: a file whose id a JAVA-registered kind already
 * answers is DECORATION - it exists to give that kind an authored {@code Presentation} (how its
 * rewards read on a chip) without taking the payout over, so it is skipped QUIETLY and
 * {@link RewardKindValidator} reports it as an informational note. A command-less file whose id
 * nothing answers stays the loud case it always was, because that one really does pay nothing.
 */
public final class RewardKindFold {

    /** The owner prefix authored kinds are attributed to in the registry ledger. */
    public static final String OWNER_PREFIX = "rewardkind:";

    /**
     * What one fold did: which ids it registered, which of those took an id a Java registration
     * already held, and which files it left out because they name no command.
     *
     * <p>Every list holds the id as the FILE spells it, because that is what an owner has to go and
     * find. Registry lookups stay case-insensitive regardless.
     */
    public record Result(@Nonnull List<String> registered, @Nonnull List<String> shadowed,
                         @Nonnull List<String> skipped) {

        /** A fold that had nothing to do. */
        public static final Result EMPTY = new Result(List.of(), List.of(), List.of());

        /** True when at least one authored kind took an id something else already answered to. */
        public boolean anyShadowed() {
            return !shadowed.isEmpty();
        }
    }

    private RewardKindFold() {
    }

    /**
     * Fold every loaded kind asset into {@code kinds}, running each one's command as the server
     * console and warning through the shared guarded log.
     */
    @Nonnull
    public static Result foldInto(@Nonnull RewardKindRegistry kinds) {
        return foldInto(kinds, RewardKindConfig.getInstance().all().values(), CommandRunner.CONSOLE,
                SafeLog::warn);
    }

    /**
     * As {@link #foldInto(RewardKindRegistry)}, over a caller-supplied set of kinds, dispatcher and
     * warning sink - the form a test drives and a consumer with its own dispatch policy calls.
     */
    @Nonnull
    public static Result foldInto(@Nonnull RewardKindRegistry kinds,
            @Nullable Collection<RewardKindAsset> assets,
            @Nonnull CommandRunner.Dispatcher dispatcher, @Nullable Consumer<String> warn) {
        if (assets == null || assets.isEmpty()) {
            return Result.EMPTY;
        }
        List<String> registered = new ArrayList<>();
        List<String> shadowed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (RewardKindAsset asset : assets) {
            if (asset == null || asset.getId() == null || asset.getId().isBlank()) {
                continue;
            }
            String authoredId = asset.authoredId();
            if (asset.isBlank()) {
                skipped.add(authoredId);
                RewardHandler existing = kinds.handler(asset.getId());
                if (existing == null || existing instanceof CommandRewardKind) {
                    report(warn, "[reward-kind] '" + authoredId + "' names no command, so it was not registered"
                            + " and pays out nothing. Add a Command, or delete the file.");
                }
                // A Java-registered id keeps paying as before; the command-less file is that kind's
                // authored Presentation, read by every chip surface, and nothing to warn about.
                continue;
            }
            // A kind this fold already registered on an earlier pass is not a shadow: an asset
            // re-import re-registers every authored kind, and counting those would report the whole
            // catalogue as having taken something over the second time a pack is loaded.
            boolean takesAnExistingId = !(kinds.handler(asset.getId()) instanceof CommandRewardKind)
                    && kinds.isRegistered(asset.getId());
            kinds.registerQuietly(asset.getId(), OWNER_PREFIX + authoredId,
                    new CommandRewardKind(asset, dispatcher));
            registered.add(authoredId);
            if (takesAnExistingId) {
                shadowed.add(authoredId);
                report(warn, shadowWarning(authoredId));
            }
        }
        return new Result(List.copyOf(registered), List.copyOf(shadowed), List.copyOf(skipped));
    }

    /**
     * The one sentence a server owner needs when a file has taken over a kind a mod was providing:
     * what changed, what it costs, and how to undo it.
     */
    @Nonnull
    public static String shadowWarning(@Nonnull String authoredId) {
        return "[reward-kind] '" + authoredId + "' is now paid out by the authored file "
                + RewardKindAsset.TYPE_ROOT + "/" + authoredId + ".json instead of the mod that registered"
                + " it. An authored kind runs a command and nothing else, so this kind loses that mod's"
                + " engine services: the ask-first inventory check that lets a payout say 'come back with"
                + " room' before charging a price, and any retry richer than re-running the same command"
                + " line. Delete the file to hand the kind back.";
    }

    private static void report(@Nullable Consumer<String> warn, @Nonnull String message) {
        if (warn == null) {
            return;
        }
        try {
            warn.accept(message);
        } catch (Throwable ignored) {
            // A sink that throws costs its own line, never the rest of the fold.
        }
    }
}
