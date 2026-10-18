package com.ziggfreed.common.instance.claims;

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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A reward chest file's codec: every group and leaf reads as written, is written back and reads the same, an empty
 * file reads every documented default (the library's boss chest), under {@code Parent} a child keeps every leaf it
 * does not restate, group by group, and the two shipped chests decode: {@code Zc_Spoils_Chest} reads exactly as the
 * empty file (what a won fight left before chest files existed), and every key either shipped file names is a key the
 * shipped en-US claims file defines. Untagged: nothing here builds an engine item.
 */
class RewardChestAssetCodecTest {

    private static final String FULL = """
            {
              "Look":     { "Prop": " Furniture_Ancient_Chest_Small ", "HintKey": "mypack.chest.hint" },
              "Open":     { "Credited": false },
              "Contents": { "Lootable": " MyPack_Trork_Hoard ", "Diminish": { "Steps": [1, 0.5], "Window": "Daily" },
                            "TitleKey": "mypack.chest.title" },
              "View":     { "ItemsWindow": false },
              "Banner":   { "TitleKey": "mypack.chest.banner.title", "SubtitleKey": "mypack.chest.banner", "Major": true },
              "Text":     { "LeadKey": "mypack.chest.lead", "NoticeKey": "mypack.chest.notice" },
              "Despawn":  { "Lifetime": 120, "WhenEmptied": false }
            }
            """;

    private static final String SHIPPED = "/Server/ZiggfreedCommon/RewardChests/";
    private static final String CLAIMS_LANG = "/Server/Languages/en-US/ziggfreedcommon.claims.lang";

    @Test
    void everyLeafRoundTripsThroughTheCodec() throws IOException {
        RewardChestAsset read = decode("MyPack_Trork_Chest", FULL, null);
        assertFull(read);

        RewardChestAsset again = decode("MyPack_Trork_Chest",
                RewardChestAsset.CODEC.encode(read, new ExtraInfo()).toJson(), null);
        assertFull(again);
        for (String group : List.of("Look", "Open", "Contents", "View", "Banner", "Text", "Despawn")) {
            assertNotNull(RewardChestAsset.CODEC.toSchema(new SchemaContext()).getProperties().get(group),
                    "the schema the Asset Editor loads declares " + group);
        }
    }

    @Test
    void anEmptyFileReadsEveryDefaultTheBossChest() throws IOException {
        RewardChestAsset bare = decode("Bare", "{}", null);
        assertEquals(RewardChestAsset.DEFAULT_PROP, bare.prop());
        assertEquals(RewardChestAsset.DEFAULT_HINT_KEY, bare.hintKey());
        assertTrue(bare.openCredited(), "the credited players open it unless a file says not");
        assertNull(bare.lootable(), "no Contents: it holds only what its source pays");
        assertNull(bare.diminish());
        assertNull(bare.contentsTitleKey());
        assertTrue(bare.itemsWindow(), "what is not an item first, then the game's chest window");
        assertNull(bare.bannerTitleKey(), "no banner of its own");
        assertNull(bare.bannerSubtitleKey());
        assertFalse(bare.bannerMajor());
        assertEquals(RewardChestAsset.DEFAULT_LEAD_KEY, bare.leadKey());
        assertEquals(RewardChestAsset.DEFAULT_NOTICE_KEY, bare.noticeKey());
        assertEquals(RewardChestAsset.DEFAULT_LIFETIME_SECONDS, bare.lifetimeSeconds());
        assertTrue(bare.goesWhenEmptied());
        assertEquals(RewardChestAsset.DEFAULT_LIFETIME_SECONDS,
                decode("Zero", "{ \"Despawn\": { \"Lifetime\": 0 } }", null).lifetimeSeconds(),
                "a lifetime of nothing reads as the default");
        assertSameChest(RewardChestAsset.BARE, bare, "an empty file is the structural default");
    }

    @Test
    void aChildKeepsWhatItDoesNotRestateGroupByGroup() throws IOException {
        RewardChestAsset parent = decode("MyPack_Trork_Chest", FULL, null);
        RewardChestAsset child = decode("MyPack_Goblin_Chest",
                "{ \"Look\": { \"HintKey\": \"mypack.goblin.hint\" }, \"Contents\": { \"Lootable\": \"MyPack_Goblin\" } }",
                parent);
        assertEquals("mypack.goblin.hint", child.hintKey(), "the mentioned leaf changed");
        assertEquals("Furniture_Ancient_Chest_Small", child.prop(), "its sibling in the group survived");
        assertEquals("MyPack_Goblin", child.lootable());
        assertEquals("mypack.chest.title", child.contentsTitleKey(), "the group's other leaves survived");
        assertNotNull(child.diminish());
        assertEquals("mypack.chest.banner.title", child.bannerTitleKey(), "an unmentioned group is the parent's");
        assertEquals(120, child.lifetimeSeconds());
        assertFalse(child.itemsWindow());
    }

    @Test
    void theShippedBossChestIsTodaysChest() throws IOException {
        RewardChestAsset spoils = shipped(RewardChestAsset.SPOILS_CHEST);
        assertSameChest(RewardChestAsset.BARE, spoils,
                "Zc_Spoils_Chest stands exactly what a won fight left before chest files existed");
    }

    @Test
    void theShippedRewardChestShowsABannerAndSpeaksItsOwnWords() throws IOException {
        RewardChestAsset reward = shipped(RewardChestAsset.REWARD_CHEST);
        assertNotNull(reward.bannerTitleKey(), "the neutral chest shows a banner as it goes up");
        assertNull(reward.lootable(), "it holds nothing of its own until a chest that takes it as Parent names one");
        assertFalse(reward.leadKey().equals(RewardChestAsset.DEFAULT_LEAD_KEY), "its page does not speak of a boss");
        assertFalse(reward.noticeKey().equals(RewardChestAsset.DEFAULT_NOTICE_KEY),
                "its toast does not speak of a boss");
    }

    @Test
    void everyKeyAShippedChestNamesIsShippedInEnglish() throws IOException {
        Set<String> defined = claimsKeys();
        List<String> missing = new ArrayList<>();
        for (String id : List.of(RewardChestAsset.SPOILS_CHEST, RewardChestAsset.REWARD_CHEST)) {
            RewardChestAsset chest = shipped(id);
            List<String> named = new ArrayList<>(List.of(chest.hintKey(), chest.leadKey(), chest.noticeKey(),
                    chest.noticeKey() + RewardChestAsset.NAMED_SUFFIX));
            if (chest.bannerTitleKey() != null) {
                named.add(chest.bannerTitleKey());
            }
            if (chest.bannerSubtitleKey() != null) {
                named.add(chest.bannerSubtitleKey());
            }
            for (String key : named) {
                if (!key.startsWith(ClaimsText.PREFIX) || !defined.contains(key.substring(ClaimsText.PREFIX.length()))) {
                    missing.add(id + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys a shipped chest names that ziggfreedcommon.claims.lang lacks: " + missing);
    }

    // ==================== the pieces ====================

    private static void assertFull(@Nonnull RewardChestAsset read) {
        assertEquals("Furniture_Ancient_Chest_Small", read.prop(), "the prop is read trimmed");
        assertEquals("mypack.chest.hint", read.hintKey());
        assertFalse(read.openCredited());
        assertEquals("MyPack_Trork_Hoard", read.lootable(), "the table is read trimmed");
        assertNotNull(read.diminish());
        assertEquals("mypack.chest.title", read.contentsTitleKey());
        assertFalse(read.itemsWindow());
        assertEquals("mypack.chest.banner.title", read.bannerTitleKey());
        assertEquals("mypack.chest.banner", read.bannerSubtitleKey());
        assertTrue(read.bannerMajor());
        assertEquals("mypack.chest.lead", read.leadKey());
        assertEquals("mypack.chest.notice", read.noticeKey());
        assertEquals(120, read.lifetimeSeconds());
        assertFalse(read.goesWhenEmptied());
    }

    /** Every read of {@code actual} answers as {@code expected}'s does. */
    private static void assertSameChest(@Nonnull RewardChestAsset expected, @Nonnull RewardChestAsset actual,
            @Nonnull String why) {
        assertEquals(expected.prop(), actual.prop(), why + ": Look.Prop");
        assertEquals(expected.hintKey(), actual.hintKey(), why + ": Look.HintKey");
        assertEquals(expected.openCredited(), actual.openCredited(), why + ": Open.Credited");
        assertEquals(expected.lootable(), actual.lootable(), why + ": Contents.Lootable");
        assertEquals(expected.diminish() == null, actual.diminish() == null, why + ": Contents.Diminish");
        assertEquals(expected.contentsTitleKey(), actual.contentsTitleKey(), why + ": Contents.TitleKey");
        assertEquals(expected.itemsWindow(), actual.itemsWindow(), why + ": View.ItemsWindow");
        assertEquals(expected.bannerTitleKey(), actual.bannerTitleKey(), why + ": Banner.TitleKey");
        assertEquals(expected.bannerSubtitleKey(), actual.bannerSubtitleKey(), why + ": Banner.SubtitleKey");
        assertEquals(expected.bannerMajor(), actual.bannerMajor(), why + ": Banner.Major");
        assertEquals(expected.leadKey(), actual.leadKey(), why + ": Text.LeadKey");
        assertEquals(expected.noticeKey(), actual.noticeKey(), why + ": Text.NoticeKey");
        assertEquals(expected.lifetimeSeconds(), actual.lifetimeSeconds(), why + ": Despawn.Lifetime");
        assertEquals(expected.goesWhenEmptied(), actual.goesWhenEmptied(), why + ": Despawn.WhenEmptied");
    }

    @Nonnull
    private static RewardChestAsset shipped(@Nonnull String id) throws IOException {
        String path = SHIPPED + id + ".json";
        try (InputStream in = RewardChestAssetCodecTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped chest: " + path);
            return decode(id, new String(in.readAllBytes(), StandardCharsets.UTF_8), null);
        }
    }

    /** The keys {@code ziggfreedcommon.claims.lang} defines, without the prefix its file name carries. */
    @Nonnull
    private static Set<String> claimsKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        try (InputStream in = RewardChestAssetCodecTest.class.getResourceAsStream(CLAIMS_LANG)) {
            assertNotNull(in, "missing shipped lang file: " + CLAIMS_LANG);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                int eq = line.indexOf('=');
                if (!line.isBlank() && !line.trim().startsWith("#") && eq > 0) {
                    keys.add(line.substring(0, eq).trim());
                }
            }
        }
        return keys;
    }

    @Nonnull
    private static RewardChestAsset decode(@Nonnull String id, @Nonnull String json,
            @Nullable RewardChestAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(RewardChestAsset.class, id,
                parent == null ? null : parent.getId());
        return RewardChestAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
