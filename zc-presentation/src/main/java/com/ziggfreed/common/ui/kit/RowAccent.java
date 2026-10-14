package com.ziggfreed.common.ui.kit;

/**
 * What a list row's accent bar shows: its tone's fill (the default, as every list has always drawn it), the gold
 * accent of a featured row, or nothing. A list whose state pills already carry the tone frees the bar to mark the
 * rows that matter most (a quest list's main quests) and draws none on the rest.
 */
public enum RowAccent {
    TONE,
    FEATURED,
    NONE
}
