package com.ziggfreed.common.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The half every per-player admin verb in this library shares: name a player, find them, and be on
 * their world's thread holding whatever the concrete command family needs to act.
 *
 * <p>Two library command families ({@code commerce} and {@code objectives}) each had their own copy
 * of exactly this resolution walk, wired to a different subject source (a live {@link
 * com.ziggfreed.common.subject.Subject} built straight off the player's own components, versus a
 * runtime-registered store's own subject-building). The walk itself - the argument, the online
 * check, the world-thread hop - was byte-for-byte the same in both; only what gets built out of a
 * resolved player differs. {@link #buildTarget} is that one seam: a family builds whatever object
 * its own engines want from the resolved {@code (store, ref, playerRef)} triple, and everything
 * before that point lives here once.
 *
 * <p><b>The player has to be ONLINE.</b> A command family reaching this base always keeps its state
 * on the player's own entity, so there is nowhere for an offline edit to land that a later login
 * would read back. Saying so is better than writing somewhere nothing reads.
 *
 * <p>It deliberately does not extend the engine's own target-player base, though it is otherwise the
 * same shape: that base demands a SECOND permission node before a sender may name anybody but
 * themselves, composed as {@code hytale.command.<this command's own node>.other}, which for an admin
 * family is a node nobody would think to grant and a refusal nobody could explain. One node per verb
 * is the whole of the permission story here.
 *
 * @param <T> whatever a concrete family builds from a resolved, online player (a {@code Subject}
 *         directly, or a family-specific handle bundle a family's own accessors turn into one later)
 */
public abstract class AbstractTargetPlayerCommand<T> extends AbstractAsyncCommand {

    /**
     * Says a line was refused, and why. Each command family answers through its own message
     * catalogue, so the wording and the language stay the family's own; this base only ever
     * triggers the two universal reasons, {@code player.needed} and {@code player.offline}.
     */
    @FunctionalInterface
    public interface Refusal {
        void refuse(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args);
    }

    private final Refusal refusal;
    private final OptionalArg<PlayerRef> playerArg;

    protected AbstractTargetPlayerCommand(@Nonnull String name, @Nonnull String description,
            @Nonnull String argDescription, @Nonnull Refusal refusal) {
        super(name, description);
        this.refusal = refusal;
        this.playerArg = withOptionalArg("player", argDescription, ArgTypes.PLAYER_REF);
    }

    /**
     * NOT final: a verb that steps outside the per-player walk on one authored flag (a broadcast
     * "give this to everyone online") overrides this and calls back into it for the ordinary case,
     * rather than the base teaching every family about a broadcast only one verb wants.
     */
    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> target = targetRef(ctx);
        if (target == null) {
            refusal.refuse(ctx, "player.needed");
            return CompletableFuture.completedFuture(null);
        }
        if (!target.isValid()) {
            refusal.refuse(ctx, "player.offline");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = target.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> onWorldThread(ctx, store, target), world);
    }

    /** The named player, else the sender when they are one, else null. */
    @Nullable
    private Ref<EntityStore> targetRef(@Nonnull CommandContext ctx) {
        if (playerArg.provided(ctx)) {
            PlayerRef named = playerArg.get(ctx);
            return named == null ? null : named.getReference();
        }
        return ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
    }

    private void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            refusal.refuse(ctx, "player.offline");
            return;
        }
        T target = buildTarget(store, ref, playerRef);
        if (target == null) {
            refusal.refuse(ctx, "player.offline");
            return;
        }
        execute(ctx, target);
    }

    /**
     * Build whatever this family's engines want from a resolved, online player, or null when this
     * family cannot make one out of a player that is technically online (the same "offline" refusal
     * covers both, since neither is a case worth a different sentence).
     */
    @Nullable
    protected abstract T buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef);

    /**
     * Do the thing, on the target's own world thread.
     *
     * @param target whatever {@link #buildTarget} made of the resolved player
     */
    protected abstract void execute(@Nonnull CommandContext ctx, @Nonnull T target);
}
