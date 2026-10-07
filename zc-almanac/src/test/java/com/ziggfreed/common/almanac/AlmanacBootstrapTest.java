package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.almanac.stats.AlmanacStatistics;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.ui.kit.LedgerContributions;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The plugin-free half of the Almanac's setup: the feature, the destination, the menu tab, the counter and
 * the book's Seasons statistics; and its stop, which writes the server's totals still waiting on their
 * debounce.
 */
class AlmanacBootstrapTest {

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        ProgressionRuntime.resetForTests();
        FeatureFlags.reset();
        Destinations.clearForTests();
        AlmanacSwitch.resetForTests();
        ZigMenu.clearForTests();
        LedgerContributions.resetForTests();
        ServerTallies.shared().init(null);
    }

    @Test
    void theVocabularyPhaseClaimsTheFeatureTheDestinationAndTheCounter() {
        AlmanacBootstrap.registerVocabulary();

        assertTrue(FeatureFlags.isKnown(AlmanacSwitch.NAMESPACE, AlmanacSwitch.FEATURE));
        assertTrue(Destinations.isRegistered(AlmanacDestinations.TYPE));
        assertNotNull(ZigMenu.slot(MenuSlot.ALMANAC), "the Almanac fills its menu tab");
        assertTrue(ProgressionRuntime.momentListenerOwners().contains(AlmanacBootstrap.OWNER),
                "the Almanac counts off the shared moment stream");
        assertTrue(LedgerContributions.sources(LedgerContributions.STATISTICS).stream()
                .anyMatch(source -> AlmanacStatistics.ID.equals(source.id())),
                "the Almanac fills the book's Seasons statistics");
    }

    /**
     * Tagged {@code engine-items}: it reads back what the stop wrote through the engine's own atomic
     * writer, which on Update 7 loads only under the engine's log manager. A count the server's debounce
     * is still holding when the server stops is on disk once the stop returns.
     */
    @Tag("engine-items")
    @Test
    void theStopWritesTheServerTotalsStillWaiting() {
        String key = AlmanacKeys.lifetime("test_season", "bombs_thrown");
        ServerTallies.shared().init(dir);
        ServerTallies.shared().add(key, 4L);

        AlmanacBootstrap.shutdown();

        ServerTallies reloaded = new ServerTallies((task, delayMs) -> task.run());
        reloaded.init(dir);
        assertEquals(4L, reloaded.get(key), "the stop wrote the server's totals");
    }

    /** Untagged on purpose: the stop never throws, whether or not the totals were ever pointed at a folder. */
    @Test
    void theStopNeverThrows() {
        ServerTallies.shared().init(null);

        assertDoesNotThrow(AlmanacBootstrap::shutdown);
        assertDoesNotThrow(AlmanacBootstrap::shutdown, "a second stop is as harmless as the first");
    }
}
