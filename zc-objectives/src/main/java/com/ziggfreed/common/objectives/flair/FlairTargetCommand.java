package com.ziggfreed.common.objectives.flair;

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
import com.ziggfreed.common.entity.flair.ZigFlairComponent;

/**
 * The flair family's fill of the shared target-player walk ({@link AbstractTargetPlayerCommand}):
 * the resolved online player becomes a {@link Target} holding the three handles
 * {@link FlairUnlocks} wants, and the two writing verbs share one reading of {@code --flair}.
 *
 * <p>The player has to be ONLINE: a flair set lives on the player's own entity, so an offline edit
 * has nowhere to land. A grant owed to somebody offline is the reward kind's business, through the
 * consumer's retry queue.
 */
abstract class FlairTargetCommand extends AbstractTargetPlayerCommand<FlairTargetCommand.Target> {

    /** The player a verb was pointed at, resolved on their own world thread. */
    record Target(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                  @Nonnull PlayerRef playerRef) {

        /** The name the answers speak, which is DATA and never translated. */
        @Nonnull
        String name() {
            return playerRef.getUsername();
        }
    }

    FlairTargetCommand(@Nonnull String verb) {
        super(verb, FlairAdminMessages.desc(verb), FlairAdminMessages.desc("arg.player"),
                FlairAdminMessages::refused);
    }

    @Override
    @Nonnull
    protected Target buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef) {
        return new Target(store, ref, playerRef);
    }

    /** Declare the {@code --flair} argument on a verb that takes one. */
    @Nonnull
    protected OptionalArg<String> flairArg() {
        return withOptionalArg(FlairCommandLine.ARG_FLAIR, FlairAdminMessages.desc("arg.flair"),
                ArgTypes.STRING);
    }

    /**
     * The flair {@code arg} names, trimmed, or null after telling the sender that nothing was named
     * or that the id carries a character the save format reserves.
     */
    @Nullable
    protected static String flairId(@Nonnull CommandContext ctx, @Nonnull OptionalArg<String> arg) {
        String raw = arg.provided(ctx) ? arg.get(ctx) : null;
        if (raw == null || raw.isBlank()) {
            FlairAdminMessages.refused(ctx, "flair.needed");
            return null;
        }
        String flairId = raw.trim();
        if (ZigFlairComponent.usesReservedDelimiter(flairId)) {
            FlairAdminMessages.refused(ctx, "flair.refused", flairId);
            return null;
        }
        return flairId;
    }
}
