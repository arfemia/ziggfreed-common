package com.ziggfreed.common.instance.leaderboard.statboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.instance.leaderboard.LeaderboardSplit;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;
import com.ziggfreed.common.instance.result.ColumnFormat;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/** The key a Records section's tab is named by, and what a section's split reads off All Time. */
class StatBoardRecordsTest {

    /**
     * A split over a gauge reads All Time alone, unless it names what it ranks on every other period: then it is not
     * pinned, and off All Time its first choice reads the periodic stat's total and then the section's columns that
     * count into a window, a group the periodic lines summed, a pick its own line, each with the meta figure read the
     * same way. Its All Time columns stay as they were.
     */
    @Test
    void aSplitNamingPeriodicStatsRanksThemOffAllTimeWithTheMetaBesideEachName() {
        StatBoardSource source = new StatBoardSource() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public StatColumnDef column(@Nonnull String line) {
                return line.startsWith("Uncounted") ? null : columnOf(line);
            }

            @Override
            public boolean allTimeOnly(@Nonnull String statId) {
                return statId.toLowerCase(Locale.ROOT).equals("level");
            }
        };
        StatSplit gauges = new StatSplit(List.of(new StatSplit.Column("Level", Message.raw("level"), 1L)),
                Message.raw("all"), Message.raw("every skill"),
                () -> List.of(new StatSplit.Group("Gathering", Message.raw("gathering"),
                        List.of(new StatSplit.Thing("MINING", Message.raw("mining")),
                                new StatSplit.Thing("WOODCUTTING", Message.raw("woodcutting"))))),
                LeaderboardSplit.EVERYONE);
        List<StatColumnDef> own = List.of(columnOf("Level"), columnOf("Casts"));

        LeaderboardSplit pinned = StatBoardRecords.split(gauges, source, "All_Time", own);
        assertNotNull(pinned);
        assertEquals("All_Time", pinned.pinnedTab(), "a split over gauges alone reads All Time alone");
        assertNull(pinned.periods());
        assertNull(StatBoardRecords.split(gauges.offAllTime(List.of(new StatSplit.Column("Uncounted", Message.raw("x"),
                0L)), null), source, "All_Time", own).periods(), "a periodic stat nothing counts changes nothing");

        LeaderboardSplit split = StatBoardRecords.split(gauges.offAllTime(
                List.of(new StatSplit.Column("Gained", Message.raw("gained"), 0L)),
                new StatSplit.Meta("Level", 1L, level -> Message.raw("level"))), source, "All_Time", own);
        assertNotNull(split);
        assertNull(split.pinnedTab(), "a split with a view of its own for a window keeps the period row");
        LeaderboardSplit.Periods periods = split.periods();
        assertNotNull(periods);
        assertEquals("All_Time", periods.allTimeTab());
        assertEquals(List.of("Gained", "Casts"), keys(periods.first().columns()),
                "the first choice reads the periodic total, then the section's columns a window counts, never a gauge");
        assertEquals("Level", periods.first().meta().statKey());

        LeaderboardSplit.Choice gathering = split.choices().get(0);
        assertEquals(List.of("Level+Gathering"), keys(gathering.columns()), "All Time reads as it did");
        StatColumnDef gained = gathering.periodic().columns().get(0);
        assertEquals("Gained+Gathering", gained.statKey());
        assertEquals(List.of("Gained/MINING", "Gained/WOODCUTTING"), gained.parts());
        assertEquals(0L, gained.partsOver());
        StatColumnDef level = gathering.periodic().meta();
        assertEquals(List.of("Level/MINING", "Level/WOODCUTTING"), level.parts());
        assertEquals(1L, level.partsOver(), "a group's figure counts its things as its level column does");

        LeaderboardSplit.Pick mining = gathering.picks().get(0);
        assertEquals(List.of("Level/MINING"), keys(mining.columns()));
        assertEquals(List.of("Gained/MINING"), keys(mining.periodic().columns()));
        assertEquals("Level/MINING", mining.periodic().meta().statKey());
    }

    @Nonnull
    private static StatColumnDef columnOf(@Nonnull String line) {
        return new StatColumnDef(line, Message.raw(line), ColumnFormat.GROUPED, false);
    }

    @Nonnull
    private static List<String> keys(@Nonnull List<StatColumnDef> columns) {
        List<String> out = new ArrayList<>();
        for (StatColumnDef column : columns) {
            out.add(column.statKey());
        }
        return out;
    }

    /**
     * A section generated for a season page that names no title takes its event's title from the calendar, read as
     * the page builds, so one generated before the calendar loaded its events is named once the calendar has it. The
     * section's own NameKey always wins, and a section file, which belongs to no event, never asks the calendar.
     */
    @Test
    void aGeneratedSectionNamingNoTitleTakesItsEventsTitleFromTheCalendarAsThePageBuilds() {
        Map<String, String> titles = new HashMap<>();
        OccurrenceSource calendar = new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String eventId) {
                return false;
            }

            @Override
            @Nullable
            public Occurrence live(@Nonnull String eventId, long nowMs) {
                return null;
            }

            @Override
            @Nonnull
            public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
                return List.of();
            }

            @Override
            @Nullable
            public String titleKey(@Nonnull String eventId) {
                return titles.get(eventId);
            }
        };
        StatSection untitled = new StatSection("season_hallows_eve", null, 1010, List.of("hallows_eve/*"), "hallows_eve");

        assertNull(StatBoardRecords.sectionNameKey(untitled, calendar),
                "before the calendar knows the event, the tab shows the section's id");
        titles.put("hallows_eve", "calendar.hallows_eve.title");
        titles.put("combat", "calendar.combat.title");
        assertEquals("calendar.hallows_eve.title", StatBoardRecords.sectionNameKey(untitled, calendar),
                "once it does, the event's title names the tab");
        assertEquals("almanac.hallows_eve.title", StatBoardRecords.sectionNameKey(new StatSection("season_hallows_eve",
                "almanac.hallows_eve.title", 1010, List.of("hallows_eve/*"), "hallows_eve"), calendar),
                "the page's own title wins");
        assertNull(StatBoardRecords.sectionNameKey(new StatSection("combat", null, 10, List.of("Mob_Kills")), calendar),
                "a section file belongs to no event, so its tab shows its id");
    }
}
