package com.ziggfreed.common.playerstats.board;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardSource;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSection;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSectionConfig;
import com.ziggfreed.common.instance.result.ColumnFormat;
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
 * bare translation and a thing has no name of its own to give it.
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
            return null;
        }
        StatKind kind = stat.kind();
        StatFormat format = stat.format();
        if (kind == null || format == null) {
            return null;
        }
        String nameKey = stat.nameKey();
        Message label = nameKey == null ? Message.raw(stat.getId()) : Message.translation(ContentKeys.resolved(nameKey));
        return new StatColumnDef(storedKey(stat.getId(), line), label, columnFormat(format), kind == StatKind.MIN);
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
            case PERCENT -> ColumnFormat.PERCENT;
            case COUNT, DISTANCE -> ColumnFormat.GROUPED;
        };
    }

    /** Every section's lines, by section id, for the stat audit's unknown-stat check. */
    @Nonnull
    public static Map<String, Collection<String>> sectionLines() {
        Map<String, Collection<String>> out = new LinkedHashMap<>();
        List<StatSection> sections = StatSectionConfig.getInstance().sections();
        for (StatSection section : sections) {
            out.put(section.id(), section.lines());
        }
        return out;
    }
}
