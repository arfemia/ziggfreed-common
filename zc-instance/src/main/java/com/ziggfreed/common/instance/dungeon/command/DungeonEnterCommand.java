package com.ziggfreed.common.instance.dungeon.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonEntry;

/**
 * {@code enter <dungeon>}: send the admin running it into a dungeon, in an empty area (a party of one, whatever
 * party they are in; a dungeon whose areas are not party-locked may later put other parties in with them),
 * through the same entry every other way in takes ({@code DungeonEntry}): the dungeon must be open, its prefab
 * shipped and its audit free of ERRORs, and the admin in no area already. Runs on the admin's world thread.
 */
final class DungeonEnterCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> dungeonArg;

    DungeonEnterCommand() {
        super(ZigDungeonCommand.ENTER, DungeonCommandMessages.desc(ZigDungeonCommand.ENTER));
        this.dungeonArg = withRequiredArg(ZigDungeonCommand.ARG_DUNGEON, DungeonCommandMessages.desc("arg.dungeon"),
                ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world) {
        String id = dungeonArg.get(ctx).trim();
        DungeonEntry.Outcome outcome = DungeonEntry.enterAlone(ref, store, player, world, id);
        DungeonAsset dungeon = outcome.dungeon();
        switch (outcome.result()) {
            case SENT -> DungeonCommandMessages.done(ctx, "enter.sent", DungeonCommandMessages.dungeonName(dungeon),
                    outcome.area().area() + 1, outcome.area().world());
            case UNKNOWN -> DungeonCommandMessages.refused(ctx, "enter.unknown", id);
            case CLOSED -> DungeonCommandMessages.refused(ctx, "enter.closed",
                    DungeonCommandMessages.dungeonName(dungeon), DungeonCommandMessages.state(dungeon));
            case NO_PREFAB -> DungeonCommandMessages.refused(ctx, "enter.no_prefab",
                    DungeonCommandMessages.dungeonName(dungeon));
            case MISSING_PREFAB -> DungeonCommandMessages.refused(ctx, "enter.missing_prefab",
                    DungeonCommandMessages.dungeonName(dungeon), Msg.raw(outcome.prefab()));
            case AUDIT_ERROR -> DungeonCommandMessages.refused(ctx, "enter.audit_error",
                    DungeonCommandMessages.dungeonName(dungeon));
            case ON_THE_WAY -> DungeonCommandMessages.refused(ctx, "enter.on_the_way");
            case INSIDE -> DungeonCommandMessages.refused(ctx, "enter.inside");
            case FULL -> DungeonCommandMessages.refused(ctx, "enter.full");
            case COPY_GONE -> DungeonCommandMessages.refused(ctx, "enter.copy_gone");
            case FAILED -> DungeonCommandMessages.refused(ctx, "enter.failed");
        }
    }
}
