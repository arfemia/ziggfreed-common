package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardEntry;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one way a Records split finds its things on the board ({@link StatSplit}'s groups, read as Records opens):
 * every line of the split's stats some player holds in All Time, each read by a {@link Reading} as one thing's line
 * (and one variant's), the things named and grouped by it, and each group a {@link RecordGroup} shown while it holds
 * a thing. The Bosses tab reads its lines as binding rows and party sizes ({@link StatBoardBossFights}), the Dungeons
 * tab as dungeons and difficulty labels ({@link StatBoardDungeons}); a minigame's tab reads its own.
 *
 * <p>A thing's lines are every recorded line it holds, so its columns fold them all (across runs, sizes, labels);
 * a variant's lines are those of its variant alone. Things sort by id, variants by the reading's order, groups by
 * their {@code Order}; a thing in no group, or in one no file defines, sits in a group with a blank id, offered under
 * the first choice alone.
 */
public final class RecordedThings {

    /** Where one recorded line belongs: its thing's id, and its variant's (null for none). */
    public record Place(@Nonnull String thing, @Nullable String variant) {
    }

    /** How a split reads its things off the lines the board holds. */
    public interface Reading {

        /** The thing (and its variant) recorded line {@code line} belongs to, or null for none. */
        @Nullable
        Place place(@Nonnull String line);

        /** Thing {@code thing}'s name: a bare translation where it can be, since the picker resolves only a key. */
        @Nonnull
        Message name(@Nonnull String thing);

        /** The {@code RecordGroups} id thing {@code thing} sits in, or null for none (the first choice alone). */
        @Nullable
        default String group(@Nonnull String thing) {
            return null;
        }

        /** Variant {@code variant}'s name, painted on a label's {@code TextSpans} (it may bind a typed number). */
        @Nonnull
        Message variantName(@Nonnull String variant);

        /** The order a thing's variants show in; text order unless the reading says otherwise. */
        @Nonnull
        default Comparator<String> variantOrder() {
            return Comparator.naturalOrder();
        }

        /** How many variants a thing must hold to show its row of them (1 shows a lone one, 2 hides it). */
        default int fewestVariants() {
            return 1;
        }
    }

    private RecordedThings() {
    }

    /**
     * The groups of section {@code sectionId}'s split: its stats' (the split's columns, its things' and its groups')
     * lines the board holds in All Time, read by {@code reading}. Empty while the board is not open or the section has
     * no split.
     */
    @Nonnull
    public static List<StatSplit.Group> groups(@Nonnull String sectionId, @Nonnull Reading reading) {
        Leaderboard board = StatBoard.board();
        StatSplit split = StatSectionConfig.getInstance().splitOf(sectionId);
        if (board == null || split == null) {
            return List.of();
        }
        Set<String> stats = new LinkedHashSet<>();
        for (StatSplit.Column column : split.columns()) {
            stats.add(column.stat());
        }
        for (StatSplit.Column column : split.groupColumns()) {
            stats.add(column.stat());
        }
        return groups(recorded(board, stats), reading);
    }

    /** The groups {@code lines} make, each read by {@code reading}: the pure half of {@link #groups(String, Reading)}. */
    @Nonnull
    static List<StatSplit.Group> groups(@Nonnull Collection<String> lines, @Nonnull Reading reading) {
        // Thing id (lower-cased) -> its id as first read, its lines, and each variant's lines.
        Map<String, Gathered> things = new TreeMap<>();
        for (String line : lines) {
            Place place;
            try {
                place = reading.place(line);
            } catch (RuntimeException e) {
                SafeLog.warn("[statboard] a Records split could not read the line '" + line + "', so it is left out", e);
                continue;
            }
            if (place == null || place.thing().isBlank()) {
                continue;
            }
            Gathered thing = things.computeIfAbsent(place.thing().toLowerCase(Locale.ROOT), k -> new Gathered(place.thing()));
            thing.lines.add(line);
            if (place.variant() != null) {
                thing.variants.computeIfAbsent(place.variant(), k -> new LinkedHashSet<>()).add(line);
            }
        }
        // Group id (lower-cased, "" for none) -> its things, in thing order.
        Map<String, List<StatSplit.Thing>> byGroup = new LinkedHashMap<>();
        for (Gathered gathered : things.values()) {
            RecordGroup group = RecordGroupConfig.getInstance().group(reading.group(gathered.id));
            byGroup.computeIfAbsent(group == null ? "" : group.id(), k -> new ArrayList<>())
                    .add(thingOf(gathered, reading));
        }
        List<StatSplit.Group> out = new ArrayList<>();
        for (RecordGroup group : RecordGroupConfig.getInstance().groups()) {
            List<StatSplit.Thing> held = byGroup.remove(group.id());
            if (held != null) {
                out.add(new StatSplit.Group(group.id(), nameOf(group), held));
            }
        }
        List<StatSplit.Thing> loose = byGroup.remove("");
        if (loose != null) {
            out.add(new StatSplit.Group("", Message.raw(""), loose));
        }
        return out;
    }

    /** One thing as the split reads it: its lines, and its variants when it holds enough of them. */
    @Nonnull
    private static StatSplit.Thing thingOf(@Nonnull Gathered gathered, @Nonnull Reading reading) {
        List<StatSplit.Variant> variants = new ArrayList<>();
        if (gathered.variants.size() >= Math.max(1, reading.fewestVariants())) {
            List<String> ids = new ArrayList<>(gathered.variants.keySet());
            ids.sort(reading.variantOrder());
            for (String id : ids) {
                variants.add(new StatSplit.Variant(id, reading.variantName(id), List.copyOf(gathered.variants.get(id))));
            }
        }
        return new StatSplit.Thing(gathered.id, reading.name(gathered.id), List.copyOf(gathered.lines), variants);
    }

    /** A group's segment name: its key as the client resolves it, or its id when it names none. */
    @Nonnull
    private static Message nameOf(@Nonnull RecordGroup group) {
        return group.nameKey() != null ? Message.translation(ContentKeys.resolved(group.nameKey()))
                : Message.raw(group.id());
    }

    /**
     * Every line some player holds of {@code stats} in All Time: the part after the stat's {@code /}, each once
     * however its casing differs between players (a tally key is matched without regard to case, so a column folding
     * two spellings would count one line twice), as first read.
     */
    @Nonnull
    static Collection<String> recorded(@Nonnull Leaderboard board, @Nonnull Collection<String> stats) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String stat : stats) {
            wanted.add(stat.toLowerCase(Locale.ROOT));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (LeaderboardEntry entry : board.forBucket(StatPeriod.ALL_TIME).values()) {
            CounterMap counters = entry.counters;
            if (counters == null) {
                continue;
            }
            for (String key : counters.keys()) {
                int slash = key.indexOf('/');
                if (slash > 0 && slash < key.length() - 1
                        && wanted.contains(key.substring(0, slash).toLowerCase(Locale.ROOT))) {
                    String line = key.substring(slash + 1);
                    out.putIfAbsent(line.toLowerCase(Locale.ROOT), line);
                }
            }
        }
        return out.values();
    }

    /** One thing's lines as they gather. */
    private static final class Gathered {
        final String id;
        final Set<String> lines = new LinkedHashSet<>();
        final Map<String, Set<String>> variants = new LinkedHashMap<>();

        Gathered(@Nonnull String id) {
            this.id = id;
        }
    }
}
