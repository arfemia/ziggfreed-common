package com.ziggfreed.common.settings.page;

/**
 * The library's own sections of the Settings tab, in the order the page draws them under a consumer's.
 * Each is filled at setup by the module that owns what it sets (zc-objectives the tracker and the title,
 * zc-presentation the notifications), through {@link ZigSettings#fill}; an unfilled slot draws nothing.
 */
public enum SettingsSlot {
    QUEST_TRACKER,
    NOTIFICATIONS,
    TITLE
}
