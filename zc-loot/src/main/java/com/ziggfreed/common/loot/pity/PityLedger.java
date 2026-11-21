package com.ziggfreed.common.loot.pity;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ziggfreed.common.loot.FactorGate;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.util.RecordFile;

/**
 * How many Claims in a row each player was paid without a pity roll firing ({@link Roll.Pity}), kept across a
 * restart: per player and pity key ({@link Roll.Pity#keyOf}), the misses since a roll under it last fired.
 *
 * <p>Only a payout that writes a Claim from loot tables reads and counts it, and only once its Claim is written
 * (zc-instance's {@code ClaimRolls} and zc-encounter's {@code EncounterClaims}): it rolls under {@link #over}, so a
 * pity roll's chance grows by the player's misses, and settles with {@link #settle} after {@code ClaimStore.award}
 * answers true, so a payout that writes nothing (a roll that landed nothing, a Claim the player still holds, a
 * repeat its returns pay nothing for) counts nothing. A world drop, a bonus row or a preview never reads it.
 *
 * <p>Settling is per pity key, across every pass of the payout ({@link Tally}): a key a kept pick names (its roll
 * under that key fired and the Claim holds it) starts over at none; a key some roll held in reach (its table's
 * gates and its conditions passed) but never kept is one more miss, however many passes or rolls missed; a key no
 * roll held in reach is left as it was.
 *
 * <p>The file is kept through zc-core's {@code util/RecordFile} and carries a {@code "version"}; a file declaring
 * a newer version than this build reads is left unread, and unwritten, with one warning, a torn file reads its
 * {@code .bak}, and a file this build cannot read at all, backup included, is left unread and unwritten too, so
 * one bad read never wipes the counts on disk. A missing or empty file is a fresh start. A write that fails keeps
 * the counts in memory.
 */
public final class PityLedger {

    /** The one file shape this build reads and writes. */
    static final int SCHEMA_VERSION = 1;

    private static final PityLedger SHARED = new PityLedger("claims-pity");

    private final String fileName;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, Map<String, Integer>> counts = new ConcurrentHashMap<>();
    /** The file, kept the one library way: a newer or unreadable file is never overwritten by this build. */
    private final RecordFile file;

    /** The file's leaves; its {@code version} is the record file's. */
    private static final class Dto {
        Map<String, Map<String, Integer>> players;
    }

