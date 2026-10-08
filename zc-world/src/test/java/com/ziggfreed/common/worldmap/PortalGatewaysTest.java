package com.ziggfreed.common.worldmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldSelector;

/**
 * The gateways the base game already describes: a block that draws a map marker and teleports into
 * an instance leads into the world that instance spawns. Pure over what it is handed; the engine read
 * is pinned in game by the boot's derived line.
 */
class PortalGatewaysTest {

    private static final WorldSelector IN_THE_TEMPLE = WorldSelector.of(null, new String[]{"ForgottenTemple"}, null);
    private static final String PORTAL = "Forgotten_Temple_Portal_Enter";
    private static final Function<String, PortalGateways.InstanceTarget> TEMPLE_INSTANCE = name ->
            "Forgotten_Temple_Goblins".equals(name)
                    ? new PortalGateways.InstanceTarget("instance-forgotten-temple-goblins", "ForgottenTemple")
                    : null;

    @AfterEach
    void clear() {
        GatewayConfig.getInstance().loadDefaults(Map.of());
        GatewayConfig.getInstance().mergePackLayer(Map.of());
    }

    private static PortalGateways.PortalBlock portal(String blockId, String instance, String key) {
        return new PortalGateways.PortalBlock(blockId, instance, key);
    }

    @Test
    void aPortalIntoAnInstanceIsAGatewayIntoTheWorldThatInstanceSpawns() {
        Map<String, GatewayAsset> found = PortalGateways.derive(
                List.of(portal(PORTAL, "Forgotten_Temple_Goblins", null)), TEMPLE_INSTANCE);

        GatewayAsset gateway = found.get(PORTAL);
        assertNotNull(gateway);
        assertTrue(gateway.isEnabled());
        assertEquals(List.of(PORTAL), gateway.blockIds());
        assertEquals("instance-forgotten-temple-goblins", gateway.getInto().getWorld());
        assertEquals("ForgottenTemple", gateway.getInto().getGameplayConfig());
        assertTrue(gateway.standsIn("default", "Default"), "worldgen places the portal anywhere");
        assertTrue(gateway.leadsInto(List.of(IN_THE_TEMPLE)));
    }

    @Test
    void theInteractionsOwnKeyNamesTheWorldBeforeTheInstances() {
        GatewayAsset gateway = PortalGateways.derive(
                List.of(portal(PORTAL, "Forgotten_Temple_Goblins", "portal-own-key")), TEMPLE_INSTANCE).get(PORTAL);
        assertEquals("portal-own-key", gateway.getInto().getWorld());
    }

    @Test
    void anUnpinnedInstanceIsReachedByItsGameplayConfigAlone() {
        GatewayAsset gateway = PortalGateways.derive(List.of(portal(PORTAL, "Old_Temple", null)),
                name -> new PortalGateways.InstanceTarget(null, "ForgottenTemple")).get(PORTAL);
        assertNull(gateway.getInto().getWorld());
        assertTrue(gateway.leadsInto(List.of(IN_THE_TEMPLE)));
    }

    @Test
    void aPortalIntoAnInstanceNobodyShipsOrOneNamingNoWorldIsNoGateway() {
        assertTrue(PortalGateways.derive(List.of(portal(PORTAL, "Missing", null)), TEMPLE_INSTANCE).isEmpty());
        assertTrue(PortalGateways.derive(List.of(portal(PORTAL, "Blank", null)),
                name -> new PortalGateways.InstanceTarget(" ", null)).isEmpty());
    }

    @Test
    void twoBlocksOfOneItemMakeOneGateway() {
        Map<String, GatewayAsset> found = PortalGateways.derive(List.of(
                portal(PORTAL, "Forgotten_Temple_Goblins", null),
                portal("forgotten_temple_portal_enter", "Forgotten_Temple_Goblins", null)), TEMPLE_INSTANCE);
        assertEquals(1, found.size());
    }

    @Test
    void aPackFileWithTheDerivedIdReplacesItWhole() {
        GatewayConfig.getInstance().loadDefaults(PortalGateways.derive(
                List.of(portal(PORTAL, "Forgotten_Temple_Goblins", null)), TEMPLE_INSTANCE));
        assertTrue(GatewayConfig.getInstance().resolve(PORTAL).isEnabled());

        GatewayConfig.getInstance().mergePackLayer(Map.of(PORTAL,
                GatewayAsset.of(PORTAL, false, null, null, null, null)));

        assertFalse(GatewayConfig.getInstance().resolve(PORTAL).isEnabled(), "a pack switches it off by its id");
        assertEquals(1, GatewayConfig.getInstance().all().size());
    }
}
