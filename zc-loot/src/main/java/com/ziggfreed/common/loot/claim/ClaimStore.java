package com.ziggfreed.common.loot.claim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.StoredReward;
import com.ziggfreed.common.loot.StochasticCount;
import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every player's Claims: what they have earned and not taken yet, kept across a restart until they press
 * Claim (M428). Every payout that writes a Claim goes through {@link #award}, which is the one place a
 * Claim is counted against its diminishing returns, scaled, and announced ({@link ClaimAddedEvent}).
 *
 * <p>It sits beside {@code PendingRewardStore}, which stays the retry queue: a Claim is a payout the player
 * collects on purpose, a pending reward one that could not be delivered. A Claim never expires (lead's call).
 *
 * <p>One versioned JSON file, written atomically through the engine's {@code FileUtil} on every change (the
 * {@code PendingRewardStore} shape). A file declaring a newer version than this build reads is left unread,
 * and unwritten, with one warning; so is a file this build cannot read at all (a damaged or cut-short one),
 * so one bad read never costs a player the Claims on disk. A missing or empty file is a fresh start, not a
 * refusal. A write that fails keeps the Claims in memory, which a plain unit JVM (where the engine's writer
 * cannot load) relies on. A reward's optional spec (what a deferred row reads as, {@code StoredReward}) is an
 * additive field: a file written before it reads unchanged and the version does not move for it.
 *
 * <p>Thread-safe: every change holds the store's lock, and {@link ClaimAddedEvent} fires inside it.
 */
public final class ClaimStore {

    /** The one file shape this build reads and writes. */
    static final int SCHEMA_VERSION = 1;

    private static final ClaimStore SHARED = new ClaimStore("claims", DiminishLedger.shared());

    /** Oldest first: the order a player earned them. */
    private static final Comparator<StoredClaim> BY_AGE = Comparator.comparingLong(c -> c.createdAt);

    private final String fileName;
    private final DiminishLedger ledger;
    private final DoubleSupplier sample;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, List<StoredClaim>> claims = new ConcurrentHashMap<>();
    @Nullable private volatile Path file;
    /** True once a newer or unreadable file was found: it is never overwritten by this build. */
    private volatile boolean refused;

    /** Gson-friendly persisted shape of one Claim; each reward is {@code PendingRewardStore}'s own shape. */
    private static final class StoredClaim {
        String id;
        String source;
        String titleKey;
        List<String> titleArgs;
        String difficulty;
        List<StoredReward> rewards;
        long createdAt;
    }

    private static final class Dto {
        int version;
        Map<String, List<StoredClaim>> players;
    }

    /**
     * @param name   the file's name in the data folder ({@code .json} added when missing)
     * @param ledger where the diminishing returns are counted
     */
    public ClaimStore(@Nonnull String name, @Nonnull DiminishLedger ledger) {
        this(name, ledger, () -> ThreadLocalRandom.current().nextDouble());
    }

    ClaimStore(@Nonnull String name, @Nonnull DiminishLedger ledger, @Nonnull DoubleSupplier sample) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
        this.ledger = ledger;
        this.sample = sample;
    }

    /** The library's one store, the one the Claims tab reads and every library payout writes. */
    @Nonnull
    public static ClaimStore shared() {
        return SHARED;
    }

    /** Resolve the file under {@code dataDir} and load every Claim waiting. Call once at setup. */
    public synchronized void init(@Nullable Path dataDir) {
        if (dataDir == null) {
            return;
        }
        this.file = dataDir.resolve(fileName);
        refused = false;
        load();
    }

    /**
     * Pay {@code player} {@code claim}. A Claim with no rewards (a roll that landed nothing), or one whose id the
     * player still holds, answers false and counts nothing, so a payout of nothing never spends a clear. Otherwise
     * one clear of {@code diminishKey} is counted under {@code spec} (none with a null spec),
     * every reward's quantity is scaled by the multiplier that clear pays at (a fraction pays whole through
     * {@link StochasticCount}, and a reward scaled to nothing is dropped; a command line with no
     * {@code {amount}} to fill is kept whole with the multiplier's chance), and a Claim left with no rewards is
     * not written. A written Claim is saved and announced ({@link ClaimAddedEvent}). Every payout that writes
     * a Claim pays through here.
     *
     * @return true when a Claim was written
     */
    public synchronized boolean award(@Nonnull UUID player, @Nonnull Claim claim, @Nullable DiminishSpec spec,
            @Nonnull String diminishKey, long nowMs) {
        if (claim.rewards().isEmpty() || holds(player, claim.id())) {
            return false;
        }
        double multiplier = spec == null ? 1.0 : ledger.count(player, diminishKey, spec, nowMs);
        List<InstanceReward> scaled = scaled(claim.rewards(), multiplier);
        if (scaled.isEmpty()) {
            return false;
        }
        Claim written = claim.withRewards(scaled);
        claims.computeIfAbsent(player, k -> new ArrayList<>()).add(toStored(written));
        flush();
        fireAdded(player, written);
        return true;
    }

    /** Every Claim {@code player} has waiting, oldest first. */
    @Nonnull
    public synchronized List<Claim> list(@Nonnull UUID player) {
        List<StoredClaim> held = claims.get(player);
        if (held == null || held.isEmpty()) {
            return List.of();
        }
        List<Claim> out = new ArrayList<>(held.size());
        for (StoredClaim stored : held) {
            Claim claim = fromStored(stored);
            if (claim != null) {
                out.add(claim);
            }
        }
        return List.copyOf(out);
    }

    /** Whether {@code player} has any Claim waiting. */
    public synchronized boolean has(@Nonnull UUID player) {
        List<StoredClaim> held = claims.get(player);
        return held != null && !held.isEmpty();
    }

    /** How many Claims {@code player} has waiting. */
    public synchronized int count(@Nonnull UUID player) {
        List<StoredClaim> held = claims.get(player);
        return held == null ? 0 : held.size();
    }

    /** Remove and return {@code player}'s Claim {@code id} (any case), or null when they hold none by that id. */
    @Nullable
    public synchronized Claim take(@Nonnull UUID player, @Nonnull String id) {
        List<StoredClaim> held = claims.get(player);
        if (held == null) {
            return null;
        }
        for (int i = 0; i < held.size(); i++) {
            StoredClaim stored = held.get(i);
            if (stored.id != null && stored.id.equalsIgnoreCase(id.trim())) {
                held.remove(i);
                if (held.isEmpty()) {
                    claims.remove(player);
                }
                flush();
                return fromStored(stored);
            }
        }
        return null;
    }

    /** Remove and return every Claim {@code player} has waiting, oldest first. */
    @Nonnull
    public synchronized List<Claim> takeAll(@Nonnull UUID player) {
        List<Claim> all = list(player);
        if (claims.remove(player) != null) {
            flush();
        }
        return all;
    }

    /**
     * Put {@code claim} back, in its place by age: what a Claim press could not hand over (a full bag). No
     * clear is counted and nothing is announced. A Claim with no rewards is not put back; one the player
     * holds again by the same id takes these rewards beside its own.
     */
    public synchronized void putBack(@Nonnull UUID player, @Nonnull Claim claim) {
        if (claim.rewards().isEmpty()) {
            return;
        }
        List<StoredClaim> held = claims.computeIfAbsent(player, k -> new ArrayList<>());
        for (int i = 0; i < held.size(); i++) {
            Claim same = fromStored(held.get(i));
            if (same != null && same.is(claim.id())) {
                List<InstanceReward> joined = new ArrayList<>(same.rewards());
                joined.addAll(claim.rewards());
                held.set(i, toStored(new Claim(same.id(), same.source(), same.titleKey(), same.titleArgs(),
                        same.difficulty(), joined, Math.min(same.createdAt(), claim.createdAt()))));
                held.sort(BY_AGE);
                flush();
                return;
            }
        }
        held.add(toStored(claim));
        held.sort(BY_AGE);
        flush();
    }

    // ==================== the award's pieces ====================

    private boolean holds(@Nonnull UUID player, @Nonnull String id) {
        List<StoredClaim> held = claims.get(player);
        if (held == null) {
            return false;
        }
        for (StoredClaim stored : held) {
            if (stored.id != null && stored.id.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** {@code rewards} at {@code multiplier} ({@link #scaledQuantity}), none left at zero. */
    @Nonnull
    private List<InstanceReward> scaled(@Nonnull List<InstanceReward> rewards, double multiplier) {
        List<InstanceReward> out = new ArrayList<>(rewards.size());
        for (InstanceReward reward : rewards) {
            int quantity = scaledQuantity(reward, multiplier);
            if (quantity > 0) {
                out.add(reward.withQuantity(quantity));
            }
        }
        return out;
    }

    /**
     * The quantity {@code reward} is written at under {@code multiplier}, 0 to drop it: the one number its chip
     * shows and its payout pays. A reward that pays its quantity ({@link InstanceReward#scalesWithQuantity()})
     * is scaled and made whole; a command line that cannot pay a share is one whole unit, kept as it is with the
     * multiplier's chance (certain at 1 or more) or dropped. Both go through {@link StochasticCount}.
     */
    private int scaledQuantity(@Nonnull InstanceReward reward, double multiplier) {
        if (reward.scalesWithQuantity()) {
            return StochasticCount.resolve(reward.quantity() * multiplier, sample);
        }
        return StochasticCount.resolve(Math.min(1.0, multiplier), sample) > 0 ? reward.quantity() : 0;
    }

    /** Announce a written Claim; nothing listening costs nothing, and a listener that throws costs only itself. */
    private static void fireAdded(@Nonnull UUID player, @Nonnull Claim claim) {
        try {
            IEventDispatcher<ClaimAddedEvent, ClaimAddedEvent> dispatcher =
                    HytaleServer.get().getEventBus().dispatchFor(ClaimAddedEvent.class);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(new ClaimAddedEvent(player, claim));
            }
        } catch (Throwable t) {
            SafeLog.fine("[claims] the new Claim could not be announced: " + t.getMessage());
        }
    }

    // ==================== persistence ====================

    private void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            Dto dto = gson.fromJson(Files.readString(f), Dto.class);
            if (dto != null && dto.version > SCHEMA_VERSION) {
                refused = true;
                SafeLog.warn("[claims] " + fileName + " declares version " + dto.version + ", newer than the "
                        + SCHEMA_VERSION + " this build reads; leaving it unread and unwritten rather than "
                        + "misreading it");
                return;
            }
            claims.clear();
            if (dto != null && dto.players != null) {
                for (Map.Entry<String, List<StoredClaim>> e : dto.players.entrySet()) {
                    UUID player = uuid(e.getKey());
                    if (player == null || e.getValue() == null) {
                        continue;
                    }
                    List<StoredClaim> held = new ArrayList<>();
                    for (StoredClaim stored : e.getValue()) {
                        if (stored != null && fromStored(stored) != null) {
                            held.add(stored);
                        }
                    }
                    if (!held.isEmpty()) {
                        held.sort(BY_AGE);
                        claims.put(player, held);
                    }
                }
            }
        } catch (Throwable t) {
            refused = true;
            SafeLog.warn("[claims] " + fileName + " unreadable; starting with no Claims and leaving it unread and "
                    + "unwritten: " + t.getMessage());
        }
    }

    private void flush() {
        Path f = file;
        if (f == null || refused) {
            return;
        }
        try {
            Dto dto = new Dto();
            dto.version = SCHEMA_VERSION;
            dto.players = new HashMap<>();
            for (Map.Entry<UUID, List<StoredClaim>> e : claims.entrySet()) {
                dto.players.put(e.getKey().toString(), new ArrayList<>(e.getValue()));
            }
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            FileUtil.writeStringAtomic(f, gson.toJson(dto), true);
        } catch (Throwable t) {
            SafeLog.warn("[claims] " + fileName + " could not be written: " + t.getMessage());
        }
    }

    @Nonnull
    private static StoredClaim toStored(@Nonnull Claim claim) {
        StoredClaim s = new StoredClaim();
        s.id = claim.id();
        s.source = claim.source();
        s.titleKey = claim.titleKey();
        s.titleArgs = new ArrayList<>(claim.titleArgs());
        s.difficulty = claim.difficulty();
        s.rewards = new ArrayList<>(claim.rewards().size());
        for (InstanceReward reward : claim.rewards()) {
            s.rewards.add(StoredReward.of(reward));
        }
        s.createdAt = claim.createdAt();
        return s;
    }

    /** The Claim a stored row holds, or null for a row too damaged to read (no id, no source). */
    @Nullable
    private static Claim fromStored(@Nonnull StoredClaim s) {
        if (s.id == null || s.id.isBlank() || s.source == null || s.source.isBlank()) {
            return null;
        }
        List<InstanceReward> rewards = new ArrayList<>();
        if (s.rewards != null) {
            for (StoredReward r : s.rewards) {
                InstanceReward reward = r == null ? null : r.toReward();
                if (reward != null) {
                    rewards.add(reward);
                }
            }
        }
        List<String> args = new ArrayList<>();
        if (s.titleArgs != null) {
            for (String arg : s.titleArgs) {
                args.add(arg == null ? "" : arg);
            }
        }
        return new Claim(s.id, s.source, s.titleKey, args, s.difficulty, rewards, s.createdAt);
    }

    @Nullable
    private static UUID uuid(@Nullable String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
