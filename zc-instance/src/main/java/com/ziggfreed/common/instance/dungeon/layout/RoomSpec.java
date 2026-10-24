package com.ziggfreed.common.instance.dungeon.layout;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One room the generator can place, as its file reads once folded (the engine-free view of a
 * {@code DungeonRooms} file). Every id, role, tag and need is folded ({@link Words}).
 *
 * @param id             the room's id
 * @param prefab         the prefab or pool it pastes, in the engine's dot form under {@code Server/Prefabs/}; a
 *                       trailing {@code .*} is every prefab in that folder, each an interchangeable variant
 * @param box            the cells it covers relative to its prefab anchor, unrotated, covering every variant
 * @param doors          its door sockets, in the order written
 * @param roles          the beats it can fill ({@code entry}, {@code combat}, {@code cap}, ...)
 * @param tags           its tags as the engine expands them (each key, each value and each {@code key=value})
 * @param weight         how likely it is picked among the rooms that fit; 0 or less never picks it
 * @param depthMin       the least fraction of the run's path it may stand at, 0 to 1
 * @param depthMax       the most fraction of the run's path it may stand at, 0 to 1
 * @param needs          the roles a room placed earlier, on a branch, must fill before this one
 * @param keepMarkers    whether the painter keeps the prefab's own spawn markers
 * @param keepContainers whether the painter keeps the prefab's own containers
 * @param fill           the block the painter fills the cells its prefab leaves unlisted inside {@code box} with, as
 *                       written (a block id is the engine's, never folded); null for its stratum's, else none. The
 *                       generator only passes it on ({@link PlacedRoom#fill()}).
 */
public record RoomSpec(@Nonnull String id, @Nonnull String prefab, @Nonnull Box box, @Nonnull List<DoorSpec> doors,
                       @Nonnull List<String> roles, @Nonnull List<String> tags, double weight, double depthMin,
                       double depthMax, @Nonnull List<String> needs, boolean keepMarkers, boolean keepContainers,
                       @Nullable String fill) {

    /** The role of a room that closes one open door. */
    public static final String CAP_ROLE = "cap";

    /** The suffix that makes a prefab name a pool of every prefab in its folder. */
    public static final String POOL_SUFFIX = ".*";

    public RoomSpec {
        String folded = Words.fold(id);
        id = folded == null ? "" : folded;
        prefab = prefab == null ? "" : prefab.trim();
        doors = doors == null ? List.of() : List.copyOf(doors);
        roles = Words.foldAll(roles);
        tags = Words.foldAll(tags);
        needs = Words.foldAll(needs);
        weight = Double.isNaN(weight) ? 0 : Math.max(0, weight);
        depthMin = clamp01(depthMin, 0);
        depthMax = clamp01(depthMax, 1);
        fill = Words.trimmed(fill);
    }

    /** Whether its prefab names a pool ({@code Folder.*}) rather than one prefab. */
    public boolean isPool() {
        return prefab.endsWith(POOL_SUFFIX);
    }

    public boolean hasRole(@Nullable String role) {
        String folded = Words.fold(role);
        return folded != null && roles.contains(folded);
    }

    public boolean carries(@Nullable String tag) {
        String folded = Words.fold(tag);
        return folded != null && tags.contains(folded);
    }

    /** Whether it carries every tag listed (folded tags). */
    public boolean carriesAll(@Nonnull Collection<String> folded) {
        return tags.containsAll(folded);
    }

    /** Whether it may stand at {@code depth}, a fraction of the run's path. */
    public boolean fitsDepth(double depth) {
        return depth >= depthMin - 1e-9 && depth <= depthMax + 1e-9;
    }

    /** Whether it is a cap: role {@code cap} with exactly one usable door. */
    public boolean isCap() {
        return hasRole(CAP_ROLE) && usableDoorCount() == 1;
    }

    /** How many of its doors have a facing. */
    public int usableDoorCount() {
        int n = 0;
        for (DoorSpec door : doors) {
            if (door.usable()) {
                n++;
            }
        }
        return n;
    }

    private static double clamp01(double value, double fallback) {
        return Double.isNaN(value) ? fallback : Math.max(0, Math.min(1, value));
    }
}
