package com.ziggfreed.common.loot.claim;

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
import com.google.gson.JsonObject;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.StoredReward;
import com.ziggfreed.common.loot.StochasticCount;
import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.util.RecordFile;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every player's Claims: what they have earned and not taken yet, kept across a restart until they press
 * Claim (M428). Every payout that writes a Claim goes through {@link #award}, or {@link #awardOrTopUp} to add
 * to one the player still holds, which share the one place a Claim is counted against its diminishing returns,
 * scaled, and announced ({@link ClaimAddedEvent}).
 *
 * <p>It sits beside {@code PendingRewardStore}, which stays the retry queue: a Claim is a payout the player
 * collects on purpose, a pending reward one that could not be delivered. A Claim never expires (lead's call).
 *
 * <p>One versioned JSON file, written on every change through zc-core's {@code util/RecordFile} (the engine's
 * atomic write with a {@code .bak}, the {@code PendingRewardStore} shape). A file declaring a newer version
 * than this build reads is left unread, and unwritten, with one warning; a torn file reads its {@code .bak};
 * and a file this build cannot read at all, backup included (a damaged or cut-short one), is left unread and
 * unwritten too, so one bad read never costs a player the Claims on disk. A missing or empty file is a fresh
 * start, not a refusal. A write that fails keeps the Claims in memory, which a plain unit JVM (where the
 * engine's writer cannot load) relies on. A reward's optional spec (what a deferred row reads as, {@code StoredReward}) and drop
 * chance ({@link InstanceReward#dropChance}), and a Claim's optional fight ({@link Claim#fight()}), are additive
 * fields: a file written before any of them reads unchanged (a row with no fight is a Claim no fight paid, a reward
 * with no chance reads as its item's own quality) and the version does not move for them.
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
    /** The file, kept the one library way: a newer or unreadable file is never overwritten by this build. */
    private final RecordFile file;

    /** Gson-friendly persisted shape of one Claim; each reward is {@code PendingRewardStore}'s own shape. */
    private static final class StoredClaim {
        String id;
        String source;
        String titleKey;
        List<String> titleArgs;
        String difficulty;
        List<StoredReward> rewards;
        long createdAt;
        /** Appended: absent from a row written before it, which reads as a Claim no fight paid. */
        String fight;
    }

    /** The file's leaves; its {@code version} is the record file's. */
    private static final class Dto {
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
        this.file = RecordFile.builder("claims", "the Claims in " + fileName, new RecordFile.Body() {
                    @Override
                    @Nonnull
                    public JsonObject write() {
                        return writeDoc();
                    }

                    @Override
                    public void read(@Nonnull JsonObject doc, int version) {
                        readDoc(doc);
                    }

                    @Override
                    public void forget() {
                        claims.clear();
                    }
                })
                .version(SCHEMA_VERSION)
                .lostWrite("a restart may lose a Claim")
                .refuseUnreadable()
                .build();
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
        file.persistTo(dataDir.resolve(fileName), null);
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
        return write(player, claim, spec, diminishKey, nowMs, false);
    }

    /**
     * {@link #award}, except that a Claim {@code player} still holds by {@code claim}'s id takes the new rewards
     * beside its own (a top-up) instead of refusing them: one Claim per payout that keeps coming, such as a quest
     * step's item. A top-up is counted, scaled and announced exactly as a first write is ({@link ClaimAddedEvent},
     * carrying the Claim as it now stands); the held Claim keeps its title and its place by age, and a reward the
     * same as one it holds, its drop reading as the same rarity, adds to that one's quantity
     * ({@link InstanceReward#merge}). A top-up that names a fight
     * ({@link Claim#fight()}) moves the whole Claim to that fight, so the chest the newest fight leaves offers it;
     * one that names none keeps the held Claim's. Whatever caps the payout is the caller's: this adds what it is
     * given.
     *
     * @return true when a Claim was written or topped up
     */
    public synchronized boolean awardOrTopUp(@Nonnull UUID player, @Nonnull Claim claim, @Nullable DiminishSpec spec,
            @Nonnull String diminishKey, long nowMs) {
        return write(player, claim, spec, diminishKey, nowMs, true);
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
     * holds again by the same id takes these rewards beside its own, as a top-up joins them.
     */
    public synchronized void putBack(@Nonnull UUID player, @Nonnull Claim claim) {
        if (claim.rewards().isEmpty()) {
            return;
        }
        List<StoredClaim> held = claims.computeIfAbsent(player, k -> new ArrayList<>());
        int at = indexOf(held, claim.id());
        if (at >= 0) {
            held.set(at, toStored(joined(fromStored(held.get(at)), claim)));
        } else {
            held.add(toStored(claim));
        }
        held.sort(BY_AGE);
        flush();
    }

    // ==================== the award's pieces ====================

    /**
     * The one write behind {@link #award} and {@link #awardOrTopUp}: a Claim with no rewards, or one the player
     * still holds unless {@code topUp}, counts nothing; otherwise one clear is counted, the rewards are scaled,
     * and what is left is written (or joined into the held Claim), saved and announced.
     */
    private boolean write(@Nonnull UUID player, @Nonnull Claim claim, @Nullable DiminishSpec spec,
            @Nonnull String diminishKey, long nowMs, boolean topUp) {
        List<StoredClaim> held = claims.get(player);
        int at = held == null ? -1 : indexOf(held, claim.id());
        if (claim.rewards().isEmpty() || (at >= 0 && !topUp)) {
            return false;
        }
        double multiplier = spec == null ? 1.0 : ledger.count(player, diminishKey, spec, nowMs);
        List<InstanceReward> scaled = scaled(claim.rewards(), multiplier);
        if (scaled.isEmpty()) {
            return false;
        }
        Claim written;
        if (at >= 0) {
            written = inFight(joined(fromStored(held.get(at)), claim.withRewards(scaled)), claim.fight());
            held.set(at, toStored(written));
        } else {
            written = claim.withRewards(scaled);
            claims.computeIfAbsent(player, k -> new ArrayList<>()).add(toStored(written));
        }
        flush();
        fireAdded(player, written);
        return true;
    }

    /** Where the Claim {@code id} (any case) sits in {@code held}, or -1 when it is not there. */
    private static int indexOf(@Nonnull List<StoredClaim> held, @Nonnull String id) {
        for (int i = 0; i < held.size(); i++) {
            String storedId = held.get(i).id;
            if (storedId != null && storedId.equalsIgnoreCase(id.trim())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code held} taking {@code more}'s rewards beside its own, a reward the same as one it holds, its drop reading
     * as the same rarity, adding to that one's quantity ({@link InstanceReward#merge}); its title, source and
     * difficulty kept, its age the older of the two. A held row too damaged to read gives way to {@code more}.
     */
    @Nonnull
    private static Claim joined(@Nullable Claim held, @Nonnull Claim more) {
        if (held == null) {
            return more;
        }
        List<InstanceReward> rewards = new ArrayList<>(held.rewards());
        rewards.addAll(more.rewards());
        return new Claim(held.id(), held.source(), held.titleKey(), held.titleArgs(), held.difficulty(),
                InstanceReward.merge(rewards), Math.min(held.createdAt(), more.createdAt()), held.fight());
    }

    /** {@code claim} paid for {@code fight} instead, everything else kept; unchanged for a null fight. */
    @Nonnull
    private static Claim inFight(@Nonnull Claim claim, @Nullable String fight) {
        if (fight == null || claim.fromFight(fight)) {
            return claim;
        }
        return new Claim(claim.id(), claim.source(), claim.titleKey(), claim.titleArgs(), claim.difficulty(),
                claim.rewards(), claim.createdAt(), fight);
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

    private void readDoc(@Nonnull JsonObject doc) {
        Dto dto = gson.fromJson(doc, Dto.class);
        claims.clear();
        if (dto == null || dto.players == null) {
            return;
        }
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

    @Nonnull
    private JsonObject writeDoc() {
        Dto dto = new Dto();
        dto.players = new HashMap<>();
        for (Map.Entry<UUID, List<StoredClaim>> e : claims.entrySet()) {
            dto.players.put(e.getKey().toString(), new ArrayList<>(e.getValue()));
        }
        return gson.toJsonTree(dto).getAsJsonObject();
    }

    /** Write every Claim now; a refused file, or no data folder, writes nothing. */
    private void flush() {
        file.writeNow();
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
        s.fight = claim.fight();
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
        return new Claim(s.id, s.source, s.titleKey, args, s.difficulty, rewards, s.createdAt, s.fight);
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
