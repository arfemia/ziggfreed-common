package com.ziggfreed.common.dialogue;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.world.WhereValidator;
import com.ziggfreed.common.world.WorldIdentity;

/**
 * The dialogue engine's guarded reads of the world-identity layer, in one place so the
 * {@code World} condition and the per-world {@code World} scope leaf can never disagree about what
 * "the player's world" or "a loaded world" means.
 *
 * <p>Every method is try-guarded and fails CLOSED: an unreadable world resolves to {@code null}
 * and an unreadable world roster resolves to empty, which downstream reads as "this condition does
 * not pass" / "this scoped flag does not exist here". That matches
 * {@link DialogueEngine#conditionsPass}'s own rule that a failing evaluator HIDES gated content
 * rather than revealing it.
 *
 * <p>Public because a consumer registering its OWN world-aware condition needs the same guarded
 * reads.
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
        for (WhereValidator.LoadedWorld world : WorldIdentity.loadedWorlds()) {
            if (world.name() != null && !world.name().isBlank()) {
                out.add(world.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
