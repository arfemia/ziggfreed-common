package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * What the rail lists and where {@code /ziggui} lands: the consumer's section above the library's slots in
 * their fixed order (the Almanac first), an unfilled slot or a hidden entry drawing nothing, a rule that
 * throws hiding only its own entry, a tab's second line asked for the viewer on every paint, and the landing
 * falling from the consumer's to the first visible slot to nothing.
 */
class ZigMenuTest {

    /** A screen that opens, counting each open. */
    static final class Probe extends Destination {
        static final BuilderCodec<Probe> CODEC = BuilderCodec.builder(Probe.class, Probe::new).build();
    }

    /** A screen whose handler declines. */
    static final class Refusing extends Destination {
        static final BuilderCodec<Refusing> CODEC = BuilderCodec.builder(Refusing.class, Refusing::new).build();
    }

    static final AtomicInteger OPENED = new AtomicInteger();
    static final DestinationContext VIEWER = new DestinationContext(null, null, null, null, null, null);

    @BeforeEach
    void seed() {
        ZigMenu.clearForTests();
        Destinations.clearForTests();
        OPENED.set(0);
        Destinations.register("test", DestinationType.of("Test_Probe", Probe.class, Probe.CODEC,
                (d, ctx) -> OPENED.incrementAndGet() > 0));
        Destinations.register("test", DestinationType.of("Test_Refusing", Refusing.class, Refusing.CODEC,
                (d, ctx) -> false));
    }

    @AfterEach
    void clear() {
        ZigMenu.clearForTests();
        Destinations.clearForTests();
    }

    static MenuEntry entry(String id, boolean visible) {
        return new MenuEntry(id, Message.raw(id), null, new Probe(), viewer -> visible);
    }

    static MenuEntry slot(MenuSlot slot, boolean visible) {
        return slot.entry(Message.raw(slot.id()), null, new Probe(), viewer -> visible);
    }

    static List<String> ids(List<MenuRow> rows) {
        return rows.stream().map(row -> switch (row.kind()) {
            case HEADER -> "header";
            case SPACER -> "spacer";
            case ENTRY -> row.entry().id();
        }).toList();
    }

    @Test
    void theConsumerSeamIsEmptyUntilAConsumerFillsIt() {
        assertSame(MenuDeps.EMPTY, ZigMenu.resolved());
        assertNull(MenuDeps.EMPTY.section());
        assertNull(MenuDeps.EMPTY.landing());
        assertNull(MenuDeps.EMPTY.theme(), "with no consumer paint, the library paints the frame from the palette");
        assertEquals(MenuPalette.resolve(null), MenuPalette.resolve(MenuDeps.EMPTY.palette()), "the default palette");
        assertEquals(MenuPalette.resolve(null), MenuPalette.resolve(MenuDeps.builder().palette(null).build().palette()));

        MenuDeps mine = MenuDeps.builder().landing(new Probe()).build();
        ZigMenu.consumer(() -> mine);
        assertSame(mine, ZigMenu.resolved());

        ZigMenu.consumer(() -> {
            throw new IllegalStateException("boom");
        });
        assertSame(MenuDeps.EMPTY, ZigMenu.resolved(), "a consumer that throws costs its section, never the menu");
        ZigMenu.consumer(() -> null);
        assertSame(MenuDeps.EMPTY, ZigMenu.resolved());
    }

    @Test
    void theConsumerSectionSitsAboveTheSlotsAndTheSlotsKeepTheirOrder() {
        ZigMenu.fill(MenuSlot.RECORDS, slot(MenuSlot.RECORDS, true));
        ZigMenu.fill(MenuSlot.QUESTS, slot(MenuSlot.QUESTS, true));
        MenuDeps deps = MenuDeps.builder()
                .section(new MenuSection(Message.raw("Skills"), List.of(entry("a", true), entry("b", true))))
                .build();

        assertEquals(List.of("header", "a", "b", "spacer", "quests", "records"),
                ids(ZigMenu.rows(deps, ZigMenu.slots(), VIEWER)));
    }

    /** M484: the Almanac tab sits above Quests and Achievements; Records and the rest keep their order below. */
    @Test
    void theAlmanacSitsAboveQuestsAndAchievements() {
        for (MenuSlot slot : MenuSlot.values()) {
            ZigMenu.fill(slot, slot(slot, true));
        }

        assertEquals(List.of("almanac", "quests", "achievements", "records", "reputation", "settings"),
                ids(ZigMenu.rows(MenuDeps.EMPTY, ZigMenu.slots(), VIEWER)));
    }

    /** M485: a tab's second line is asked for the player looking, on every paint; a tab without one has none. */
    @Test
    void aTabsSecondLineIsAskedForTheViewerOnEveryPaint() {
        MenuSubline season = new MenuSubline(Message.raw("Hallow's Eve"), IconSpec.ofItem("Test_Icon"));
        AtomicInteger asked = new AtomicInteger();
        ZigMenu.fill(MenuSlot.ALMANAC, slot(MenuSlot.ALMANAC, true).withSubline(viewer -> {
            asked.incrementAndGet();
            return season;
        }));
        ZigMenu.fill(MenuSlot.QUESTS, slot(MenuSlot.QUESTS, true));
        ZigMenu.fill(MenuSlot.ACHIEVEMENTS, slot(MenuSlot.ACHIEVEMENTS, true).withSubline(viewer -> null));

        List<MenuRow> rows = ZigMenu.rows(MenuDeps.EMPTY, ZigMenu.slots(), VIEWER);
        assertEquals(List.of("almanac", "quests", "achievements"), ids(rows));
        assertSame(season, rows.get(0).subline(), "the tab carries the line it was given for this paint");
        assertNull(rows.get(1).subline(), "a tab that never asks for a line has none");
        assertNull(rows.get(2).subline(), "a line that says nothing right now draws no second line");
        ZigMenu.rows(MenuDeps.EMPTY, ZigMenu.slots(), VIEWER);
        assertEquals(2, asked.get(), "asked again on the next paint, so a season that starts or ends shows at once");
    }

