package com.ziggfreed.common.npc.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.ziggfreed.common.npc.NpcDestinations;
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
 * <p><b>An NPC nothing placed still works.</b> The stamp is attached only by the placement sweep, so
 * an NPC spawned by a command, an egg, a prefab or another mod carries none - and a press-F that
 * found no stamp used to render its prompt and then silently do nothing, which reads in game as a
 * broken character rather than as an unconfigured one. The ROLE therefore gets to author its own
 * {@code Dialogue} or {@code Open} (see {@link BuilderActionPlacementInteract}), used when there is
 * no placement to read. It is a FALLBACK, never an override: a placement standing this NPC still
 * decides, so one shared role can be re-pointed per standing.
 *
 * <p><b>Why one action instead of one per behaviour.</b> A role cannot carry PER-PLACEMENT data, so
 * an action that could only be told a destination in the role file would need one role per
 * placement. Reading the identity off the ENTITY is what lets one base role serve every placement on
 * the server, and a mod gets press-F behaviour by registering a destination rather than by shipping
 * a role and an action class. The role-level fields add a default for the case where there is no
 * placement to read; they do not make the role the authority.
 *
 * <p><b>The identity always travels with the open.</b> Whatever the destination turns out to be, it
 * is told which character the player is standing at: without that a {@code MarkTalked} beat has
 * nobody to credit, {@code @self} substitutes nothing, and every quest-aware condition asks about a
 * character with no name and is answered no. The placement knows exactly who stands here, so it says
 * so, and pressing F behaves identically to opening the same destination from anywhere else.
 *
 * <p>Every step is guarded: an NPC with neither a stamp nor a role default, an unreadable authored
 * destination, or a handler that throws each degrade to doing less, never to an exception inside the
 * interaction.
 */
public class ActionPlacementInteract extends ActionBase {

    /** The conversation this ROLE opens when no placement stamp says otherwise, or null. */
    @Nullable
    private final String roleDialogue;

    /** The destination this ROLE opens, as authored JSON, decoded on first use. */
    @Nullable
    private final JsonElement roleOpen;

    /** The decoded {@link #roleOpen}, resolved once and then reused. */
    @Nullable
    private volatile Destination roleOpenDecoded;

    /** Whether {@link #roleOpen} has been through a decode attempt, successful or not. */
    private volatile boolean roleOpenResolved;

    public ActionPlacementInteract(@Nonnull BuilderActionPlacementInteract builder, @Nonnull BuilderSupport support) {
        super(builder);
        this.roleDialogue = trimToNull(builder.getDialogue(support));
        this.roleOpen = builder.getOpen();
    }

    @Override
    public boolean canExecute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport,
            @Nullable InfoProvider sensorInfo, double dt, @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, executionSupport, sensorInfo, dt, store)
                && executionSupport.getStateSupport().getInteractionIterationTarget() != null;
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport executionSupport, @Nullable InfoProvider sensorInfo,
            double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, executionSupport, sensorInfo, dt, store);

        Ref<EntityStore> playerReference = executionSupport.getStateSupport().getInteractionIterationTarget();
        if (playerReference == null) {
            return false;
        }

        String placementId = placementIdOf(ref, store);
        NpcPlacementAsset placement = placementId == null
                ? null
                : NpcPlacementConfig.getInstance().resolve(placementId);

        Destination destination = destinationFor(placement);
        if (destination == null) {
            SafeLog.fine("[placement] press-F on an NPC with no placement stamp and a role authoring"
                    + " neither Dialogue nor Open - nothing to open");
            return false;
        }

        DestinationContext ctx = contextOf(ref, playerReference, store, placementId);
        if (ctx == null) {
            return false;
        }
        return Destinations.open(destination, ctx);
    }

    /**
     * WHAT to open, in the one order that keeps a placement authoritative: the placement standing
     * this NPC, then what the ROLE says for an NPC nothing placed, then the character's quest list.
     *
     * <p>The role's answer is a FALLBACK rather than an override so a placement can always re-point
     * one standing of a shared role; the quest-list rung is reached only when a placement stood this
     * NPC and authored no {@code Interact}, since a role that authors nothing for an unplaced NPC has
     * not asked for a screen at all.
     */
    @Nullable
    private Destination destinationFor(@Nullable NpcPlacementAsset placement) {
        if (placement != null) {
            return destinationOf(placement);
        }
        return roleDestination();
    }

    /**
     * The role's own authored destination: the explicit {@code Open} when there is one, else the
     * terse {@code Dialogue} spelling. Authoring both runs {@code Open}, matching a placement's
     * {@code Interact}, so the two spellings mean the same thing in both places.
     */
    @Nullable
    private Destination roleDestination() {
        Destination open = decodedRoleOpen();
        if (open != null) {
            return open;
        }
        return roleDialogue == null ? null : NpcDestinations.Dialogue.of(roleDialogue);
    }

    /**
     * {@link #roleOpen} decoded, once. Deferred to first press-F because decoding a destination marks
     * the process-wide vocabulary as read, and a role asset is read at boot while a mod registering
     * its screens is still coming up.
     */
    @Nullable
    private Destination decodedRoleOpen() {
        if (roleOpenResolved) {
            return roleOpenDecoded;
        }
        Destination decoded = null;
        if (roleOpen != null && !roleOpen.isJsonNull()) {
            try {
                decoded = Destination.CODEC.decodeJson(
                        RawJsonReader.fromJsonString(roleOpen.toString()), new ExtraInfo());
            } catch (Throwable t) {
                SafeLog.warn("[placement] a role's press-F Open could not be read, so it opens"
                        + " nothing: " + t.getMessage());
            }
        }
        roleOpenDecoded = decoded;
        roleOpenResolved = true;
        return decoded;
    }

    /** {@code value} without surrounding blanks, or null when there was nothing but blanks. */
    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
     *
     * <p>With no placement to read the character off, the identity comes from
     * {@link NpcIdentities#npcIdOfEntity}, which walks an identity overlay on the role, an overlay on
     * a group it belongs to, and finally the role id itself - including through a chain of native
     * {@code Variant}s. That is what keeps a {@code MarkTalked} beat crediting somebody and
     * {@code @self} substituting a name for an NPC nothing placed.
     */
    @Nullable
    private DestinationContext contextOf(@Nonnull Ref<EntityStore> npcRef,
            @Nonnull Ref<EntityStore> playerReference, @Nonnull Store<EntityStore> store,
            @Nullable String placementId) {
        try {
            PlayerRef playerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
            Player player = store.getComponent(playerReference, Player.getComponentType());
            if (playerRef == null || player == null) {
                return null;
            }
            String npcId = placementId != null
                    ? NpcIdentities.npcIdOfPlacement(placementId)
                    : NpcIdentities.npcIdOfEntity(store, npcRef);
            return DestinationContext.of(store, playerReference, player)
                    .withNpc(npcRef, npcId, placementId);
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
