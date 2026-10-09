package com.ziggfreed.common.npc.placement.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.entity.ItemPropEntityService;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.npc.NpcSpawnService;
import com.ziggfreed.common.npc.placement.anchor.AnchorPosition;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections;
import com.ziggfreed.common.world.TickingSections.SectionPos;

/**
 * Draws a placement's {@code Props}: one still, intangible item prop ({@link ItemPropEntityService}) per
 * entry at each placement instance that stands, and takes them away when it stops standing.
 *
 * <p><b>Drawn, never saved, so absence IS proof here.</b> An item prop carries the engine's
 * {@code NonSerialized} marker, and a chunk section going to sleep keeps only the holders with something
 * to save ({@code EntitySection.EntitySectionLoadingSystem}): the prop is dropped, not parked, and nothing
 * ever brings it back. That inverts the NPC rule ("never place from absence alone"): a prop missing from
 * a ticking section is genuinely gone, so it is drawn again. It also means no ledger row, no stamp and no
 * adoption pass, and nothing is left behind by a restart, a removed placement or an uninstalled library.
 *
 * <p><b>Only into a ticking section, and never a wake for a prop.</b> A section with no player near goes
 * back to sleep within seconds and would drop the prop with it, so a round draws a prop only where its
 * section already ticks and leaves the rest waiting. {@link PropSectionWatch} sweeps the world again
 * when a section holding a waiting prop loads or wakes ({@link #wantsSection}), which is a player
 * arriving, so the table is there whenever someone can see it.
 *
 * <p><b>What stands.</b> A placement drawing props and naming no role stands no NPC: its instances are
 * its resolved positions, with {@code Limits.MaxPerWorld} counted across them. A placement with a role
 * draws its props at each instance whose NPC is placed (its ledger row), so the two spawn and go
 * together. Either way the round's gate, {@code Where} and {@code Season} decide first: the sweep asks
 * {@link #want} only for a placement that stands in the world, and one it does not ask about loses its
 * props in that round ({@link Book#reconcile}). A placement the round could not decide (no position
 * yet, a failure) keeps what stands and draws nothing.
 *
 * <p>World thread only, inside the sweep's world task (outside any system's processing window). The
 * rules are pure and run over the {@link Drawer} seam, so a unit test drives them with a fake engine.
 */
final class PlacementProps {

    /** One prop at one placement instance: its entry's place in the list, the item, where, and how. */
    record Spot(int index, @Nonnull String item, double x, double y, double z, float yaw, float scale) {

        /** The chunk section the prop stands in. */
        @Nonnull
        SectionPos section() {
            return SectionPos.ofBlock(x, y, z);
        }
    }

    /** What one placement instance wants drawn this round. */
    record Want(@Nonnull String placementId, @Nonnull List<Spot> spots) {
    }

    /** What one round did: props drawn, and standing props taken away. */
    record Tally(int drawn, int removed) {
    }

    /** The engine side of a round, a seam so the rules run in a unit JVM. Package-private for the test. */
    interface Drawer<H> {

        /** Whether a prop added at {@code spot} would stay: its chunk section ticks. */
        boolean ticking(@Nonnull Spot spot);

        /** Draw the prop at {@code spot}; null when it could not be drawn this time. */
        @Nullable
        H draw(@Nonnull Spot spot);

        /** Whether a prop drawn earlier still stands. */
        boolean standing(@Nonnull H handle);

        /** Take a standing prop away. */
        void remove(@Nonnull H handle);
    }

    private PlacementProps() {
    }

    // ==================== the pure rules ====================

    /**
     * The props {@code props} draw at {@code at}, the placement's own spot (its anchor plus the anchor
     * group's own {@code Offset}): each entry at that spot plus its own {@code Offset} in WORLD axes (the
     * anchor's yaw turns the NPC, never the props' offsets), facing its own {@code Yaw}. An entry naming no
     * item, or one {@code itemKnown} refuses, is skipped and the rest are kept, each with its authored
     * index. PURE.
     */
    @Nonnull
    static List<Spot> spotsAt(@Nonnull List<NpcPlacementAsset.Prop> props, @Nonnull AnchorPosition at,
            @Nonnull Predicate<String> itemKnown) {
        List<Spot> out = new ArrayList<>(props.size());
        for (int i = 0; i < props.size(); i++) {
            NpcPlacementAsset.Prop prop = props.get(i);
            String item = prop == null ? null : prop.itemId();
            if (item == null || !itemKnown.test(item)) {
                continue;
            }
            Vec3 offset = prop.getOffset();
            out.add(new Spot(i, item,
                    at.x() + (offset == null ? 0.0 : offset.effectiveX()),
                    at.y() + (offset == null ? 0.0 : offset.effectiveY()),
                    at.z() + (offset == null ? 0.0 : offset.effectiveZ()),
                    (float) prop.effectiveYaw(), (float) prop.effectiveScale()));
        }
        return List.copyOf(out);
    }

