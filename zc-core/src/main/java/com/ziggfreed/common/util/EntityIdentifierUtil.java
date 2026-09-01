package com.ziggfreed.common.util;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Shared utilities for identifying entities (mobs, players) from ECS
 * components: a human-readable description for logging, and the mob identity
 * every keyed-on-mob surface in this family matches against. Used for XP/kill
 * attribution, blacklisting, config matching and log lines. World-thread only.
 *
 * <p>
 * <b>A mob is its NPC ROLE.</b> The role name is what the role file is called,
 * what an owner can see and type, and what a spawn marker references; the model
 * asset it wears is a costume, and two unrelated roles wearing one model are
 * not the same mob. That the two strings usually read the same word is a
 * coincidence of how the vanilla assets are named, not an identity - a training
 * dummy is a role called {@code Test_Dummy} wearing a {@code Mannequin}, and a
 * pack mob dressed as a vanilla one is the same shape. So {@link #getMobId}
 * answers the ROLE, and the model is read only for an entity no role drives at
 * all, so such an entity is still nameable rather than silently unmatched.
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
     * THE mob identity: the entity's NPC role name, which is what config,
     * content and deny lists are keyed on across this family.
     *
     * <p>
     * An entity no role drives has no role name, and only then is its costume
     * read instead (the model asset id, falling back to its display name), so a
     * bare spawned entity is still nameable rather than matching nothing. Never
     * reach past this method for a model id to match on: two roles can wear one
     * model, so a model-keyed rule would quietly govern mobs it never meant.
     *
     * @return e.g. "Skeleton_Fighter", "Test_Dummy", or null when nothing about
     * the entity reads
     */
    @Nullable
    public static String getMobId(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        String role = roleName(store, ref);
        if (role != null && !role.isBlank()) {
            return role;
        }
        return costumeId(store, ref);
    }

    /**
     * What an entity WEARS, for the one case {@link #getMobId} needs it: an
     * entity with no role at all. Deliberately not public - a caller reaching
     * for a costume to match on is the mistake this method exists to keep
     * contained.
     */
    @Nullable
    private static String costumeId(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
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
     * The NPC's ROLE NAME (e.g. {@code "Skeleton_Fighter"}), the restart-STABLE
     * identity string to key allow / deny / override / classification config
     * on. Reads {@code NPCEntity.getRoleName()};
     * {@code null} for a non-NPC (no {@link NPCEntity} component) or on any
     * error. World-thread only.
     *
     * <p>
     * Takes a bare {@link ComponentAccessor} rather than a {@link Store}, so a
     * caller inside an entity-added hook can ask through the
     * {@code CommandBuffer} it was handed without fetching the component
     * itself.
     */
    @Nullable
    public static String roleName(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        try {
            NPCEntity npc = accessor.getComponent(ref, NPCEntity.getComponentType());
            return npc == null ? null : npc.getRoleName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Ref-less {@link #roleName(ComponentAccessor, Ref)}: the role name off a
     * pre-add {@link Holder} (inside a {@code HolderSystem.onEntityAdd} spawn
     * hook, before a valid ref exists). {@code null} for a non-NPC holder or on
     * any error.
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
     * The NPC's ROLE INDEX ({@code NPCEntity.getRoleIndex()}), a
     * restart-UNSTABLE integer for hot in-tick lookups ONLY - never persist it
     * or key stable config on it (use {@link #roleName}). {@code -1} for a
     * non-NPC or on any error. Takes an accessor on the same terms as
     * {@link #roleName}. World-thread only.
     */
    public static int roleIndex(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref) {
        try {
            NPCEntity npc = accessor.getComponent(ref, NPCEntity.getComponentType());
            return npc == null ? -1 : npc.getRoleIndex();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Ref-less {@link #roleIndex(ComponentAccessor, Ref)}: the role index off a
     * pre-add {@link Holder}. {@code -1} for a non-NPC holder or on any error.
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

    /**
     * Try multiple getter names directly on ModelComponent.
     */
    @Nullable
    private static String probeModelComponent(@Nonnull ModelComponent mc) {
        String[] candidates = {"getModelId", "getModelAssetId", "getId", "getAssetId"};
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
    @SuppressWarnings("UseSpecificCatch")
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
