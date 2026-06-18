package com.ziggfreed.common.instance.reward;

import java.nio.file.Files;
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
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A durable per-player queue of rewards that could not be delivered yet (an item reward
 * BLOCKED by a full inventory at claim time, or any reward earned while a player was
 * away). The generalized lift of hyMMO's {@code PendingRewardStore}: the consumer queues
 * the blocked rewards, the player re-claims them later (the results screen's re-claim, or
 * a {@code PlayerConnectEvent} drain on next login) once they have made space.
 *
 * <p>File-backed JSON, atomic write. Thread-safe (concurrent map). The blocked-at-claim
 * guard keeps a finished round from ever silently dropping a reward.
 */
public final class PendingRewardStore {

    private final String fileName;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, List<StoredReward>> pending = new ConcurrentHashMap<>();
    @Nullable private volatile Path file;

    /** Gson-friendly persisted shape of one reward. */
    private static final class StoredReward {
        String kind;
        String id;
        int quantity;
        String displayKey;
    }

    private static final class Dto {
        Map<String, List<StoredReward>> players;
    }

    public PendingRewardStore(@Nonnull String name) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
    }

    /** Resolve the file under {@code dataDir} and load any existing queue. Call once at setup. */
    public void init(@Nullable Path dataDir) {
        if (dataDir == null) {
            return;
        }
        this.file = dataDir.resolve(fileName);
        load();
    }

    /** Queue {@code rewards} for {@code uuid} (appended to any already pending). */
    public void queue(@Nonnull UUID uuid, @Nonnull List<InstanceReward> rewards) {
        if (rewards.isEmpty()) {
            return;
        }
        pending.compute(uuid, (k, existing) -> {
            List<StoredReward> list = existing != null ? existing : new ArrayList<>();
            for (InstanceReward r : rewards) {
                list.add(toStored(r));
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
            InstanceReward r = fromStored(s);
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

    private void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            Dto dto = gson.fromJson(Files.readString(f), Dto.class);
            pending.clear();
            if (dto != null && dto.players != null) {
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
        } catch (Throwable t) {
            warn(fileName + " unreadable; starting empty: " + t.getMessage());
        }
    }

    private void flush() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Dto dto = new Dto();
            dto.players = new HashMap<>();
            for (Map.Entry<UUID, List<StoredReward>> e : pending.entrySet()) {
                dto.players.put(e.getKey().toString(), new ArrayList<>(e.getValue()));
            }
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            FileUtil.writeStringAtomic(f, gson.toJson(dto), true);
        } catch (Throwable t) {
            warn(fileName + " flush failed: " + t.getMessage());
        }
    }

    @Nonnull
    private static StoredReward toStored(@Nonnull InstanceReward r) {
        StoredReward s = new StoredReward();
        s.kind = r.kind().name();
        s.id = r.id();
        s.quantity = r.quantity();
        s.displayKey = r.displayKey();
        return s;
    }

    @Nullable
    private static InstanceReward fromStored(@Nonnull StoredReward s) {
        if (s.kind == null || s.id == null) {
            return null;
        }
        try {
            InstanceReward.Kind kind = InstanceReward.Kind.valueOf(s.kind);
            return new InstanceReward(kind, s.id, s.quantity, s.displayKey);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void warn(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
        }
    }
}
