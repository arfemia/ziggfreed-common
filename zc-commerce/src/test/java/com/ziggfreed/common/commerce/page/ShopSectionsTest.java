package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a storefront's standing catalogue reads, which is the part a player notices immediately and a
 * refactor breaks silently.
 */
class ShopSectionsTest {

    @Test
    @DisplayName("a storefront's declared category order is exactly what it gets")
    void declaredCategoriesReadInOrder() {
        List<ShopSections.Section> sections = ShopSections.standing(List.of(
                new ShopSections.Entry("potion", "consumables", 0),
                new ShopSections.Entry("axe", "tools", 0),
                new ShopSections.Entry("boost", "boosts", 0)),
                List.of("boosts", "tools", "consumables"));

        assertEquals(List.of("boosts", "tools", "consumables"), idsOf(sections));
    }

    @Test
    @DisplayName("a category nobody declared still reads, alphabetically, after every declared one")
    void undeclaredCategoriesFollowDeclaredOnes() {
        List<ShopSections.Section> sections = ShopSections.standing(List.of(
                new ShopSections.Entry("zeta", "zeta", 0),
                new ShopSections.Entry("alpha", "alpha", 0),
                new ShopSections.Entry("boost", "boosts", 0)),
                List.of("boosts"));

        assertEquals(List.of("boosts", "alpha", "zeta"), idsOf(sections));
    }

    @Test
    @DisplayName("whatever carries no category at all reads last, in its own run")
    void theUncategorisedBucketReadsLast() {
        List<ShopSections.Section> sections = ShopSections.standing(List.of(
                ShopSections.Entry.of("loose"),
                new ShopSections.Entry("boost", "boosts", 0)),
                List.of());

        assertEquals(List.of("boosts", ShopSections.UNCATEGORISED), idsOf(sections));
        assertEquals(List.of("loose"), sections.get(1).offerIds());
    }

    @Test
    @DisplayName("inside a run, an author's own order wins and an id settles the ties")
    void rowsSortByOrderThenId() {
        List<ShopSections.Section> sections = ShopSections.standing(List.of(
                new ShopSections.Entry("third", "shelf", 30),
                new ShopSections.Entry("b_same", "shelf", 10),
                new ShopSections.Entry("a_same", "shelf", 10)),
                List.of());

        assertEquals(List.of("a_same", "b_same", "third"), sections.get(0).offerIds());
    }

    @Test
    @DisplayName("a category is matched however either side wrote it")
    void categoriesMatchCaseInsensitively() {
        List<ShopSections.Section> sections = ShopSections.standing(List.of(
                new ShopSections.Entry("boost", "Boosts", 0),
                new ShopSections.Entry("tool", "TOOLS", 0)),
                List.of("tools", "boosts"));

        assertEquals(List.of("tools", "boosts"), idsOf(sections));
    }

    @Test
    @DisplayName("a surviving selection beats the first row, so a purchase does not scroll you away")
    void selectionSurvivesARefresh() {
        List<String> ids = List.of("first", "second", "third");

        assertEquals("second", ShopSections.select(ids, "second"));
        assertEquals("second", ShopSections.select(ids, "SECOND"));
        assertEquals("first", ShopSections.select(ids, "gone"));
        assertEquals("first", ShopSections.select(ids, null));
        assertNull(ShopSections.select(List.of(), "anything"));
    }

    private static List<String> idsOf(List<ShopSections.Section> sections) {
        List<String> out = new ArrayList<>();
        for (ShopSections.Section section : sections) {
            out.add(section.id());
        }
        return out;
    }
}
