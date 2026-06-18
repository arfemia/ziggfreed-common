package com.ziggfreed.common.npc;

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
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.dialogue.page.DialoguePage;
import com.ziggfreed.common.dialogue.page.DialoguePageDeps;

/**
 * Generic custom NPC interaction action that opens a {@link DialoguePage} for the
 * interacting player when they press F on the NPC. Registered as
 * {@code "ZigOpenDialogue"} via {@link NpcActions#register()} and referenced from an
 * NPC role's {@code InteractionInstruction}
 * ({@code { "Type": "ZigOpenDialogue", "Dialogue": "<id>" }}).
 *
 * <p>Modeled on the engine's own {@code ActionOpenBarterShop}. It carries only data
 * (the dialogue id, an optional {@code ContextNpc} for {@code @self} resolution, and
 * an optional {@code DepsKey}); the consumer's {@link DialoguePageDeps} are resolved
 * LAZILY at press-F time from {@link NpcDialogueDepsRegistry}, so this engine class
 * stays decoupled from any one consumer's wiring. Only runs inside an
 * {@code InteractionInstruction} (enforced by {@link BuilderActionOpenDialogue#readConfig}).
 */
public class ActionOpenDialogue extends ActionBase {

    /** The dialogue id to open (the page resolves its nodes through the consumer's resolver). */
    @Nonnull
    protected final String dialogueId;

    /** The optional context NPC id ({@code @self}-target resolution + header), or {@code null}. */
    @Nullable
    protected final String contextNpc;

    /** Which registered deps provider to resolve at open time ({@link NpcDialogueDepsRegistry#DEFAULT_KEY} if blank). */
    @Nonnull
    protected final String depsKey;

    public ActionOpenDialogue(@Nonnull BuilderActionOpenDialogue builder, @Nonnull BuilderSupport support) {
        super(builder);
        String d = builder.getDialogue(support);
        this.dialogueId = (d == null) ? "" : d.trim();
        String c = builder.getContextNpc(support);
        this.contextNpc = (c == null || c.isBlank()) ? null : c.trim();
        String k = builder.getDepsKey(support);
        this.depsKey = (k == null || k.isBlank()) ? NpcDialogueDepsRegistry.DEFAULT_KEY : k.trim();
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

        // Resolve the consumer's deps lazily (the action was decoded from a role asset
        // long before the consumer registered its provider).
        Supplier<DialoguePageDeps> supplier = NpcDialogueDepsRegistry.get(depsKey);
        if (supplier == null) {
            warn("[NpcDialogue] no DialoguePageDeps provider registered for key '" + depsKey + "'");
            return false;
        }
        DialoguePageDeps deps = supplier.get();
        if (deps == null) {
            warn("[NpcDialogue] DialoguePageDeps provider for key '" + depsKey + "' returned null");
            return false;
        }

        // Open the dialogue on the NPC's own ref (the page manager is the interacting
        // player's), exactly like ActionOpenBarterShop.
        player.getPageManager().openCustomPage(ref, store,
                new DialoguePage(playerRef, dialogueId, contextNpc, deps));
        return true;
    }

    private static void warn(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM; swallow.
        }
    }
}
