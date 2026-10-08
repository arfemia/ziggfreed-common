package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerSection;

/**
 * The book's one state record: every binding carries all of it, so a click decodes back to exactly the
 * screen it came from; an event a pre-redesign binding sent still decodes; and the section rule (a section
 * the player never touched follows its own default) holds without the caller parsing anything.
 */
class BookStateTest {

    /** What the client sends back: the binding's map as JSON, through the page's own codec. */
    @Nonnull
    private static ObjectiveBookEventData decode(@Nonnull Map<String, String> sent) throws IOException {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> e : sent.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(e.getKey()).append("\":\"")
                    .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        json.append('}');
        return ObjectiveBookEventData.CODEC.decodeJson(RawJsonReader.fromJsonString(json.toString()), new ExtraInfo());
    }

    @Nonnull
    private static LedgerSection section(@Nonnull String id, boolean openByDefault) {
        return new LedgerSection(id, Msg.raw(id), List.of(), openByDefault, 40);
    }

    @Test
    void aFreshStateIsTheTabsDefaults() {
        BookState quests = BookState.of(null);
        assertEquals(ObjectiveBookPage.TAB_QUESTS, quests.tab());
        assertEquals(BookState.VIEW_BROWSE, quests.view(), "the journal is one list and page");
        assertEquals(BookState.ALL, quests.category());
        assertEquals(BookState.ALL, quests.status());
        assertEquals(BookState.SORT_DEFAULT, quests.sort(), "the sort's default is a real id, never blank");
        assertEquals("", quests.search());
        assertEquals(BookState.ALL, quests.tag());
        assertNull(quests.selectedId());
        assertTrue(quests.openSections().isEmpty());
        assertFalse(quests.anyFilter());

        BookState achievements = BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS);
        assertEquals(ObjectiveBookPage.TAB_ACHIEVEMENTS, achievements.tab());
        assertEquals(BookState.VIEW_OVERVIEW, achievements.view(), "Achievements opens on its overview");
        assertEquals(ObjectiveBookPage.TAB_QUESTS, BookState.of("nonsense").tab(), "an unknown tab reads as quests");
    }

    @Test
    void everyKeyRoundTripsThroughTheCodec() throws IOException {
        BookState state = new BookState(ObjectiveBookPage.TAB_ACHIEVEMENTS, BookState.VIEW_BROWSE, "Seasons",
                "progress", "az", "ghoul \"breaker\"", "daily", "Ghoul_Breaker_2026",
                Set.of("hallows_eve", "!feats"));

        EventData sent = state.event("select");

        assertEquals("select", sent.events().get(BookState.KEY_ACTION));
        BookState back = BookState.decode(decode(sent.events()));
        assertEquals(state, back, "a binding carries the whole state, and the codec reads all of it back");
    }

    @Test
    void anEmptySelectionAndNoSectionsRoundTripToo() throws IOException {
        BookState state = BookState.of(ObjectiveBookPage.TAB_QUESTS);
        assertEquals(state, BookState.decode(decode(state.event("view").events())));
    }

    @Test
    void anEventFromTheOldBindingsStillDecodes() throws IOException {
        // The pre-redesign book's fullState: no View, no selection key, a blank sort and tag, a subcategory
        // that no longer exists, and the live search field under its @ key.
        ObjectiveBookEventData old = decode(Map.of(
                "Action", "category",
                "Tab", "achievements",
                "Category", "combat",
                "Status", "all",
                "Search", "gho",
                "Tag", "",
                "Subcategory", "melee",
                "Sort", "",
                "Id", "seasons",
                "@SearchInput", "ghoul"));

        BookState state = BookState.decode(old);

        assertEquals(ObjectiveBookPage.TAB_ACHIEVEMENTS, state.tab());
        assertEquals(BookState.VIEW_OVERVIEW, state.view(), "no View key reads as the tab's default view");
        assertEquals("combat", state.category());
        assertEquals(BookState.ALL, state.status());
        assertEquals(BookState.SORT_DEFAULT, state.sort(), "the old blank sort is the default sort");
        assertEquals(BookState.ALL, state.tag(), "the old blank tag is every tag");
        assertEquals("ghoul", state.search(), "the live field is the search truth, as it always was");
        assertNull(state.selectedId());
        assertTrue(state.openSections().isEmpty());
        assertEquals("seasons", old.id, "the action's own payload still rides Id");
    }

    @Test
    void aBareEventDecodesToTheQuestDefaults() throws IOException {
        assertEquals(BookState.of(null), BookState.decode(decode(Map.of("Action", "close"))));
    }

    @Test
    void withersChangeOneThingAndKeepTheRest() {
        BookState base = BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS).withSelected("a1");

        BookState browse = base.withView(BookState.VIEW_STATISTICS);
        assertEquals(BookState.VIEW_STATISTICS, browse.view());
        assertEquals("a1", browse.selectedId(), "a view change keeps the selection");

        BookState filtered = base.withFilters("combat", null, "closest", " ghoul ", null);
        assertEquals("combat", filtered.category());
        assertEquals(BookState.ALL, filtered.status(), "a null filter keeps its value");
        assertEquals("closest", filtered.sort());
        assertEquals("ghoul", filtered.search(), "search text is trimmed");
        assertEquals("a1", filtered.selectedId(), "a filter keeps the selection");
        assertTrue(filtered.anyFilter());

        BookState cleared = filtered.withFilters("", "", "", "", "");
        assertEquals(BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS).withSelected("a1"), cleared,
                "a blank filter is that filter's default");
        assertNull(base.withSelected("  ").selectedId(), "a blank selection is none");
        assertEquals(BookState.VIEW_OVERVIEW, base.withView(" ").view(), "a blank view is the tab's default");
    }

    @Test
    void aSectionTheyNeverTouchedFollowsItsOwnDefault() {
        LedgerSection ready = section("ready", true);
        LedgerSection completed = section("completed", false);
        BookState state = BookState.of(null);
        assertTrue(state.isOpen(ready));
        assertFalse(state.isOpen(completed));

        BookState toggled = state.withSection("completed", true).withSection("ready", false);
        assertTrue(toggled.isOpen(completed), "opened by hand");
        assertFalse(toggled.isOpen(ready), "closed by hand");
        assertEquals(Set.of("completed", "!ready"), toggled.openSections());
        assertTrue(LedgerPainter.isOpen(completed, toggled.openSections())
                && !LedgerPainter.isOpen(ready, toggled.openSections()),
                "the set goes to the list painter as it is: one record of the player's word");

        BookState back = toggled.withSection("completed", false);
        assertFalse(back.isOpen(completed));
        assertEquals(Set.of("!completed", "!ready"), back.openSections(), "the last word on a section wins");
        assertEquals(state, state.withSection("  ", true), "a blank section id changes nothing");
    }

    @Test
    void anOpenWithASelectionLandsWhereThatRowIsShown() {
        BookState achievement = BookState.opening(ObjectiveBookPage.TAB_ACHIEVEMENTS, "ghoul_breaker_2026");
        assertEquals(BookState.VIEW_BROWSE, achievement.view(), "the overview shows no page, so Browse does");
        assertEquals("ghoul_breaker_2026", achievement.selectedId());

        BookState quest = BookState.opening(null, "the_lantern");
        assertEquals(ObjectiveBookPage.TAB_QUESTS, quest.tab());
        assertEquals("the_lantern", quest.selectedId());

        assertEquals(BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS),
                BookState.opening(ObjectiveBookPage.TAB_ACHIEVEMENTS, null), "no selection keeps the overview");
    }

    @Test
    void aFocusedOpenBrowsesItsCategoryWithTheSectionsItIsHanded() {
        BookState state = BookState.browsing(ObjectiveBookPage.TAB_ACHIEVEMENTS, "seasons",
                Set.of("s.seasons.hallows_eve", "!s.seasons.winter"), null);

        assertEquals(ObjectiveBookPage.TAB_ACHIEVEMENTS, state.tab());
        assertEquals(BookState.VIEW_BROWSE, state.view(), "a category reads as a list, never the overview");
        assertEquals("seasons", state.category());
        assertTrue(state.isOpen(section("s.seasons.hallows_eve", false)), "opened by the open itself");
        assertFalse(state.isOpen(section("s.seasons.winter", true)), "closed by the open itself");
        assertTrue(state.isOpen(section("pinned", true)), "a section it names nothing about keeps its default");
    }
}
