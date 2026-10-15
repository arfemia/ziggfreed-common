package com.ziggfreed.common.objectives.drop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.drop.QuestItemDrops.Rest;
import com.ziggfreed.common.objectives.drop.QuestItemDrops.Route;
import com.ziggfreed.common.quest.QuestItemDrop;
import com.ziggfreed.common.quest.QuestItemDrop.Delivery;
import com.ziggfreed.common.quest.QuestItemDrop.Source;

/**
 * The one place a quest item drop's way is chosen ({@link QuestItemDrops#route}): each delivery takes its own
 * route, an unauthored one its source's, and a delivery that cannot be made keeps the item in the step's Claim.
 */
class QuestItemDropRouteTest {

    @Test
    void eachDeliveryTakesItsOwnRouteAndAGroundDropWithNowhereToLandWaitsInTheClaim() {
        assertEquals(new Route(false, Rest.CLAIM), QuestItemDrops.route(Delivery.SPOILS, true),
                "spoils wait in the step's Claim, room in the bag or not");
        assertEquals(new Route(true, Rest.CLAIM), QuestItemDrops.route(Delivery.BAG_THEN_CLAIM, true),
                "the bag first, what it cannot take kept");
        assertEquals(new Route(true, Rest.NOTHING), QuestItemDrops.route(Delivery.BAG, true),
                "the bag alone: what it cannot take is not handed over");
        assertEquals(new Route(false, Rest.GROUND), QuestItemDrops.route(Delivery.GROUND, true),
                "spilled where it fell, never offered to the bag");
        assertEquals(new Route(false, Rest.CLAIM), QuestItemDrops.route(Delivery.GROUND, false),
                "with nowhere to spill it, it waits in the Claim");

        QuestItemDrop unset = QuestItemDrop.of(List.of("Wolf_Grey"), List.of("yourmod_Ogre_King"), null, null);
        assertEquals(new Route(false, Rest.CLAIM), QuestItemDrops.route(unset.deliveryFor(Source.ENCOUNTER), true),
                "an unauthored delivery on a won fight waits in its spoils");
        assertEquals(new Route(true, Rest.CLAIM), QuestItemDrops.route(unset.deliveryFor(Source.ROLE), true),
                "an unauthored delivery on a kill goes into the bag first");
    }
}
