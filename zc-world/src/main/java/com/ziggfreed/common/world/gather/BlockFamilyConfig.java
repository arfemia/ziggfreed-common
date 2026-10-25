package com.ziggfreed.common.world.gather;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.world.BlockOps;

/**
 * The {@code defaults < pack < owner} fold of every {@link BlockFamilyAsset}, and the one answer to "which families
 * is this block in?" ({@link #familiesOf}). A block's answer is worked out once from its item's merged native tags and
 * its id, then kept per block id until a family folds again or the item store reloads ({@link #itemsChanged}), so the
 * gather and thrift steps pay a map lookup per hit.
 *
 * <p>Family ids are lower-cased, as every keyed store's are; an answer's set holds them lower-cased, so a reader
 * building a stat id from one asks {@link #displayId} for the spelling the file was written with.
 */
public final class BlockFamilyConfig extends AbstractKeyedAssetConfig<BlockFamilyAsset> {

    private static final BlockFamilyConfig INSTANCE = new BlockFamilyConfig();

    /** Block id to the lower-cased ids of the families holding it, worked out on first ask. */
    private final Map<String, Set<String>> memberships = new ConcurrentHashMap<>();

    /** Bumped on every fold and item reload, so a reader keeping something derived knows to rebuild it. */
    private final AtomicLong generation = new AtomicLong();

    private BlockFamilyConfig() {
    }

    @Nonnull
    public static BlockFamilyConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, BlockFamilyAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, BlockFamilyAsset> layer) {
        super.mergePackLayer(layer);
        invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, BlockFamilyAsset> layer) {
        super.mergeOwnerLayer(layer);
        invalidate();
    }

    /** The item store loaded or dropped items: every block's tags may read differently. */
    public void itemsChanged() {
        invalidate();
    }

    /** A counter that moves whenever a family or an item changes, for a reader keeping a derived view. */
    public long generation() {
        return generation.get();
    }

    /**
     * The lower-cased ids of every family holding the block {@code blockId} (a block type id, any casing); empty
     * for air, an unknown block or no family. Never null; the set is unmodifiable.
     */
    @Nonnull
    public Set<String> familiesOf(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank() || "Empty".equals(blockId)) {
            return Set.of();
        }
        Set<String> known = memberships.get(blockId);
        if (known != null) {
            return known;
        }
        Set<String> worked = workOut(blockId);
        memberships.put(blockId, worked);
        return worked;
    }

    /** Is the block {@code blockId} in the family {@code familyId} (any casing)? */
    public boolean holds(@Nullable String familyId, @Nullable String blockId) {
        return familyId != null && familiesOf(blockId).contains(familyId.trim().toLowerCase(Locale.ROOT));
    }

    /** Is the block {@code blockId} in any of {@code familyIds} (any casing)? False for none named. */
    public boolean holdsAny(@Nullable String[] familyIds, @Nullable String blockId) {
        if (familyIds == null || familyIds.length == 0) {
            return false;
        }
        Set<String> families = familiesOf(blockId);
        if (families.isEmpty()) {
            return false;
        }
        for (String family : familyIds) {
            if (family != null && families.contains(family.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** The id as its file spells it (the store keys lower-cased ids), or the id given when no file has it. */
    @Nonnull
    public String displayId(@Nonnull String familyId) {
        BlockFamilyAsset asset = resolve(familyId);
        return asset != null && asset.getId() != null ? asset.getId() : familyId;
    }

    /** The families holding the block {@code blockId}, each as its file spells it, sorted; empty for none. */
    @Nonnull
    public List<String> displayFamiliesOf(@Nullable String blockId) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String family : familiesOf(blockId)) {
            out.add(displayId(family));
        }
        return new ArrayList<>(out);
    }

    /** Every family id as its file spells it, sorted. */
    @Nonnull
    public List<String> displayIds() {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (BlockFamilyAsset asset : all().values()) {
            if (asset != null && asset.getId() != null) {
                out.add(asset.getId());
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Which families hold {@code blockId}, from its item's merged tags (read through {@link BlockOps}), its name
     * patterns read against the block's base id so every state of one block (a stripped log, an open door) answers
     * as the block itself.
     */
    @Nonnull
    private Set<String> workOut(@Nonnull String blockId) {
        return membership(BlockOps.baseItemIdOf(blockId), BlockOps.rawTagsOf(blockId), all());
    }

    /**
     * The lower-cased ids of the families in {@code families} holding the block {@code blockId} whose item reads
     * {@code rawTags}, a family a selector names through {@code Families} worked out once for the block and shared by
     * every family naming it; a family that reaches itself that way names nothing through the loop. Pure, so a test
     * reads it with no engine.
     */
    @Nonnull
    static Set<String> membership(@Nonnull String blockId, @Nullable Map<String, String[]> rawTags,
            @Nonnull Map<String, BlockFamilyAsset> families) {
        Map<String, BlockFamilyAsset> byId = new LinkedHashMap<>();
        for (Map.Entry<String, BlockFamilyAsset> entry : families.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                byId.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        Membership walk = new Membership(blockId, rawTags, byId);
        Set<String> out = null;
        for (String id : byId.keySet()) {
            if (walk.test(id)) {
                if (out == null) {
                    out = new TreeSet<>();
                }
                out.add(id);
            }
        }
        return out == null ? Set.of() : Collections.unmodifiableSet(out);
    }

    /**
     * One block's answer per family, each worked out once, a family reaching itself answering no along the loop. An
     * answer worked out through a loop is not kept, so every family's answer is the one asking it first would give,
     * whatever order the families are asked in.
     */
    private static final class Membership implements Predicate<String> {

        @Nonnull private final String blockId;
        @Nullable private final Map<String, String[]> rawTags;
        @Nonnull private final Map<String, BlockFamilyAsset> byId;
        @Nonnull private final Map<String, Boolean> known = new HashMap<>();
        @Nonnull private final Set<String> walking = new HashSet<>();

        /** Whether the answer being worked out met a family already being walked (a loop). */
        private boolean looped;

        Membership(@Nonnull String blockId, @Nullable Map<String, String[]> rawTags,
                @Nonnull Map<String, BlockFamilyAsset> byId) {
            this.blockId = blockId;
            this.rawTags = rawTags;
            this.byId = byId;
        }

        @Override
        public boolean test(@Nonnull String familyId) {
            String key = familyId.trim().toLowerCase(Locale.ROOT);
            Boolean answer = known.get(key);
            if (answer != null) {
                return answer;
            }
            BlockFamilyAsset family = byId.get(key);
            if (family == null) {
                return false;
            }
            if (!walking.add(key)) {
                looped = true;
                return false;
            }
            boolean outer = looped;
            looped = false;
            try {
                String id = family.getId() == null ? key : family.getId();
                boolean holds = family.holds(id, blockId, rawTags, this);
                if (!looped) {
                    known.put(key, holds);
                }
                return holds;
            } finally {
                walking.remove(key);
                looped = outer || looped;
            }
        }
    }

    private void invalidate() {
        memberships.clear();
        generation.incrementAndGet();
    }
}
