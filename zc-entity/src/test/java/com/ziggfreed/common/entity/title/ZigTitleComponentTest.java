package com.ziggfreed.common.entity.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The per-player title record: case-blind ids, the reserved save delimiters, a shown title that
 * is always an earned one, the codec round trip the engine save makes, the connect seed that
 * puts the shown title where off-thread readers find it, and a leaving player who keeps it there.
 */
class ZigTitleComponentTest {

    @AfterEach
    void forgetTheMirror() {
        ActiveTitles.persistTo(null, null);
        ActiveTitles.clear();
    }

    @Test
    void aGrantAndALookupMeetWhateverTheCasing() {
        ZigTitleComponent titles = new ZigTitleComponent();

        assertTrue(titles.unlock("Hallows_Eve_Hallowed"));
        assertTrue(titles.hasTitle("hallows_eve_hallowed"));
        assertTrue(titles.hasTitle("HALLOWS_EVE_HALLOWED"));
        assertFalse(titles.unlock("hallows_eve_hallowed"), "a second grant adds nothing");
    }

    @Test
    void anIdCarryingAReservedDelimiterOrNothingIsRefused() {
        ZigTitleComponent titles = new ZigTitleComponent();

        assertFalse(titles.unlock("bad|title"), "'|' is the join character of the save format");
        assertFalse(titles.unlock("ns:title"), "':' is reserved with it");
        assertFalse(titles.unlock("   "));
        assertFalse(titles.unlock(null));
        assertTrue(titles.unlockedTitles.isEmpty(), "a refused grant leaves the set untouched");
    }

    @Test
    void onlyAnUnlockedTitleCanBeShownAndShowingItTwiceChangesNothing() {
        ZigTitleComponent titles = new ZigTitleComponent();
        assertFalse(titles.activate("hallows_eve_hallowed"), "not earned yet");
        assertNull(titles.activeTitle());

        titles.unlock("Hallows_Eve_Hallowed");
        assertTrue(titles.activate("HALLOWS_EVE_HALLOWED"));
        assertEquals("hallows_eve_hallowed", titles.activeTitle());
        assertFalse(titles.activate("hallows_eve_hallowed"), "already the one shown");
    }

    @Test
    void takingTheShownTitleOffLeavesNoneAndASecondTakeOffChangesNothing() {
        ZigTitleComponent titles = new ZigTitleComponent();
        titles.unlock("hallows_eve_hallowed");
        titles.activate("hallows_eve_hallowed");

        assertTrue(titles.deactivate());
        assertNull(titles.activeTitle());
        assertFalse(titles.deactivate());
        assertTrue(titles.hasTitle("hallows_eve_hallowed"), "taking a title off never takes it away");
    }

    @Test
    void revokingTheShownTitleAlsoStopsShowingIt() {
        ZigTitleComponent titles = new ZigTitleComponent();
        titles.unlock("hallows_eve_hallowed");
        titles.unlock("pumpkin_king");
        titles.activate("hallows_eve_hallowed");

        assertTrue(titles.revoke("Hallows_Eve_Hallowed"));
        assertNull(titles.activeTitle());
        assertFalse(titles.revoke("hallows_eve_hallowed"), "already gone");
        assertTrue(titles.hasTitle("pumpkin_king"));
    }

    @Test
    void theCodecRoundTripsBothLeavesAndAnEmptyRecord() {
        ZigTitleComponent titles = new ZigTitleComponent();
        titles.unlock("hallows_eve_hallowed");
        titles.unlock("pumpkin_king");
        titles.activate("pumpkin_king");

        ExtraInfo info = new ExtraInfo();
        ZigTitleComponent decoded = ZigTitleComponent.CODEC.decode(ZigTitleComponent.CODEC.encode(titles, info), info);
        assertEquals(titles.unlockedTitles, decoded.unlockedTitles);
        assertEquals("pumpkin_king", decoded.activeTitle());

        ZigTitleComponent empty = ZigTitleComponent.CODEC.decode(
                ZigTitleComponent.CODEC.encode(new ZigTitleComponent(), info), info);
        assertTrue(empty.unlockedTitles.isEmpty());
        assertNull(empty.activeTitle());
    }

    @Test
    void aSavedShownTitleThatIsNoLongerUnlockedReadsAsNone() throws IOException {
        ZigTitleComponent decoded = ZigTitleComponent.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"UnlockedTitles\": \"pumpkin_king\", \"ActiveTitle\": \"Hallows_Eve_Hallowed\"}"),
                new ExtraInfo());

        assertNull(decoded.activeTitle(), "a hand-edited or half-written save never shows an unearned title");
        assertTrue(decoded.hasTitle("pumpkin_king"));
    }

    @Test
    void theWritePathDropsAReservedIdLoudlyInsteadOfCorruptingItsNeighbours() {
        // A grant is already refused, so only a direct write to the public set can smuggle one in.
        Set<String> unlocked = new LinkedHashSet<>();
        unlocked.add("hallows_eve_hallowed");
        unlocked.add("bad|title");
        unlocked.add("pumpkin_king");

        assertEquals(Set.of("hallows_eve_hallowed", "pumpkin_king"),
                ZigTitleComponent.deserializeStringSet(ZigTitleComponent.serializeStringSet(unlocked)));
    }

    @Test
    void aCopyKeepsBothLeavesAndIsIndependent() {
        ZigTitleComponent titles = new ZigTitleComponent();
        titles.unlock("pumpkin_king");
        titles.activate("pumpkin_king");

        ZigTitleComponent copy = titles.clone();
        assertEquals("pumpkin_king", copy.activeTitle());
        copy.unlock("hallows_eve_hallowed");
        assertFalse(titles.hasTitle("hallows_eve_hallowed"));
    }

    @Test
    void connectingSeedsTheMirrorWithTheSavedShownTitle() {
        UUID player = UUID.randomUUID();
        ZigTitleComponent titles = new ZigTitleComponent();
        titles.unlock("pumpkin_king");
        titles.activate("pumpkin_king");

        ZigTitleComponent.seed(player, titles);
        assertEquals("pumpkin_king", ActiveTitles.of(player), "a restart shows the title from the first frame");

        ZigTitleComponent.seed(player, new ZigTitleComponent());
        assertNull(ActiveTitles.of(player), "a record showing nothing clears what the mirror held");

        ZigTitleComponent.seed(null, titles); // no identity to key: a quiet no-op
        ZigTitleComponent.seed(player, null);
        assertNull(ActiveTitles.of(player));
    }

    @Test
    void leavingKeepsTheShownTitleForOfflineRows() {
        UUID player = UUID.randomUUID();
        ZigTitleComponent titles = new ZigTitleComponent();
        titles.unlock("pumpkin_king");
        titles.activate("pumpkin_king");
        ZigTitleComponent.seed(player, titles);

        ZigTitleComponent.left();

        assertEquals("pumpkin_king", ActiveTitles.of(player),
                "a row names an offline player with the title they chose, so a title says nothing about presence");
    }

    @Test
    void leavingWritesTheShownTitleDownForARestart(@TempDir Path dir) {
        Path file = dir.resolve("shown-titles.json");
        ActiveTitles.persistTo(file, null);
        UUID player = UUID.randomUUID();
        ZigTitleComponent titles = new ZigTitleComponent();
        titles.unlock("pumpkin_king");
        titles.activate("pumpkin_king");
        ZigTitleComponent.seed(player, titles);

        ZigTitleComponent.left();
        ActiveTitles.clear();
        ActiveTitles.persistTo(file, null);

        assertEquals("pumpkin_king", ActiveTitles.of(player), "a restart still names them with it");
    }
}
