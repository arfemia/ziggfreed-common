package com.ziggfreed.common.npc.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.npc.NpcDialogueDepsRegistry;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ONE press-F action a placed NPC needs, whatever it is for.
 *
 * <p>Registered as {@code "ZigPlacementInteract"} and referenced from a base role's
 * {@code InteractionInstruction}, it reads the NPC's own {@link PlacedNpcComponent}, resolves the
 * placement behind it, and opens whatever that placement's {@code Interact} says - a conversation,
 * or any destination a mod on this server registered. A placement authoring neither opens that
 * character's quest list.
 *
 * <p><b>Why one action instead of one per behaviour.</b> The action is decoded from a role asset
 * long before any consumer has registered anything, and a role cannot carry per-placement data, so
 * an action that encoded a destination would need one role per destination. Reading the identity
 * off the ENTITY instead means one base role serves every placement in the server, and a mod gets
 * press-F behaviour by registering a destination rather than by shipping a role and an action class.
 *
 * <p><b>The identity always travels with the open.</b> Whatever the destination turns out to be, it
 * is told which character the player is standing at: without that a {@code MarkTalked} beat has
 * nobody to credit, {@code @self} substitutes nothing, and every quest-aware condition asks about a
 * character with no name and is answered no. The placement knows exactly who stands here, so it says
 * so, and pressing F behaves identically to opening the same destination from anywhere else.
 *
 * <p>Every step is guarded: an NPC with no stamp, an unknown placement, or a handler that throws
 * each degrade to doing less, never to an exception inside the interaction.
 */
public class ActionPlacementInteract extends ActionBase {

    /** Which registered dialogue-deps provider to use ({@code DEFAULT_KEY} when blank). */
    @Nonnull
    protected final String depsKey;

    public ActionPlacementInteract(@Nonnull BuilderActionPlacementInteract builder, @Nonnull BuilderSupport support) {
        super(builder);
        String key = builder.getDepsKey(support);
        this.depsKey = (key == null || key.isBlank()) ? NpcDialogueDepsRegistry.DEFAULT_KEY : key.trim();
    }

    @Override
    public boolean canExecute(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
            @Nullable InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, role, sensorInfo, dt, store)
                && role.getStateSupport().getInteractionIterationTarget() != null;
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull Role role, @Nullable InfoProvider sensorInfo,
            double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, role, sensorInfo, dt, store);

        Ref<EntityStore> playerReference = role.getStateSupport().getInteractionIterationTarget();
        if (playerReference == null) {
            return false;
        }

        String placementId = placementIdOf(ref, store);
        if (placementId == null) {
            SafeLog.fine("[placement] press-F on an NPC with no placement stamp - nothing to do");
            return false;
        }
        NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(placementId);
        if (placement == null) {
            return false;
        }

        DestinationContext ctx = contextOf(ref, playerReference, store, placementId);
        if (ctx == null) {
            return false;
        }
        return Destinations.open(destinationOf(placement), ctx);
    }

    /**
     * WHAT this placement opens: its authored destination, or - when it authors no {@code Interact}
     * at all - the character's quest list, which is what a placed NPC with nothing else to say is
     * for.
     */
    @Nullable
    private static Destination destinationOf(@Nonnull NpcPlacementAsset placement) {
        NpcPlacementAsset.Interact interact = placement.getInteract();
        Destination authored = interact == null ? null : interact.destination();
        return authored != null ? authored : NpcDestinations.Quests.of(null);
    }

    /**
     * The moment as a destination handler sees it: the player's live handles, plus WHO is standing
     * here and WHERE, so nothing downstream has to resolve an identity of its own. Null when the
     * interacting entity turns out not to be a player.
     */
    @Nullable
    private DestinationContext contextOf(@Nonnull Ref<EntityStore> npcRef,
            @Nonnull Ref<EntityStore> playerReference, @Nonnull Store<EntityStore> store,
            @Nonnull String placementId) {
        try {
            PlayerRef playerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
            Player player = store.getComponent(playerReference, Player.getComponentType());
            if (playerRef == null || player == null) {
                return null;
            }
            return DestinationContext.of(store, playerReference, player)
                    .withNpc(npcRef, NpcIdentities.npcIdOfPlacement(placementId), placementId)
                    .withDepsKey(depsKey);
        } catch (Throwable t) {
            SafeLog.warn("[placement] could not read the interacting player: " + t.getMessage());
            return null;
        }
    }

    /** The placement id stamped on this NPC, or {@code null} when it carries no stamp. */
    @Nullable
    private static String placementIdOf(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            var type = PlacedNpcComponent.getComponentType();
            if (type == null) {
                return null;
            }
            PlacedNpcComponent component = store.getComponent(ref, type);
            if (component == null || component.placementId == null || component.placementId.isBlank()) {
                return null;
            }
            return component.placementId;
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not read the placement stamp: " + t.getMessage());
            return null;
        }
    }
}
