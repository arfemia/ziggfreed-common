package com.ziggfreed.common.instance.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.instance.hub.HubLots.Lot;
import com.ziggfreed.common.instance.hub.HubLots.Size;
import com.ziggfreed.common.instance.world.TravelDestinations;

/**
 * A hub district file's codec: every group and leaf reads as written, is written back and reads the same, an empty
 * file reads every documented default, under {@code Parent} a child keeps every leaf it does not restate, a spot turns
 * with its lot, and the shipped districts decode into a working hub (the Concourse on the middle lot with its porter's
 * spot, the scaffolding on every other lot), every hub key a shipped file names being one the en-US hub file defines.
 * Untagged: nothing here builds an engine item.
 */
class HubDistrictAssetCodecTest {

    private static final String FULL = """
            {
              "Enabled": false,
              "Season": " Harvest_Feast ",
              "Lot":     { "Id": " North ", "Size": "large", "FillsEmpty": true },
              "Prefab":  { "Base": " MyPack.Hub.Hall ", "Overlays": ["MyPack.Hub.Banners", " ", "MyPack.Hub.Lamps"],
                           "Floor": "Rock_Chalk_Brick_Smooth", "KeepContainers": true },
              "Anchors": [ { "Name": " Trainer ", "At": { "X": 4, "Y": 1, "Z": -6 }, "Yaw": 90 },
                           { "At": { "X": 1 } } ],
              "Marker":  { "Icon": "Portal.png", "NameKey": "mypack.hub.hall", "Anchor": "Trainer" }
            }
            """;

    private static final String SHIPPED = "/Server/ZiggfreedCommon/HubDistricts/";
    private static final String HUB_LANG = "/Server/Languages/en-US/ziggfreedcommon.hub.lang";
    private static final String HUB_PREFIX = "ziggfreedcommon.hub.";
    private static final Pattern HUB_KEY = Pattern.compile("\"(ziggfreedcommon\\.hub\\.[A-Za-z0-9_.]+)\"");

    /** Every shipped file of the hub that names a hub key. */
    private static final List<String> SHIPPED_FILES = List.of(
            SHIPPED + "Zc_Hub_Concourse.json",
            "/Server/Instances/ZiggfreedCommon_Hub/instance.bson",
            "/Server/NPC/Roles/Passive/Zc_Hub_Doorkeeper.json",
            "/Server/NPC/Roles/Passive/Zc_Hub_Porter.json",
            "/Server/ZiggfreedCommon/Dialogues/ZiggfreedCommon/Zc_Hub_Doorkeeper.json",
            "/Server/ZiggfreedCommon/Dialogues/ZiggfreedCommon/Zc_Hub_Porter.json",
            "/Server/NPC/Roles/Passive/Zc_Hub_Builder.json",
            "/Server/Item/Items/Hub/Zc_Hub_Door_Temple.json",
            "/Server/Item/Items/Hub/Zc_Hub_Door_Overworld.json",
            "/Server/Item/Items/Hub/Zc_Hub_Map_Board.json",
            "/Server/Item/Items/Hub/Zc_Hub_Notice_Board.json",
            "/Server/Item/Items/Hub/Zc_Hub_Sign_Building.json",
            "/Server/Item/Items/Hub/Zc_Hub_Lore_Welcome.json",
            "/Server/Item/Items/Hub/Zc_Hub_Lore_Work_Order.json",
            "/Server/Item/Items/Hub/Zc_Hub_Lore_Kweebec.json");

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        HubDistrictAsset read = decode("MyPack_Hall", FULL, null);
        assertFull(read);

