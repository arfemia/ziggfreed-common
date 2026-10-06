package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.Destinations;

/** The book's setup phase claims its two screens and fills its two tabs, each opening its own screen. */
class ObjectiveBookBootstrapTest {

    @AfterEach
    void reset() {
        ZigMenu.clearForTests();
        Destinations.clearForTests();
    }

    @Test
    void theBookFillsTheQuestsAndAchievementsSlots() {
        ObjectiveBookBootstrap.registerMenu();

        MenuEntry quests = ZigMenu.slot(MenuSlot.QUESTS);
        MenuEntry achievements = ZigMenu.slot(MenuSlot.ACHIEVEMENTS);
        assertNotNull(quests);
        assertNotNull(achievements);
        assertEquals(ObjectiveBookDestinations.QUEST_LOG_TYPE, Destinations.typeIdOf(quests.opens()));
        assertEquals(ObjectiveBookDestinations.ACHIEVEMENTS_TYPE, Destinations.typeIdOf(achievements.opens()));
    }
}
