package com.ziggfreed.common.pvp;

/**
 * The bounty rules, pure: a player whose streak of credited kills (since they last died) reaches the world's
 * {@code Bounty.At} carries a bounty, and whoever ends it is paid part of the fallen player's health for each kill in
 * the streak (its {@code PerKill} percent, at most {@code MaxStreak} kills), raised by the hunter's own
 * {@code PvpBountyBonus}. Only credited kills grow a streak, so trading kills with a friend (which the repeat guard
 * counts at nothing) never raises one, and every death ends it.
 */
public final class PvpBounty {

    private PvpBounty() {
    }

    /** Does a streak of {@code streak} carry a bounty under {@code rules}? */
    public static boolean stands(int streak, PvpRules.Bounty rules) {
        return rules.enabled() && streak >= rules.at();
    }

    /** Did the kill that took a streak to {@code streak} put a bounty up? */
    public static boolean raised(int streak, PvpRules.Bounty rules) {
        return rules.enabled() && streak == rules.at();
    }

    /** Did the kill that took a streak to {@code streak} grow a bounty that already stood? */
    public static boolean grew(int streak, PvpRules.Bounty rules) {
        return rules.enabled() && streak > rules.at();
    }

    /**
     * What ending a bounty on a streak of {@code streak} is worth: {@code victimMaxHealth} times the rules'
     * {@code PerKill} percent times the streak (at most {@code MaxStreak}), times one plus the hunter's
     * {@code bonusPoints} percent (never below nothing); 0 for a streak that carries no bounty or a health that is not
     * a number.
     */
    public static double worth(int streak, double victimMaxHealth, double bonusPoints, PvpRules.Bounty rules) {
        if (!stands(streak, rules) || !Double.isFinite(victimMaxHealth) || victimMaxHealth <= 0.0) {
            return 0.0;
        }
        double bonus = Double.isFinite(bonusPoints) ? bonusPoints : 0.0;
        double kills = Math.min(streak, rules.maxStreak());
        return victimMaxHealth * rules.perKill() / 100.0 * kills * Math.max(0.0, 1.0 + bonus / 100.0);
    }

    /** Did a player leave {@code nowMs} too soon after another player's hit at {@code struckAtMs}? */
    public static boolean fled(long struckAtMs, long nowMs, PvpRules.Bounty rules) {
        long window = rules.logoutMillis();
        return window > 0L && struckAtMs > 0L && nowMs >= struckAtMs && nowMs - struckAtMs <= window;
    }
}
