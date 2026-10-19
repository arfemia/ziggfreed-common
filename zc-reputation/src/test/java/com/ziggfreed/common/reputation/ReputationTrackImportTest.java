package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.track.PlayerTrackStore;
import com.ziggfreed.common.track.TrackComponent;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackStore;
import com.ziggfreed.common.track.TrackStores;
import com.ziggfreed.common.track.asset.TrackOwnerLayers;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.event.TrackEvents;

/**
 * Today's reputation save onto the track and back (R602, R692), on the test's own ladder: the engine's
 * standing and the Beyond payouts already parked become a climb that the saved record reads back the same, and
 * the engine's copy written from it is the very value the engine held; the curve is the ladder, rank for rank,
 * so the ladder keeps its numbers; and an outside write to the engine's value (the admin {@code /reputation})
 * is folded into the track once, never lost or doubled.
 *
 * <p>Every number here comes from the test's own data, never the rank files the module ships (a retune of
 * those moves nothing here): the engine holds {@link ReputationFixtures#LADDER} (the fake's ladder), whose
 * floors are levels 1 to 7 (Hated -30000, Unfriendly -3000, Neutral 0, Friendly 1000, Honored 3000, Revered
 * 9000, Exalted 21000); {@link #BEYOND}'s Every of 5000 makes each step past a top one level more (on the
 * shared top: level 8 at 26000, 12 at 46000, 13 at 51000); and the festival's own ranks at 30000, 40000 and
 * 50000 are levels 8 to 10, so its first Beyond step, level 11, is at 55000.
 */
class ReputationTrackImportTest {

    private static final String JACK = ReputationFixtures.OLD_JACK;
    private static final String HALLOWED = "Test_Hallowed";
    private static final String FESTIVAL = "Test_Festival";
    private static final String TRAINER = "Test_Trainer";

    private static final String BEYOND = "\"Beyond\": { \"Every\": 5000, \"Rewards\": [ { \"Kind\": \"Lootable\","
            + " \"Params\": { \"Lootable\": \"Test_Cache\" } } ] }";

