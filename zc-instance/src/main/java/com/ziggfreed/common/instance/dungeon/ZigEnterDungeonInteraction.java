package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.util.SafeLog;

/**
 * Send the player into a dungeon with their party, from any interaction chain (an entrance block's use, a walk-in
 * trigger): the step that ends a pack's entry chain, after the gate that decides who may go.
 *
 * <pre>{@code
 * { "Type": "ZigEnterDungeon", "Dungeon": "MyPack_Crypt" }
 * }</pre>
 *
 * <p>It runs {@link DungeonEntry#enterWithParty}, the same entry every other way in takes, so the dungeon's rules
 * (switched on, in season, its party's area or a new one, the area pasted while the player loads, their own return
 * point where they stood) hold here as everywhere. The gate stays in front of it, a separate step
 * ({@code [ZigRequires, ZigEnterDungeon]}), so this module never reads progression.
 *
 * <p><b>Finished means sent.</b> The chain finishes, running {@code Next}, only when the player is on their way
 * in. A refusal tells the player why ({@link DungeonRefusals}) and takes the {@code Failed} branch; so does a chain
 * no player owns, and, silently, a player who is dead, still arriving from a teleport (so a walk-in trigger they
 * come back onto does not send them straight back in) or already being teleported, as the engine's own instance
 * teleporter declines them. A dungeon whose entry cost opens its confirm screen ({@link DungeonEntryPrompt}) takes
 * {@code Failed} too, with no line: the player has not gone yet, and the screen's button sends them. Deciding needs
 * the server, hence {@link WaitForDataFrom#Server}.
 *
 * <p>The teleport writes components, so the entry gets the chain's command buffer, never the store, which refuses
 * a write while the interaction system runs.
 */
public final class ZigEnterDungeonInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigEnterDungeon";

    @Nullable
    protected String dungeon;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigEnterDungeonInteraction> CODEC = BuilderCodec.builder(
                        ZigEnterDungeonInteraction.class, ZigEnterDungeonInteraction::new,
                        SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Dungeon", Codec.STRING, false),
                        (i, v) -> i.dungeon = v, i -> i.dungeon, (i, p) -> i.dungeon = p.dungeon)
                .documentation("The dungeon to send the player into, by its id (the file name under "
                        + "Server/ZiggfreedCommon/Dungeons/). The player goes in with their party, or alone when "
                        + "they have none, paying the dungeon's EntryCost when they open an area for their party. "
                        + "A dungeon that is closed, out of season or full, or whose cost the player cannot pay, "
                        + "tells the player why, and the chain takes its Failed branch.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigEnterDungeonInteraction> codec() {
        return Holder.CODEC;
    }

    /** This Type resolves Failed on a refusal, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server decides; a simulated run would try to send the player twice. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            Ref<EntityStore> owner = InteractionCtx.owner(ctx);
            CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
            World world = InteractionCtx.world(ctx);
            PlayerRef playerRef = InteractionCtx.player(ctx, owner);
            if (owner == null || buffer == null || world == null || playerRef == null) {
                SafeLog.fine("[interaction] " + TYPE_NAME + ": no player owns this chain, so it takes the Failed"
                        + " branch");
                return false;
            }
            if (!readyToGo(buffer, owner)) {
                return false;
            }
            String id = dungeon == null ? "" : dungeon;
            DungeonEntry.Outcome outcome = DungeonEntry.enterWithParty(owner, buffer, playerRef, world, id);
            if (outcome.result() == DungeonEntry.Result.SENT) {
                return true;
            }
            DungeonRefusals.tell(playerRef, id, outcome);
            return false;
        });
    }

    /**
     * Is the player free to set off: alive, not still arriving from a teleport, and not being teleported? The
     * engine's own instance teleporter asks the same before it sends anyone.
     */
    private static boolean readyToGo(@Nonnull CommandBuffer<EntityStore> buffer, @Nonnull Ref<EntityStore> owner) {
        if (buffer.getComponent(owner, DeathComponent.getComponentType()) != null) {
            return false;
        }
        Player player = buffer.getComponent(owner, Player.getComponentType());
        if (player == null || player.isWaitingForClientReady()) {
            return false;
        }
        Archetype<EntityStore> archetype = buffer.getArchetype(owner);
        return !archetype.contains(Teleport.getComponentType())
                && !archetype.contains(PendingTeleport.getComponentType());
    }
}
