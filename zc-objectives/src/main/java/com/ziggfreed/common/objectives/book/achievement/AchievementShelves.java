package com.ziggfreed.common.objectives.book.achievement;

import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;

/**
 * Where the achievement tab lists an achievement for one player, and what it counts toward.
 *
 * <p>One rule under every decision, the engine's own ({@code AchievementEngine.isVisible}: one
 * already earned always is): what a player EARNED is theirs, so it is listed whatever its circulation
 * says. An owner retiring it, a feature switched off or a yearly occurrence that is over stop it
 * being EARNED, never stop it being SHOWN to whoever has it. Circulation and visibility decide only
 * what a player has not earned. Pure decisions over booleans, so the rule is pinned with no page.
 */
final class AchievementShelves {

    /** Which part of the tab lists an achievement. */
    enum Shelf {
        /** The browse list: categories, search, sort. */
        BROWSE,
        /** The earned-only feats section. */
        FEATS,
        /** Not listed for this player. */
        NONE
    }

    private AchievementShelves() {
    }

    /**
     * The shelf for one achievement. Earned: the feats section for a feat, the browse list for any
     * other. Not earned: the browse list only when it is in circulation, not a feat, and the engine
     * says the player may see it, asked last and lazily because it walks gates.
     */
    @Nonnull
    static Shelf shelfOf(boolean available, boolean featOfStrength, boolean unlocked,
            @Nonnull BooleanSupplier visibleWhileUnearned) {
        if (unlocked) {
            return featOfStrength ? Shelf.FEATS : Shelf.BROWSE;
        }
        if (!available || featOfStrength || !visibleWhileUnearned.getAsBoolean()) {
            return Shelf.NONE;
        }
        return Shelf.BROWSE;
    }

    /** Does it count in its category's earned-of-total on the browse list? */
    static boolean countsInCategory(boolean available, boolean featOfStrength, boolean unlocked) {
        return !featOfStrength && (available || unlocked);
    }

    /** Does it count in the header's earned number? Feats count in their own section only. */
    static boolean countsAsEarned(boolean featOfStrength, boolean unlocked) {
        return unlocked && !featOfStrength;
    }

    /** Does a capstone's list of what it needs show this child? */
    static boolean listsAsCapstoneChild(boolean available, boolean hidden, boolean unlocked) {
        return unlocked || (available && !hidden);
    }

    /** Does an achievement's "part of" list show this capstone? */
    static boolean listsAsCapstone(boolean available, boolean unlocked) {
        return unlocked || available;
    }
}
