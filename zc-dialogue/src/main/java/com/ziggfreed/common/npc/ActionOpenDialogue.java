package com.ziggfreed.common.npc;


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
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.dialogue.page.DialogueOpener;
import com.ziggfreed.common.dialogue.page.DialoguePage;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * Generic custom NPC interaction action that opens a {@link DialoguePage} for the
 * interacting player when they press F on the NPC. Registered as
 * {@code "ZigOpenDialogue"} via {@link NpcActions#register()} and referenced from an
 * NPC role's {@code InteractionInstruction}
 * ({@code { "Type": "ZigOpenDialogue", "Dialogue": "<id>" }}).
 *
 * <p>Modeled on the engine's own {@code ActionOpenBarterShop}. It carries only data: the dialogue
 * id, and an optional {@code ContextNpc} for {@code @self} resolution. Everything the screen needs
 * beyond that is process-wide, so a role naming a conversation is the whole of the wiring and no mod
 * has to be asked which conversation system this NPC belongs to. Only runs inside an
 * {@code InteractionInstruction} (enforced by {@link BuilderActionOpenDialogue#readConfig}).
 */
public class ActionOpenDialogue extends ActionBase {

    /** The dialogue id to open; the page reads it from the one conversation store. */
    @Nonnull
    protected final String dialogueId;

    /** The optional context NPC id ({@code @self}-target resolution + header), or {@code null}. */
    @Nullable
    protected final String contextNpc;

    public ActionOpenDialogue(@Nonnull BuilderActionOpenDialogue builder, @Nonnull BuilderSupport support) {
        super(builder);
        String d = builder.getDialogue(support);
        this.dialogueId = (d == null) ? "" : d.trim();
        String c = builder.getContextNpc(support);
        this.contextNpc = (c == null || c.isBlank()) ? null : c.trim();
    }

    @Override
    public boolean canExecute(
            @Nonnull Ref<EntityStore> ref, @Nonnull Role role, @Nullable InfoProvider sensorInfo, double dt,
            @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, role, sensorInfo, dt, store)
                && role.getStateSupport().getInteractionIterationTarget() != null;
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull Role role, @Nullable InfoProvider sensorInfo,
            double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, role, sensorInfo, dt, store);

        if (dialogueId.isBlank()) {
            return false; // nothing to open
        }

        // The entity that triggered this interaction (the player who pressed F).
        Ref<EntityStore> playerReference = role.getStateSupport().getInteractionIterationTarget();
        if (playerReference == null) {
            return false;
        }

        PlayerRef playerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
        if (playerRef == null) {
            return false;
        }
        Player player = store.getComponent(playerReference, Player.getComponentType());
        if (player == null) {
            return false;
        }

        // Open on the NPC's own ref (the page manager is the interacting player's), exactly like
        // ActionOpenBarterShop - and through the opener, so a conversation whose Start routes to a
        // quest row's destination hands the screen over instead of opening on nothing.
        return DialogueOpener.open(
                new DestinationContext(store, playerReference, player, ref, contextNpc, null),
                dialogueId, contextNpc);
    }

    private static void warn(@Nonnull String msg) {
        try {
            CommonLog.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }
}
