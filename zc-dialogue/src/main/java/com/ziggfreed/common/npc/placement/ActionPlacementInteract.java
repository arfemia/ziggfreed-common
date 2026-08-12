package com.ziggfreed.common.npc.placement;

import java.util.Map;
import java.util.function.Supplier;

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
import com.ziggfreed.common.dialogue.page.DialoguePage;
import com.ziggfreed.common.dialogue.page.DialoguePageDeps;
import com.ziggfreed.common.npc.NpcDialogueDepsRegistry;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ONE press-F action a placed NPC needs, whatever it is for.
 *
 * <p>Registered as {@code "ZigPlacementInteract"} and referenced from a base role's
 * {@code InteractionInstruction}, it reads the NPC's own {@link PlacedNpcComponent}, resolves the
 * placement behind it, and does two things:
 * <ol>
 *   <li>opens {@code Interact.Dialogue} when one is authored (this library owns the dialogue
 *       engine, so that behaviour is genuinely generic);</li>
 *   <li>hands every {@code Interact.Bindings} entry to whichever mod claimed its namespace.</li>
 * </ol>
 *
 * <p><b>Why one action instead of one per behaviour.</b> The action is decoded from a role asset
 * long before any consumer has registered anything, and a role cannot carry per-placement data, so
 * an action that encoded a destination would need one role per destination. Reading the identity
 * off the ENTITY instead means one base role serves every placement in the server, and a mod gets
 * press-F behaviour by registering a handler rather than by shipping a role and an action class.
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
        if (placement == null || placement.getInteract() == null) {
            return false;
        }
        NpcPlacementAsset.Interact interact = placement.getInteract();

        boolean acted = false;

        String dialogueId = interact.getDialogue();
        if (dialogueId != null && !dialogueId.isBlank()) {
            acted = openDialogue(ref, playerReference, store, dialogueId.trim(),
                    NpcIdentities.npcIdOfPlacement(placementId));
        }

        Map<String, PlacementBinding> bindings = interact.getBindings();
        if (!bindings.isEmpty()) {
            acted |= NpcPlacementBindings.dispatchInteract(
                    placementId, bindings, ref, playerReference, store) > 0;
        }
        return acted;
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

    /**
     * Open the placement's conversation, TOLD who it is with.
     *
     * <p>The npc context is what makes a conversation NPC-aware: without it a {@code MarkTalked} beat
     * has nobody to credit, {@code @self} substitutes nothing, and every quest-aware condition asks
     * about a character with no name and is answered no. The placement knows exactly who is standing
     * here, so it says so, and a conversation opened by pressing F behaves identically to the same
     * one opened through a named route.
     */
    private boolean openDialogue(@Nonnull Ref<EntityStore> npcRef, @Nonnull Ref<EntityStore> playerReference,
            @Nonnull Store<EntityStore> store, @Nonnull String dialogueId, @Nullable String npcId) {
        try {
            PlayerRef playerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
            Player player = store.getComponent(playerReference, Player.getComponentType());
            if (playerRef == null || player == null) {
                return false;
            }
            Supplier<DialoguePageDeps> supplier = NpcDialogueDepsRegistry.get(depsKey);
            DialoguePageDeps deps = supplier == null ? null : supplier.get();
            if (deps == null) {
                SafeLog.warn("[placement] no dialogue deps registered for key '" + depsKey
                        + "' - the dialogue on this placement cannot open");
                return false;
            }
            player.getPageManager().openCustomPage(npcRef, store,
                    new DialoguePage(playerRef, dialogueId, npcId, deps));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[placement] could not open the placement dialogue: " + t.getMessage());
            return false;
        }
    }
}
