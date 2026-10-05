package com.ziggfreed.common.objectives.book;

/**
 * Where the achievement tab offers Pin: the list row and the detail header both ask this, so the two
 * never disagree. The offer follows the engine's own pin ({@code AchievementEngine.pinnable}, the
 * check {@code pin} makes short of the cap), so a Pin the player is shown is one the engine takes,
 * and the only refusal left to show is the cap, which the toast names truly. A pure decision over
 * booleans, so the rule is pinned with no page.
 */
final class AchievementPinOffer {

    private AchievementPinOffer() {
    }

    /**
     * Is Pin offered? Never on a feat (an earned trophy tracks nothing). Otherwise where a pin is
     * held, so it can come off whatever has happened since, or where the engine's pin would take it
     * short of the cap. At the cap the offer stays, so the cap is then the true refusal.
     */
    static boolean offersPin(boolean featOfStrength, boolean pinned, boolean pinnable) {
        return !featOfStrength && (pinned || pinnable);
    }
}