    private final Map<TrackKey, TrackRecord> kept = new HashMap<>();
    private FakeReputationNative engine;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        TrackEvents.SEAM.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
            }
        });
        engine = ReputationFixtures.engine();
        engine.group(HALLOWED, 0).group(FESTIVAL, 0).group(TRAINER, 0);
        ReputationFixtures.loadCompanions(Map.of(
                "test_old_jack", ReputationFixtures.companion(JACK, "{ \"Cap\": 21000 }"),
                "test_hallowed", ReputationFixtures.companion(HALLOWED, "{ " + BEYOND + " }"),
                "test_festival", ReputationFixtures.companion(FESTIVAL, "{ \"Ranks\": {"
                        + " \"Festival_Tier_1\": { \"From\": 30000 }, \"Festival_Tier_2\": { \"From\": 40000 },"
                        + " \"Festival_Tier_3\": { \"From\": 50000 } }, " + BEYOND + " }")));
        TrackStores.register(TrackKey.Subject.PLAYER, new KeptStore(kept));
        service = new ReputationService(engine, ReputationFanOut.NONE, new TrackReputationLedger(engine));
        ReputationTracks.refresh(service);
    }

    @AfterEach
    void clear() {
        TrackStores.register(TrackKey.Subject.PLAYER, PlayerTrackStore.INSTANCE);
        TrackOwnerLayers.setDirectory(TrackOwnerLayers.DEFAULT_DIRECTORY);
        ReputationFixtures.reset();
        ReputationConfig.getInstance().setGlobalEnabled(false);
        ReputationTracks.refresh(service);
        ReputationConfig.getInstance().setGlobalEnabled(true);
        TrackEvents.SEAM.publishTo(null);
    }

    @Test
    void todaysSaveImportsOntoTheTrackAndCopiesBackToTheSameNativeValue() {
        // reputation, the engine's standing, the highest standing on the reputation record, the best level, the
        // Beyond mark (0 for none).
        assertImports(HALLOWED, 45_000, 46_000L, 12, 12);
        assertImports(HALLOWED, 3_100, null, 5, 0);
        assertImports(JACK, 21_000, null, 7, 0);
        assertImports(JACK, -4_500, null, 1, 0);
        assertImports(FESTIVAL, 55_000, null, 11, 11);
        assertImports(TRAINER, 1_999_999_999, null, 7, 0);
    }

    @Test
    void theCurveIsTheLadderRankForRank() {
        for (String id : List.of(JACK, HALLOWED, FESTIVAL, TRAINER)) {
            ReputationDef def = service.known(id);
            assertNotNull(def, id);
            ReputationLadder ladder = service.ladderFor(def);
            TrackDef track = ReputationTracks.track(def);
            assertNotNull(track, id + " is a track");
            CurveTable table = track.table();
            List<ReputationLadder.Rank> ranks = ladder.ranks();
            assertEquals(ranks.size(), table.top(), id + ": one level per rank");
            for (int i = 0; i < ranks.size(); i++) {
                assertEquals(ranks.get(i).min(), table.floorOf(i + 1), id + ": level " + (i + 1) + "'s floor");
                assertEquals(ranks.get(i).id(), table.tierOf(i + 1).id(), id + ": level " + (i + 1) + "'s tier");
            }
            assertEquals(ReputationTracks.CATEGORY, track.asset().category());
            assertFalse(track.asset().prestiges(), id + " does not prestige until an owner turns it on (R643)");
        }
        CurveTable festival = ReputationTracks.track(service.known(FESTIVAL)).table();
        assertEquals(5_000, festival.every(), "the Beyond step is the curve's Beyond");
        assertEquals(11, festival.levelOf(55_000), "one level past the festival's own top per step");
        CurveTable trainer = ReputationTracks.track(service.known(TRAINER)).table();
        assertEquals(7, trainer.levelOf(1_999_999_999), "with no Beyond nothing is a level past the top");
        assertEquals(Long.MAX_VALUE, trainer.ceiling(null), "and standing climbs past the top floor as it always did");
    }

    @Test
    void anOutsideWriteToTheEnginesValueFoldsIntoTheTrackOnce() {
        engine.stored.put(HALLOWED, 45_000);
        service.settle(null, null);
        assertEquals(45_000, climb(HALLOWED).value(), "the first world entry imports today's standing");
        assertTrue(engine.writes.isEmpty(), "and the import writes nothing back");

        assertEquals(ReputationService.Status.CHANGED,
                service.change(null, null, HALLOWED, 1_000, "quest:test").status());
        assertEquals(46_000, climb(HALLOWED).value(), "a change lands on the track");
        assertEquals(46_000, engine.stored.get(HALLOWED), "and the engine's copy follows it");
        assertEquals(List.of(HALLOWED + " 1000"), engine.writes);

        engine.stored.put(HALLOWED, 50_000);
        assertEquals(50_000, service.standing(null, null, HALLOWED).earned(),
                "an admin's set reads at once");
        assertEquals(46_000, climb(HALLOWED).value(), "before it is folded in");
        service.settle(null, null);
        assertEquals(50_000, climb(HALLOWED).value(), "the next world entry folds it into the track");
        service.settle(null, null);
        assertEquals(50_000, climb(HALLOWED).value(), "once");
        assertEquals(50_000, service.standing(null, null, HALLOWED).earned());
        assertEquals(1, engine.writes.size(), "a fold writes nothing back to the engine");
    }

    @Test
    void anOwnersPrestigeStartsStandingAgainWhileTheBestRankKeepsItsUnlocksAndAttitude() throws IOException {
        TrackKey key = ReputationTracks.key(engine.player, service.known(HALLOWED));
        engine.stored.put(HALLOWED, 26_000);
        service.settle(null, null);
        assertEquals(TrackService.Status.NOT_ALLOWED, TrackService.prestige(key, "test").status(),
                "a reputation does not prestige until an owner turns it on (R643)");

        Path owner = Files.createTempDirectory("sk4-owner");
        Files.writeString(owner.resolve(TrackOwnerLayers.FILE),
                "{ \"" + HALLOWED + "\": { \"Prestige\": { \"Ranks\": 1 } } }", StandardCharsets.UTF_8);
        TrackOwnerLayers.setDirectory(owner);
        ReputationTracks.refresh(service);
        ReputationDef def = service.known(HALLOWED);
        TrackDef track = ReputationTracks.track(def);
        assertTrue(track.asset().prestiges(), "the owner's word turns it on, the rest of the track kept");
        assertEquals(7, track.asset().prestigeLevel(1), "the first prestige asks the top rank");
        ReputationLedger.Reading before = service.ledger().read(null, null, def, service.ladderFor(def));
        assertEquals(TrackService.Status.CHANGED, TrackService.prestige(key, "test").status());
        service.ledger().followed(null, null, def, service.ladderFor(def), before);

        ReputationService.Standing standing = service.standing(null, null, HALLOWED);
        assertEquals(0, standing.earned(), "the standing starts again");
        assertEquals("Exalted", standing.rank().id(), "the best rank keeps every unlock");
        assertEquals(26_000, engine.stored.get(HALLOWED), "and the copy NPC attitudes read keeps that rank");
        assertEquals(ReputationService.Status.CHANGED,
                service.change(null, null, HALLOWED, 1_000, "quest:test").status());
        assertEquals(1_000, service.standing(null, null, HALLOWED).earned(), "a gain shows on the new climb");
        assertEquals(26_000, engine.stored.get(HALLOWED), "below the best rank's floor the copy stays put");
    }

    @Test
    void aGainWhereReputationIsKeptPerWorldIsNeverTakenOffTheTrackByTheCopyItLeftBehind() {
        ReputationComponent record = new ReputationComponent();
        ReputationService mixed = withRecord(record);
        engine.stored.put(HALLOWED, 45_000);
        mixed.settle(null, null);
        assertEquals(45_000, climb(HALLOWED).value(), "a world keeping the player's own value imports it");

        engine.perWorld = true;
        engine.world.put(HALLOWED, 3_000);
        assertEquals(ReputationService.Status.CHANGED,
                mixed.change(null, null, HALLOWED, 1_000, "quest:test").status());
        assertEquals(46_000, climb(HALLOWED).value(), "a gain where reputation is kept per world lands on the track");
        assertEquals(45_000, engine.stored.get(HALLOWED), "with no copy written to the player's own value");
        assertEquals(3_000, engine.world.get(HALLOWED), "nor to the world's shared one");
        assertTrue(record.unsynced(HALLOWED), "so the record says the copy is behind");

        ExtraInfo info = new ExtraInfo();
        ReputationComponent restarted = ReputationComponent.CODEC.decode(ReputationComponent.CODEC.encode(record, info),
                info);
        ReputationService afterRestart = withRecord(restarted);
        engine.perWorld = false;
        assertEquals(46_000, afterRestart.standing(null, null, HALLOWED).earned(),
                "after a restart the copy left behind is not read as an outside write");
        afterRestart.settle(null, null);
        assertEquals(46_000, climb(HALLOWED).value(), "back in a per-player world it is never folded into the track");
        assertEquals(46_000, engine.stored.get(HALLOWED), "it is written again instead");
        assertFalse(restarted.unsynced(HALLOWED), "and the record says the copy caught up");
        afterRestart.settle(null, null);
        assertEquals(46_000, climb(HALLOWED).value(), "for good");
        assertEquals(46_000, engine.stored.get(HALLOWED));
    }

    @Test
    void theImportWaitsThroughWorldsThatKeepReputationPerWorldAndKeepsWhatWasGainedThere() {
        ReputationComponent record = new ReputationComponent();
        ReputationService mixed = withRecord(record);
        ReputationDef def = mixed.known(HALLOWED);
        record.climbed(HALLOWED, 52_000, 52_000, mixed.ladderFor(def).top().min(), def.beyondEvery());
        engine.stored.put(HALLOWED, 45_000);
        engine.perWorld = true;
        engine.world.put(HALLOWED, 3_000);

        mixed.settle(null, null);
        assertTrue(climb(HALLOWED).isEmpty(), "the world's shared value is no player's standing, so none is imported");
        assertFalse(record.imported(HALLOWED), "and the import waits");
        assertEquals(ReputationService.Status.CHANGED,
                mixed.change(null, null, HALLOWED, 1_000, "quest:test").status());
        assertEquals(1_000, climb(HALLOWED).value(), "a gain there lands on the track alone");

        engine.perWorld = false;
        mixed.settle(null, null);
        assertTrue(record.imported(HALLOWED), "the first world keeping the player's own value imports it");
        TrackRecord climb = climb(HALLOWED);
        assertEquals(46_000, climb.value(), "under the gain made before it, so neither is lost");
        assertEquals(13, climb.best(), "the best level from the player's own highest standing");
        assertEquals(13, climb.paidThrough(ReputationTracks.BEYOND_ROW),
                "and every Beyond payout through it marked paid");
        assertEquals(46_000, engine.stored.get(HALLOWED), "the copy holds the climb");
        assertEquals(3_000, engine.world.get(HALLOWED), "and the world's value was never taken for the player's");
        mixed.settle(null, null);
        assertEquals(46_000, climb(HALLOWED).value(), "once");
        assertEquals(46_000, engine.stored.get(HALLOWED));
    }

    /** A service whose ledger finds the player's reputation record in {@code record}, as a live player carries one. */
    @Nonnull
    private ReputationService withRecord(@Nonnull ReputationComponent record) {
        return new ReputationService(engine, ReputationFanOut.NONE,
                new TrackReputationLedger(engine, (store, ref) -> record));
    }

    /**
     * One save: imported through the track's own import, kept through the track record's save format, and
     * copied back to the engine.
     */
    private void assertImports(@Nonnull String id, int standing, @Nullable Long highest, int best, int beyondMark) {
        kept.clear();
        ReputationDef def = service.known(id);
        ReputationLadder ladder = service.ladderFor(def);
        TrackDef track = ReputationTracks.track(def);
        assertNotNull(track, id);
        TrackKey key = ReputationTracks.key(engine.player, def);
        TrackService.Result result = TrackService.seed(key,
                ReputationTracks.imported(standing, highest, track.table()), TrackReputationLedger.IMPORT_SOURCE);
        assertTrue(result.moved(), id + " at " + standing + " imports");

        TrackComponent saved = new TrackComponent();
        saved.put(key, climb(id));
        ExtraInfo info = new ExtraInfo();
        TrackRecord read = TrackComponent.CODEC.decode(TrackComponent.CODEC.encode(saved, info), info).get(key);

        String what = id + " at " + standing;
        assertEquals(climb(id), read, what + ": the saved climb reads back the same");
        assertEquals(standing, read.value(), what + ": the climb holds the standing");
        assertEquals(best, read.best(), what + ": the best level is where the highest standing stands");
        assertEquals(beyondMark, read.paidThrough(ReputationTracks.BEYOND_ROW),
                what + ": every Beyond payout already parked or collected is marked paid");
        assertEquals(standing, ReputationTracks.copyOf(ReputationTracks.held(read, read.value(), track.table()),
                ladder), what + ": the engine's copy is the value the engine held");
        long reached = highest == null ? standing : Math.max(highest, standing);
        assertEquals(ladder.rankFor(reached).id(), track.table().tierOf(read.best()).id(),
                what + ": the best rank reads the same");
    }

    @Nonnull
    private TrackRecord climb(@Nonnull String id) {
        return kept.getOrDefault(ReputationTracks.key(engine.player, service.known(id)), TrackRecord.EMPTY);
    }

    /** A player store over a map, standing in for the player's own track record. */
    private record KeptStore(@Nonnull Map<TrackKey, TrackRecord> kept) implements TrackStore {

        @Nonnull
        @Override
        public TrackRecord get(@Nonnull TrackKey key) {
            return kept.getOrDefault(key, TrackRecord.EMPTY);
        }

        @Override
        public boolean put(@Nonnull TrackKey key, @Nonnull TrackRecord record) {
            kept.put(key, record);
            return true;
        }

        @Nonnull
        @Override
        public CompletableFuture<TrackRecord> load(@Nonnull TrackKey key) {
            return CompletableFuture.completedFuture(get(key));
        }
    }
}
