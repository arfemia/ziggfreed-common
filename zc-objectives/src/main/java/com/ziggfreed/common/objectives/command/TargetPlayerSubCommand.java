package com.ziggfreed.common.objectives.command;

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
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * The half every per-player verb here shares: name a player, find them, and be on their world's
 * thread holding the handles the shared runtime builds a {@link Subject} from.
 *
 * <p><b>The subject comes from the RUNTIME, never from here.</b> A store reaches a player's
 * persisted progress through whatever handle its own owner attached to the subject, so a subject
 * built by anybody else reads NEUTRAL through it - no status, no progress - and silently drops every
 * write. {@link Target#questSubject()} and {@link Target#achievementSubject()} ask
 * {@link ProgressionRuntime#subjects()}, so a verb here always speaks to whichever stores are
 * registered, on a bare server and on one where a consumer mod owns them alike.
 *
 * <p><b>The player has to be ONLINE.</b> Progress lives on the player's own entity, so there is
 * nowhere for an offline edit to land that a later login would read back. Saying so is better than
 * writing somewhere nothing reads.
 *
 * <p>It deliberately does not extend the engine's own target-player base, though it is otherwise the
 * same shape: that base demands a SECOND permission node before a sender may name anybody but
 * themselves, composed as {@code hytale.command.<this command's own node>.other}, which for an admin
 * family is a node nobody would think to grant and a refusal nobody could explain. One node per verb
 * is the whole of the permission story here.
 */
abstract class TargetPlayerSubCommand extends AbstractAsyncCommand {

    /** The player a verb was pointed at, resolved on their own world thread. */
    record Target(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                  @Nonnull PlayerRef playerRef) {

        /** The name the answers speak, which is DATA and never translated. */
        @Nonnull
        String name() {
            return playerRef.getUsername();
        }

        /** The subject the ACTIVE quest store understands, or null when there is nothing to build one from. */
        @Nullable
        Subject questSubject() {
            return ProgressionRuntime.subjects().questSubject(store, ref);
        }

        /** The subject the ACTIVE achievement store understands, or null on the same terms. */
        @Nullable
        Subject achievementSubject() {
            return ProgressionRuntime.subjects().achievementSubject(store, ref);
        }
    }

    private final OptionalArg<PlayerRef> playerArg;

    TargetPlayerSubCommand(@Nonnull String group, @Nonnull String verb) {
        super(verb, ProgressAdminMessages.desc(group + "." + verb));
        this.playerArg = withOptionalArg("player", ProgressAdminMessages.desc("arg.player"),
                ArgTypes.PLAYER_REF);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> target = targetRef(ctx);
        if (target == null) {
            ProgressAdminMessages.refused(ctx, "player.needed");
            return CompletableFuture.completedFuture(null);
        }
        if (!target.isValid()) {
            ProgressAdminMessages.refused(ctx, "player.offline");
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
            ProgressAdminMessages.refused(ctx, "player.offline");
            return;
        }
        execute(ctx, new Target(store, ref, playerRef));
    }

    /**
     * Do the thing, on the target's own world thread.
     *
     * @param target the player, with the handles the runtime builds their subject from
     */
    protected abstract void execute(@Nonnull CommandContext ctx, @Nonnull Target target);

    /**
     * The target's quest subject, or null after telling the sender that this player has no
     * progress record the active store can read.
     */
    @Nullable
    protected static Subject questSubjectOf(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Subject subject = target.questSubject();
        if (subject == null) {
            ProgressAdminMessages.refused(ctx, "player.no_record", target.name());
        }
        return subject;
    }

    /** The achievement twin of {@link #questSubjectOf}. */
    @Nullable
    protected static Subject achievementSubjectOf(@Nonnull CommandContext ctx,
            @Nonnull Target target) {
        Subject subject = target.achievementSubject();
        if (subject == null) {
            ProgressAdminMessages.refused(ctx, "player.no_record", target.name());
        }
        return subject;
    }
}
