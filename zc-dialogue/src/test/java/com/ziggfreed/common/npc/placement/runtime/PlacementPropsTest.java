package com.ziggfreed.common.npc.placement.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.npc.placement.anchor.AnchorPosition;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.registry.PlacementGates;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.PlaceDecision;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.PlaceInputs;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.ResidentDecision;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler.ResidentInputs;
import com.ziggfreed.common.npc.placement.runtime.PlacementProps.Spot;
import com.ziggfreed.common.npc.placement.runtime.PlacementProps.Tally;
import com.ziggfreed.common.npc.placement.runtime.PlacementProps.Want;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.world.TickingSections.SectionPos;

/**
 * A placement's {@code Props} (decorations drawn at its spot, gone off-season), driven through the round's
 * pure rules and a fake drawer in place of {@code ItemPropEntityService}: one prop per entry at the
 * placement's spot plus its Offset in world axes, with its own Yaw; all of them gone when the placement
 * stops standing (its season ending among the reasons); a role's props standing only while its NPC is
 * placed; an unknown item skipped while the rest draw; and a prop, which is drawn rather than saved, drawn
 * again whenever its chunk section ticks after dropping it. The engine side is proved by a boot.
 */
class PlacementPropsTest {

    /** The placement's own spot: the anchor plus the anchor's Offset, already resolved, facing south. */
    private static final AnchorPosition SPOT =
            AnchorPosition.single(AnchorPosition.AnchorKind.WORLD_SPAWN, 10.0, 64.0, 20.0, 180.0f);

    private static final Predicate<String> EVERY_ITEM = id -> true;
    private static final Predicate<String> NOT_PLACED = anchorKey -> false;

    private static final NpcPlacementAsset.Prop TABLE =
            NpcPlacementAsset.Prop.of("Furniture_Tavern_Table", Vec3.of(1.5, 0.0, 0.0), 90.0, null);
    private static final NpcPlacementAsset.Prop BENCH =
            NpcPlacementAsset.Prop.of("Furniture_Tavern_Bench", Vec3.of(1.5, null, 1.2), 90.0, 1.0);

