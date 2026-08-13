package com.ziggfreed.common.dialogue;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.world.WorldIdentity;
import com.ziggfreed.common.world.WorldSelectorValidator;

/**
 * The dialogue engine's guarded reads of the world-identity layer, in one place so the
 * {@code World} condition and the per-world {@code World} scope leaf can never disagree about what
 * "the player's world", "a loaded world" or "a known selector name" means.
 *
 * <p>Every method is try-guarded and fails CLOSED: an unreadable world resolves to {@code null}
 * and an unresolvable name set resolves to empty, which downstream reads as "this condition does
 * not pass" / "this scoped flag does not exist here". That matches
 * {@link DialogueEngine#conditionsPass}'s own rule that a failing evaluator HIDES gated content
 * rather than revealing it.
 *
 * <p>Public because a consumer registering its OWN world-aware condition needs the same guarded
 * reads, and because a consumer's validation command feeds {@link #knownSelectorNames()} to
 * {@code validate.DialogueStructureValidator}.
 */
public final class DialogueWorlds {

    private DialogueWorlds() {
    }

    /**
     * The world the player is currently in, or {@code null} when it cannot be read. The
     * established read is {@code store.getExternalData().getWorld()} - the same one every
     * world-aware ticking system uses - so {@link DialogueContext} needs no new member.
     */
    @Nullable
    public static World currentWorld(@Nonnull DialogueContext ctx) {
        try {
            return ctx.store().getExternalData().getWorld();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Every selector name {@code world} carries; empty for a null world or a failed resolve. */
    @Nonnull
    public static Set<String> selectorNamesOf(@Nullable World world) {
        if (world == null) {
            return Set.of();
        }
        try {
            return WorldIdentity.namesFor(world);
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    /** The NAME of the world the player is currently in, lower-cased; null when unreadable. */
    @Nullable
    public static String currentWorldName(@Nonnull DialogueContext ctx) {
        World world = currentWorld(ctx);
        if (world == null) {
            return null;
        }
        try {
            String name = world.getName();
            return name == null ? null : name.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The lower-cased NAME of every world the server currently has loaded - what a per-world
     * {@code Once} or memory pattern is checked against. Empty means "cannot tell" (no server, or
     * a failed read), never "no worlds exist": a caller must not turn it into a verdict.
     */
    @Nonnull
    public static Set<String> loadedWorldNames() {
        Set<String> out = new LinkedHashSet<>();
        for (WorldSelectorValidator.LoadedWorld world : WorldIdentity.loadedWorlds()) {
            if (world.name() != null && !world.name().isBlank()) {
                out.add(world.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * Every selector name any loaded {@code WorldSelectorAsset} hands out (lower-cased). Empty
     * when the pool has not loaded yet or could not be read - callers must treat empty as "cannot
     * tell", never as "nothing is known", or a pre-load evaluation turns into a false alarm.
     *
     * <p>Also the set a consumer feeds
     * {@code validate.DialogueStructureValidator.validateAll(dialogues, knownSelectorNames)} so an
     * authored name that matches no selector is a startup finding rather than silent content. The
     * scan itself belongs to the selector layer, so this only forwards - a placement and a
     * conversation asking the same question must never get two answers.
     */
    @Nonnull
    public static Set<String> knownSelectorNames() {
        return WorldSelectorValidator.knownNames();
    }
}
