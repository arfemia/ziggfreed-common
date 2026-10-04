package com.ziggfreed.common.calendar.tick;

import static com.ziggfreed.common.calendar.CalendarFixtures.at;
import static com.ziggfreed.common.calendar.CalendarFixtures.waitsFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.calendar.CalendarContent;
import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.CalendarForces;
import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarOwnerLayers;
import com.ziggfreed.common.calendar.asset.CalendarSpawnAsset;
import com.ziggfreed.common.calendar.asset.CalendarSpawnConfig;
import com.ziggfreed.common.calendar.event.CalendarEventEndedEvent;
import com.ziggfreed.common.calendar.event.CalendarEventStartedEvent;
import com.ziggfreed.common.calendar.event.CalendarEvents;
import com.ziggfreed.common.calendar.spawn.CalendarSpawns;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.factor.FeatureFlags;

/**
 * The minute tick: silent before boot, a resumed start at boot, each start and end told once, a
 * switch-off told as one, a reload that says the same thing told as nothing, and a look and a reload
 * never overlapping: each waits for the calendar's lock while the other holds it.
 */
class CalendarTickerTest {

    /** One spawn file riding Hallows_Eve, for the two cases that pin the spawn layer's lock. */
    private static final String HALLOWS_EVE_GHOULS = """
            { "Event": "Hallows_Eve", "Spawn": { "Environments": ["Env_Test_Forest"],
              "NPCs": [ { "Id": "Test_Ghoul", "Weight": 10 } ] } }
            """;

    @TempDir
    Path ownerDir;

    private final long[] now = {at("2026-10-02T12:00:00Z")};
    private final ManualTickScheduler scheduler = new ManualTickScheduler();
    private final List<IEvent<Void>> fired = new ArrayList<>();
    private final List<CalendarTick> heard = new ArrayList<>();
    private CalendarTicker ticker;