    private static final NpcPlacementAsset.Anchor AT_SPAWN =
            NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null);

    @AfterEach
    void forgetTheSeasons() {
        FeatureFlags.reset();
    }

    /** A role-less placement: it draws only its props. */
    @Nonnull
    private static NpcPlacementAsset feastTable(@Nonnull NpcPlacementAsset.Prop... props) {
        return NpcPlacementAsset.of("feast_table", true, null, null, AT_SPAWN, null, null, null, null, props);
    }

    /** One round's wants for {@code placement}, standing at {@link #SPOT}. */
    @Nonnull
    private static Map<String, Want> round(@Nonnull NpcPlacementAsset placement, boolean propOnly,
            @Nonnull Predicate<String> placedHere, @Nonnull Predicate<String> itemKnown) {
        Map<String, Want> wanted = new LinkedHashMap<>();
        PlacementProps.want(wanted, placement, propOnly, List.of(SPOT), placedHere, itemKnown);
        return wanted;
    }

    // ==================== where a prop is drawn ====================

    @Test
    void aPropPlacementDrawsOneEntityPerEntryAtItsSpotPlusTheOffsetWithItsYaw() {
        FakeProps engine = new FakeProps();
        PlacementProps.Book<Integer> book = new PlacementProps.Book<>();

        Tally tally = book.reconcile(round(feastTable(TABLE, BENCH), true, NOT_PLACED, EVERY_ITEM), Set.of(), engine);

        assertEquals(new Tally(2, 0), tally);
        assertEquals(List.of(
                        new Spot(0, "Furniture_Tavern_Table", 11.5, 64.0, 20.0, 90.0f, 1.0f),
                        new Spot(1, "Furniture_Tavern_Bench", 11.5, 64.0, 21.2, 90.0f, 1.0f)),
                engine.drawn,
                "each entry at the placement's spot plus its own Offset, in world axes: the spot faces south"
                        + " (Yaw 180) and the table still sits 1.5 blocks east, facing its own Yaw");

        assertEquals(new Tally(0, 0), book.reconcile(round(feastTable(TABLE, BENCH), true, NOT_PLACED, EVERY_ITEM),
                Set.of(), engine), "a prop still standing is never drawn twice");
        assertEquals(2, engine.standing.size());
    }

    @Test
    void aPropsYawIsDegreesTheEngineReadsAsRadians() {
        // The engine turns an entity by radians (its own spawn effects convert with Math.toRadians), and the
        // authored Yaw reads in degrees like the anchor's: 90 is a quarter turn, not 90 radians.
        assertEquals(Math.PI / 2, PlacementProps.yawRadians(90.0f), 1e-6);
        assertEquals(Math.PI, PlacementProps.yawRadians(180.0f), 1e-6);
        assertEquals(0.0, PlacementProps.yawRadians(0.0f), 1e-9);
    }

    @Test
    void anUnknownItemIsSkippedAndTheRestAreDrawn() {
        FakeProps engine = new FakeProps();
        NpcPlacementAsset.Prop missing = NpcPlacementAsset.Prop.of("Furniture_No_Such_Table", null, null, null);

        new PlacementProps.Book<Integer>().reconcile(round(feastTable(missing, BENCH), true, NOT_PLACED,
                id -> !"Furniture_No_Such_Table".equals(id)), Set.of(), engine);

        assertEquals(1, engine.drawn.size(), "an item the server lacks would draw the unknown-item picture: " + engine.drawn);
        assertEquals("Furniture_Tavern_Bench", engine.drawn.get(0).item());
        assertEquals(1, engine.drawn.get(0).index(), "an entry keeps its authored place in the list");
    }

    @Test
    void aBlankItemDrawsNothing() {
        FakeProps engine = new FakeProps();

        new PlacementProps.Book<Integer>().reconcile(round(feastTable(NpcPlacementAsset.Prop.of(" ", null, null, null),
                TABLE), true, NOT_PLACED, EVERY_ITEM), Set.of(), engine);

        assertEquals(List.of("Furniture_Tavern_Table"), engine.drawn.stream().map(Spot::item).toList());
    }

    // ==================== the placement's whole lifecycle ====================

    @Test
    void itsPropsGoWhenItsSeasonEnds() throws IOException {
        AtomicBoolean feastLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", feastLive::get);
        NpcPlacementAsset table = NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString("""
                        { "Season": "Harvest_Feast",
                          "Anchor": { "WorldSpawn": { "Offset": { "X": -4, "Z": -2 }, "Yaw": 180 } },
                          "Props": [ { "Item": "Furniture_Tavern_Table", "Offset": { "Z": 1.5 } },
                                     { "Item": "Furniture_Tavern_Bench", "Offset": { "Z": 2.5 }, "Yaw": 90 } ] }
                        """), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(NpcPlacementAsset.class, "Harvest_Feast_Table", null)));
        FakeProps engine = new FakeProps();
        PlacementProps.Book<Integer> book = new PlacementProps.Book<>();

        book.reconcile(roundIfStanding(table, engine), Set.of(), engine);
        assertTrue(engine.drawn.isEmpty(), "off-season the placement stands nowhere, so neither do its props");

        feastLive.set(true);
        book.reconcile(roundIfStanding(table, engine), Set.of(), engine);
        assertEquals(2, engine.standing.size(), "the calendar's start sweep draws the table and its bench");

        feastLive.set(false);
        Tally ended = book.reconcile(roundIfStanding(table, engine), Set.of(), engine);
        assertEquals(new Tally(0, 2), ended, "the calendar's end sweep takes both away");
        assertTrue(engine.standing.isEmpty());
        assertFalse(book.wantsSection(engine.drawn.get(0).section(), engine::standing),
                "and nothing is left waiting for its section to wake");
    }

    /** The place pass's order: the gate first, and a placement that does not stand wants nothing drawn. */
    @Nonnull
    private static Map<String, Want> roundIfStanding(@Nonnull NpcPlacementAsset placement, @Nonnull FakeProps engine) {
        Map<String, Want> wanted = new LinkedHashMap<>();
        if (!PlacementGates.decide(placement, null, null).isDenied()) {
            PlacementProps.want(wanted, placement, true, List.of(SPOT), NOT_PLACED, EVERY_ITEM);
        }
        return wanted;
    }

    @Test
    void aPlacementWithARoleAndPropsStandsBothAndRemovesBoth() {
        NpcPlacementAsset martha = NpcPlacementAsset.of("harvest_feast_cook", true,
                NpcPlacementAsset.Identity.of("Harvest_Feast_Cook"), null, AT_SPAWN, null, null, null, null,
                new NpcPlacementAsset.Prop[] {TABLE, BENCH});
        FakeProps engine = new FakeProps();
        PlacementProps.Book<Integer> book = new PlacementProps.Book<>();
        Set<String> ledger = new HashSet<>();

        book.reconcile(round(martha, false, ledger::contains, EVERY_ITEM), Set.of(), engine);
        assertTrue(engine.drawn.isEmpty(), "a role's props stand with its NPC, so none before it is placed");

        // The place pass stands the cook (a ledger miss over a ticking section), and its row is written.
        assertEquals(PlaceDecision.PLACE, NpcPlacementReconciler.decidePlace(
                new PlaceInputs(true, true, false, true, false, false, false, false)));
        ledger.add(SPOT.anchorKey());
        book.reconcile(round(martha, false, ledger::contains, EVERY_ITEM), Set.of(), engine);
        assertEquals(2, engine.standing.size(), "the cook and her table stand together");

        // The gate now denies: the despawn pass removes the cook, and the round wants none of her props.
        assertEquals(ResidentDecision.DESPAWN, NpcPlacementReconciler.decideResident(
                new ResidentInputs(true, false, false, true, true)));
        Tally removed = book.reconcile(Map.of(), Set.of(), engine);
        assertEquals(new Tally(0, 2), removed, "the table goes with her");
        assertTrue(engine.standing.isEmpty());
    }

    @Test
    void anInstanceDrawsOnlyWhereItStands() {
        AnchorPosition first = AnchorPosition.single(AnchorPosition.AnchorKind.WORLD_SPAWN, 0, 64, 0, 0f);
        AnchorPosition second = new AnchorPosition(AnchorPosition.AnchorKind.STRUCTURE, "1_64_1", 1, 64, 1, 0f);
        AnchorPosition third = new AnchorPosition(AnchorPosition.AnchorKind.STRUCTURE, "2_64_2", 2, 64, 2, 0f);
        List<AnchorPosition> union = List.of(first, second, third);

        assertEquals(List.of(first, second), PlacementProps.standingAt(true, union, NOT_PLACED, 2),
                "a placement drawing only props counts its MaxPerWorld across them, in the anchor order");
        assertEquals(union, PlacementProps.standingAt(true, union, NOT_PLACED, 0), "0 is unlimited");
        assertEquals(List.of(second), PlacementProps.standingAt(false, union, second.anchorKey()::equals, 2),
                "a role's instance draws where its NPC is placed, whose own count the ledger already keeps");
    }

    @Test
    void anUndecidedPlacementKeepsWhatItDrew() {
        // Its anchor resolved nothing this round (a spawn point still loading): absence of a position proves
        // nothing, so neither a draw nor a removal.
        FakeProps engine = new FakeProps();
        PlacementProps.Book<Integer> book = new PlacementProps.Book<>();
        book.reconcile(round(feastTable(TABLE), true, NOT_PLACED, EVERY_ITEM), Set.of(), engine);

        assertEquals(new Tally(0, 0), book.reconcile(Map.of(), Set.of("feast_table"), engine));
        assertEquals(1, engine.standing.size());
    }

    @Test
    void anEditedEntryIsDrawnAfreshAndARemovedOneGoes() {
        FakeProps engine = new FakeProps();
        PlacementProps.Book<Integer> book = new PlacementProps.Book<>();
        book.reconcile(round(feastTable(TABLE, BENCH), true, NOT_PLACED, EVERY_ITEM), Set.of(), engine);

        NpcPlacementAsset.Prop movedTable = NpcPlacementAsset.Prop.of("Furniture_Tavern_Table", Vec3.of(3.0, null, null),
                90.0, null);
        Tally edited = book.reconcile(round(feastTable(movedTable), true, NOT_PLACED, EVERY_ITEM), Set.of(), engine);

        assertEquals(new Tally(1, 2), edited, "the moved table is redrawn where it now belongs, and the bench is gone");
        assertEquals(List.of(13.0), engine.standing.values().stream().map(Spot::x).toList());
    }

    // ==================== drawn, not saved: the section's sleep and wake ====================

    @Test
    void aPropWaitsForItsSectionToTickAndTheWakeAsksForIt() {
        FakeProps engine = new FakeProps();
        engine.allTicking = false;
        PlacementProps.Book<Integer> book = new PlacementProps.Book<>();
        Map<String, Want> wanted = round(feastTable(TABLE), true, NOT_PLACED, EVERY_ITEM);
        SectionPos section = wanted.values().iterator().next().spots().get(0).section();

        assertEquals(new Tally(0, 0), book.reconcile(wanted, Set.of(), engine),
                "a section asleep would drop the prop at once, so nothing is drawn into it and nothing wakes it");
        assertTrue(book.wantsSection(section, engine::standing), "its wake is what draws it");
        assertFalse(book.wantsSection(new SectionPos(section.x() + 4, section.y(), section.z()), engine::standing),
                "a section holding no prop asks for nothing");

        engine.ticking.add(section);
        assertEquals(new Tally(1, 0), book.reconcile(wanted, Set.of(), engine));
        assertFalse(book.wantsSection(section, engine::standing), "drawn and standing: its next wake asks for nothing");
    }

    @Test
    void aPropDroppedByItsSectionSleepingIsDrawnAgainWhenItTicks() {
        FakeProps engine = new FakeProps();
        PlacementProps.Book<Integer> book = new PlacementProps.Book<>();
        Map<String, Want> wanted = round(feastTable(TABLE), true, NOT_PLACED, EVERY_ITEM);
        book.reconcile(wanted, Set.of(), engine);
        Spot table = engine.drawn.get(0);

        engine.sleep(table.section());
        assertTrue(engine.standing.isEmpty(), "the engine keeps no holder for an entity that is never saved");
        assertTrue(book.wantsSection(table.section(), engine::standing), "so the section's wake asks for a sweep");
        assertEquals(new Tally(0, 0), book.reconcile(wanted, Set.of(), engine),
                "while it sleeps, a round draws nothing into it");

        engine.ticking.add(table.section());
        assertEquals(new Tally(1, 0), book.reconcile(wanted, Set.of(), engine),
                "and the sweep draws it again: absence is proof for a prop, since nothing brings it back");
        assertEquals(1, engine.standing.size());
    }

    // ==================== the fake engine ====================

    /** {@code ItemPropEntityService} and the section reads, as the round sees them. */
    private static final class FakeProps implements PlacementProps.Drawer<Integer> {

        boolean allTicking = true;
        final Set<SectionPos> ticking = new HashSet<>();
        final Map<Integer, Spot> standing = new LinkedHashMap<>();
        final List<Spot> drawn = new ArrayList<>();
        private int next = 1;

        @Override
        public boolean ticking(@Nonnull Spot spot) {
            return allTicking || ticking.contains(spot.section());
        }

        @Nullable
        @Override
        public Integer draw(@Nonnull Spot spot) {
            int handle = next++;
            standing.put(handle, spot);
            drawn.add(spot);
            return handle;
        }

        @Override
        public boolean standing(@Nonnull Integer handle) {
            return standing.containsKey(handle);
        }

        @Override
        public void remove(@Nonnull Integer handle) {
            standing.remove(handle);
        }

        /** The section goes to sleep: a prop is never saved, so the engine drops it rather than parking it. */
        void sleep(@Nonnull SectionPos section) {
            standing.values().removeIf(spot -> spot.section().equals(section));
            allTicking = false;
            ticking.remove(section);
        }
    }
}
