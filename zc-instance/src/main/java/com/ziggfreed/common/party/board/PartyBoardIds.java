package com.ziggfreed.common.party.board;

import java.util.List;

/**
 * The ids {@link PartyBoardPage} names in its own document, {@code Pages/ZigPartyBoardPage.ui}, in one place with no
 * engine type, so {@code PartyBoardPageIdsTest} holds each to a declaration there: a command on an id the page lacks
 * disconnects the player. The ids inside an appended kit template are its painter's, never named here.
 */
final class PartyBoardIds {

    /** The page's document. */
    static final String PAGE_TEMPLATE = "Pages/ZigPartyBoardPage.ui";

    static final String LIST_VIEW = "#ListView";
    static final String LIST = "#BoardList";
    static final String EMPTY = "#BoardEmpty";
    static final String POST_BUTTON = "#PostButton";
    static final String TAKE_DOWN_BUTTON = "#TakeDownButton";
    static final String POST_VIEW = "#PostView";
    static final String AIM_TILES = "#AimTiles";
    static final String WANT_TILES = "#WantTiles";
    static final String NOTE_HEAD = "#NoteHead";
    static final String NOTE_TILES = "#NoteTiles";
    static final String ANNOUNCE_BOX = "#AnnounceBox";
    static final String PREVIEW = "#PreviewText";
    static final String ENDS = "#PostEnds";
    static final String PUT_UP_BUTTON = "#PutUpButton";
    static final String BACK_BUTTON = "#BackButton";

    /** Every id above. */
    static final List<String> ALL = List.of(LIST_VIEW, LIST, EMPTY, POST_BUTTON, TAKE_DOWN_BUTTON, POST_VIEW,
            AIM_TILES, WANT_TILES, NOTE_HEAD, NOTE_TILES, ANNOUNCE_BOX, PREVIEW, ENDS, PUT_UP_BUTTON, BACK_BUTTON);

    private PartyBoardIds() {
    }
}
