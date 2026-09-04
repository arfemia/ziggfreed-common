package com.ziggfreed.common.encounter.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Who did what in a run: three independent counters per {@code (runId, playerUuid)}, damage dealt
 * to the subject, damage taken while a member, and seconds spent as a member, plus whether they
 * died. It is fed by the observing damage system and the encounter tick, and read once at the end
 * of the run to produce {@link ParticipationShares}.
 *
 * <p><b>Keyed by run AND player, never by player alone.</b> A player can stand inside two
 * overlapping encounters at once (the engine's own member tick is non-parallel for exactly that
 * reason), and credit earned in one must never leak into the other.
 *
 * <p>Pure: no engine type anywhere, so a test feeds it numbers and reads back shares. Writes happen
 * on a world thread, one run's writes all on that run's own world's thread; the maps are concurrent
 * so an admin read from another thread never tears.
 */
public final class ParticipationLedger {

    /** One participant's running counters. */
    private static final class Counters {
        @Nonnull volatile String name;
        double damageDealt;
        double damageTaken;
        double presenceSeconds;
        boolean died;

        Counters(@Nonnull String name) {
            this.name = name;
        }
    }

    private final Map<UUID, Map<UUID, Counters>> runs = new ConcurrentHashMap<>();

    public ParticipationLedger() {
    }

    /** Credit {@code amount} damage dealt to the subject of {@code runId} by {@code playerId}. */
    public void creditDamageDealt(@Nonnull UUID runId, @Nonnull UUID playerId, @Nullable String playerName,
            double amount) {
        if (!(amount > 0.0)) {
            return;
        }
        Counters c = counters(runId, playerId, playerName);
        c.damageDealt += amount;
    }

    /** Credit {@code amount} damage taken by {@code playerId} while a member of {@code runId}. */
    public void creditDamageTaken(@Nonnull UUID runId, @Nonnull UUID playerId, @Nullable String playerName,
            double amount) {
        if (!(amount > 0.0)) {
            return;
        }
        Counters c = counters(runId, playerId, playerName);
        c.damageTaken += amount;
    }

    /** Credit {@code seconds} of membership in {@code runId} to {@code playerId}. */
    public void creditPresence(@Nonnull UUID runId, @Nonnull UUID playerId, @Nullable String playerName,
            double seconds) {
        if (!(seconds > 0.0)) {
            return;
        }
        Counters c = counters(runId, playerId, playerName);
        c.presenceSeconds += seconds;
    }

    /** Note that {@code playerId} died during {@code runId}; a death alone makes them a participant. */
    public void recordDeath(@Nonnull UUID runId, @Nonnull UUID playerId, @Nullable String playerName) {
        counters(runId, playerId, playerName).died = true;
    }

    /** Everyone with any entry in {@code runId}, in no particular order. */
    @Nonnull
    public Set<UUID> participants(@Nonnull UUID runId) {
        Map<UUID, Counters> byPlayer = runs.get(runId);
        return byPlayer == null ? Set.of() : Set.copyOf(byPlayer.keySet());
    }

    /** True when nobody has any entry in {@code runId}. */
    public boolean isEmpty(@Nonnull UUID runId) {
        Map<UUID, Counters> byPlayer = runs.get(runId);
        return byPlayer == null || byPlayer.isEmpty();
    }

    /** How many deaths {@code runId} recorded. */
    public int deaths(@Nonnull UUID runId) {
        Map<UUID, Counters> byPlayer = runs.get(runId);
        if (byPlayer == null) {
            return 0;
        }
        int deaths = 0;
        for (Counters c : byPlayer.values()) {
            if (c.died) {
                deaths++;
            }
        }
        return deaths;
    }

    /** Whether {@code playerId} died in {@code runId}. */
    public boolean died(@Nonnull UUID runId, @Nonnull UUID playerId) {
        Map<UUID, Counters> byPlayer = runs.get(runId);
        Counters c = byPlayer == null ? null : byPlayer.get(playerId);
        return c != null && c.died;
    }

    /**
     * Everybody's standing in {@code runId}, share descending: each participant's weighted score
     * against the top contributor's, so the top reads 1.0. A participant who died keeps their score
     * only when {@code creditDead}; otherwise it reads 0 and they are listed as attempt-only. A share
     * under {@code minShare}, or a zero score, is attempt-only too.
     *
     * @param weights the three weights for a given participant, asked once each
     */
    @Nonnull
    public ParticipationShares shares(@Nonnull UUID runId, double minShare, boolean creditDead,
            @Nonnull Function<UUID, ParticipationWeights> weights) {
        Map<UUID, Counters> byPlayer = runs.get(runId);
        if (byPlayer == null || byPlayer.isEmpty()) {
            return ParticipationShares.EMPTY;
        }
        List<UUID> ids = new ArrayList<>(byPlayer.keySet());
        double[] scores = new double[ids.size()];
        double top = 0.0;
        for (int i = 0; i < ids.size(); i++) {
            Counters c = byPlayer.get(ids.get(i));
            if (c == null) {
                continue;
            }
            ParticipationWeights w = weights.apply(ids.get(i));
            if (w == null) {
                w = ParticipationWeights.DEALT_ONLY;
            }
            double score = c.died && !creditDead ? 0.0
                    : c.damageDealt * w.damageDealt() + c.damageTaken * w.damageTaken()
                            + c.presenceSeconds * w.presence();
            scores[i] = Double.isFinite(score) && score > 0.0 ? score : 0.0;
            top = Math.max(top, scores[i]);
        }
        List<ParticipantShare> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            Counters c = byPlayer.get(ids.get(i));
            if (c == null) {
                continue;
            }
            double share = top > 0.0 ? scores[i] / top : 0.0;
            boolean credited = scores[i] > 0.0 && share >= minShare;
            out.add(new ParticipantShare(ids.get(i), c.name, share, credited, c.damageDealt, c.damageTaken,
                    c.presenceSeconds, c.died));
        }
        out.sort(Comparator.comparingDouble(ParticipantShare::share).reversed()
                .thenComparing(ParticipantShare::playerName)
                .thenComparing(p -> p.playerId().toString()));
        return new ParticipationShares(out);
    }

    /** Forget everything about {@code runId}; called when the run ends. */
    public void drop(@Nonnull UUID runId) {
        runs.remove(runId);
    }

    /** How many runs currently hold entries, for an admin listing. */
    public int openRuns() {
        return runs.size();
    }

    @Nonnull
    private Counters counters(@Nonnull UUID runId, @Nonnull UUID playerId, @Nullable String playerName) {
        Map<UUID, Counters> byPlayer = runs.computeIfAbsent(runId, k -> new ConcurrentHashMap<>());
        Counters c = byPlayer.computeIfAbsent(playerId, k -> new Counters(nameOrBlank(playerName)));
        if (playerName != null && !playerName.isBlank()) {
            c.name = playerName;
        }
        return c;
    }

    @Nonnull
    private static String nameOrBlank(@Nullable String name) {
        return name == null ? "" : name;
    }
}