        HubDistrictAsset again = decode("MyPack_Hall", HubDistrictAsset.CODEC.encode(read, new ExtraInfo()).toJson(),
                null);
        assertFull(again);
        for (String group : List.of("Enabled", "Requires", "Season", "Lot", "Prefab", "Anchors", "Marker")) {
            assertNotNull(HubDistrictAsset.CODEC.toSchema(new SchemaContext()).getProperties().get(group),
                    "the schema the Asset Editor loads declares " + group);
        }
    }

    @Test
    void anEmptyFileReadsEveryDefault() throws IOException {
        HubDistrictAsset bare = decode("Bare", "{}", null);
        assertTrue(bare.isEnabled());
        assertNull(bare.requires());
        assertNull(bare.getSeason(), "on all year");
        assertNull(bare.lotId());
        assertNull(bare.size(), "fits whatever lot it names");
        assertFalse(bare.fillsEmpty());
        assertNull(bare.basePrefab());
        assertTrue(bare.overlays().isEmpty());
        assertNull(bare.floor());
        assertFalse(bare.keepContainers());
        assertTrue(bare.anchors().isEmpty());
        assertFalse(bare.hasMarker());
        assertEquals(HubDistrictAsset.DEFAULT_MARKER_ICON, bare.markerIcon());
    }

    @Test
    void aChildKeepsWhatItDoesNotRestateLeafByLeaf() throws IOException {
        HubDistrictAsset parent = decode("MyPack_Hall", FULL, null);
        HubDistrictAsset child = decode("MyPack_Hall_East",
                "{ \"Enabled\": true, \"Lot\": { \"Id\": \"East\" }, \"Prefab\": { \"Floor\": \"Rock_Stone\" } }",
                parent);
        assertTrue(child.isEnabled());
        assertEquals("East", child.lotId(), "the mentioned leaf changed");
        assertEquals(Size.LARGE, child.size(), "its sibling in the group survived");
        assertEquals("Rock_Stone", child.floor());
        assertEquals("MyPack.Hub.Hall", child.basePrefab(), "the group's other leaves survived");
        assertEquals("Harvest_Feast", child.getSeason(), "the season is kept");
        assertEquals("Trainer", child.anchors().get(0).name(), "an unmentioned list is the parent's");
        assertEquals("mypack.hub.hall", child.markerNameKey());
    }

    @Test
    void aSpotTurnsWithItsLot() {
        HubDistrictAsset.Anchor front = HubDistrictAsset.Anchor.of("Front", null, 0.0);
        Lot north = HubLots.find("North");
        assertNotNull(north);
        HubDistrictAsset.Anchor ahead = HubDistrictAsset.Anchor.of("Ahead", Vec3.of(0.0, 1.0, -10.0), 0.0);
        HubPlan.Spot centre = HubPlan.spotOf("Hall", north, front);
        assertEquals(north.centreX() + 0.5, centre.x(), 1e-9, "the anchor block's centre stays put");
        assertEquals(north.centreZ() + 0.5, centre.z(), 1e-9);
        HubPlan.Spot turned = HubPlan.spotOf("Hall", north, ahead);
        assertEquals(north.centreZ() + 10.5, turned.z(), 1e-9,
                "ten blocks to the prefab's front (North) is ten blocks south once the North lot turns it to face "
                        + "the Concourse");
        assertEquals(HubLots.FLOOR_Y + 1.0, turned.y(), 1e-9, "Y 1 stands on the floor");
        assertEquals(180f, turned.yaw(), 1e-4, "it faces where the lot's front faces");
    }

    @Test
    void theShippedDistrictsMakeAWorkingHub() throws IOException {
        HubDistrictAsset concourse = shipped("Zc_Hub_Concourse");
        assertEquals(HubLots.CONCOURSE, concourse.lotId());
        assertNotNull(concourse.anchor("Porter"), "the porter's placement stands on this spot");
        assertNotNull(concourse.floor(), "players arrive on its floor");
        HubDistrictAsset large = shipped("Zc_Hub_Scaffold_Large");
        HubDistrictAsset small = shipped("Zc_Hub_Scaffold_Small");
        HubLayout.Result layout = HubLayout.assign(HubLots.all(), List.of(claimOf(concourse), claimOf(large),
                claimOf(small)));
        assertTrue(layout.problems().isEmpty(), "the shipped districts never clash: " + layout.problems());
        assertEquals(HubLots.all().size(), layout.placed().size(), "a library-only hub has something on every lot");
        assertEquals(HubLots.CONCOURSE, layout.lotOf(concourse.getId()).id());
    }

    @Test
    void everyHubKeyAShippedFileNamesIsShippedInEnglish() throws IOException {
        Set<String> defined = hubKeys();
        List<String> missing = new ArrayList<>();
        List<String> named = new ArrayList<>(List.of(TravelDestinations.CLOSED_KEY, TravelDestinations.HERE_KEY));
        for (String path : SHIPPED_FILES) {
            Matcher m = HUB_KEY.matcher(resource(path));
            while (m.find()) {
                named.add(m.group(1));
            }
        }
        for (String key : named) {
            if (!defined.contains(key.substring(HUB_PREFIX.length()))) {
                missing.add(key);
            }
        }
        assertTrue(named.size() > 2, "the shipped files name their keys");
        assertTrue(missing.isEmpty(), "keys a shipped hub file names that ziggfreedcommon.hub.lang lacks: " + missing);
    }

    // ==================== the pieces ====================

    private static void assertFull(@Nonnull HubDistrictAsset read) {
        assertFalse(read.isEnabled());
        assertEquals("Harvest_Feast", read.getSeason(), "the season is read trimmed");
        assertEquals("North", read.lotId(), "the lot is read trimmed");
        assertEquals(Size.LARGE, read.size(), "the size is matched without regard to case");
        assertTrue(read.fillsEmpty());
        assertEquals("MyPack.Hub.Hall", read.basePrefab());
        assertEquals(List.of("MyPack.Hub.Banners", "MyPack.Hub.Lamps"), read.overlays(), "a blank overlay is dropped");
        assertEquals("Rock_Chalk_Brick_Smooth", read.floor());
        assertTrue(read.keepContainers());
        assertEquals(1, read.anchors().size(), "a spot with no name is dropped");
        HubDistrictAsset.Anchor trainer = read.anchor("trainer");
        assertNotNull(trainer, "a spot is found without regard to case");
        assertEquals(4.0, trainer.x(), 1e-9);
        assertEquals(1.0, trainer.y(), 1e-9);
        assertEquals(-6.0, trainer.z(), 1e-9);
        assertEquals(90.0, trainer.yaw(), 1e-9);
        assertTrue(read.hasMarker());
        assertEquals("Portal.png", read.markerIcon());
        assertEquals("mypack.hub.hall", read.markerNameKey());
        assertEquals("Trainer", read.markerAnchor());
    }

    @Nonnull
    private static HubLayout.Claim claimOf(@Nonnull HubDistrictAsset district) {
        return new HubLayout.Claim(district.getId(), district.lotId(), district.size(), district.fillsEmpty(), false);
    }

    @Nonnull
    private static HubDistrictAsset shipped(@Nonnull String id) throws IOException {
        return decode(id, resource(SHIPPED + id + ".json"), null);
    }

    @Nonnull
    private static String resource(@Nonnull String path) throws IOException {
        try (InputStream in = HubDistrictAssetCodecTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped file: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The keys {@code ziggfreedcommon.hub.lang} defines, without the prefix its file name carries. */
    @Nonnull
    private static Set<String> hubKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        for (String line : resource(HUB_LANG).split("\\R")) {
            int eq = line.indexOf('=');
            if (!line.isBlank() && !line.trim().startsWith("#") && eq > 0) {
                keys.add(line.substring(0, eq).trim());
            }
        }
        return keys;
    }

    @Nonnull
    private static HubDistrictAsset decode(@Nonnull String id, @Nonnull String json,
            @Nullable HubDistrictAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HubDistrictAsset.class, id,
                parent == null ? null : parent.getId());
        return HubDistrictAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
