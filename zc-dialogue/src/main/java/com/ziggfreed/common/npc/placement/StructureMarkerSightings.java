package com.ziggfreed.common.npc.placement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * A bounded, transient log of the spawn markers this engine has actually seen.
 *
 * <p>It answers the question a pack author cannot answer from the outside: WHICH marker ids and
 * roles a generated village, camp or instance dungeon really emits, so a structure anchor can be
 * written against something that exists rather than against a guess. Without it, an anchor that
 * matches nothing is indistinguishable from an anchor whose structure has not generated yet.
 *
 * <p>Bounded to the most recent {@value #MAX_ENTRIES} and deduped by
 * {@code (world, markerId, instanceId)} - the instance id being the marker's floored world
 * position - with a sighting count, so repeated chunk loads of one structure collapse to a single
 * row. Never persisted; fully synchronized because chunk loads race across worker threads.
 */
public final class StructureMarkerSightings {

    private static final int MAX_ENTRIES = 64;

    private static final StructureMarkerSightings INSTANCE = new StructureMarkerSightings();

    @Nonnull
    public static StructureMarkerSightings getInstance() {
        return INSTANCE;
    }

    private StructureMarkerSightings() {
    }

    /** One immutable sighting view (most-recent-first from {@link #listForWorld}). */
    public record Sighting(@Nonnull String world, @Nonnull String markerId, int x, int y, int z,
                           @Nonnull String instanceId, @Nonnull List<String> roles, int count) {
    }

    /** Mutable internal record, guarded by {@code this}. */
    private static final class Entry {
        final String world;
        final String markerId;
        final String instanceId;
        int x;
        int y;
        int z;
        List<String> roles = List.of();
        int count;

        Entry(String world, String markerId, String instanceId) {
            this.world = world;
            this.markerId = markerId;
            this.instanceId = instanceId;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    /**
     * Record (or bump) a sighting keyed by {@code (world, markerId, instanceId)}. The
     * touched entry moves to the head; the oldest is evicted past {@value #MAX_ENTRIES}.
     */
    public synchronized void record(@Nonnull String world, @Nonnull String markerId, double x, double y, double z,
            @Nonnull String instanceId, @Nonnull List<String> roles) {
        Entry found = null;
        for (Entry e : entries) {
            if (e.instanceId.equals(instanceId) && e.world.equals(world) && e.markerId.equals(markerId)) {
                found = e;
                break;
            }
        }
        if (found != null) {
            entries.remove(found);
        } else {
            found = new Entry(world, markerId, instanceId);
        }
        found.x = (int) Math.round(x);
        found.y = (int) Math.round(y);
        found.z = (int) Math.round(z);
        found.roles = List.copyOf(roles);
        found.count++;
        entries.addFirst(found);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    /** Most-recent-first views for one world. */
    @Nonnull
    public synchronized List<Sighting> listForWorld(@Nonnull String world) {
        List<Sighting> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.world.equals(world)) {
                out.add(new Sighting(e.world, e.markerId, e.x, e.y, e.z, e.instanceId, e.roles, e.count));
            }
        }
        return out;
    }

    /** Every sighting, most-recent-first (a diagnostic with no world filter). */
    @Nonnull
    public synchronized List<Sighting> listAll() {
        List<Sighting> out = new ArrayList<>();
        for (Entry e : entries) {
            out.add(new Sighting(e.world, e.markerId, e.x, e.y, e.z, e.instanceId, e.roles, e.count));
        }
        return out;
    }

    /** Forget everything (tests). */
    synchronized void clearForTests() {
        entries.clear();
    }
}
