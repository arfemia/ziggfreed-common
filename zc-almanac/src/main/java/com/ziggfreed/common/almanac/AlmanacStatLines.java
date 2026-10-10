package com.ziggfreed.common.almanac;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.SeasonLines;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Almanac's stat lines, kept in the player stat store: every season page's lines handed to
 * {@link PlayerStats#season} each time a page layer folds, so a line counts into every player's stat record
 * under the Almanac's own keys ({@link AlmanacKeys#lifetime}, {@link AlmanacKeys#season}), ranks on Records
 * in a section of its page's own, and the server's totals ({@link ServerTallies}) grow by what each count
 * adds. The lines count while the Almanac is on ({@link AlmanacSwitch}), whatever the stat store's own switch
 * says.
 */
public final class AlmanacStatLines {

    /** A page's lines in the order the page shows them: by {@code Order}, then by name. */
    private static final Comparator<AlmanacIndex.Line> PAGE_ORDER =
            Comparator.comparingInt(AlmanacIndex.Line::order).thenComparing(AlmanacIndex.Line::statId);

    private AlmanacStatLines() {
    }

    /**
     * Hand the store every page's lines in place of the last set. Called at the end of each page layer's fold;
     * a store that refuses costs the counting, never the fold, and says so.
     */
    public static void publish(@Nonnull Map<String, AlmanacEntryAsset> pages, @Nonnull AlmanacIndex index) {
        try {
            PlayerStats.season(AlmanacBootstrap.OWNER, pages(pages, index), AlmanacSwitch::isOn,
                    growth -> growth.forEach(ServerTallies.shared()::add));
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the season pages' lines could not be handed to the stat store, so they count"
                    + " nothing until the next load: " + t.getMessage());
        }
    }

    /**
     * Every page holding a usable line, as the store keeps it: its event, its title's key, its order and its
     * lines in the page's order. Pure.
     */
    @Nonnull
    static List<SeasonLines.Page> pages(@Nonnull Map<String, AlmanacEntryAsset> pages, @Nonnull AlmanacIndex index) {
        Map<String, List<AlmanacIndex.Line>> byEvent = new LinkedHashMap<>();
        for (AlmanacIndex.Line line : index.lines()) {
            byEvent.computeIfAbsent(line.eventId(), k -> new ArrayList<>()).add(line);
        }
        Map<String, AlmanacEntryAsset> byId = new TreeMap<>();
        for (Map.Entry<String, AlmanacEntryAsset> page : pages.entrySet()) {
            byId.putIfAbsent(AlmanacKeys.normalize(page.getKey()), page.getValue());
        }
        List<SeasonLines.Page> out = new ArrayList<>();
        for (Map.Entry<String, List<AlmanacIndex.Line>> entry : byEvent.entrySet()) {
            AlmanacEntryAsset page = byId.get(entry.getKey());
            List<AlmanacIndex.Line> ordered = new ArrayList<>(entry.getValue());
            ordered.sort(PAGE_ORDER);
            List<SeasonLines.Line> lines = new ArrayList<>(ordered.size());
            for (AlmanacIndex.Line line : ordered) {
                lines.add(new SeasonLines.Line(line.eventId(), line.statId(), line.def(), line.liveOnly(),
                        line.textKey()));
            }
            out.add(new SeasonLines.Page(entry.getKey(), page == null ? null : page.titleKey(),
                    page == null ? Integer.MAX_VALUE : page.orderOrLast(), lines));
        }
        return out;
    }
}
