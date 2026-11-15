package com.ziggfreed.common.objectives.authoring;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

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
 * A new quest giver the editor stands for its quest, through the one placement writer
 * ({@link NpcPlacementAuthoring#place}): an ordinary placement in the owner's {@code npc-placements.json}, its own
 * character ({@code Identity.NpcId}, the id the quest names as its giver), stood once the quest itself has saved, so a
 * refused quest leaves nobody standing. The library's {@link #DEFAULT_ROLE} opens its quests on a press of F.
 */
final class QuestGivers {

    /** The library's quest giver role, a Variant of {@code Template_Zig_QuestGiver} this module ships. */
    static final String DEFAULT_ROLE = "Zig_QuestGiver";

    private QuestGivers() {
    }

    /** A character id for the giver of the quest named {@code questName}, free among every placement and character. */
    @Nonnull
    static String mintId(@Nonnull String questName) {
        Set<String> declared = NpcIdentities.allDeclaredNpcIds();
        return QuestDraft.assetId(questName + " Giver", candidate -> placed(candidate)
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
     * Stand the giver {@code giverId} as {@code role} once {@code quest} has landed: on top of the block {@code spot}
     * names (facing back the way the admin looks), else where the admin stands (facing the way they look). Its result
     * fails, with the quest still saved, when the admin has gone or the placement is refused.
     */
    @Nonnull
    static ServerContent.Result placeAfter(@Nonnull ServerContent.Result quest, @Nonnull PlayerRef admin,
            @Nonnull String role, @Nonnull String giverId, @Nullable String spot) {
        CompletableFuture<Void> stood = quest.completion().thenCompose(v -> stand(admin, role, giverId, cell(spot)));
        return new ServerContent.Result(giverId, AppliesWhen.NOW, stood);
    }

    /** Hop to the admin's world thread and place the giver there. */
    @Nonnull
    private static CompletableFuture<Void> stand(@Nonnull PlayerRef admin, @Nonnull String role,
            @Nonnull String giverId, @Nullable int[] cell) {
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
                standNow(here, admin, role, giverId, cell, done);
            } catch (Throwable t) {
                SafeLog.warn("[quest-editor] the new quest giver " + giverId + " could not be placed", t);
                done.completeExceptionally(refused(giverId, String.valueOf(t.getMessage())));
            }
        });
        return done;
    }

    private static void standNow(@Nonnull World world, @Nonnull PlayerRef admin, @Nonnull String role,
            @Nonnull String giverId, @Nullable int[] cell, @Nonnull CompletableFuture<Void> done) {
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
        NpcPlacementAuthoring.Result result = NpcPlacementAuthoring.place(world, store, role, giverId, giverId, null,
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