    public PityLedger(@Nonnull String name) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
        this.file = RecordFile.builder("claims", "the pity counts in " + fileName,
                        new RecordFile.Body() {
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
                                counts.clear();
                            }
                        })
                .version(SCHEMA_VERSION)
                .lostWrite("a restart may forget a pity miss")
                .refuseUnreadable()
                .build();
    }

    /** The library's one ledger, the one every Claim payout reads and counts through. */
    @Nonnull
    public static PityLedger shared() {
        return SHARED;
    }

    /** Resolve the file under {@code dataDir} and load every count. Call once at setup. */
    public synchronized void init(@Nullable Path dataDir) {
        if (dataDir == null) {
            return;
        }
        file.persistTo(dataDir.resolve(fileName), null);
    }

    /** How many Claims in a row {@code player} was paid without a roll under {@code pityId} (any case) firing. */
    public synchronized int misses(@Nonnull UUID player, @Nullable String pityId) {
        String key = Roll.Pity.keyOf(pityId);
        Map<String, Integer> mine = counts.get(player);
        if (key == null || mine == null) {
            return 0;
        }
        Integer held = mine.get(key);
        return held == null ? 0 : Math.max(0, held);
    }

    /** {@code base} answering {@code player}'s misses ({@link FactorLookup#pityMisses}): what a Claim payout rolls under. */
    @Nonnull
    public FactorLookup over(@Nonnull FactorLookup base, @Nonnull UUID player) {
        return FactorLookup.withPityMisses(base, key -> misses(player, key));
    }

    /**
     * Settle one written Claim's pass for {@code player}: {@code rolls} the payout held, {@code kept} the picks its
     * Claim holds, {@code lookup} what they rolled under. The one-pass form of {@link #settle(UUID, Tally)}.
     */
    public void settle(@Nonnull UUID player, @Nonnull List<Roll> rolls, @Nonnull List<LootEngine.Selected> kept,
            @Nonnull FactorLookup lookup) {
        Tally tally = new Tally();
        tally.add(rolls, kept, lookup);
        settle(player, tally);
    }

    /**
     * Settle one written Claim for {@code player}: each key {@code tally} saw fire starts over, each it saw only miss
     * gains one miss, and the file is written once. Call only once the Claim is written.
     */
    public synchronized void settle(@Nonnull UUID player, @Nonnull Tally tally) {
        if (tally.outcomes.isEmpty()) {
            return;
        }
        Map<String, Integer> mine = counts.computeIfAbsent(player, k -> new HashMap<>());
        for (Map.Entry<String, Boolean> outcome : tally.outcomes.entrySet()) {
            if (outcome.getValue()) {
                mine.remove(outcome.getKey());
            } else {
                int held = mine.getOrDefault(outcome.getKey(), 0);
                mine.put(outcome.getKey(), held >= Integer.MAX_VALUE ? held : Math.max(0, held) + 1);
            }
        }
        if (mine.isEmpty()) {
            counts.remove(player);
        }
        file.writeNow();
    }

    // ==================== one payout's passes ====================

    /**
     * What one payout's passes decided for each pity key, gathered over every table and pass it rolled before its
     * Claim is written: a key a kept pick's roll carries fired, a key a roll held in reach without a kept pick missed.
     * Pure; not thread-safe (one payout fills it on one thread).
     */
    public static final class Tally {

        /** Per pity key: true once a kept pick named it, false while it was only in reach. */
        private final Map<String, Boolean> outcomes = new LinkedHashMap<>();

        public Tally() {
        }

        /**
         * One pass: {@code rolls} it evaluated, {@code kept} the picks its Claim will hold, under {@code lookup}. A
         * roll is in reach when its table's gates and its conditions pass for {@code lookup}, read with no sample.
         */
        public void add(@Nonnull List<Roll> rolls, @Nonnull List<LootEngine.Selected> kept,
                @Nonnull FactorLookup lookup) {
            for (Roll roll : rolls) {
                String key = keyOf(roll);
                if (key != null && inReach(roll, lookup)) {
                    outcomes.putIfAbsent(key, Boolean.FALSE);
                }
            }
            for (LootEngine.Selected pick : kept) {
                String key = pick == null ? null : keyOf(pick.roll());
                if (key != null) {
                    outcomes.put(key, Boolean.TRUE);
                }
            }
        }

        /** True when nothing here carries a pity key, so settling changes nothing. */
        public boolean isEmpty() {
            return outcomes.isEmpty();
        }

        /** Per key, true for fired and false for missed; a key out of reach everywhere is absent. */
        @Nonnull
        public Map<String, Boolean> outcomes() {
            return Collections.unmodifiableMap(outcomes);
        }

        @Nullable
        private static String keyOf(@Nullable Roll roll) {
            Roll.Pity pity = roll == null ? null : roll.getPity();
            return pity == null ? null : pity.key();
        }

        private static boolean inReach(@Nonnull Roll roll, @Nonnull FactorLookup lookup) {
            return FactorGate.passesGates(roll.gates(), lookup) && FactorGate.pass(roll.getConditions(), lookup);
        }
    }

    // ==================== persistence ====================

    private void readDoc(@Nonnull JsonObject doc) {
        Dto dto = gson.fromJson(doc, Dto.class);
        counts.clear();
        if (dto == null || dto.players == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Integer>> e : dto.players.entrySet()) {
            UUID player = uuid(e.getKey());
            if (player == null || e.getValue() == null) {
                continue;
            }
            Map<String, Integer> mine = new HashMap<>();
            for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                String key = Roll.Pity.keyOf(c.getKey());
                if (key != null && c.getValue() != null && c.getValue() > 0) {
                    mine.merge(key, c.getValue(), Math::max);
                }
            }
            if (!mine.isEmpty()) {
                counts.put(player, mine);
            }
        }
    }

    @Nonnull
    private JsonObject writeDoc() {
        Dto dto = new Dto();
        dto.players = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Integer>> e : counts.entrySet()) {
            dto.players.put(e.getKey().toString(), new HashMap<>(e.getValue()));
        }
        return gson.toJsonTree(dto).getAsJsonObject();
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
