package com.ziggfreed.common.usegate;

/**
 * The four things a use gate can be about ({@link UseGateOn}), a closed vocabulary. The target axes
 * ({@link #BLOCK}, {@link #MOB}) are asked before the item axes, and an item axis is the one a kept stack
 * ({@link UseGateKeeps}) answers for.
 */
public enum UseGateAxis {

    /** The block a player is breaking. */
    BLOCK,
    /** The creature a player is hitting, by its NPC role id. */
    MOB,
    /** The item in the player's active hand while they break or hit. */
    HELD,
    /** An item going into one of the player's armor slots. */
    WORN;

    /** Does this axis read an item, so a stack kept for its holder ({@link UseGateKeeps}) passes it? */
    public boolean readsItem() {
        return this == HELD || this == WORN;
    }

    /** Does this axis walk an id's {@code Parent} lineage (an item or block id), rather than match it alone? */
    public boolean walksLineage() {
        return this != MOB;
    }
}
