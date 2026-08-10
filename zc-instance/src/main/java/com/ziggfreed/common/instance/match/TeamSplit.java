package com.ziggfreed.common.instance.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * Splits a flat roster into balanced teams while keeping pre-made party blocks together.
 *
 * <p>PURE, engine-free utility: {@code java.util} + {@link UUID} only, no Hytale imports, no
 * RNG. Given the same inputs it always produces the same teams (deterministic), so a caller can
 * compute the split off the world thread, log it, or replay it without surprises. Nothing here
 * touches a Store/Ref, so there is no world-thread requirement and nothing to try-guard.
 *
 * <p><b>Input contract.</b> {@code roster} is online-first order with every contiguous pre-made
 * party block at the HEAD, in {@code partyBlockSizes} order; whatever remains after the blocks are
 * solo fillers. Example: a roster of 7 with {@code partyBlockSizes = [3, 2]} means indices 0..2 are
 * one party, indices 3..4 are a second party, and indices 5..6 are two solo players.
 *
 * <p><b>Placement.</b> Party blocks are placed FIRST, largest block first (ties broken by the
 * block's position in {@code partyBlockSizes} so the result stays deterministic), each onto the team
 * with the most remaining capacity (ties broken by lowest team index). A block is NEVER split across
 * teams. Solo fillers are then distributed one at a time, in roster order, to the least-filled team,
 * which keeps team sizes as even as the block placement allows.
 *
 * <p><b>Clamp behaviour.</b> If a single block is larger than {@code teamSize} it cannot fit whole on
 * any team. Rather than throw, the largest contiguous prefix that fits the emptiest team is placed and
 * the overflow members are demoted to the solo-filler pool (so they still get seated, just not with
 * their full party). If the roster has more members than {@code teamCount * teamSize} total capacity,
 * the surplus members (after every team is full) are dropped from the result; the caller decides what
 * to do with anyone the configured team layout cannot seat.
 */
public final class TeamSplit {

    private TeamSplit() {
    }

    /**
     * Splits {@code roster} into {@code teamCount} teams of up to {@code teamSize} each, keeping each
     * pre-made party block (sized by {@code partyBlockSizes}, at the head of {@code roster}) wholly on
     * one team and filling the rest with solo players to balance.
     *
     * <p>Always returns a list of exactly {@code max(teamCount, 0)} inner lists (each possibly empty);
     * defensive on bad input: an empty roster or {@code teamCount <= 0} yields the empty-team shape,
     * {@code teamSize <= 0} yields all-empty teams, and a {@code partyBlockSizes} total that exceeds the
     * roster length is clamped to the roster (extra/oversized blocks fall through to fillers).
     *
     * @param roster          online-first players, party blocks at the head, then solo fillers
     * @param partyBlockSizes contiguous block sizes in roster order (sum may be 0 for an all-solo roster)
     * @param teamCount       number of teams to produce (>= 1 for a real split)
     * @param teamSize        max players per team
     * @return {@code teamCount} teams, each a list of player ids (caller may treat order as seat order)
     */
    @Nonnull
    public static List<List<UUID>> split(@Nonnull List<UUID> roster, @Nonnull List<Integer> partyBlockSizes,
                                         int teamCount, int teamSize) {
        // Defensive: a non-positive team count has no teams to fill.
        int teams = Math.max(teamCount, 0);
        List<List<UUID>> result = new ArrayList<>(teams);
        for (int i = 0; i < teams; i++) {
            result.add(new ArrayList<>());
        }
        if (teams == 0 || roster.isEmpty() || teamSize <= 0) {
            return result;
        }

        int cap = teamSize; // per-team capacity, identical for every team

        // 1) Carve the roster into the contiguous head blocks + the solo fillers. We clamp block
        //    sizes defensively so a malformed partyBlockSizes (negative, or summing past the roster)
        //    can never index out of bounds; anything past the carved blocks is a solo filler.
        List<Block> blocks = new ArrayList<>();
        List<UUID> fillers = new ArrayList<>();
        int cursor = 0;
        int rosterSize = roster.size();
        for (int b = 0; b < partyBlockSizes.size() && cursor < rosterSize; b++) {
            Integer rawSize = partyBlockSizes.get(b);
            int size = rawSize == null ? 0 : Math.max(rawSize, 0);
            if (size <= 0) {
                continue; // an empty/invalid block contributes nobody
            }
            int end = Math.min(cursor + size, rosterSize);
            List<UUID> members = new ArrayList<>(roster.subList(cursor, end));
            // Order index 'b' is the tie-breaker that keeps largest-first placement deterministic.
            blocks.add(new Block(members, b));
            cursor = end;
        }
        // Everyone after the last carved block is a solo filler.
        for (int i = cursor; i < rosterSize; i++) {
            fillers.add(roster.get(i));
        }

        // 2) Place blocks largest-first (ties -> earlier block first) onto the emptiest team.
        //    Sorting a copy keeps the input list untouched and the order fully deterministic.
        blocks.sort((x, y) -> {
            int bySize = Integer.compare(y.members.size(), x.members.size()); // larger first
            return bySize != 0 ? bySize : Integer.compare(x.order, y.order);  // then stable by order
        });
        int[] used = new int[teams]; // running fill count per team

        for (Block block : blocks) {
            int members = block.members.size();
            int target = emptiestTeam(used, cap);
            int room = cap - used[target];

            if (members <= room) {
                // Whole block fits: keep the party together.
                result.get(target).addAll(block.members);
                used[target] += members;
            } else {
                // Clamp: a block bigger than the emptiest team's room cannot stay whole. Seat the
                // prefix that fits, demote the overflow to the solo pool (never throw, never split a
                // party across two teams - the overflow simply becomes loose fillers).
                if (room > 0) {
                    result.get(target).addAll(block.members.subList(0, room));
                    used[target] += room;
                }
                fillers.addAll(block.members.subList(room, members));
            }
        }

        // 3) Distribute solos one at a time to the least-filled team, in roster order, until either
        //    the fillers run out or every team is full. Surplus beyond total capacity is dropped.
        for (UUID solo : fillers) {
            int target = emptiestTeam(used, cap);
            if (used[target] >= cap) {
                break; // all teams full; remaining fillers cannot be seated
            }
            result.get(target).add(solo);
            used[target]++;
        }

        return result;
    }

    /**
     * Convenience over {@link #split}: the same balanced split flattened to a {@code UUID -> teamIndex}
     * (0-based) map. A player who could not be seated (surplus past total capacity) is absent from the
     * map. Returns an empty map for any input that {@link #split} resolves to all-empty teams.
     */
    @Nonnull
    public static Map<UUID, Integer> assign(@Nonnull List<UUID> roster, @Nonnull List<Integer> partyBlockSizes,
                                            int teamCount, int teamSize) {
        List<List<UUID>> teams = split(roster, partyBlockSizes, teamCount, teamSize);
        Map<UUID, Integer> out = new HashMap<>();
        for (int t = 0; t < teams.size(); t++) {
            for (UUID id : teams.get(t)) {
                out.put(id, t);
            }
        }
        return out;
    }

    /**
     * Index of the team with the most remaining capacity (least used). Deterministic: ties resolve to
     * the lowest team index so equal-size inputs always land the same way.
     */
    private static int emptiestTeam(@Nonnull int[] used, int cap) {
        int best = 0;
        int bestRoom = cap - used[0];
        for (int t = 1; t < used.length; t++) {
            int room = cap - used[t];
            if (room > bestRoom) {
                bestRoom = room;
                best = t;
            }
        }
        return best;
    }

    /** A carved pre-made party block: its members plus its original roster-order index (the tie-breaker). */
    private static final class Block {
        final List<UUID> members;
        final int order;

        Block(@Nonnull List<UUID> members, int order) {
            this.members = members;
            this.order = order;
        }
    }
}
