package com.ziggfreed.common.instance.claims.command;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.instance.claims.RewardChestAsset;
import com.ziggfreed.common.instance.claims.RewardChestConfig;
import com.ziggfreed.common.instance.claims.SpoilsChests;
import com.ziggfreed.common.loot.LootableConfig;

/**
 * {@code chest [--player=<name>] [--chest=<id>] [--lootable=<id>]}: stand a reward chest two blocks east of an online
 * player, credited to them alone, as a rare kill would ({@link SpoilsChests#stand} under a fresh {@code chest:<uuid>}
 * key), so a reward chest can be seen and tested on any server. {@code --chest} names the reward chest file (default
 * {@code Zc_Reward_Chest}); {@code --lootable} names the loot table rolled into it, in place of the file's own
 * {@code Contents.Lootable}, which is needed when the file names none. The roll writes one Claim filed under the
 * chest, counted against the file's {@code Contents.Diminish}, and the chest goes up at that Claim; a roll that lands
 * nothing raises no chest. The banner's {@code {0}} reads the table's name.
 *
 * <p>The player has to be online: a chest needs a place to stand. Left out, the sender is the player.
 */
final class ZigClaimsChestCommand extends AbstractTargetPlayerCommand<ZigClaimsChestCommand.Target> {

    /** How far east of the player the chest stands, in blocks, so it never stands inside them. */
    static final double OFFSET = 2.0;

    /** The player a chest was stood for: who it credits, where it stands, and the name the answers speak. */
    record Target(@Nonnull UUID id, @Nonnull String name, @Nonnull UUID world, @Nonnull Vector3d at) {
    }

    private final OptionalArg<String> chestArg;
    private final OptionalArg<String> lootableArg;

    ZigClaimsChestCommand() {
        super(ZigClaimsCommand.CHEST, ClaimsCommandMessages.desc(ZigClaimsCommand.CHEST),
                ClaimsCommandMessages.desc("arg.player.online"), ClaimsCommandMessages::refused);
        this.chestArg = withOptionalArg("chest", ClaimsCommandMessages.desc("arg.chest"), ArgTypes.STRING);
        this.lootableArg = withOptionalArg("lootable", ClaimsCommandMessages.desc("arg.lootable"), ArgTypes.STRING);
    }

    @Override
    @Nullable
    protected Target buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        World world = store.getExternalData().getWorld();
        if (transform == null || world == null || playerRef.getUuid() == null) {
            return null;
        }
        Vector3d at = new Vector3d(transform.getPosition()).add(OFFSET, 0.0, 0.0);
        return new Target(playerRef.getUuid(), playerRef.getUsername(), world.getWorldConfig().getUuid(), at);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String chest = text(chestArg, ctx);
        String chestId = chest == null ? RewardChestAsset.REWARD_CHEST : chest;
        RewardChestAsset file = RewardChestConfig.getInstance().resolve(chestId);
        if (file == null) {
            ClaimsCommandMessages.refused(ctx, "chest.unknown", chestId);
            return;
        }
        String lootable = text(lootableArg, ctx);
        String tableId = lootable != null ? lootable : file.lootable();
        if (tableId == null) {
            ClaimsCommandMessages.refused(ctx, "lootable.needed");
            return;
        }
        if (LootableConfig.getInstance().resolve(tableId) == null) {
            ClaimsCommandMessages.refused(ctx, "lootable.unknown", tableId);
            return;
        }
        SpoilsChests.Stood stood = SpoilsChests.stand(SpoilsChests.Stand.of(chestId, SpoilsChests.newKey(),
                target.world(), target.at(), Set.of(target.id()), NativeNames.targetNameMsg(tableId),
                "admin=" + target.name()).withLootable(tableId));
        if (!stood.noted()) {
            ClaimsCommandMessages.refused(ctx, "chest.failed");
        } else if (stood.written() > 0) {
            ClaimsCommandMessages.done(ctx, "chest.done", chestId, target.name(), tableId);
        } else {
            ClaimsCommandMessages.detail(ctx, "chest.nothing", tableId, target.name());
        }
    }

    /** An argument as typed, trimmed, or null when it was left out or blank. */
    @Nullable
    private static String text(@Nonnull OptionalArg<String> arg, @Nonnull CommandContext ctx) {
        String value = arg.provided(ctx) ? arg.get(ctx) : null;
        return value == null || value.isBlank() ? null : value.trim();
    }
}