    /**
     * The instances of one placement that draw their props, out of the positions the round resolved: for a
     * placement drawing only props, every position up to {@code maxPerWorld} (0 or less is unlimited), in
     * the anchor order; for one with a role, each position whose NPC is placed ({@code placedHere}, asked
     * by anchor key), whose own count the ledger already keeps. PURE.
     */
    @Nonnull
    static List<AnchorPosition> standingAt(boolean propOnly, @Nonnull List<AnchorPosition> positions,
            @Nonnull Predicate<String> placedHere, int maxPerWorld) {
        if (propOnly) {
            return PlacementAnchors.applyMaxPerWorld(positions, maxPerWorld, 0);
        }
        List<AnchorPosition> out = new ArrayList<>(positions.size());
        for (AnchorPosition position : positions) {
            if (placedHere.test(position.anchorKey())) {
                out.add(position);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Record into {@code wanted} what {@code placement}, which stands in this world this round, wants drawn
     * at {@code positions}, keyed by instance ({@code NpcPlacementService.instanceKey}). An instance whose
     * every entry is skipped wants nothing. PURE.
     */
    static void want(@Nonnull Map<String, Want> wanted, @Nonnull NpcPlacementAsset placement, boolean propOnly,
            @Nonnull List<AnchorPosition> positions, @Nonnull Predicate<String> placedHere,
            @Nonnull Predicate<String> itemKnown) {
        String placementId = placement.getId();
        if (placementId == null || placementId.isBlank() || !placement.hasProps()) {
            return;
        }
        NpcPlacementAsset.Limits limits = placement.getLimits();
        int max = limits == null ? 0 : limits.effectiveMaxPerWorld();
        for (AnchorPosition at : standingAt(propOnly, positions, placedHere, max)) {
            List<Spot> spots = spotsAt(placement.getProps(), at, itemKnown);
            if (!spots.isEmpty()) {
                wanted.put(NpcPlacementService.instanceKey(placementId, at.anchorKey()), new Want(placementId, spots));
            }
        }
    }

    // ==================== the book: what one world has drawn ====================

    /**
     * The props one world has drawn, by placement instance and entry, and the round that brings it into
     * agreement with what is wanted. Package-private for the test.
     */
    static final class Book<H> {

        /** One entry drawn (or waiting to be) at one instance. */
        private record Drawn<T>(@Nonnull Spot spot, @Nonnull SectionPos section, @Nullable T handle) {
        }

        /** One placement instance's entries, by their authored index. */
        private record Instance<T>(@Nonnull String placementId, @Nonnull Map<Integer, Drawn<T>> drawn) {
        }

        private final Map<String, Instance<H>> instances = new LinkedHashMap<>();

        /** Every section holding an entry of this book: the cheap first answer for {@link #wantsSection}. */
        private volatile Set<SectionPos> sections = Set.of();

        /**
         * One round. Every instance not in {@code wanted} loses its standing props and is forgotten, unless
         * its placement is in {@code undecided} (the round could not tell whether it stands), which keeps
         * what stands and forgets what does not. Every wanted entry already standing as wanted is left alone;
         * one standing at a spot the content no longer says (an edited or removed entry) is taken away; one
         * missing is drawn when its section ticks and otherwise waits.
         */
        @Nonnull
        synchronized Tally reconcile(@Nonnull Map<String, Want> wanted, @Nonnull Set<String> undecided,
                @Nonnull Drawer<H> drawer) {
            int drawn = 0;
            int removed = 0;
            Iterator<Map.Entry<String, Instance<H>>> known = instances.entrySet().iterator();
            while (known.hasNext()) {
                Map.Entry<String, Instance<H>> entry = known.next();
                if (wanted.containsKey(entry.getKey())) {
                    continue;
                }
                Instance<H> instance = entry.getValue();
                if (undecided.contains(instance.placementId())) {
                    instance.drawn().values().removeIf(d -> !stands(drawer, d.handle()));
                } else {
                    for (Drawn<H> d : instance.drawn().values()) {
                        removed += remove(drawer, d.handle());
                    }
                    instance.drawn().clear();
                }
                if (instance.drawn().isEmpty()) {
                    known.remove();
                }
            }
            for (Map.Entry<String, Want> entry : wanted.entrySet()) {
                Want want = entry.getValue();
                Instance<H> instance = instances.computeIfAbsent(entry.getKey(),
                        k -> new Instance<>(want.placementId(), new LinkedHashMap<>()));
                Map<Integer, Spot> byIndex = new LinkedHashMap<>();
                for (Spot spot : want.spots()) {
                    byIndex.put(spot.index(), spot);
                }
                Iterator<Map.Entry<Integer, Drawn<H>>> entries = instance.drawn().entrySet().iterator();
                while (entries.hasNext()) {
                    Map.Entry<Integer, Drawn<H>> d = entries.next();
                    if (!d.getValue().spot().equals(byIndex.get(d.getKey()))) {
                        removed += remove(drawer, d.getValue().handle());
                        entries.remove();
                    }
                }
                for (Spot spot : want.spots()) {
                    Drawn<H> already = instance.drawn().get(spot.index());
                    if (already != null && stands(drawer, already.handle())) {
                        continue;
                    }
                    H handle = ticking(drawer, spot) ? draw(drawer, spot) : null;
                    instance.drawn().put(spot.index(), new Drawn<>(spot, spot.section(), handle));
                    if (handle != null) {
                        drawn++;
                    }
                }
            }
            Set<SectionPos> held = new HashSet<>();
            for (Instance<H> instance : instances.values()) {
                for (Drawn<H> d : instance.drawn().values()) {
                    held.add(d.section());
                }
            }
            sections = Set.copyOf(held);
            return new Tally(drawn, removed);
        }

        /**
         * Whether {@code section} holds an entry that is wanted and not standing (never drawn, or dropped when
         * the section slept): the question {@link PropSectionWatch} asks as a section loads or wakes.
         */
        boolean wantsSection(@Nonnull SectionPos section, @Nonnull Predicate<H> standing) {
            if (!sections.contains(section)) {
                return false;
            }
            synchronized (this) {
                for (Instance<H> instance : instances.values()) {
                    for (Drawn<H> d : instance.drawn().values()) {
                        if (d.section().equals(section) && (d.handle() == null || !standingSafely(standing, d.handle()))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** How many of {@code placementId}'s entries stand now, across its instances. */
        synchronized int standing(@Nonnull String placementId, @Nonnull Predicate<H> standing) {
            int count = 0;
            for (Instance<H> instance : instances.values()) {
                if (!instance.placementId().equalsIgnoreCase(placementId)) {
                    continue;
                }
                for (Drawn<H> d : instance.drawn().values()) {
                    if (d.handle() != null && standingSafely(standing, d.handle())) {
                        count++;
                    }
                }
            }
            return count;
        }

        /** Whether this book holds nothing at all. */
        synchronized boolean isEmpty() {
            return instances.isEmpty();
        }

        // Each engine call is guarded, so a throwing drawer costs one prop this round and never the round.

        private static <H> boolean stands(@Nonnull Drawer<H> drawer, @Nullable H handle) {
            try {
                return handle != null && drawer.standing(handle);
            } catch (Throwable t) {
                return false;
            }
        }

        private static <H> boolean standingSafely(@Nonnull Predicate<H> standing, @Nonnull H handle) {
            try {
                return standing.test(handle);
            } catch (Throwable t) {
                return false;
            }
        }

        private static <H> boolean ticking(@Nonnull Drawer<H> drawer, @Nonnull Spot spot) {
            try {
                return drawer.ticking(spot);
            } catch (Throwable t) {
                return false;
            }
        }

        @Nullable
        private static <H> H draw(@Nonnull Drawer<H> drawer, @Nonnull Spot spot) {
            try {
                return drawer.draw(spot);
            } catch (Throwable t) {
                SafeLog.fine("[placement] could not draw the prop '" + spot.item() + "': " + t.getMessage());
                return null;
            }
        }

        /** Take {@code handle} away when it stands; 1 when it did. */
        private static <H> int remove(@Nonnull Drawer<H> drawer, @Nullable H handle) {
            if (!stands(drawer, handle)) {
                return 0;
            }
            try {
                drawer.remove(handle);
                return 1;
            } catch (Throwable t) {
                SafeLog.fine("[placement] could not take a prop away: " + t.getMessage());
                return 0;
            }
        }
    }

    // ==================== the live books, one per world ====================

    /** Each world's book, keyed by world name; a removed world's goes with it ({@link #forgetWorld}). */
    private static final Map<String, Book<Ref<EntityStore>>> BOOKS = new ConcurrentHashMap<>();

    /**
     * Bring {@code world}'s props into agreement with this round's {@code wanted}, sparing what
     * {@code undecided} placements keep standing. The sweep's place pass calls it once per round, after its
     * NPCs are placed. World thread only. Never throws.
     */
    static void reconcile(@Nonnull World world, @Nonnull Store<EntityStore> store, @Nonnull String worldName,
            @Nonnull Map<String, Want> wanted, @Nonnull Set<String> undecided) {
        if (worldName.isEmpty()) {
            return;
        }
        try {
            Book<Ref<EntityStore>> book = wanted.isEmpty() ? BOOKS.get(worldName)
                    : BOOKS.computeIfAbsent(worldName, k -> new Book<>());
            if (book == null) {
                return;
            }
            Tally tally = book.reconcile(wanted, undecided, new LiveDrawer(world, store));
            if (book.isEmpty()) {
                BOOKS.remove(worldName, book);
            }
            if (tally.drawn() > 0) {
                PlacementDiag.once(world, "props-drawn",
                        "[placement] drawing placement props in '" + worldName + "' (" + tally.drawn()
                                + " now; a prop is drawn again whenever its chunk section ticks after dropping it)");
            }
            if (tally.removed() > 0) {
                SafeLog.info("[placement] took " + tally.removed() + " placement prop(s) away in '" + worldName
                        + "': their placement no longer stands there, or its Props changed");
            }
            if (tally.drawn() > 0 || tally.removed() > 0) {
                SafeLog.fine("[placement] props in '" + worldName + "': drawn=" + tally.drawn()
                        + " removed=" + tally.removed());
            }
        } catch (Throwable t) {
            SafeLog.warn("[placement] the props pass failed in '" + worldName + "': " + t.getMessage());
        }
    }

    /**
     * Whether a section of {@code worldName} holds a prop that is wanted and not standing, so its loading or
     * waking is worth a sweep. Cheap for any other section. Never throws.
     */
    static boolean wantsSection(@Nonnull String worldName, @Nonnull SectionPos section) {
        Book<Ref<EntityStore>> book = BOOKS.get(worldName);
        return book != null && book.wantsSection(section, Ref::isValid);
    }

    /** How many of {@code placementId}'s props stand in {@code worldName} now. Never throws. */
    static int standingCount(@Nonnull String worldName, @Nonnull String placementId) {
        Book<Ref<EntityStore>> book = BOOKS.get(worldName);
        return book == null ? 0 : book.standing(placementId, Ref::isValid);
    }

    /** Forget a removed world's props: they went with its entities. */
    static void forgetWorld(@Nonnull String worldName) {
        BOOKS.remove(worldName);
    }

    /**
     * The item check a round draws through: whether this server has the id (case for case, as the engine
     * looks it up), with one line per world, placement and item for an id it lacks, since drawing one would
     * paint the unknown-item picture. The validator's {@code UNKNOWN_PROP_ITEM} names it too.
     */
    @Nonnull
    static Predicate<String> loadedItems(@Nonnull World world, @Nonnull String worldName,
            @Nonnull String placementId) {
        return item -> {
            boolean known = ItemIds.exists(item);
            if (!known) {
                PlacementDiag.once(world, "prop-unknown|" + placementId + '|' + item,
                        "[placement] '" + placementId + "' in '" + worldName + "': the prop item '" + item
                                + "' is not an item this server has, so that prop is skipped (the rest still draw)");
            }
            return known;
        };
    }

    /** The live engine side: the library's item prop, still and intangible, through the world's store. */
    private record LiveDrawer(@Nonnull World world, @Nonnull Store<EntityStore> store)
            implements Drawer<Ref<EntityStore>> {

        @Override
        public boolean ticking(@Nonnull Spot spot) {
            return TickingSections.stateAt(world, spot.x(), spot.y(), spot.z()) == TickingSections.State.TICKING;
        }

        @Nullable
        @Override
        public Ref<EntityStore> draw(@Nonnull Spot spot) {
            // The spot's Yaw is authored degrees; the NPC spawn path's one conversion turns it into the
            // engine's radians here, where the entity is built.
            Holder<EntityStore> holder = ItemPropEntityService.buildHolder(store, spot.item(),
                    new Vector3d(spot.x(), spot.y(), spot.z()),
                    NpcSpawnService.spawnRotation(spot.yaw()), spot.scale(),
                    ItemPropEntityService.Options.DEFAULT.withIntangible());
            return holder == null ? null : ItemPropEntityService.spawn(store, holder);
        }

        @Override
        public boolean standing(@Nonnull Ref<EntityStore> handle) {
            return handle.isValid();
        }

        @Override
        public void remove(@Nonnull Ref<EntityStore> handle) {
            ItemPropEntityService.despawn(handle, store);
        }
    }
}
