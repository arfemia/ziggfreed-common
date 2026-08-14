package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The two commerce destinations: readable now, opening nothing yet.
 *
 * <p>That combination is the point of this test. An unknown {@code Type} FAILS an asset read, so a
 * shop block or a bounty master could not be authored at all until these types exist - and a handler
 * that declines is something every caller already has to cope with, since it still owes the player
 * whatever it would have done anyway. When the pages land, only the two handler bodies change; the
 * decode, the audit and every authored file stay exactly as they are, which is what these
 * assertions pin.
 */
class CommerceDestinationsTest {

    @BeforeEach
    void seed() {
        Destinations.clearForTests();
        CommerceDestinations.register();
    }

    @AfterEach
    void clear() {
        Destinations.clearForTests();
        ShopConfig.getInstance().mergePackLayer(Map.of());
        BoardConfig.getInstance().mergePackLayer(Map.of());
    }

    private static Destination decode(String json) throws IOException {
        return Destination.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    /** A context carrying no live handles; nothing under test reads one. */
    private static DestinationContext noHandles() {
        return new DestinationContext(null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("content can name a storefront or a board, and say which one")
    void bothTypesDecodeWithTheirOwnField() throws Exception {
        CommerceDestinations.Shop shop = assertInstanceOf(CommerceDestinations.Shop.class,
                decode("{ \"Type\": \"Shop\", \"Shop\": \"General\" }"));
        CommerceDestinations.Board board = assertInstanceOf(CommerceDestinations.Board.class,
                decode("{ \"Type\": \"Board\", \"Board\": \"Daily\" }"));

        assertEquals("General", shop.getShop());
        assertEquals("Daily", board.getBoard());
    }

    @Test
    @DisplayName("the bare word is the same value, for the commonest case of all")
    void theBareFormIsTheSameThing() throws Exception {
        CommerceDestinations.Shop shop =
                assertInstanceOf(CommerceDestinations.Shop.class, decode("\"Shop\""));
        CommerceDestinations.Board board =
                assertInstanceOf(CommerceDestinations.Board.class, decode("\"Board\""));

        assertNull(shop.getShop(), "unnamed means whichever one the moment is already about");
        assertNull(board.getBoard());
    }

    @Test
    @DisplayName("opening one declines rather than reporting a screen it did not paint")
    void aHandlerDeclinesUntilThePagesLand() {
        assertFalse(Destinations.open(CommerceDestinations.Shop.of("general"), noHandles()));
        assertFalse(Destinations.open(CommerceDestinations.Board.of("daily"), noHandles()));
    }

    @Test
    @DisplayName("a storefront or a board no layer defines is a warning, never an error")
    void anUnknownIdIsReportedLeniently() {
        List<Finding> shop = Destinations.validate(CommerceDestinations.Shop.of("no_such_shop"),
                "a_placement");
        List<Finding> board = Destinations.validate(CommerceDestinations.Board.of("no_such_board"),
                "a_dialogue");

        assertEquals(1, shop.size());
        assertEquals(Severity.WARNING, shop.get(0).severity(),
                "the pack that ships it may simply not be installed on the server doing the checking");
        assertEquals(CommerceDestinations.UNKNOWN_SHOP, shop.get(0).code());
        assertEquals(1, board.size());
        assertEquals(CommerceDestinations.UNKNOWN_BOARD, board.get(0).code());
    }

    @Test
    @DisplayName("a storefront that does exist, and one that names none, are both silent")
    void aKnownOrUnnamedTargetSaysNothing() throws Exception {
        ShopConfig.getInstance().mergePackLayer(Map.of("general",
                CommerceFoldFixtures.shop("Shops/MMOSkillTree/General.json")));

        assertTrue(Destinations.validate(CommerceDestinations.Shop.of("General"), "src").isEmpty(),
                "an id is matched however it is written");
        assertTrue(Destinations.validate(CommerceDestinations.Shop.of(null), "src").isEmpty(),
                "a destination naming nothing has nothing to check");
    }
}