    @BeforeEach
    void setUp() {
        CalendarFixtures.reset();
        CalendarOwnerLayers.setDirectory(ownerDir);
        CalendarFixtures.loadDesignEvents();
        CalendarEvents.SEAM.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                fired.add(build.get());
            }
        });
        ticker = new CalendarTicker(CalendarRuntime.service(), scheduler, () -> now[0]);
        ticker.listen(heard::add);
    }

    @AfterEach
    void tearDown() {
        CalendarEvents.SEAM.publishTo(null);
        CalendarOwnerLayers.setDirectory(CalendarOwnerLayers.DEFAULT_DIRECTORY);
        FeatureFlags.reset();
        CalendarFixtures.reset();
    }

    private List<CalendarEventStartedEvent> starts() {
        return fired.stream().filter(CalendarEventStartedEvent.class::isInstance)
                .map(CalendarEventStartedEvent.class::cast).toList();
    }

    private List<CalendarEventEndedEvent> ends() {
        return fired.stream().filter(CalendarEventEndedEvent.class::isInstance)
                .map(CalendarEventEndedEvent.class::cast).toList();
    }

    @Test
    void nothingHappensBeforeBoot() {
        ticker.requestEvaluation();
        scheduler.drain();
        assertTrue(fired.isEmpty());
        assertTrue(heard.isEmpty());
        assertEquals(0, scheduler.repeatingCount(), "the tick waits for the boot");
    }

    @Test
    void bootResumesWhatIsAlreadyRunningAndStartsTheMinuteTick() {
        ticker.start();
        assertEquals(1, starts().size());
        CalendarEventStartedEvent started = starts().get(0);
        assertEquals("hallows_eve", started.eventId());
        assertEquals(2026, started.year());
        assertTrue(started.resumed(), "the server caught up; nothing began");
        assertTrue(heard.get(0).booting());
        assertEquals(1, scheduler.repeatingCount());
        assertEquals(CalendarTicker.PERIOD_MS, scheduler.lastPeriodMs());
    }

    @Test
    void aStartAndAnEndAreEachToldOnce() {
        now[0] = at("2026-10-28T23:59:00Z");
        ticker.start();
        fired.clear();
        now[0] = at("2026-10-29T00:00:00Z");
        scheduler.tick();
        scheduler.tick();
        assertEquals(List.of("harvest_moon"), starts().stream().map(CalendarEventStartedEvent::eventId).toList());
        assertFalse(starts().get(0).resumed());
        now[0] = at("2026-11-01T00:00:00Z");
        scheduler.tick();
        assertEquals(1, ends().size());
        assertEquals("harvest_moon", ends().get(0).eventId());
        assertFalse(ends().get(0).switchedOff());
    }

    @Test
    void switchingAnEventOffWhileItRunsEndsItAsASwitchOff() throws IOException {
        ticker.start();
        fired.clear();
        Files.writeString(ownerDir.resolve(CalendarOwnerLayers.FILE), "{ \"Hallows_Eve\": { \"Enabled\": false } }",
                StandardCharsets.UTF_8);
        CalendarContent.reloadOwnerFile();
        ticker.requestEvaluation();
        scheduler.drain();
        assertEquals(1, ends().size());
        assertTrue(ends().get(0).switchedOff(), "off means absent: the run did not end, the event went away");
    }

    @Test
    void aReloadThatSaysTheSameThingIsNoStartAndNoEnd() {
        ticker.start();
        fired.clear();
        heard.clear();
        CalendarContent.reloadEvents(Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE),
                "harvest_moon", CalendarFixtures.event("Harvest_Moon", CalendarFixtures.HARVEST_MOON)));
        ticker.requestEvaluation();
        scheduler.drain();
        assertTrue(fired.isEmpty());
        assertFalse(heard.get(0).changed());
    }

    /**
     * A fold clears a layer before it fills it again, so a look landing in between would read every event
     * as gone: an end, its banner and its spawn rules retired, then a start that is no resume. This thread
     * plays the reload, holding the calendar's lock with the pack layer cleared, while a minute tick lands;
     * the tick waits for the lock and reads the reload whole.
     */
    @Test
    void aTickLandingHalfwayThroughAReloadWaitsForItAndReadsItWhole() throws InterruptedException {
        ticker.start();
        fired.clear();
        heard.clear();
        Object lock = CalendarRuntime.service().lock();
        Thread tick = new Thread(scheduler::tick, "calendar-test-tick");
        boolean waited;
        synchronized (lock) {
            CalendarFixtures.loadEvents(Map.of());
            tick.start();
            waited = waitsFor(tick, lock);
            CalendarFixtures.loadDesignEvents();
        }
        assertTrue(tick.join(Duration.ofSeconds(5)), "the tick ran once the reload let go");
        assertTrue(fired.isEmpty(), "the tick read the reload whole, and the reload says the same thing");
        assertEquals(1, heard.size());
        assertFalse(heard.get(0).changed());
        assertTrue(waited, "the tick waited for the lock the reload folds under");
    }

    /** A pack reload reads the layer it folds in only while holding the lock a look reads under. */
    @Test
    void aPackReloadFoldsItsLayerOnlyUnderTheLockALookReadsUnder() {
        Map<String, CalendarEventAsset> design = Map.of(
                "hallows_eve", CalendarFixtures.event("Hallows_Eve", CalendarFixtures.HALLOWS_EVE),
                "harvest_moon", CalendarFixtures.event("Harvest_Moon", CalendarFixtures.HARVEST_MOON));
        List<Boolean> lockHeldAtEachRead = new ArrayList<>();
        CalendarContent.reloadEvents(new AbstractMap<String, CalendarEventAsset>() {
            @Override
            public Set<Map.Entry<String, CalendarEventAsset>> entrySet() {
                lockHeldAtEachRead.add(Thread.holdsLock(CalendarRuntime.service().lock()));
                return design.entrySet();
            }
        });
        assertFalse(lockHeldAtEachRead.isEmpty(), "the reload folded its pack layer in");
        assertFalse(lockHeldAtEachRead.contains(false), "and only while holding the lock a look reads under");
    }

    /** A spawn reload reads the layer it folds in only while holding the lock a look reads under. */
    @Test
    void aSpawnReloadFoldsItsLayerOnlyUnderTheLockALookReadsUnder() {
        Map<String, CalendarSpawnAsset> spawns =
                Map.of("hallows_eve_ghouls", CalendarFixtures.spawn("Hallows_Eve_Ghouls", HALLOWS_EVE_GHOULS));
        List<Boolean> lockHeldAtEachRead = new ArrayList<>();
        CalendarContent.reloadSpawns(new AbstractMap<String, CalendarSpawnAsset>() {
            @Override
            public Set<Map.Entry<String, CalendarSpawnAsset>> entrySet() {
                lockHeldAtEachRead.add(Thread.holdsLock(CalendarRuntime.service().lock()));
                return spawns.entrySet();
            }
        });
        assertFalse(lockHeldAtEachRead.isEmpty(), "the reload folded its spawn layer in");
        assertFalse(lockHeldAtEachRead.contains(false), "and only while holding the lock a look reads under");
    }

    /**
     * The owner file's fold clears the owner layer before it fills it again, so a look landing in between
     * would read an event the owner switched off as on. This thread plays a look in progress, holding the
     * calendar's lock (a look holds it only while it reads, never while its listeners run); the owner file
     * waits for it, then lands whole.
     */
    @Test
    void anOwnerFileReloadWaitsForALookInProgress() throws IOException, InterruptedException {
        Files.writeString(ownerDir.resolve(CalendarOwnerLayers.FILE), "{ \"Hallows_Eve\": { \"Enabled\": false } }",
                StandardCharsets.UTF_8);
        Object lock = CalendarRuntime.service().lock();
        Thread reload = new Thread(CalendarContent::reloadOwnerFile, "calendar-test-owner-reload");
        boolean waited;
        boolean onWhileItWaited;
        synchronized (lock) {
            reload.start();
            waited = waitsFor(reload, lock);
            onWhileItWaited = CalendarRuntime.service().isEnabled("hallows_eve");
        }
        assertTrue(reload.join(Duration.ofSeconds(5)), "the reload ran once the look let go");
        assertTrue(waited, "the owner file waited for the lock a look reads under");
        assertTrue(onWhileItWaited, "none of it landed while the look held the lock");
        assertFalse(CalendarRuntime.service().isEnabled("hallows_eve"), "then all of it landed");
    }

    /**
     * A spawn reload clears the spawn layer before it fills it again, so the spawn rules reading it in
     * between would find every file gone: each rule retired, then written back at the next look. This
     * thread plays the reload, holding the calendar's lock with the spawn layer cleared, while a look's
     * spawn listener brings the rules in line; it waits for the lock and reads the reload whole.
     */
    @Test
    void theSpawnRulesLandingHalfwayThroughASpawnReloadWaitForItAndRetireNothing() throws InterruptedException {
        Map<String, CalendarSpawnAsset> spawns =
                Map.of("hallows_eve_ghouls", CalendarFixtures.spawn("Hallows_Eve_Ghouls", HALLOWS_EVE_GHOULS));
        List<String> sent = new ArrayList<>();
        CalendarSpawns.useSinkForTests((rule, json) -> {
            sent.add(rule + "=" + json);
            return true;
        });
        CalendarSpawnConfig.getInstance().mergePackLayer(spawns);
        CalendarSpawns.reconcile(Set.of("hallows_eve"));
        Object lock = CalendarRuntime.service().lock();
        Thread rules = new Thread(() -> CalendarSpawns.reconcile(Set.of("hallows_eve")), "calendar-test-spawns");
        boolean waited;
        synchronized (lock) {
            CalendarSpawnConfig.getInstance().mergePackLayer(Map.of());
            rules.start();
            waited = waitsFor(rules, lock);
            CalendarSpawnConfig.getInstance().mergePackLayer(spawns);
        }
        assertTrue(rules.join(Duration.ofSeconds(5)), "the rules were brought in line once the reload let go");
        assertEquals(1, sent.size(), "nothing was retired: the rules read the reload whole");
        assertTrue(waited, "they waited for the lock the reload folds under");
    }

    @Test
    void forcingOnStartsARunAndClearingEndsIt() {
        now[0] = at("2026-06-01T00:00:00Z");
        ticker.start();
        assertTrue(fired.isEmpty());
        CalendarForces.getInstance().force("Hallows_Eve", true);
        ticker.requestEvaluation();
        scheduler.drain();
        assertEquals(1, starts().size());
        assertFalse(starts().get(0).resumed());
        CalendarForces.getInstance().clear("hallows_eve");
        ticker.requestEvaluation();
        scheduler.drain();
        assertEquals(1, ends().size());
        assertFalse(ends().get(0).switchedOff());
    }

    @Test
    void aListenerThatThrowsCostsOnlyItself() {
        List<CalendarTick> after = new ArrayList<>();
        ticker.listen(tick -> {
            throw new IllegalStateException("broken listener");
        });
        ticker.listen(after::add);
        ticker.start();
        assertEquals(1, after.size());
    }

    @Test
    void stopCancelsTheMinuteTick() {
        ticker.start();
        ticker.stop();
        assertEquals(0, scheduler.repeatingCount());
        assertFalse(ticker.isStarted());
    }

    /**
     * The engine runs a content load inside its asset write lock, and the load asks for a look; the look in
     * progress may be waiting for that same lock (a listener writing spawn rules). So asking never waits for
     * the look in progress, or each thread would wait for the other for good, and the look it asked for
     * still comes.
     */
    @Test
    void askingForALookWhileOneRunsNeverWaitsForIt() throws InterruptedException {
        CountDownLatch asked = new CountDownLatch(1);
        AtomicBoolean answeredDuringTheLook = new AtomicBoolean();
        List<Thread> loaders = new ArrayList<>();
        ticker.listen(tick -> {
            if (!tick.booting()) {
                return;
            }
            Thread loader = new Thread(() -> {
                ticker.requestEvaluation();
                asked.countDown();
            }, "calendar-test-loader");
            loaders.add(loader);
            loader.start();
            try {
                answeredDuringTheLook.set(asked.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ticker.start();
        for (Thread loader : loaders) {
            loader.join();
        }
        assertTrue(answeredDuringTheLook.get(), "the ask returned while the boot's look was still running");
        heard.clear();
        scheduler.drain();
        assertEquals(1, heard.size(), "and the look it asked for came");
    }
}
