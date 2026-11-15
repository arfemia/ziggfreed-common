package com.ziggfreed.common.objectives.authoring;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.content.AppliesWhen;
import com.ziggfreed.common.content.ContentProblem;
import com.ziggfreed.common.content.ContentRefusedException;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAuthoring;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementService;
import com.ziggfreed.common.util.SafeLog;

/**
 * A new quest giver the editor stands for its quest, with a name of its own. Its character type is a role written into
 * the server content pack ({@link ServerContent#writeRole}): a {@code Variant} of the library's
 * {@code Template_Zig_QuestGiver} naming only its nameplate ({@link #roleJson}), the way a pack writes each of its
 * givers, its words the language key {@code npcs.<Id>.name}. The giver then stands through the one placement writer
 * ({@link NpcPlacementAuthoring#place}), an ordinary placement in the owner's {@code npc-placements.json} named for
 * that role, once the role and the quest have both landed, so a refused save leaves nobody standing. Its id names the
 * role, the placement and the character the quest's {@code Npc.ViewId} names.
 */
final class QuestGivers {

    /** The library's quest giver template, whose press of F opens the quests of whoever stands there. */
    static final String TEMPLATE = "Template_Zig_QuestGiver";

    /** The text key family a giver's nameplate is filed under: {@code npcs.<Id>.name}, as every pack's npcs.lang. */
    static final String NAME_STORE = "npcs";

    private QuestGivers() {
    }

    /** An id for a giver named {@code name}, free among every role, placement and character. */
    @Nonnull
    static String mintId(@Nonnull String name) {
        Set<String> declared = NpcIdentities.allDeclaredNpcIds();
        return ServerContent.mintRoleId(name, candidate -> placed(candidate)
                || declared.stream().anyMatch(candidate::equalsIgnoreCase));
    }

    /** True when a placement already stands under {@code id}. */
    static boolean placed(@Nonnull String id) {
        try {
            return NpcPlacementConfig.getInstance().has(id.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The giver's role: a {@code Variant} of {@link #TEMPLATE} whose {@code Modify} names only the template's own
     * {@code NameTranslationKey} (the engine refuses a whole role whose variant names anything the template does not
     * declare), so it looks as the template does and reads {@code nameKey}.
     */
    @Nonnull
    static String roleJson(@Nonnull String nameKey) {
        JsonObject modify = new JsonObject();
        modify.addProperty("NameTranslationKey", nameKey);
        JsonObject role = new JsonObject();
        role.addProperty("Type", "Variant");
        role.addProperty("Reference", TEMPLATE);
        role.add("Modify", modify);
        return role.toString();
    }

    /** The block cell {@code "x y z"} a pick wrote, or null when it does not read as one. */
    @Nullable
    static int[] cell(@Nullable String spot) {
        if (spot == null || spot.isBlank()) {
            return null;
        }
        String[] parts = spot.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Stand giver {@code giverId} as its own role once {@code role} and {@code quest} have both landed: on top of the
     * block {@code spot} names (facing back the way the admin looks), else where the admin stands (facing the way
     * they look). Its result fails, with the quest still saved, when the role or the quest was refused, the admin has
     * gone or the placement is refused.
     */
    @Nonnull
    static ServerContent.Result placeAfter(@Nonnull ServerContent.Result role, @Nonnull ServerContent.Result quest,
            @Nonnull PlayerRef admin, @Nonnull String giverId, @Nullable String spot) {
        CompletableFuture<Void> stood = CompletableFuture.allOf(role.completion(), quest.completion())
                .thenCompose(v -> stand(admin, giverId, cell(spot)));
        return new ServerContent.Result(giverId, AppliesWhen.NOW, stood);
    }

    /** Hop to the admin's world thread and place the giver there. */
    @Nonnull
    private static CompletableFuture<Void> stand(@Nonnull PlayerRef admin, @Nonnull String giverId,
            @Nullable int[] cell) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        World world;
        try {
            Ref<EntityStore> ref = admin.getReference();
            world = ref == null ? null : ref.getStore().getExternalData().getWorld();
        } catch (Throwable t) {
            world = null;
        }
        if (world == null) {
            done.completeExceptionally(refused(giverId, "the admin who saved it has left"));
            return done;
        }
        World here = world;
        here.execute(() -> {
            try {
                standNow(here, admin, giverId, cell, done);
            } catch (Throwable t) {
                SafeLog.warn("[quest-editor] the new quest giver " + giverId + " could not be placed", t);
                done.completeExceptionally(refused(giverId, String.valueOf(t.getMessage())));
            }
        });
        return done;
    }

    private static void standNow(@Nonnull World world, @Nonnull PlayerRef admin, @Nonnull String giverId,
            @Nullable int[] cell, @Nonnull CompletableFuture<Void> done) {
        Ref<EntityStore> ref = admin.getReference();
        if (ref == null || !ref.isValid()) {
            done.completeExceptionally(refused(giverId, "the admin who saved it has left"));
            return;
        }
        Store<EntityStore> store = ref.getStore();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            done.completeExceptionally(refused(giverId, "the admin's position could not be read"));
            return;
        }
        double yaw = NpcPlacementAuthoring.capturedYaw(transform.getRotation());
        double x;
        double y;
        double z;
        if (cell != null) {
            x = cell[0] + 0.5;
            y = cell[1] + 1.0;
            z = cell[2] + 0.5;
            yaw = yaw > 0 ? yaw - 180.0 : yaw + 180.0;
        } else {
            Vector3d position = transform.getPosition();
            x = position.x();
            y = position.y();
            z = position.z();
        }
        NpcPlacementAuthoring.Result result = NpcPlacementAuthoring.place(world, store, giverId, giverId, null,
                NpcPlacementService.worldName(world), NpcPlacementAuthoring.round(x, 2),
                NpcPlacementAuthoring.round(y, 2), NpcPlacementAuthoring.round(z, 2),
                NpcPlacementAuthoring.round(yaw, 1));
        if (result.ok()) {
            done.complete(null);
        } else {
            done.completeExceptionally(refused(giverId, "the placement was refused: " + result.outcome()));
        }
    }

    @Nonnull
    private static ContentRefusedException refused(@Nonnull String giverId, @Nonnull String why) {
        return new ContentRefusedException(ContentProblem.REJECTED, "quest giver " + giverId + ": " + why);
    }
}
