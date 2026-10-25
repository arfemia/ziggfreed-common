package com.ziggfreed.common.match;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.builtin.tagset.TagSetPlugin;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.util.SafeLog;

import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * The readers behind a {@link MatchKey} set key: is this id in that item tag, block family or NPC group? One per
 * set form, each with a generation that moves whenever an answer may change, so a table keeping answers per target
 * ({@link MomentTable}) drops them when {@link #generation()} moves.
 *
 * <ul>
 *   <li><b>Item tags</b> and <b>NPC groups</b> read the engine here: an item's (or a block's containing item's)
 *       raw tags, which the engine already expanded and merged with its {@code Parent}'s, and the native NPC
 *       group sets ({@code TagSetPlugin}) over the role's index. Their generations move on an item load or
 *       removal and on an NPC group load, removal or role load, which zc-core's {@code MatchBootstrap} wires.</li>
 *   <li><b>Block families</b> live in zc-world, above this module: the seam {@link #families} is filled at setup
 *       by zc-world's {@code GatherBootstrap}. Until it is, a {@code BlockFamily:} key matches nothing and says so
 *       once.</li>
 * </ul>
 *
 * <p>Every read is guarded: an engine that cannot answer (a unit test, a store not loaded yet) reads as "not in
 * the set", never as an error.
 */
public final class MatchSets {

    /** One set form's reader. */
    public interface Reader {

        /** Is the thing {@code targetId} names (as a moment spelt it) in the set {@code setId} (any case)? */
        boolean inSet(@Nonnull String targetId, @Nonnull String setId);

        /** Does a set {@code setId} exist here: true, false, or null when this reader cannot tell. */
        @Nullable
        default Boolean known(@Nonnull String setId) {
            return null;
        }

        /** A counter that moves whenever an answer may change. Only ever grows. */
        long generation();
    }

    /** A {@link Reader} from its parts (a module filling the seam from its own config). */
    @Nonnull
    public static Reader reader(@Nonnull BiPredicate<String, String> inSet, @Nonnull Predicate<String> known,
            @Nonnull LongSupplier generation) {
        return new Reader() {
            @Override
            public boolean inSet(@Nonnull String targetId, @Nonnull String setId) {
                return inSet.test(targetId, setId);
            }

            @Override
            public Boolean known(@Nonnull String setId) {
                return known.test(setId);
            }

            @Override
            public long generation() {
                return generation.getAsLong();
            }
        };
    }

    private static final AtomicLong ITEMS = new AtomicLong();

    private static final AtomicLong GROUPS = new AtomicLong();

    /** Moves when a reader is filled, so a table kept under the old reader drops its answers. */
    private static final AtomicLong FILLS = new AtomicLong();

    @Nullable
    private static volatile Reader families;

    private static final AtomicBoolean FAMILIES_FALLBACK_REPORTED = new AtomicBoolean();

    private MatchSets() {
    }

    /** Fill the block family reader (zc-world's {@code GatherBootstrap}, at setup). */
    public static void families(@Nullable Reader reader) {
        families = reader;
        FILLS.incrementAndGet();
    }

    /** The item store loaded or dropped items: every item tag answer may read differently. */
    public static void itemsChanged() {
        ITEMS.incrementAndGet();
    }

    /** The NPC groups or the roles they hold were loaded or dropped: every group answer may read differently. */
    public static void npcGroupsChanged() {
        GROUPS.incrementAndGet();
    }

    /** Moves whenever any set answer may change: a sum of counters that only grow. */
    public static long generation() {
        Reader family = families;
        return ITEMS.get() + GROUPS.get() + FILLS.get() + (family == null ? 0L : family.generation());
    }

    /** Is {@code targetId} in {@code form}'s set {@code setId}? False for a name form, a blank side or no reader. */
    public static boolean inSet(@Nonnull MatchKey.Form form, @Nullable String targetId, @Nullable String setId) {
        if (targetId == null || targetId.isEmpty() || setId == null || setId.isEmpty()) {
            return false;
        }
        try {
            return switch (form) {
                case ITEM_TAG -> carriesTag(rawTagsOf(targetId), setId);
                case BLOCK_FAMILY -> {
                    Reader family = familyReader();
                    yield family != null && family.inSet(targetId, setId);
                }
                case NPC_GROUP -> inGroup(targetId, setId);
                case NAME -> false;
            };
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Does {@code key}'s set exist on this server: true, false, or null when that cannot be told (a name key, an
     * item tag, which any item may carry, or a reader that is not filled or not loaded). For an audit's WARNING.
     */
    @Nullable
    public static Boolean known(@Nonnull MatchKey key) {
        if (!key.isSet() || key.set().isEmpty()) {
            return null;
        }
        try {
            return switch (key.form()) {
                case BLOCK_FAMILY -> {
                    Reader family = families;
                    yield family == null ? null : family.known(key.set());
                }
                case NPC_GROUP -> groupKnown(key.set());
                case ITEM_TAG, NAME -> null;
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Does a raw tag map, as the engine keeps it, carry {@code tag}? The engine stores every value and every
     * {@code key=value} pair as a key of its own beside the authored keys, so carrying a tag is having that key,
     * matched without regard to case. Pure: the seam a unit test drives.
     */
    public static boolean carriesTag(@Nullable Map<String, String[]> rawTags, @Nonnull String tag) {
        if (rawTags == null || rawTags.isEmpty()) {
            return false;
        }
        if (rawTags.containsKey(tag)) {
            return true;
        }
        for (String key : rawTags.keySet()) {
            if (key != null && key.equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /** The raw tags of the item {@code id} names, else of the item the block {@code id} lives in; null for none. */
    @Nullable
    private static Map<String, String[]> rawTagsOf(@Nonnull String id) {
        Item item = Item.getAssetMap().getAsset(id);
        if (item == null) {
            BlockType block = BlockType.getAssetMap().getAsset(id);
            item = block == null ? null : block.getItem();
        }
        AssetExtraInfo.Data data = item == null ? null : item.getData();
        return data == null ? null : data.getRawTags();
    }

    @Nullable
    private static Reader familyReader() {
        Reader family = families;
        if (family == null && FAMILIES_FALLBACK_REPORTED.compareAndSet(false, true)) {
            SafeLog.info("[match] no block family reader is wired, so a BlockFamily: key matches nothing");
        }
        return family;
    }

    /** Is the creature whose role is {@code roleName} in the NPC group {@code groupId} (any case)? */
    private static boolean inGroup(@Nonnull String roleName, @Nonnull String groupId) {
        NPCPlugin npcs = NPCPlugin.get();
        if (npcs == null) {
            return false;
        }
        int role = npcs.getIndex(roleName);
        int group = groupIndex(groupId);
        if (role == AssetMapWithIndexes.NOT_FOUND || group == AssetMapWithIndexes.NOT_FOUND) {
            return false;
        }
        IntSet members = TagSetPlugin.get(NPCGroup.class).getSet(group);
        return members != null && members.contains(role);
    }

    @Nullable
    private static Boolean groupKnown(@Nonnull String groupId) {
        IndexedLookupTableAssetMap<String, NPCGroup> groups = NPCGroup.getAssetMap();
        if (groups == null || groups.getAssetMap().isEmpty()) {
            return null;
        }
        return groupIndex(groupId) != AssetMapWithIndexes.NOT_FOUND;
    }

    /** The NPC group's index, its id matched exactly first and then without regard to case. */
    private static int groupIndex(@Nonnull String groupId) {
        IndexedLookupTableAssetMap<String, NPCGroup> groups = NPCGroup.getAssetMap();
        int exact = groups.getIndex(groupId);
        if (exact != AssetMapWithIndexes.NOT_FOUND) {
            return exact;
        }
        for (String id : groups.getAssetMap().keySet()) {
            if (id != null && id.equalsIgnoreCase(groupId)) {
                return groups.getIndex(id);
            }
        }
        return AssetMapWithIndexes.NOT_FOUND;
    }
}
