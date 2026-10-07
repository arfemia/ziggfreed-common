package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The storefront page sells through an engine over its own storefront's catalogue, so a press, a shelf draw
 * and a reroll judge an included offer by this page's storefront; and everything it lists comes through
 * StorefrontView, so Includes reach the offers, the shelves, the category order and names and the header's
 * wallets alike. The page cannot be built in a unit JVM, so the guard reads its source.
 */
class ZigShopPageIncludesTest {

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "commerce",
            "page", "ZigShopPage.java");

    @Test
    void thePageSellsAndListsAsItsOwnStorefront() throws IOException {
        String source = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertFalse(source.contains("CommerceEngines.shops()"), "no engine over the catalogue's own views");
        Matcher engines = Pattern.compile("CommerceEngines\\.shopsAt\\(shopId\\)").matcher(source);
        int count = 0;
        while (engines.find()) {
            count++;
        }
        assertEquals(3, count, "build, the action handler and the row select each build the host's engine");
        assertFalse(source.contains("availableOffersOf(shopId)"), "the standing offers");
        assertFalse(source.contains("CommerceCatalogs.shelvesOf(shopId)"), "the shelves, drawn and rerolled");
        assertFalse(source.contains("asset.categoryOrder()"), "the category order");
        assertFalse(source.contains("asset.currencyIds()"), "the header's wallets");
        assertTrue(source.contains("StorefrontView.offers(shopId)"));
        assertTrue(source.contains("StorefrontView.shelves(shopId)"));
        assertTrue(source.contains("StorefrontView.categoryOrder(shopId)"));
        assertTrue(source.contains("StorefrontView.currencyIds(shopId)"));
        assertTrue(source.contains("StorefrontView.namingCategory(shopId, categoryId)"));
    }
}