    @Test
    void aSecondLineThatThrowsCostsOnlyTheLine() {
        ZigMenu.fill(MenuSlot.ALMANAC, slot(MenuSlot.ALMANAC, true).withSubline(viewer -> {
            throw new IllegalStateException("boom");
        }));

        List<MenuRow> rows = ZigMenu.rows(MenuDeps.EMPTY, ZigMenu.slots(), VIEWER);
        assertEquals(List.of("almanac"), ids(rows), "the tab itself still shows");
        assertNull(rows.get(0).subline());
    }

    @Test
    void aHiddenTabsLineIsNeverAsked() {
        AtomicInteger asked = new AtomicInteger();
        ZigMenu.fill(MenuSlot.ALMANAC, slot(MenuSlot.ALMANAC, false).withSubline(viewer -> {
            asked.incrementAndGet();
            return new MenuSubline(Message.raw("x"), null);
        }));

        assertTrue(ZigMenu.rows(MenuDeps.EMPTY, ZigMenu.slots(), VIEWER).isEmpty());
        assertEquals(0, asked.get());
    }

    @Test
    void aSecondLineNeedsWordsAndAnEntryKeepsItsLineThroughItsOtherFields() {
        MenuEntry plain = slot(MenuSlot.ALMANAC, true);
        assertNull(plain.subline(), "the five-field entry asks for no second line");
        MenuEntry lined = plain.withSubline(viewer -> null);
        assertNotNull(lined.subline());
        assertEquals(plain.id(), lined.id());
        assertSame(plain.label(), lined.label());
        assertSame(plain.opens(), lined.opens());
        assertSame(plain.visible(), lined.visible());
        assertThrows(NullPointerException.class, () -> new MenuSubline(null, null),
                "a second line with no words is refused");
    }

    @Test
    void anUnfilledSlotAndAHiddenEntryDrawNothing() {
        ZigMenu.fill(MenuSlot.ACHIEVEMENTS, slot(MenuSlot.ACHIEVEMENTS, false));
        ZigMenu.fill(MenuSlot.ALMANAC, slot(MenuSlot.ALMANAC, true));

        assertEquals(List.of("almanac"), ids(ZigMenu.rows(MenuDeps.EMPTY, ZigMenu.slots(), VIEWER)));
    }

    @Test
    void aSectionWithNothingToShowDrawsNoHeaderAndNoGap() {
        ZigMenu.fill(MenuSlot.QUESTS, slot(MenuSlot.QUESTS, true));
        MenuDeps deps = MenuDeps.builder()
                .section(new MenuSection(Message.raw("Skills"), List.of(entry("a", false))))
                .build();

        assertEquals(List.of("quests"), ids(ZigMenu.rows(deps, ZigMenu.slots(), VIEWER)));
    }

    @Test
    void aVisibilityRuleThatThrowsHidesItsOwnEntryOnly() {
        MenuEntry broken = new MenuEntry("broken", Message.raw("x"), null, new Probe(), viewer -> {
            throw new IllegalStateException("boom");
        });
        MenuDeps deps = MenuDeps.builder().section(new MenuSection(null, List.of(broken, entry("ok", true)))).build();

        assertEquals(List.of("ok"), ids(ZigMenu.rows(deps, Map.of(), VIEWER)));
    }

    @Test
    void theLandingIsTheConsumersElseTheFirstVisibleSlotElseNothing() {
        Probe landing = new Probe();
        MenuEntry quests = slot(MenuSlot.QUESTS, true);

        assertEquals(List.of(landing, quests.opens()),
                ZigMenu.landingCandidates(MenuDeps.builder().landing(landing).build(), List.of(quests)));
        assertEquals(List.of(quests.opens()), ZigMenu.landingCandidates(MenuDeps.EMPTY, List.of(quests)));
        assertTrue(ZigMenu.landingCandidates(MenuDeps.EMPTY, List.of()).isEmpty());
    }

    @Test
    void openLandingFallsToTheFirstSlotWhenTheConsumersLandingDeclines() {
        ZigMenu.consumer(() -> MenuDeps.builder().landing(new Refusing()).build());
        ZigMenu.fill(MenuSlot.QUESTS, slot(MenuSlot.QUESTS, true));

        assertTrue(ZigMenu.openLanding(VIEWER));
        assertEquals(1, OPENED.get());
    }

    @Test
    void openLandingSaysSoWhenThereIsNothingToOpen() {
        ZigMenu.fill(MenuSlot.QUESTS, slot(MenuSlot.QUESTS, false));

        assertFalse(ZigMenu.openLanding(VIEWER), "the command then says nothing is available");
        assertEquals(0, OPENED.get());
    }
}
