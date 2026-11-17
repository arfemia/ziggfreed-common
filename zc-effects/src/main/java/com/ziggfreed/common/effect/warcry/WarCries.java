package com.ziggfreed.common.effect.warcry;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.common.util.SafeLog;

/**
 * The war cry (R690, R1057): an effect that makes the creature wearing it rally the foes round it. Any effect whose own
 * native {@code Tags} carry {@value #TAG} is a war cry, the tag naming the effect it rallies them with (R751: the effect
 * itself says what it does, so a pack's omen, a Mob Scaling affix or a boss's own role opts a creature in with one
 * line):
 *
 * <pre>{@code
 * "Tags": { "ZigWarCry": ["Zc_Rallied"], "ZigWarCryShout": ["Zc_War_Cry_Shout"],
 *           "ZigWarCryRadius": ["10"], "ZigWarCryEvery": ["18", "25"], "ZigWarCryDelay": ["1"] }
 * }</pre>
 *
 * <p>While a player is near, the wearer shouts (the {@value #TAG_SHOUT} effect on it for the delay: its tell), then
 * every creature within the radius that is hostile to players, the wearer too, takes the rally effect; and it shouts
 * again every so many seconds. A creature never shouts while another cry nearby is still in force, so a pack rallies as
 * one, once at a time. The numbers left out are the game's own goblin war cry's: 10 blocks, every 18 to 25 seconds,
 * a one-second shout. Read from the effect files as they load ({@link WarCrySystem} asks); nothing else is kept.
 */
public final class WarCries {

    /** The tag that makes an effect a war cry, naming the effect it rallies with. */
    public static final String TAG = "ZigWarCry";

    /** The effect the wearer shows as it shouts, for the delay. */
    public static final String TAG_SHOUT = "ZigWarCryShout";

    /** How far the cry reaches, in blocks. */
    public static final String TAG_RADIUS = "ZigWarCryRadius";

    /** The seconds between one shout and the next, a range of two numbers or one. */
    public static final String TAG_EVERY = "ZigWarCryEvery";

    /** The seconds from the shout to the rally. */
    public static final String TAG_DELAY = "ZigWarCryDelay";

    /** The game's own war cry's numbers, for a tag left out. */
    public static final double DEFAULT_RADIUS = 10.0;
    public static final double DEFAULT_EVERY_MIN = 18.0;
    public static final double DEFAULT_EVERY_MAX = 25.0;
    public static final double DEFAULT_DELAY = 1.0;

    /**
     * One war cry.
     *
     * @param rally    the effect the cry puts on the creatures it reaches
     * @param shout    the effect the wearer shows as it shouts; null for none
     * @param radius   how far it reaches, in blocks
     * @param everyMin the least seconds between two shouts
     * @param everyMax the most seconds between two shouts
     * @param delay    the seconds from the shout to the rally
     */
    public record Cry(@Nonnull String rally, @Nullable String shout, double radius, double everyMin, double everyMax,
                      double delay) {
    }

    private static final Object LOCK = new Object();
    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    /** The cries by effect index; an index with none answers null. */
    @Nonnull
    private static volatile Cry[] table = new Cry[0];

    private static volatile boolean dirty = true;

    private WarCries() {
    }

    /** An effect load or removal: the table is read again on its next use. */
    static void markDirty() {
        dirty = true;
    }

    /** The war cry the effect at {@code index} carries, or null. */
    @Nullable
    static Cry at(int index) {
        Cry[] now = current();
        return index >= 0 && index < now.length ? now[index] : null;
    }

    /** Whether any loaded effect is a war cry: a server with none reads no creature's effects. */
    static boolean any() {
        return current().length > 0;
    }

    /**
     * The war cry an effect's raw native tags describe, or null when they carry no {@value #TAG} naming an effect.
     * Pure over the tags: a number that does not read keeps its default, and the range is put in order.
     */
    @Nullable
    public static Cry of(@Nullable Map<String, String[]> rawTags) {
        String rally = first(rawTags, TAG);
        if (rally == null) {
            return null;
        }
        double radius = number(first(rawTags, TAG_RADIUS), DEFAULT_RADIUS);
        String[] every = values(rawTags, TAG_EVERY);
        double min = every != null && every.length > 0 ? number(every[0], DEFAULT_EVERY_MIN) : DEFAULT_EVERY_MIN;
        double max = every != null && every.length > 1 ? number(every[1], min)
                : every != null && every.length == 1 ? min : DEFAULT_EVERY_MAX;
        double delay = Math.max(0.0, number(first(rawTags, TAG_DELAY), DEFAULT_DELAY));
        return new Cry(rally, first(rawTags, TAG_SHOUT), Math.max(1.0, radius), Math.max(1.0, Math.min(min, max)),
                Math.max(1.0, Math.max(min, max)), delay);
    }

    @Nonnull
    private static Cry[] current() {
        if (dirty) {
            rebuild();
        }
        return table;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            dirty = false;
            try {
                table = build();
            } catch (Throwable t) {
                if (BUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn("[warcry] could not read which effects are war cries; none is until the next effect "
                            + "load", t);
                }
                table = new Cry[0];
            }
        }
    }

    @Nonnull
    private static Cry[] build() {
        IndexedLookupTableAssetMap<String, EntityEffect> map = EntityEffect.getAssetMap();
        int end = Math.max(0, map.getNextIndex());
        Map<Integer, Cry> found = new HashMap<>();
        for (int index = 0; index < end; index++) {
            EntityEffect effect = map.getAsset(index);
            if (effect == null) {
                continue;
            }
            AssetExtraInfo.Data data = EntityEffect.CODEC.getData(effect);
            Cry cry = of(data == null ? null : data.getRawTags());
            if (cry != null) {
                found.put(index, cry);
            }
        }
        if (found.isEmpty()) {
            return new Cry[0];
        }
        Cry[] out = new Cry[end];
        for (Map.Entry<Integer, Cry> entry : found.entrySet()) {
            out[entry.getKey()] = entry.getValue();
        }
        return out;
    }

    @Nullable
    private static String first(@Nullable Map<String, String[]> rawTags, @Nonnull String key) {
        String[] values = values(rawTags, key);
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    @Nullable
    private static String[] values(@Nullable Map<String, String[]> rawTags, @Nonnull String key) {
        if (rawTags == null) {
            return null;
        }
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(key.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static double number(@Nullable String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
