package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopConfig;

/**
 * The server owner's last word: one file, entries keyed by id, each carrying only the leaves that id
 * should read differently.
 *
 * <p>The two that matter most are the ones a hand-rolled reader gets wrong. An override has to
 * inherit from the PACK every time it is read, or a second read stacks on the first and a file that
 * says one thing starts meaning another; and a malformed file has to cost the overrides rather than
 * the boot, because an admin editing JSON by hand at 2am is the normal case rather than the
 * exceptional one.
 */
class CommerceOwnerLayersTest {

    @TempDir
    Path dir;

    @BeforeEach
    void pointAtTheTempDirectory() throws IOException {
        CommerceOwnerLayers.setDirectory(dir);
        CurrencyConfig.getInstance().mergePackLayer(Map.of("bounty_token",
                CommerceFoldFixtures.currency("Currencies/MMOSkillTree/Bounty_Token.json")));
        ShopConfig.getInstance().mergePackLayer(Map.of("general",
                CommerceFoldFixtures.shop("Shops/MMOSkillTree/General.json")));
        BoardConfig.getInstance().mergePackLayer(Map.of("daily",
                CommerceFoldFixtures.board("Boards/MMOSkillTree/Daily.json")));
    }

    @AfterEach
    void clearEverything() {
        CommerceOwnerLayers.setDirectory(CommerceOwnerLayers.DEFAULT_DIRECTORY);
        CurrencyConfig.getInstance().mergePackLayer(Map.of());
        CurrencyConfig.getInstance().mergeOwnerLayer(Map.of());
        ShopConfig.getInstance().mergePackLayer(Map.of());
        ShopConfig.getInstance().mergeOwnerLayer(Map.of());
        BoardConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    @DisplayName("with no file at all the packs simply stand")
    void noFileMeansNoOverrides() {
        CommerceOwnerLayers.reloadCurrencies();
        CommerceOwnerLayers.reloadShops();
        CommerceOwnerLayers.reloadBoards();

        assertTrue(CurrencyConfig.getInstance().isSpendable("bounty_token"));
        assertTrue(ShopConfig.getInstance().resolve("general").isEnabled());
    }

    @Test
    @DisplayName("an override writes one leaf and keeps every other one the pack wrote")
    void anOverrideIsLeafByLeaf() throws IOException {
        StorefrontAsset packVersion = ShopConfig.getInstance().resolve("general");
        write(CommerceOwnerLayers.SHOPS_FILE, """
                { "general": { "Enabled": false } }
                """);

        CommerceOwnerLayers.reloadShops();
        StorefrontAsset owned = ShopConfig.getInstance().resolve("general");

        assertNotNull(owned);
        assertFalse(owned.isEnabled(), "the owner closed the shop");
        assertEquals(packVersion.currencyIds(), owned.currencyIds(),
                "and kept the header the pack authored, having said nothing about it");
        assertEquals(packVersion.categoryOrder(), owned.categoryOrder());
    }

    @Test
    @DisplayName("reading the file twice says the same thing, rather than stacking")
    void aRereadNeverCompounds() throws IOException {
        write(CommerceOwnerLayers.CURRENCIES_FILE, """
                { "bounty_token": { "Cap": 5000 } }
                """);

        CommerceOwnerLayers.reloadCurrencies();
        CurrencyAsset once = CurrencyConfig.getInstance().resolve("bounty_token");
        CommerceOwnerLayers.reloadCurrencies();
        CurrencyAsset twice = CurrencyConfig.getInstance().resolve("bounty_token");

        assertEquals(once.cap(), twice.cap());
        assertEquals(once.effectiveIconItemId(), twice.effectiveIconItemId(),
                "a second read inherits from the pack again, not from what the first read produced");
    }

    @Test
    @DisplayName("emptying the file gives the pack its content back")
    void removingAnOverrideRestoresThePack() throws IOException {
        write(CommerceOwnerLayers.BOARDS_FILE, """
                { "daily": { "Enabled": false } }
                """);
        CommerceOwnerLayers.reloadBoards();
        assertFalse(BoardConfig.getInstance().resolve("daily").isEnabled());

        write(CommerceOwnerLayers.BOARDS_FILE, "{ }");
        CommerceOwnerLayers.reloadBoards();

        BoardAsset restored = BoardConfig.getInstance().resolve("daily");
        assertNotNull(restored);
        assertTrue(restored.isEnabled(), "an entry the owner deleted stops overriding anything");
    }

    @Test
    @DisplayName("an owner may add an id no pack ships")
    void anOwnerCanDefineTheirOwn() throws IOException {
        write(CommerceOwnerLayers.CURRENCIES_FILE, """
                { "house_credit": { "Icon": "Ore_Iron", "Color": "#cccccc" } }
                """);

        CommerceOwnerLayers.reloadCurrencies();

        CurrencyAsset mine = CurrencyConfig.getInstance().resolve("house_credit");
        assertNotNull(mine, "an id nothing else defines is a new wallet rather than an error");
        assertTrue(mine.isEnabled());
    }

    @Test
    @DisplayName("a documentation key is documentation, not an entry")
    void commentsAreSkipped() throws IOException {
        write(CommerceOwnerLayers.SHOPS_FILE, """
                { "$Comment": "close the general store while the event runs",
                  "general": { "Enabled": false } }
                """);

        CommerceOwnerLayers.reloadShops();

        assertFalse(ShopConfig.getInstance().resolve("general").isEnabled());
        assertEquals(1, ShopConfig.getInstance().ids().size(),
                "the comment did not become a storefront of its own");
    }

    @Test
    @DisplayName("$SchemaVersion 1 is a reserved marker, not an entry, in every commerce owner file")
    void schemaVersionIsReservedInEveryReader() throws IOException {
        write(CommerceOwnerLayers.CURRENCIES_FILE, """
                { "$SchemaVersion": 1, "bounty_token": { "Cap": 5000 } }
                """);
        write(CommerceOwnerLayers.SHOPS_FILE, """
                { "$SchemaVersion": 1, "general": { "Enabled": false } }
                """);
        write(CommerceOwnerLayers.BOARDS_FILE, """
                { "$SchemaVersion": 1, "daily": { "Enabled": false } }
                """);

        CommerceOwnerLayers.reloadCurrencies();
        CommerceOwnerLayers.reloadShops();
        CommerceOwnerLayers.reloadBoards();

        assertEquals(5000, CurrencyConfig.getInstance().resolve("bounty_token").cap(),
                "the override beside the marker is in force");
        assertFalse(ShopConfig.getInstance().resolve("general").isEnabled());
        assertFalse(BoardConfig.getInstance().resolve("daily").isEnabled());
        assertEquals(1, ShopConfig.getInstance().ids().size(),
                "the marker did not become a storefront of its own");
    }

    @Test
    @DisplayName("a file declaring a newer $SchemaVersion is refused whole, never guessed at")
    void aNewerSchemaVersionRefusesTheFile() throws IOException {
        write(CommerceOwnerLayers.SHOPS_FILE, """
                { "$SchemaVersion": 2, "general": { "Enabled": false } }
                """);

        CommerceOwnerLayers.reloadShops();

        assertTrue(ShopConfig.getInstance().resolve("general").isEnabled(),
                "nothing in a future-shaped file is in force; the packs stand");
    }

    @Test
    @DisplayName("one unreadable entry costs itself and nothing else")
    void aBadEntryIsSkippedAndTheRestAreCarried() throws IOException {
        write(CommerceOwnerLayers.SHOPS_FILE, """
                { "broken": "not a block of settings",
                  "general": { "Enabled": false } }
                """);

        CommerceOwnerLayers.reloadShops();

        assertFalse(ShopConfig.getInstance().resolve("general").isEnabled(),
                "the entry that could be read is still in force");
    }

    @Test
    @DisplayName("a malformed file costs the overrides, never the boot")
    void aMalformedFileIsTreatedAsEmpty() throws IOException {
        write(CommerceOwnerLayers.BOARDS_FILE, "this is not json at all");

        CommerceOwnerLayers.reloadBoards();

        assertTrue(BoardConfig.getInstance().resolve("daily").isEnabled(),
                "nothing was applied, and the packs stand exactly as they were");
        assertEquals("this is not json at all",
                Files.readString(dir.resolve(CommerceOwnerLayers.BOARDS_FILE), StandardCharsets.UTF_8),
                "and the owner's file is left exactly as they wrote it");
    }

    private void write(String fileName, String body) throws IOException {
        Files.writeString(dir.resolve(fileName), body, StandardCharsets.UTF_8);
    }
}
