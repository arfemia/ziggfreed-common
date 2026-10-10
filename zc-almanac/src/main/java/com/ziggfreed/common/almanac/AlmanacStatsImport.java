package com.ziggfreed.common.almanac;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.StatsView;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Almanac's stat-line tallies moved into the player stat store once per player, and the page's read of
 * them after. The import copies, claimed as {@value #CLAIM}: every {@code <event>/<stat>} tally of the
 * player's Almanac record into the store's {@code Tallies} and every {@code <event>@<year>/<stat>} into its
 * {@code Seasons}, keys and values as they are, in the store's one guarded swap. Every {@code $} key
 * ({@code $attended}, {@code $runs}, {@code $owned}) is the Almanac's own record, never a stat, and stays where it
 * is. The Almanac record is never written: the old line keys freeze, unread once the claim is held, so an older
 * build finds them as it left them.
 *
 * <p>It runs as the player is ready, at FIRST priority (ahead of the store's NORMAL re-sync of the board, which
 * then ranks the copy), whatever the Almanac's or the store's switch says, since it moves saved data rather than
 * counting anything. A throw claims nothing, so the next login tries again; it never adds to the server's totals,
 * which counted these when they happened.
 */
public final class AlmanacStatsImport {

    /** The import's claim in the player's stat record. */
    public static final String CLAIM = "ziggfreedcommon:almanac@1";

    /** The two bags the Almanac record's stat lines go to. */
    public record Split(@Nonnull Map<String, Long> lifetime, @Nonnull Map<String, Long> seasons) {
    }

    private AlmanacStatsImport() {
    }

    /** The ready pass, at FIRST priority: the import runs as a task on the player's world. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player == null ? null : player.getWorld();
            Ref<EntityStore> ref = event.getPlayerRef();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> {
                try {
                    if (ref.isValid()) {
                        importOnce(ref.getStore(), ref);
                    }
                } catch (Throwable t) {
                    SafeLog.warn("[almanac] a player's Almanac tallies could not be moved into the stat store; the"
                            + " next login tries again", t);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[almanac] a player's Almanac tallies could not be moved into the stat store", t);
        }
    }

    /**
     * Copy the player's Almanac stat lines into their stat record once. Nothing happens for a player with no
     * Almanac record or no stat record, or once the claim is held. World thread.
     *
     * @return true when this call made the copy
     */
    static boolean importOnce(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (PlayerStats.hasImported(store, ref, CLAIM)) {
            return false;
        }
        ComponentType<EntityStore, AlmanacComponent> type = AlmanacComponent.TYPE;
        AlmanacComponent record = type == null ? null : store.getComponent(ref, type);
        if (record == null || record.tallies == null) {
            return false;
        }
        Split split = split(record.tallies.all());
        return PlayerStats.importOnce(store, ref, CLAIM, split.lifetime(), split.seasons());
    }

    /**
     * The Almanac bag's stat lines, by key shape: {@code <event>/<stat>} to the lifetime bag and
     * {@code <event>@<year>/<stat>} to the season-year bag, keys and values as they are. A {@code $} key on
     * either side of the {@code /}, and a key of no Almanac shape, goes to neither. Pure.
     */
    @Nonnull
    public static Split split(@Nonnull Map<String, Long> almanac) {
        Map<String, Long> lifetime = new LinkedHashMap<>();
        Map<String, Long> seasons = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : almanac.entrySet()) {
            String key = entry.getKey();
            Long value = entry.getValue();
            if (key == null || value == null || value <= 0L || !isStatLine(key)) {
                continue;
            }
            if (key.substring(0, key.indexOf(Counters.CATEGORY_SEPARATOR)).contains(AlmanacKeys.YEAR_MARK)) {
                seasons.put(key, value);
            } else {
                lifetime.put(key, value);
            }
        }
        return new Split(lifetime, seasons);
    }

    /**
     * What the page reads for the player: their Almanac record's own {@code $} keys and, from their stat record,
     * the tallies of every page line now loaded. While the claim is not yet held (the stat store not there, or the
     * player not yet ready), the whole Almanac record as it stands. A copy: a write to it reaches neither record.
     * World thread.
     */
    @Nonnull
    public static CounterMap read(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        CounterMap almanac = AlmanacComponent.talliesOf(store, ref);
        if (!PlayerStats.hasImported(store, ref, CLAIM)) {
            return almanac.copy();
        }
        StatsView stats = PlayerStats.view(store, ref);
        return merged(almanac.all(), stats.all(), stats.seasons(), AlmanacEntryConfig.getInstance().index().lines());
    }

    /**
     * The page's bag once the claim is held: {@code almanac}'s {@code $} keys, then of {@code tallies} and
     * {@code seasons} the every-season and season-year keys of {@code lines}, so a stat's own season copy never
     * reads as a page's year. Pure.
     */
    @Nonnull
    static CounterMap merged(@Nonnull Map<String, Long> almanac, @Nonnull Map<String, Long> tallies,
            @Nonnull Map<String, Long> seasons, @Nonnull Collection<AlmanacIndex.Line> lines) {
        Set<String> paged = new HashSet<>();
        for (AlmanacIndex.Line line : lines) {
            paged.add(AlmanacKeys.lifetime(line.eventId(), line.statId()));
        }
        CounterMap out = new CounterMap();
        for (Map.Entry<String, Long> entry : almanac.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && isRecord(entry.getKey())) {
                out.set(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Long> entry : tallies.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && paged.contains(fold(entry.getKey()))) {
                out.set(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Long> entry : seasons.entrySet()) {
            String line = entry.getKey() == null ? null : lineOfSeasonKey(entry.getKey());
            if (line != null && entry.getValue() != null && paged.contains(line)) {
                out.set(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** Is {@code key} a stat line's tally: {@code <category>/<stat>} with neither half blank nor starting with {@code $}? */
    private static boolean isStatLine(@Nonnull String key) {
        int slash = key.indexOf(Counters.CATEGORY_SEPARATOR);
        if (slash <= 0 || slash == key.length() - 1) {
            return false;
        }
        String category = key.substring(0, slash).trim();
        String stat = key.substring(slash + 1).trim();
        return !category.isEmpty() && !stat.isEmpty() && !category.startsWith("$") && !stat.startsWith("$");
    }

    /** Is {@code key} one of the Almanac's own records, a {@code $} key on either side of the {@code /}? */
    private static boolean isRecord(@Nonnull String key) {
        int slash = key.indexOf(Counters.CATEGORY_SEPARATOR);
        return slash > 0 && (key.trim().startsWith("$") || key.substring(slash + 1).trim().startsWith("$"));
    }

    /** {@code <event>/<stat>} for a season-year key {@code <event>@<year>/<stat>}, lower-cased; null for any other. */
    @Nullable
    private static String lineOfSeasonKey(@Nonnull String key) {
        int slash = key.indexOf(Counters.CATEGORY_SEPARATOR);
        int mark = slash < 0 ? -1 : key.lastIndexOf(AlmanacKeys.YEAR_MARK, slash);
        if (mark <= 0) {
            return null;
        }
        return fold(key.substring(0, mark) + Counters.CATEGORY_SEPARATOR + key.substring(slash + 1));
    }

    @Nonnull
    private static String fold(@Nonnull String key) {
        return AlmanacKeys.normalize(key);
    }
}
