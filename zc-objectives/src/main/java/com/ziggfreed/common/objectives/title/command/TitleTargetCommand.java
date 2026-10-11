package com.ziggfreed.common.objectives.title.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.entity.title.ZigTitleComponent;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.objectives.title.TitleQueue;
import com.ziggfreed.common.objectives.title.command.TitlePlayers.Named;

/**
 * The title family's fill of the shared target-player walk, in its typed-name form: {@code --player}
 * takes a player online or not ({@link TitlePlayers} says who a name means). An online player is
 * resolved on their own world thread, has anything queued for them paid there, and becomes a
 * {@link Target}, today's path; one who is not goes
 * to {@link #executeOffline}, where a write is queued through {@code ZigTitles} for their next login
 * and a read comes from the title record. The writing verbs share one reading of {@code --title}.
 */
abstract class TitleTargetCommand extends AbstractTargetPlayerCommand<TitleTargetCommand.Target> {

    /** The player a verb was pointed at, resolved on their own world thread. */
    record Target(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                  @Nonnull PlayerRef playerRef) {

        /** The name the answers speak, which is DATA and never translated. */
        @Nonnull
        String name() {
            return playerRef.getUsername();
        }
    }

    TitleTargetCommand(@Nonnull String verb) {
        super(verb, TitleCommandMessages.desc(verb), TitleCommandMessages.desc("arg.player"),
                TitleCommandMessages::refused, true);
    }

    /**
     * On the player's world thread, before the verb acts: pay anything queued for them first, as the
     * facade's own hop does, so a grant queued while they were away never lands after a revoke typed now.
     */
    @Override
    @Nonnull
    protected Target buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef) {
        TitleQueue.payOnWorldThread(ref);
        return new Target(store, ref, playerRef);
    }

    /** A typed name: an online player takes the shared walk, anyone else this family's offline path. */
    @Override
    @Nonnull
    protected CompletableFuture<Void> executeNamed(@Nonnull CommandContext ctx, @Nonnull String typed) {
        return TitlePlayers.resolve(typed).thenCompose(named -> {
            if (named == null) {
                TitleCommandMessages.refused(ctx, "player.unknown", typed);
                return CompletableFuture.completedFuture(null);
            }
            Ref<EntityStore> online = PlayerAccess.liveRef(named.id());
            return online != null ? actOn(ctx, online) : executeOffline(ctx, named);
        });
    }

    /** Do the thing for a player who is not online, from any thread: their id and the name answers speak. */
    @Nonnull
    protected abstract CompletableFuture<Void> executeOffline(@Nonnull CommandContext ctx, @Nonnull Named target);

    /** Declare the {@code --title} argument on a verb that takes one. */
    @Nonnull
    protected OptionalArg<String> titleArg() {
        return withOptionalArg(TitleCommandLine.ARG_TITLE, TitleCommandMessages.desc("arg.title"), ArgTypes.STRING);
    }

    /** The title {@code arg} names, trimmed, or null after telling the sender what was wrong. */
    @Nullable
    protected static String titleId(@Nonnull CommandContext ctx, @Nonnull OptionalArg<String> arg) {
        String raw = arg.provided(ctx) ? arg.get(ctx) : null;
        if (raw == null || raw.isBlank()) {
            TitleCommandMessages.refused(ctx, "title.needed");
            return null;
        }
        String titleId = raw.trim();
        if (ZigTitleComponent.usesReservedDelimiter(titleId)) {
            TitleCommandMessages.refused(ctx, "title.refused", titleId);
            return null;
        }
        return titleId;
    }
}
