package com.ziggfreed.common.util;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Shared utilities for identifying entities (mobs, players) from ECS components:
 * a human-readable description for logging, and a best-effort model-asset id. Used
 * for XP/kill attribution, blacklisting, and log lines. World-thread only.
 */
public final class EntityIdentifierUtil {

    // Matches: modelAssetId='Skeleton_Fighter'
    private static final Pattern MODEL_ASSET_ID_PATTERN
            = Pattern.compile("modelAssetId='([^']+)'");

    private EntityIdentifierUtil() {
    }

    /**
     * Returns a human-readable description of an entity for logging, e.g.
     * "Player(Steve)", "Skeleton_Fighter", "Entity(?)".
     */
    @Nonnull
    public static String describeEntity(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null) {
            return "null";
        }

        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (pr != null) {
            String username = safeInvokeToString(pr, "getUsername");
            return "Player(" + (username != null ? username : "?") + ")";
        }

        String mobId = getMobId(store, ref);
        return mobId != null ? mobId : "Entity(?)";
    }

    /**
     * Returns the best available mob identifier for an entity. Tries ModelComponent
     * deep extraction first (modelAssetId), then direct ModelComponent method
     * candidates, then falls back to DisplayNameComponent.
     *
     * @return e.g. "Skeleton_Fighter", "Bear_Grizzly", or null
     */
    @Nullable
    public static String getMobId(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ModelComponent mc = store.getComponent(ref, ModelComponent.getComponentType());
        if (mc != null) {
            String id = getModelAssetId(mc);
            if (id != null) {
                return id;
            }
            id = probeModelComponent(mc);
            if (id != null) {
                return id;
            }
        }
        return readDisplayName(store, ref);
    }

    // ==================== Role identity (NPCEntity) ====================

    /**
     * The NPC's ROLE NAME (e.g. {@code "Skeleton_Fighter"}), the restart-STABLE identity string to
     * key allow / deny / override / classification config on. Reads {@code NPCEntity.getRoleName()};
     * {@code null} for a non-NPC (no {@link NPCEntity} component) or on any error. World-thread only.
     */
    @Nullable
    public static String roleName(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            return npc == null ? null : npc.getRoleName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Ref-less {@link #roleName(Store, Ref)}: the role name off a pre-add {@link Holder} (inside a
     * {@code HolderSystem.onEntityAdd} spawn hook, before a valid ref exists). {@code null} for a
     * non-NPC holder or on any error.
     */
    @Nullable
    public static String roleName(@Nonnull Holder<EntityStore> holder) {
        try {
            NPCEntity npc = holder.getComponent(NPCEntity.getComponentType());
            return npc == null ? null : npc.getRoleName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The NPC's ROLE INDEX ({@code NPCEntity.getRoleIndex()}), a restart-UNSTABLE integer for hot
     * in-tick lookups ONLY - never persist it or key stable config on it (use {@link #roleName}).
     * {@code -1} for a non-NPC or on any error. World-thread only.
     */
    public static int roleIndex(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            return npc == null ? -1 : npc.getRoleIndex();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Ref-less {@link #roleIndex(Store, Ref)}: the role index off a pre-add {@link Holder}.
     * {@code -1} for a non-NPC holder or on any error.
     */
    public static int roleIndex(@Nonnull Holder<EntityStore> holder) {
        try {
            NPCEntity npc = holder.getComponent(NPCEntity.getComponentType());
            return npc == null ? -1 : npc.getRoleIndex();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Extract modelAssetId via ModelComponent -> getModel() -> getModelAssetId.
     * Falls back to regex parsing of model.toString().
     */
    @Nullable
    private static String getModelAssetId(@Nonnull ModelComponent mc) {
        Object model = safeInvoke(mc, "getModel");
        if (model == null) {
            return null;
        }

        String id = firstNonBlank(
                safeInvokeToString(model, "getModelAssetId"),
                safeInvokeToString(model, "modelAssetId")
        );

        if (id == null) {
            Matcher m = MODEL_ASSET_ID_PATTERN.matcher(model.toString());
            if (m.find()) {
                id = m.group(1);
            }
        }

        return id;
    }

    /** Try multiple getter names directly on ModelComponent. */
    @Nullable
    private static String probeModelComponent(@Nonnull ModelComponent mc) {
        String[] candidates = { "getModelId", "getModelAssetId", "getId", "getAssetId" };
        for (String methodName : candidates) {
            String value = safeInvokeToString(mc, methodName);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }

        String str = mc.toString();
        if (str != null && !str.startsWith("com.")) {
            return str;
        }

        return null;
    }

    @Nullable
    private static String readDisplayName(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            DisplayNameComponent name = store.getComponent(ref, DisplayNameComponent.getComponentType());
            if (name == null) {
                return null;
            }
            return String.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Reflection helpers ====================

    @Nullable
    private static Object safeInvoke(@Nonnull Object obj, @Nonnull String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            return m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static String safeInvokeToString(@Nonnull Object obj, @Nonnull String method) {
        Object out = safeInvoke(obj, method);
        return out != null ? out.toString() : null;
    }

    @Nullable
    @SafeVarargs
    private static <T> T firstNonBlank(@Nonnull T... values) {
        for (T v : values) {
            if (v instanceof String s && !s.isBlank()) {
                return v;
            }
            if (v != null && !(v instanceof String)) {
                return v;
            }
        }
        return null;
    }
}
