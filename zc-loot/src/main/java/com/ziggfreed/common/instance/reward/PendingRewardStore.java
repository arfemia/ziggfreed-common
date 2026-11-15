package com.ziggfreed.common.instance.reward;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ziggfreed.common.util.RecordFile;

/**
 * A durable per-player queue of rewards that could not be delivered yet (an item reward
 * BLOCKED by a full inventory at claim time, or any reward earned while a player was
 * away). The generalized lift of hyMMO's {@code PendingRewardStore}: the consumer queues
 * the blocked rewards, the player re-claims them later (the results screen's re-claim, or
 * a {@code PlayerConnectEvent} drain on next login) once they have made space.
 *
 * <p>File-backed JSON, kept through zc-core's {@code util/RecordFile} (atomic write with a
 * {@code .bak}). Thread-safe (concurrent map). The blocked-at-claim guard keeps a finished round from
 * ever silently dropping a reward.
 *
 * <p>The file carries a {@code "version"} so a later structural change can tell an old file from
 * a new one; a file with no version reads as version 1, since every file written before the marker
 * existed is a version-1 file. A file declaring a NEWER version than this build reads is left
 * unread, and unwritten, with one warning, rather than misread as today's shape; a torn file reads
 * its {@code .bak}; and a file this build cannot read at all, backup included, is left unread and
 * unwritten too. Either way the queue still answers from memory, and the file on disk waits for a
 * build that reads it (the latch {@code ClaimStore} and {@code DiminishLedger} keep). Each reward is
 * written as a {@link StoredReward}, whose optional spec is additive and moves no version.
 */
public final class PendingRewardStore {

    /** The one file shape this build reads and writes; also what an absent version means. */
    static final int SCHEMA_VERSION = 1;

    private final String fileName;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, List<StoredReward>> pending = new ConcurrentHashMap<>();
    /** The file, kept the one library way: a newer or unreadable file is never overwritten by this build. */
    private final RecordFile file;

    /** The file's leaves; its {@code version} is the record file's (absent in a pre-marker file: version 1). */
    private static final class Dto {
        Map<String, List<StoredReward>> players;
    }

    public PendingRewardStore(@Nonnull String name) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
        this.file = RecordFile.builder("pending", "the rewards waiting in " + fileName, new RecordFile.Body() {
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
                        pending.clear();
                    }
                })
                .version(SCHEMA_VERSION)
                .lostWrite("a restart may lose a reward still owed")
                .refuseUnreadable()
                .build();
    }

    /** Resolve the file under {@code dataDir} and load any existing queue. Call once at setup. */
    public void init(@Nullable Path dataDir) {
        if (dataDir == null) {
            return;
        }
        file.persistTo(dataDir.resolve(fileName), null);
    }

    /** Queue {@code rewards} for {@code uuid} (appended to any already pending). */
    public void queue(@Nonnull UUID uuid, @Nonnull List<InstanceReward> rewards) {
        if (rewards.isEmpty()) {
            return;
        }
        pending.compute(uuid, (k, existing) -> {
            List<StoredReward> list = existing != null ? existing : new ArrayList<>();
            for (InstanceReward r : rewards) {
                list.add(StoredReward.of(r));
            }
            return list;
        });
        flush();
    }

    /** Remove and return everything pending for {@code uuid} (for a re-claim / connect drain). */
    @Nonnull
    public List<InstanceReward> drain(@Nonnull UUID uuid) {
        List<StoredReward> removed = pending.remove(uuid);
        if (removed == null || removed.isEmpty()) {
            return List.of();
        }
        flush();
        List<InstanceReward> out = new ArrayList<>(removed.size());
        for (StoredReward s : removed) {
            InstanceReward r = s == null ? null : s.toReward();
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** Whether {@code uuid} has any pending rewards. */
    public boolean has(@Nonnull UUID uuid) {
        List<StoredReward> l = pending.get(uuid);
        return l != null && !l.isEmpty();
    }

    // ==================== persistence ====================

    private void readDoc(@Nonnull JsonObject doc) {
        Dto dto = gson.fromJson(doc, Dto.class);
        pending.clear();
        if (dto == null || dto.players == null) {
            return;
        }
        for (Map.Entry<String, List<StoredReward>> e : dto.players.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            try {
                pending.put(UUID.fromString(e.getKey()), new ArrayList<>(e.getValue()));
            } catch (Throwable ignored) {
                // skip a malformed uuid key
            }
        }
    }

    @Nonnull
    private JsonObject writeDoc() {
        Dto dto = new Dto();
        dto.players = new HashMap<>();
        for (Map.Entry<UUID, List<StoredReward>> e : pending.entrySet()) {
            dto.players.put(e.getKey().toString(), new ArrayList<>(e.getValue()));
        }
        return gson.toJsonTree(dto).getAsJsonObject();
    }

    /** Write the queue now; a refused file, or no data folder, writes nothing. */
    private void flush() {
        file.writeNow();
    }
}
