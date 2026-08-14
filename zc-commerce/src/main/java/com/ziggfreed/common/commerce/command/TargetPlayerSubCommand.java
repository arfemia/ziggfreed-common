package com.ziggfreed.common.commerce.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;

/**
 * The half every per-player verb here shares: name a player, find them, and be on their world's
 * thread holding the {@link Subject} the engines speak in.
 *
 * <p><b>The subject's handle is the live {@link Player}</b>, which is what every store and wallet in
 * this module resolves through - the component-backed state off the player's entity, and the
 * inventory behind an item-backed balance. Building one any other way would read neutral and drop
 * every write while reporting success.
 *
 * <p><b>The player has to be ONLINE.</b> Commerce state lives on the player's own entity, so there
 * is nowhere for an offline edit to land that a later login would read back. Saying so is better
 * than writing somewhere nothing reads.
 *
 * <p>It deliberately does not extend the engine's own target-player base, though it is otherwise the
 * same shape: that base demands a SECOND permission node before a sender may name anybody but
 * themselves, composed as {@code hytale.command.<this command's own node>.other}, which for an admin
 * family is a node nobody would think to grant and a refusal nobody could explain. One node per verb
 * is the whole of the permission story here.
 */
abstract class TargetPlayerSubCommand extends AbstractAsyncCommand {

    private final OptionalArg<PlayerRef> playerArg;

    TargetPlayerSubCommand(@Nonnull String name) {
        super(name, CommerceAdminMessages.desc(name));
        this.playerArg = withOptionalArg("player", CommerceAdminMessages.desc("arg.player"),
                ArgTypes.PLAYER_REF);
    }

    @Override
    @Nonnull
    protected final CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> target = targetRef(ctx);
        if (target == null) {
            CommerceAdminMessages.refused(ctx, "player.needed");
            return CompletableFuture.completedFuture(null);
        }
        if (!target.isValid()) {
            CommerceAdminMessages.refused(ctx, "player.offline");
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
        Player player = store.getComponent(ref, Player.getComponentType());
        if (playerRef == null || player == null) {
            CommerceAdminMessages.refused(ctx, "player.offline");
            return;
        }
        execute(ctx, new Subject(playerRef.getUuid(), playerRef.getUsername(), player));
    }

    /**
     * Do the thing, on the target's own world thread, with a subject every commerce engine accepts.
     *
     * @param subject the target player, handled by their live {@link Player}
     */
    protected abstract void execute(@Nonnull CommandContext ctx, @Nonnull Subject subject);
}
