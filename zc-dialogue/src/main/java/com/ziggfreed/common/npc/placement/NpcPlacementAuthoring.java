package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.SafeLog;

/**
 * Writing a placement from OUTSIDE a content pack: the shared half of "stand this role here".
 *
 * <p>It exists so the library's own {@code /zignpc place} and a consumer's aliased command are one
 * implementation rather than two that can disagree about what gets written. Everything a caller has
 * to decide - who is asking, whether they are allowed, how the answer is worded - stays with the
 * caller; what lands in the file, and what happens afterwards, is here.
 *
 * <p>What it writes is an ordinary placement in the server owner's own
 * {@code mods/ziggfreedcommon/npc-placements.json}, in exactly the shape a pack ships, so the result
 * is readable, editable and removable by hand afterwards. There is no second kind of placement and
 * no privileged path for one written this way.
 *
 * <p><b>World thread</b>, because it sweeps.
 */
public final class NpcPlacementAuthoring {

    /** Why a write did or did not happen, in terms a caller can word for whoever asked. */
    public enum Outcome {
        /** Written, re-read and placed. */
        PLACED,
        /** The id already names a placement; nothing was written. */
        ID_TAKEN,
        /** No such role, or one that exists but can never be spawned; nothing was written. */
        ROLE_NOT_SPAWNABLE,
        /** The owner file could not be written; it was left exactly as it was. */
        WRITE_FAILED
    }

    /** What happened, and the values that were written, for a caller to report back. */
    public record Result(@Nonnull Outcome outcome, @Nonnull String id, @Nonnull String role,
                         @Nonnull String worldName, double x, double y, double z, double yaw) {

        public boolean ok() {
            return outcome == Outcome.PLACED;
        }
    }

    private NpcPlacementAuthoring() {
    }

    /**
     * Write a fixed-coordinate placement, re-read it, and place it now.
     *
     * <p>REFUSES an id that already names a placement rather than overwriting one: an id collision
     * would silently replace a pack's character with this one, and the only sign in game would be
     * the wrong NPC in the right spot.
     *
     * @param id       the placement id, lower-cased here so the caller need not
     * @param dialogue the conversation press-F opens, or null for that character's quest list
     */
    @Nonnull
    public static Result place(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull String role, @Nonnull String id, @Nullable String dialogue,
            @Nonnull String worldName, double x, double y, double z, double yaw) {

        String key = id.trim().toLowerCase(Locale.ROOT);
        if (NpcPlacementConfig.getInstance().has(key)) {
            return new Result(Outcome.ID_TAKEN, key, role, worldName, x, y, z, yaw);
        }
        if (!isSpawnable(role)) {
            return new Result(Outcome.ROLE_NOT_SPAWNABLE, key, role, worldName, x, y, z, yaw);
        }

        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put(key + ".Identity.Role", role);
        leaves.put(key + ".Where.Match", List.of(worldName));
        leaves.put(key + ".Anchor.Coords.X", x);
        leaves.put(key + ".Anchor.Coords.Y", y);
        leaves.put(key + ".Anchor.Coords.Z", z);
        leaves.put(key + ".Anchor.Coords.Yaw", yaw);
        if (dialogue != null && !dialogue.isBlank()) {
            leaves.put(key + ".Interact.Dialogue", dialogue.trim());
        }

        NpcPlacementOverrides overrides = NpcPlacementOverrides.getInstance();
        if (!JsonOverrideWriter.setLeaves(overrides.getFile(), leaves)) {
            return new Result(Outcome.WRITE_FAILED, key, role, worldName, x, y, z, yaw);
        }

        // Re-read what was written, decode it against the packs, then place it now rather than at
        // the next world entry: the point of doing this in game is seeing the result in game.
        overrides.load();
        overrides.applyOwnerLayer();
        NpcPlacementReconciler.forceSweep(world, store);

        return new Result(Outcome.PLACED, key, role, worldName, x, y, z, yaw);
    }

    /**
     * The roles a placement could actually stand up, for a picker to offer: every registered role
     * that is SPAWNABLE, so the abstract templates other roles are built on are left out - naming
     * one would write a placement that can never appear. Sorted, so a listing is stable.
     *
     * <p>Empty when there is no role registry to ask (a unit JVM, a call before the NPC plugin is
     * up). That reads as "cannot tell", never as "this server has no roles", which is the same
     * convention the name and world lookups already follow.
     */
    @Nonnull
    public static List<String> spawnableRoles() {
        try {
            NPCPlugin npc = NPCPlugin.get();
            if (npc == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>(npc.getRoleTemplateNames(true));
            names.sort(java.util.Comparator.naturalOrder());
            return names;
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not list the spawnable roles: " + t.getMessage());
            return List.of();
        }
    }

    /**
     * Can {@code role} actually be stood up? False for an unknown role AND for an abstract one, both
     * of which produce a placement that never appears with nothing on screen to explain it.
     *
     * <p>Answers TRUE when there is no registry to ask, so a call made before the NPC plugin is up
     * refuses nothing: this is a courtesy check at the moment somebody types a role, not the
     * placement engine's own gate.
     */
    public static boolean isSpawnable(@Nonnull String role) {
        try {
            NPCPlugin npc = NPCPlugin.get();
            if (npc == null) {
                return true;
            }
            npc.validateSpawnableRole(role.trim());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code value} to {@code places} decimals, so the written file reads like something authored. */
    public static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
