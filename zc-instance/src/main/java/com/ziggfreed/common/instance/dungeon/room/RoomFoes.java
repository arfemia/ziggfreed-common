package com.ziggfreed.common.instance.dungeon.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.dungeon.layout.SplitMix64;
import com.ziggfreed.common.instance.dungeon.layout.Words;

/**
 * The foes a generated run puts in its rooms (a layout plan's {@code Rosters} and {@code Foes}): which roster a room
 * draws on (the first whose {@code AnyOf} names one of the room's tags), how many foes its role's rule raises for the
 * party, and which, picked from the run's seed so a daily run's rooms hold the same foes for every party. Pure: no
 * engine type, so its sizing rule is tested in a plain JVM.
 *
 * <p><b>How many.</b> A rule's {@code Count} for one player, plus {@code PerExtraPlayer} for each further player,
 * rounded down (R1054: a bigger party meets more foes, not tougher ones). Roughly one in four is a ranged foe: every
 * fourth pick draws on the roster's {@code Ranged} list, the rest on {@code Front}. An extra foe the party added is
 * never a role the roster lists under {@code PartyScaled} (a role that already grows with the party by itself, such as
 * the game's own dungeon goblins), so a party is never made to pay twice; a roster whose lists hold nothing else adds
 * no extras. Then, at the rule's {@code Elite} chance, one elite from the roster's {@code Elite} list joins them.
 */
public final class RoomFoes {

    /** Which roles a roster's room draws on. */
    public record Roster(@Nonnull List<String> anyOf, @Nonnull List<String> front, @Nonnull List<String> ranged,
                         @Nonnull List<String> elite, @Nonnull Set<String> partyScaled) {

        public Roster {
            anyOf = Words.foldAll(anyOf);
            front = clean(front);
            ranged = clean(ranged);
            elite = clean(elite);
            partyScaled = Set.copyOf(Words.foldAll(partyScaled));
        }

        /** Whether a room carrying {@code tags} (folded) draws on this roster: any of AnyOf, or every room for none. */
        public boolean fits(@Nonnull List<String> tags) {
            if (anyOf.isEmpty()) {
                return true;
            }
            for (String tag : anyOf) {
                if (tags.contains(tag)) {
                    return true;
                }
            }
            return false;
        }

        boolean selfScaled(@Nonnull String role) {
            return partyScaled.contains(Words.fold(role));
        }
    }

    /**
     * How a room of one role takes its foes.
     *
     * @param role           the room role it applies to, folded
     * @param sealed         true for a fight that seals the room's doors until its foes are down; false for an open pack
     * @param count          the foes for one player
     * @param perExtraPlayer the foes each further player adds (0.5 is one per two players), rounded down
     * @param elite          the chance, 0 to 1, that one elite joins them
     */
    public record Rule(@Nonnull String role, boolean sealed, int count, double perExtraPlayer, double elite) {

        public Rule {
            String folded = Words.fold(role);
            role = folded == null ? "" : folded;
            count = Math.max(0, count);
            perExtraPlayer = Double.isFinite(perExtraPlayer) ? Math.max(0.0, perExtraPlayer) : 0.0;
            elite = Double.isFinite(elite) ? Math.max(0.0, Math.min(1.0, elite)) : 0.0;
        }
    }

    /** A plan's foes: its rosters, its rules by room role, and the block a sealed room's doors close with. */
    public record Plan(@Nonnull List<Roster> rosters, @Nonnull List<Rule> rules, @Nullable String bars) {

        /** No foes anywhere. */
        public static final Plan NONE = new Plan(List.of(), List.of(), null);

        public Plan {
            rosters = List.copyOf(rosters);
            rules = List.copyOf(rules);
            bars = Words.trimmed(bars);
        }

        /** Whether the plan puts foes in any room. */
        public boolean empty() {
            return rosters.isEmpty() || rules.isEmpty();
        }

