package com.ziggfreed.common.objectives.title.command;

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

/**
 * The title family's fill of the shared target-player walk: the resolved ONLINE player becomes a
 * {@link Target}, and the writing verbs share one reading of {@code --title}. A title record lives
 * on the player's own entity, so an offline edit has nowhere to land; a title owed to somebody
 * offline is the reward kind's business, through a consumer's retry queue.
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
                TitleCommandMessages::refused);
    }

    @Override
    @Nonnull
    protected Target buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef) {
        return new Target(store, ref, playerRef);
    }

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
