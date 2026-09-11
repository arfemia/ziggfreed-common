package com.ziggfreed.common.quest.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The four things a character can be to a player's quests right now, in the order that decides
 * which one shows when several are true at once: a reward waiting here beats an errand that can be
 * settled here, which beats a quest on offer, which beats a quest merely being carried here.
 *
 * <p>Each names the {@link QuestIndicatorSpec} group key an author writes and the overhead state a
 * file may leave unwritten. The state id is an OPEN word: an author may point a situation at any
 * state a look file describes, and these four are only what the library assumes when nobody said.
 */
public enum QuestSituation {

    /** A finished quest whose reward is collected at this character. */
    COLLECT("Collect", "Quest_Reward_Ready"),

    /** Handing over what the player carries, to this character, would finish the quest. */
    TURN_IN("TurnIn", "Quest_Ready_To_Turn_In"),

    /** Not started, and the player could take it here right now. */
    AVAILABLE("Available", "Quest_Available"),

    /** Being carried, and this character is part of the errand. */
    IN_PROGRESS("InProgress", "Quest_In_Progress");

    private final String key;
    private final String defaultState;

    QuestSituation(@Nonnull String key, @Nonnull String defaultState) {
        this.key = key;
        this.defaultState = defaultState;
    }

    /** The group key an author writes on a {@link QuestIndicatorSpec}. */
    @Nonnull
    public String key() {
        return key;
    }

    /** The overhead state this situation shows when no file names another. */
    @Nonnull
    public String defaultState() {
        return defaultState;
    }

    /** Does {@code a} win over {@code b} when both apply to one player at one character? */
    public boolean outranks(@Nonnull QuestSituation other) {
        return ordinal() < other.ordinal();
    }

    /** The situation whose group key is {@code key}, compared without regard to case, or null. */
    @Nullable
    public static QuestSituation byKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        for (QuestSituation situation : values()) {
            if (situation.key.equalsIgnoreCase(key.trim())) {
                return situation;
            }
        }
        return null;
    }
}
