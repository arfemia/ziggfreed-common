package com.ziggfreed.common.reputation.page;

import java.util.List;

import com.ziggfreed.common.ui.menu.MenuFrame;

/**
 * What {@code Pages/ZigReputationPage.ui} must declare for {@link ReputationPage}: every element the page
 * paints, and the list column's width. Kept off the page class because a page cannot load in a unit JVM
 * (its engine superclass's static init reaches the engine logger), so the document test reads them here.
 */
final class ReputationPageLayout {

    /** Every element the page paints in its own document; a command against a missing one disconnects the player. */
    static final List<String> PAINTED_IDS = List.of("#LeftPanel", "#ReputationTitle", ReputationPage.LIST,
            "#EmptyListLabel", "#RightPanel", ReputationPage.DETAIL_ICON, "#DetailTitle", "#DetailRank",
            ReputationPage.DETAIL_LIST);

    /**
     * The list column's width: half the shared menu's body ({@link MenuFrame#BODY_WIDTH}), room for a row's
     * name, rank, what comes next and gear; the detail column flexes into the rest. The document spells the
     * same number (a document cannot read a Java constant) and the document test holds the two together.
     */
    static final int LIST_WIDTH = MenuFrame.BODY_WIDTH / 2;

    private ReputationPageLayout() {
    }
}
