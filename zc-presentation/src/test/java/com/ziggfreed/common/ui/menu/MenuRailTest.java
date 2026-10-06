package com.ziggfreed.common.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * A rail click is answered whatever happens to it: a row that opens a screen owes the client nothing
 * more, and every refused click (a header, a stale index, an entry hidden since the build, a screen
 * that declined, a token that is not a number) runs the page's answer. A click that is not the menu's
 * is left to the page.
 */
class MenuRailTest {

    @BeforeEach
    void seed() {
        Destinations.clearForTests();
        ZigMenuTest.OPENED.set(0);
        Destinations.register("test", DestinationType.of("Test_Probe", ZigMenuTest.Probe.class,
                ZigMenuTest.Probe.CODEC, (d, ctx) -> ZigMenuTest.OPENED.incrementAndGet() > 0));
        Destinations.register("test", DestinationType.of("Test_Refusing", ZigMenuTest.Refusing.class,
                ZigMenuTest.Refusing.CODEC, (d, ctx) -> false));
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
    }

    @Test
    void aClickThatIsNotTheMenusIsLeftToThePage() {
        AtomicInteger answered = new AtomicInteger();
        MenuRail rail = new MenuRail(List.of(MenuRow.entry(ZigMenuTest.entry("a", true))));

        assertFalse(rail.handle(null, null, null, null, answered::incrementAndGet));
        assertEquals(0, answered.get());
    }

    @Test
    void aClickOnAShownEntryOpensItsScreen() {
        AtomicInteger answered = new AtomicInteger();
        MenuRail rail = new MenuRail(List.of(MenuRow.header(Message.raw("h")), MenuRow.entry(ZigMenuTest.entry("a", true))));

        assertTrue(rail.handle("1", null, null, null, answered::incrementAndGet));
        assertEquals(1, ZigMenuTest.OPENED.get());
        assertEquals(0, answered.get(), "a screen took over, so the page owes the client nothing more");
    }

    @Test
    void everyRefusedClickIsAnswered() {
        MenuEntry declining = new MenuEntry("declining", Message.raw("d"), null, new ZigMenuTest.Refusing(), v -> true);
        MenuRail rail = new MenuRail(List.of(MenuRow.header(Message.raw("h")),
                MenuRow.entry(ZigMenuTest.entry("hiddenNow", false)), MenuRow.entry(declining), MenuRow.SPACER));

        for (String token : List.of("0", "1", "2", "3", "9", "-1", "x", "")) {
            AtomicInteger answered = new AtomicInteger();
            assertTrue(rail.handle(token, null, null, null, answered::incrementAndGet), "token " + token);
            assertEquals(1, answered.get(), "the client hears back for token '" + token + "'");
        }
        assertEquals(0, ZigMenuTest.OPENED.get());
    }
}
