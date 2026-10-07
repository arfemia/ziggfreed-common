package com.ziggfreed.common.ui.kit;

/** The three buttons of a detail page's action bar, bound once in a page's build and dispatched on live state. */
public enum ActionSlot {
    PRIMARY("#Primary"),
    SECONDARY("#Secondary"),
    DANGER("#Danger");

    private final String id;

    ActionSlot(String id) {
        this.id = id;
    }

    /** The button's id inside {@code @ZigActionBar}. */
    public String id() {
        return id;
    }
}
