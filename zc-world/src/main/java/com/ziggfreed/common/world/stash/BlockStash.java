package com.ziggfreed.common.world.stash;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;

/**
 * What one block position is holding for players: named {@link StashPile}s plus the couple of
 * values that describe the stash as a whole. Saved and loaded with the chunk (see
 * {@link BlockStashes}), so it survives a disconnect, a restart, and a chunk unload alike.
 *
 * <p>Pure storage. A stash records who placed what and how far unattended work has come; every
 * rule about it - who may add or take, what a pile accepts, what happens on a timer - belongs to
 * the consumer reading it.
 *
 * <ul>
 *   <li>{@code Owner} - the uuid string of the player the stash as a whole belongs to (typically
 *       whoever stood it up); per-pile ownership lives on each pile.</li>
 *   <li>{@code Piles} - the piles by the consumer's own pile key, in insertion order.</li>
 *   <li>{@code ProgressGameTime} / {@code LastGameTime} - two clock leaves in WORLD GAME TIME,
 *       never wall clock: game time does not advance while the server is down, so an outage
 *       advances a stash's clocks by exactly zero and nothing cooks, spoils or accrues across
 *       it. What each marks is the consumer's convention (typically: when the current window
 *       started, and when the stash was last settled).</li>
 *   <li>{@code Tag} - an opaque consumer label carried verbatim (say, which authored layout this
 *       stash was written for), so a reader can tell its own stashes from another consumer's.</li>
 * </ul>
 *
 * <p>Every leaf is nullable; an unauthored leaf reads as null and costs nothing on disk.
 */
public final class BlockStash {

    @Nullable protected String owner;
    @Nullable protected Map<String, StashPile> piles;
    @Nullable protected Long progressGameTime;
    @Nullable protected Long lastGameTime;
    @Nullable protected String tag;

    public static final BuilderCodec<BlockStash> CODEC = BuilderCodec.builder(BlockStash.class, BlockStash::new)
            .append(new KeyedCodec<>("Owner", Codec.STRING, false),
                    (s, v) -> s.owner = v, s -> s.owner)
            .documentation("Uuid string of the player this stash belongs to; each pile records its own owner too.")
            .add()
            .append(new KeyedCodec<>("Piles", new MapCodec<>(StashPile.CODEC, LinkedHashMap::new, false), false),
                    (s, v) -> s.piles = v, s -> s.piles)
            .documentation("The piles by pile key, in insertion order.")
            .add()
            .append(new KeyedCodec<>("ProgressGameTime", Codec.LONG, false),
                    (s, v) -> s.progressGameTime = v, s -> s.progressGameTime)
            .documentation("World GAME time, never wall clock: a server outage advances it by zero.")
            .add()
            .append(new KeyedCodec<>("LastGameTime", Codec.LONG, false),
                    (s, v) -> s.lastGameTime = v, s -> s.lastGameTime)
            .documentation("World GAME time, never wall clock: a server outage advances it by zero.")
            .add()
            .append(new KeyedCodec<>("Tag", Codec.STRING, false),
                    (s, v) -> s.tag = v, s -> s.tag)
            .documentation("Opaque consumer label carried verbatim, so a reader can tell its own stashes apart.")
            .add()
            .build();

    public BlockStash() {
    }

    /** The uuid string of the player this stash belongs to, or null when unrecorded. */
    @Nullable
    public String getOwner() {
        return owner;
    }

    public void setOwner(@Nullable String owner) {
        this.owner = owner;
    }

    /** The piles by pile key in insertion order, or null when the stash holds none. */
    @Nullable
    public Map<String, StashPile> getPiles() {
        return piles;
    }

    /** {@link #getPiles()}, creating the (insertion-ordered) map on first use. */
    @Nonnull
    public Map<String, StashPile> pilesMutable() {
        Map<String, StashPile> map = piles;
        if (map == null) {
            map = new LinkedHashMap<>();
            piles = map;
        }
        return map;
    }

    /** The pile under this key, or null. */
    @Nullable
    public StashPile pile(@Nonnull String key) {
        Map<String, StashPile> map = piles;
        return map != null ? map.get(key) : null;
    }

    /**
     * The pile under this key, created empty on first use. A pile left with no leaf set is
     * dropped by a save (an entry that says nothing is not written), so set at least its owner
     * before letting go of it.
     */
    @Nonnull
    public StashPile ensurePile(@Nonnull String key) {
        return pilesMutable().computeIfAbsent(key, k -> new StashPile());
    }

    /** World GAME time (a server outage advances it by zero), or null when unset. */
    @Nullable
    public Long getProgressGameTime() {
        return progressGameTime;
    }

    public void setProgressGameTime(@Nullable Long progressGameTime) {
        this.progressGameTime = progressGameTime;
    }

    /** World GAME time (a server outage advances it by zero), or null when unset. */
    @Nullable
    public Long getLastGameTime() {
        return lastGameTime;
    }

    public void setLastGameTime(@Nullable Long lastGameTime) {
        this.lastGameTime = lastGameTime;
    }

    /** The opaque consumer label, or null. */
    @Nullable
    public String getTag() {
        return tag;
    }

    public void setTag(@Nullable String tag) {
        this.tag = tag;
    }
}
