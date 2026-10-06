package com.ziggfreed.common.instance.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The Records tab and destination: generic on purpose (later boards can join it), claimed unprefixed
 * because the library owns the page, and shown only while the encounter board holds a fight with rows.
 */
class RecordsDestinationsTest {

    @BeforeEach
    void seed() {
        Destinations.clearForTests();
        RecordsDestinations.register();
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
        ZigMenu.clearForTests();
    }

    @Nonnull
    private static EncounterBindingAsset row(@Nonnull String id, @Nonnull String json) throws IOException {
        return EncounterBindingAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(EncounterBindingAsset.class, id, null)));
    }

    private static void defeat(@Nonnull Leaderboard board, @Nonnull EncounterBindingAsset.Leaderboard group) {
        List<ParticipantShare> shares = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            shares.add(new ParticipantShare(UUID.randomUUID(), "p" + i, 1.0, true, 100.0, 10.0, 60.0, false));
        }
        EncounterDefeatedEvent event = new EncounterDefeatedEvent(UUID.randomUUID(), "Boss_Script", null, null, "Boss",
                shares, shares.stream().map(ParticipantShare::playerId).toList(), Map.of(), Map.of(), 90.0, 0,
                null, null);
        assertEquals(2, EncounterLeaderboardListener.record(board, event, group));
    }

    @Test
    void theTypeIsClaimedUnprefixed() throws IOException {
        assertTrue(Destinations.isRegistered(RecordsDestinations.TYPE));
        assertInstanceOf(RecordsDestinations.Records.class,
                Destination.CODEC.decodeJson(RawJsonReader.fromJsonString("\"Records\""), new ExtraInfo()));
    }

    @Test
    void theTabShowsOnlyWhileTheBoardHoldsARankedFight() throws IOException {
        EncounterBindingAsset ranked = row("A_Boss", "{ \"Leaderboard\": { \"Bucket\": \"a\", \"ByPartySize\": true } }");
        EncounterBindingAsset unranked = row("C_Boss", "{}");
        Leaderboard board = new Leaderboard("test");

        assertFalse(RecordsDestinations.shows(null, List.of(ranked)), "no board on this server");
        assertFalse(RecordsDestinations.shows(board, List.of(ranked, unranked)), "a board nobody has a record on");
        defeat(board, ranked.getLeaderboard());
        assertTrue(RecordsDestinations.shows(board, List.of(ranked, unranked)));
    }

    @Test
    void theEntryFillsTheRecordsSlot() {
        MenuEntry entry = RecordsDestinations.entry();
        assertEquals(MenuSlot.RECORDS.id(), entry.id());
        assertEquals(RecordsDestinations.TYPE, Destinations.typeIdOf(entry.opens()));
    }
}
