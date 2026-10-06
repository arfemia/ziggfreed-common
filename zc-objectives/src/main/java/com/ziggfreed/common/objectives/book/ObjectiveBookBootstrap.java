package com.ziggfreed.common.objectives.book;

import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * The book's place in the shared menu, called once from the wiring root's {@code setup()}: its two
 * destinations (before any asset decodes) and the Quests and Achievements tabs. Registration only
 * ({@code RootRegistrationOnlyTest}).
 */
public final class ObjectiveBookBootstrap {

    private ObjectiveBookBootstrap() {
    }

    public static void registerMenu() {
        try {
            ObjectiveBookDestinations.register();
            ZigMenu.fill(MenuSlot.QUESTS, ObjectiveBookMenu.quests());
            ZigMenu.fill(MenuSlot.ACHIEVEMENTS, ObjectiveBookMenu.achievements());
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's menu tabs could not be registered", t);
        }
    }
}