        /** The rule for a room with {@code roles} (folded): the first rule naming one of them; null for none. */
        @Nullable
        public Rule ruleFor(@Nonnull List<String> roles) {
            for (Rule rule : rules) {
                if (roles.contains(rule.role())) {
                    return rule;
                }
            }
            return null;
        }

        /** The roster for a room carrying {@code tags} (folded): the first that fits; null for none. */
        @Nullable
        public Roster rosterFor(@Nonnull List<String> tags) {
            for (Roster roster : rosters) {
                if (roster.fits(tags)) {
                    return roster;
                }
            }
            return null;
        }
    }

    /**
     * One foe a room raises.
     *
     * @param role  its role id, as the roster spells it
     * @param elite whether it is the room's elite
     * @param extra whether the party's size added it
     */
    public record Pick(@Nonnull String role, boolean elite, boolean extra) {
    }

    /** The largest party a room is ever sized for. */
    public static final int MAX_PARTY = 5;

    /** The seed streams a room's picks and its elite roll are drawn from, offset by the room's index. */
    private static final long PICK_STREAM = 0x5A17_F0E5_0000L;
    private static final long ELITE_STREAM = 0x5A17_E117_0000L;

    private RoomFoes() {
    }

    /** How many foes (elites aside) {@code rule} raises for {@code members} players: at least the one-player count. */
    public static int count(@Nonnull Rule rule, int members) {
        int extraPlayers = Math.max(0, Math.min(MAX_PARTY, members) - 1);
        return rule.count() + (int) Math.floor(rule.perExtraPlayer() * extraPlayers + 1e-9);
    }

    /**
     * The foes room {@code room} of a run raises for {@code members} players, from {@code roster} under {@code rule},
     * picked from the run's {@code seed}: the same seed, room and party always pick the same foes, and a bigger party's
     * foes are the smaller one's plus its extras.
     */
    @Nonnull
    public static List<Pick> picks(@Nonnull Roster roster, @Nonnull Rule rule, int members, long seed, int room) {
        // Two streams, so the elite's roll never moves with the party's size.
        SplitMix64 rng = SplitMix64.derive(seed, PICK_STREAM + room);
        SplitMix64 eliteRng = SplitMix64.derive(seed, ELITE_STREAM + room);
        int total = count(rule, members);
        List<Pick> out = new ArrayList<>(total + 1);
        for (int i = 0; i < total; i++) {
            boolean extra = i >= rule.count();
            boolean ranged = i % 4 == 3;
            String role = pick(roster, ranged ? roster.ranged() : roster.front(), extra, rng);
            if (role == null) {
                role = pick(roster, ranged ? roster.front() : roster.ranged(), extra, rng);
            }
            if (role != null) {
                out.add(new Pick(role, false, extra));
            }
        }
        if (rule.elite() > 0.0 && !roster.elite().isEmpty() && eliteRng.nextDouble() < rule.elite()) {
            out.add(new Pick(roster.elite().get(eliteRng.nextInt(roster.elite().size())), true, false));
        }
        return out;
    }

    /** A role from {@code pool}, an extra never one that grows with the party by itself; null when none can stand. */
    @Nullable
    private static String pick(@Nonnull Roster roster, @Nonnull List<String> pool, boolean extra,
            @Nonnull SplitMix64 rng) {
        if (pool.isEmpty()) {
            return null;
        }
        if (!extra) {
            return pool.get(rng.nextInt(pool.size()));
        }
        List<String> open = new ArrayList<>(pool.size());
        for (String role : pool) {
            if (!roster.selfScaled(role)) {
                open.add(role);
            }
        }
        return open.isEmpty() ? null : open.get(rng.nextInt(open.size()));
    }

    @Nonnull
    private static List<String> clean(@Nullable List<String> roles) {
        if (roles == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(roles.size());
        for (String role : roles) {
            String trimmed = Words.trimmed(role);
            if (trimmed != null) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }
}
