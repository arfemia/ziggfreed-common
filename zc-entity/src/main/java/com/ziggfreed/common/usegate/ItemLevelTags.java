package com.ziggfreed.common.usegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code ZigLevel} item tag: a level an item asks for, written in the item's own native {@code Tags}, so any
 * mod's item asks for a level with one line in its own file and nothing of anyone else's.
 *
 * <pre>{@code
 * "Tags": { "ZigLevel": ["Mining:30"] }
 * "Tags": { "ZigLevel": ["Swords:20", "Defense:10"], "ZigLevelFeature": ["yourmod:Gear_Levels"] }
 * }</pre>
 *
 * <p>Each {@value #TAG} value is {@code <Track>:<Level>}: the best level the player ever reached on that track
 * ({@code ziggfreedcommon:track_level}, so a skill, a reputation or any track works), a whole number from 1. Every
 * value must be met. The track is named the way its own file names it, without regard to case; the level comes
 * after the LAST colon, so a track id carrying a colon of its own reads too. A value naming a track this server
 * does not run (or has switched off) asks nothing, so an item tagged for a mod that is not installed stays usable.
 *
 * <p>The tag is asked on holding (breaking or hitting with the item in the active hand; never for an armor piece)
 * and on wearing (putting it into an armor slot), as a use gate of the {@value #GROUP} group ({@link UseGates}), so
 * it refuses with the same one line, it is cancelled the same way and gear kept at an update keeps working
 * ({@link UseGateKeeps}). {@value #FEATURE_TAG}, optional, names server switches ({@code <namespace>:<Feature_Id>},
 * as a use gate's {@code Feature}) the tag sits behind: while one is off, or no installed mod declares it, the tag
 * asks nothing, and so it does when none of the values written there reads (a value that does not read is skipped
 * when another one does). An item with no {@value #TAG} is never gated by this, and an item inherits its {@code Parent}'s
 * tags, as the engine merges them.
 *
 * <p>The server owner still decides per item through the use-gate owner layer: a gate of the {@value #GROUP} group
 * whose {@code Held} or {@code Worn} pattern names the item asks instead of its tag on that axis (the authored gate
 * outranks the tag, however broad its pattern), and one switched off there ({@code Enabled: false}) switches the tag
 * off for what it names.
 *
 * <p>The table is read from the engine's own tag index ({@code getKeysForTag}) on the first ask after an item load
 * or removal ({@link #markDirty}), one immutable snapshot behind a volatile field, so an ask takes no lock.
 */
public final class ItemLevelTags {

    /** The item tag that asks for a level. */
    public static final String TAG = "ZigLevel";

    /** The item tag naming the switches {@value #TAG} sits behind. */
    public static final String FEATURE_TAG = "ZigLevelFeature";

    /** The use-gate group the tag competes in, as authored. */
    public static final String GROUP = "ZigLevel";

    /** The group as {@link UseGateAsset#group()} folds it. */
    static final String GROUP_KEY = UseGateAsset.groupKey(GROUP);

    /** A tag gate's id: this prefix, then the item's id. A colon no file name carries, so it names no file. */
    public static final String GATE_ID_PREFIX = "ZigLevel:";

    /** The reading a value asks of the player. */
    public static final String TRACK_LEVEL_FACTOR = "ziggfreedcommon:track_level";

    /** One value: a track and the level asked of it. */
    public record Ask(@Nonnull String track, int level) {
    }

    /** One tagged item, as its tags read. */
    public static final class Tagged {

        @Nonnull private final String itemId;
        @Nonnull private final List<Ask> asks;
        @Nonnull private final List<FeatureLift.Lifted> switches;
        @Nonnull private final List<String> malformed;
        private final boolean armor;
        private final boolean switchUnreadable;
        /** The gate for the asks whose track is usable, under the mask of usable asks it was built for. */
        @Nullable private volatile Built built;

        private record Built(long mask, @Nullable UseGateAsset gate) {
        }

        Tagged(@Nonnull String itemId, @Nonnull List<Ask> asks, @Nonnull List<FeatureLift.Lifted> switches,
                @Nonnull List<String> malformed, boolean armor, boolean switchUnreadable) {
            this.itemId = itemId;
            this.asks = List.copyOf(asks);
            this.switches = List.copyOf(switches);
            this.malformed = List.copyOf(malformed);
            this.armor = armor;
            this.switchUnreadable = switchUnreadable;
        }

        /** The item's id, as the item store spells it. */
        @Nonnull
        public String itemId() {
            return itemId;
        }

        /** Every well-formed value, in authored order. */
        @Nonnull
        public List<Ask> asks() {
            return asks;
        }

        /** The switches {@value #FEATURE_TAG} names, as feature conditions. */
        @Nonnull
        public List<FeatureLift.Lifted> switches() {
            return switches;
        }

        /** The values (of either tag) that could not be read, as written. */
        @Nonnull
        public List<String> malformed() {
            return malformed;
        }

        /** Is the item a piece of armor, which the tag asks for only on wearing? */
        public boolean armor() {
            return armor;
        }

        /**
         * Does the item write {@value #FEATURE_TAG} without one value that reads? Then the tag asks nothing: it was
         * meant to sit behind a switch, and a switch that cannot be read is never on, never "no switch at all".
         */
        public boolean switchUnreadable() {
            return switchUnreadable;
        }

        /** The id a refusal of this item's tag carries. */
        @Nonnull
        public String gateId() {
            return GATE_ID_PREFIX + itemId;
        }

        /**
         * The gate this tag is right now: every value whose track is usable, asked as one {@code Requires}, behind
         * the item's switches. Null when no value names a usable track, or when {@link #switchUnreadable}. Kept
         * while the usable values stand, so an ask reuses one block and its compiled verdicts.
         */
        @Nullable
        public UseGateAsset gate() {
            if (switchUnreadable) {
                return null;
            }
            long mask = 0L;
            for (int i = 0; i < asks.size() && i < Long.SIZE; i++) {
                if (usable(asks.get(i).track())) {
                    mask |= 1L << i;
                }
            }
            Built held = built;
            if (held != null && held.mask() == mask) {
                return held.gate();
            }
            UseGateAsset gate = build(mask);
            built = new Built(mask, gate);
            return gate;
        }

        @Nullable
        private UseGateAsset build(long mask) {
            List<FactorCondition> conditions = new ArrayList<>(asks.size());
            for (int i = 0; i < asks.size() && i < Long.SIZE; i++) {
                if ((mask & (1L << i)) != 0) {
                    Ask ask = asks.get(i);
                    conditions.add(FactorCondition.of(TRACK_LEVEL_FACTOR, ask.track(), (double) ask.level(), null));
                }
            }
            if (conditions.isEmpty()) {
                return null;
            }
            GateSpec requires = GateSpec.of(conditions.toArray(new FactorCondition[0]), null, null, null, null,
                    null, null);
            return UseGateAsset.forTag(gateId(), itemId, requires, switches);
        }
    }

    private static final Object LOCK = new Object();

    @Nonnull
    private static volatile Map<String, Tagged> snapshot = Map.of();

    private static volatile boolean dirty = true;

    /** The seam that says whether a track is usable here; zc-track fills it at setup. Null asks every track. */
    @Nullable
    private static volatile Predicate<String> usableTrack;

    private static final AtomicBoolean FALLBACK_REPORTED = new AtomicBoolean();

    private static final AtomicBoolean BUILD_FAILURE_REPORTED = new AtomicBoolean();

    private ItemLevelTags() {
    }

    /**
     * Name the lookup that says whether a track is usable on this server (zc-track's own, at setup), so a value
     * naming a track nobody runs asks nothing. Until it is set, every value is asked as written.
     */
    public static void trackLookup(@Nullable Predicate<String> usable) {
        usableTrack = usable;
    }

    /** The item store changed: the next ask reads the tags again. */
    public static void markDirty() {
        dirty = true;
    }

    /** Does any loaded item carry {@value #TAG}? The one read a moment pays when nothing is tagged. */
    public static boolean any() {
        return !current().isEmpty();
    }

    /** {@code itemId}'s tag, or null when the item carries none (or is unknown). */
    @Nullable
    public static Tagged of(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        Map<String, Tagged> table = current();
        return table.isEmpty() ? null : table.get(itemId.trim().toLowerCase(Locale.ROOT));
    }

    /** Every tagged item, in no order. */
    @Nonnull
    public static Collection<Tagged> all() {
        return current().values();
    }

    /** Is {@code track} a track this server runs? Every track is, until zc-track names its lookup. */
    static boolean usable(@Nonnull String track) {
        Predicate<String> lookup = usableTrack;
        if (lookup == null) {
            if (FALLBACK_REPORTED.compareAndSet(false, true)) {
                SafeLog.info("[usegate] no track lookup is wired, so every ZigLevel value is asked as written");
            }
            return true;
        }
        try {
            return lookup.test(track);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * One value as the tag writes it, {@code <Track>:<Level>}, or null when it is not that shape: no colon, a blank
     * track, or a level that is not a whole number of 1 or more. The level is what follows the last colon.
     */
    @Nullable
    public static Ask parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int split = trimmed.lastIndexOf(':');
        if (split <= 0 || split >= trimmed.length() - 1) {
            return null;
        }
        String track = trimmed.substring(0, split).trim();
        String level = trimmed.substring(split + 1).trim();
        if (track.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(level);
            return parsed >= 1 ? new Ask(track, parsed) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * An item's tag as its raw tags read ({@code AssetExtraInfo.Data#getRawTags}, its {@code Parent}'s merged in),
     * or null when it carries no {@value #TAG}. Pure: the seam a unit test drives.
     */
    @Nullable
    public static Tagged read(@Nonnull String itemId, @Nullable Map<String, String[]> rawTags, boolean armor) {
        if (rawTags == null) {
            return null;
        }
        String[] values = rawTags.get(TAG);
        if (values == null) {
            return null;
        }
        List<Ask> asks = new ArrayList<>(values.length);
        List<String> malformed = new ArrayList<>();
        for (String value : values) {
            Ask ask = parse(value);
            if (ask != null) {
                asks.add(ask);
            } else if (value != null && !value.isBlank()) {
                malformed.add(TAG + " " + value.trim());
            }
        }
        List<FeatureLift.Lifted> switches = new ArrayList<>();
        boolean namesSwitch = false;
        String[] features = rawTags.get(FEATURE_TAG);
        if (features != null) {
            for (String value : features) {
                FeatureLift.Lifted lifted = UseGateAsset.parseFeature(value);
                if (lifted != null) {
                    switches.add(lifted);
                    namesSwitch = true;
                } else if (value != null && !value.isBlank()) {
                    malformed.add(FEATURE_TAG + " " + value.trim());
                    namesSwitch = true;
                }
            }
        }
        // A switch tag none of whose values reads shuts the tag: dropping it would make the level unconditional.
        return new Tagged(itemId, asks, switches, malformed, armor, namesSwitch && switches.isEmpty());
    }

    @Nonnull
    private static Map<String, Tagged> current() {
        if (dirty) {
            rebuild();
        }
        return snapshot;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            // Cleared before the walk, so a load landing mid-walk marks it again and the next ask reads again.
            dirty = false;
            Map<String, Tagged> built;
            try {
                built = build();
            } catch (Throwable first) {
                // The engine's key view is live: an item load landing mid-copy can throw. Read it once more.
                SafeLog.info("[usegate] reading the ZigLevel item tags failed (" + first
                        + "), so they are read once more");
                try {
                    built = build();
                } catch (Throwable t) {
                    if (BUILD_FAILURE_REPORTED.compareAndSet(false, true)) {
                        SafeLog.warn("[usegate] the ZigLevel item tags could not be read, so no item asks for its "
                                + "level until the next item load", t);
                    }
                    built = Map.of();
                }
            }
            snapshot = built;
        }
    }

    @Nonnull
    private static Map<String, Tagged> build() {
        int tag = AssetRegistry.getTagIndex(TAG);
        if (tag == AssetRegistry.TAG_NOT_FOUND) {
            return Map.of();
        }
        AssetStore<String, Item, DefaultAssetMap<String, Item>> store = Item.getAssetStore();
        DefaultAssetMap<String, Item> items = store == null ? null : store.getAssetMap();
        if (items == null) {
            return Map.of();
        }
        Set<String> keys = items.getKeysForTag(tag);
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Tagged> out = new HashMap<>();
        for (String key : List.copyOf(keys)) {
            Item item = key == null ? null : items.getAsset(key);
            AssetExtraInfo.Data data = item == null ? null : item.getData();
            Tagged tagged = data == null ? null : read(key, data.getRawTags(), item.getArmor() != null);
            if (tagged != null) {
                out.put(key.toLowerCase(Locale.ROOT), tagged);
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
