package com.ziggfreed.common.instance.leaderboard.statboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;

/** The key a Records section's tab is named by. */
class StatBoardRecordsTest {

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
