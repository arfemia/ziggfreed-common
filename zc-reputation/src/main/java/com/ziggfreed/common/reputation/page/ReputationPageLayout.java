package com.ziggfreed.common.reputation.page;

import java.util.List;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * What {@code Pages/ZigReputationPage.ui} must declare for {@link ReputationPage}: every element the page
 * paints, and the list column's width. Kept off the page class because a page cannot load in a unit JVM
 * (its engine superclass's static init reaches the engine logger), so the document test reads them here.
 */
final class ReputationPageLayout {

    /**
     * Every element the page paints in its own document (the kit's painters reach inside the list and the
     * reading page by the ids their templates declare); a command against a missing one disconnects the player.
     */
    static final List<String> PAINTED_IDS = List.of("#LeftPanel", "#ReputationTitle", "#ReputationLead",
            ReputationPage.LIST, "#RightPanel", ReputationPage.DETAIL, ReputationPage.EMPTY);

    /**
     * The list column's width: a third of the shared menu's body ({@link MenuFrame#BODY_WIDTH}), room for a
     * row's picture, name, what comes next and its rank word; the reading page, which holds the most, flexes
     * into the rest. The document spells the same number (a document cannot read a Java constant) and the
     * document test holds the two together.
     */
    static final int LIST_WIDTH = MenuFrame.BODY_WIDTH / 3;

    private ReputationPageLayout() {
    }
}
