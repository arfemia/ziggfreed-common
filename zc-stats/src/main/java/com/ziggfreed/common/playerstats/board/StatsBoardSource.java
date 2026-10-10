package com.ziggfreed.common.playerstats.board;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.counter.NativeReader;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardSource;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSection;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSectionConfig;
import com.ziggfreed.common.instance.result.ColumnFormat;
import com.ziggfreed.common.playerstats.NativeStats;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.SeasonLines;
import com.ziggfreed.common.playerstats.StatFormat;
import com.ziggfreed.common.playerstats.StatKeys;
import com.ziggfreed.common.playerstats.StatKind;
import com.ziggfreed.common.playerstats.StatsSwitch;
import com.ziggfreed.common.playerstats.asset.StatAsset;
import com.ziggfreed.common.playerstats.asset.StatConfig;

/**
 * What the stat board asks of the store, answered from the stat files: the board runs while the store
 * is switched on, and a section line's column is its stat's own name, number format and direction.
 *
 * <p>A line no stat file defines gets no column, so a zc-only server shows no column for a stat only a
 * consumer counts. A {@code Stat/Thing} line is headed by its stat's name, since a column header is a
 * bare translation and a thing has no name of its own to give it, unless the stat is native and its
 * reader names the thing (a reputation's own name). A {@code Stat/*} line stands for each thing a native
 * stat's reader names now, and as the board opens for a player their native readings are set first. A
 * season page's {@code <event>/*} line (its generated section, {@link PlayerStats#season}) stands for the
 * page's lines, each named by its own key, while the page's owner is switched on; with the owner off they
 * show no column, so the page's section shows no tab.
 */
public final class StatsBoardSource implements StatBoardSource {

    @Override
    public boolean enabled() {
        return StatsSwitch.isOn();
    }

    @Override
    @Nullable
    public StatColumnDef column(@Nonnull String line) {
        StatAsset stat = StatConfig.getInstance().index().statOfKey(line);
        if (stat == null || stat.getId() == null) {
            return seasonColumn(line);
        }
        StatKind kind = stat.kind();
        StatFormat format = stat.format();
        if (kind == null || format == null) {
            return null;
        }
        String nameKey = thingNameKey(stat, line);
        if (nameKey == null) {
            nameKey = stat.nameKey();
        }
        Message label = nameKey == null ? Message.raw(stat.getId()) : Message.translation(ContentKeys.resolved(nameKey));
        return new StatColumnDef(storedKey(stat.getId(), line), label, columnFormat(format), kind == StatKind.MIN);
    }

    /**
     * A native stat's lines: each thing its reader names now, as {@code Stat/Thing}; for a season page's event,
     * its lines in the page's order while their owner is on; none for anything else.
     */
    @Override
    @Nonnull
    public List<String> everyLine(@Nonnull String statId) {
        StatAsset stat = StatConfig.getInstance().index().stat(statId);
        NativeReader reader = NativeStats.readerOf(stat);
        if (reader == null) {
            List<String> lines = new ArrayList<>();
            for (SeasonLines.Line line : SeasonLines.shownLinesOf(statId)) {
                lines.add(line.key());
            }
            return lines;
        }
        List<String> out = new ArrayList<>();
        for (String thing : reader.things()) {
            if (StatKeys.usableThing(thing)) {
                out.add(StatKeys.line(stat.getId(), thing));
            }
        }
        return out;
    }

    /**
     * A season page line's column, while its owner is on: named by the line's own key (resolved for the
     * client), else its name in the record; a count, highest first. Null for anything that is no such line.
     */
    @Nullable
    private static StatColumnDef seasonColumn(@Nonnull String line) {
        SeasonLines.Line season = SeasonLines.shownLine(line);
        if (season == null) {
            return null;
        }
        Message label = season.textKey() == null ? Message.raw(season.lineId())
                : Message.translation(ContentKeys.resolved(season.textKey()));
        return new StatColumnDef(season.key(), label, ColumnFormat.GROUPED, false);
    }

    /** The opener's native readings, set on the board before it opens for them. */
    @Override
    public void refresh(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        PlayerStats.readNative(store, ref);
    }

    /**
     * The name a native stat's reader gives the thing of a {@code Stat/Thing} line (a reputation's own
     * name), so lines of one stat side by side are told apart; null for a total, any other stat, or a thing
     * its reader names nothing for.
     */
    @Nullable
    private static String thingNameKey(@Nonnull StatAsset stat, @Nonnull String line) {
        int slash = line.indexOf(Counters.CATEGORY_SEPARATOR);
        NativeReader reader = slash < 0 ? null : NativeStats.readerOf(stat);
        if (reader == null) {
            return null;
        }
        String key = reader.thingNameKey(line.substring(slash + 1).trim());
        return key == null || key.isBlank() ? null : key.trim();
    }

    /**
     * The key the store files {@code line} under: the stat's own spelling for the total, and that spelling
     * joined to the line's thing for a breakdown line, so a section that writes a stat in another casing
     * still reads the store's tallies.
     */
    @Nonnull
    static String storedKey(@Nonnull String statId, @Nonnull String line) {
        int slash = line.indexOf(Counters.CATEGORY_SEPARATOR);
        return slash < 0 ? StatKeys.total(statId) : StatKeys.line(statId, line.substring(slash + 1));
    }

    /** How a stat's format reads in a column: Distance has no format of its own, so it reads as a count. */
    @Nonnull
    static ColumnFormat columnFormat(@Nonnull StatFormat format) {
        return switch (format) {
            case TIME -> ColumnFormat.TIME;
            case DURATION -> ColumnFormat.DURATION;
            case PERCENT -> ColumnFormat.PERCENT;
            case COUNT, DISTANCE -> ColumnFormat.GROUPED;
        };
    }

    /**
     * Every authored section's lines as written ({@code Stat/*} ones included), by section id, for the stat
     * audit's unknown-stat check; a generated section (a season page's) names lines no stat file defines by
     * design, so it is left out.
     */
    @Nonnull
    public static Map<String, Collection<String>> sectionLines() {
        Map<String, Collection<String>> out = new LinkedHashMap<>();
        List<StatSection> sections = StatSectionConfig.getInstance().authored();
        for (StatSection section : sections) {
            out.put(section.id(), section.lines());
        }
        return out;
    }
}
