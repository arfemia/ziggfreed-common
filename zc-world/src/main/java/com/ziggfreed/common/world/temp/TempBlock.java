package com.ziggfreed.common.world.temp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One temporary block's record, kept on its chunk section (see {@link TempBlocks}) and saved with the chunk, so a
 * block placed for a few seconds still crumbles when its section next ticks after a restart or an unload.
 *
 * <ul>
 *   <li>{@code Block} - the block type key that was placed. The block is ours only while the cell holds it with its
 *       spared mark (a break clears the mark), and the record is forgotten the moment the cell is seen without it (a
 *       placement into the cell, or the sweep), so a block a player put in the cell since is never taken.</li>
 *   <li>{@code ExpiresAt} - when it crumbles, in wall-clock milliseconds since the epoch: a temporary block is a
 *       few seconds of cover, so time the server was down counts toward it and it is gone when the server is back.</li>
 *   <li>{@code Placer} - the uuid string of the player it was placed for, carried for a reader.</li>
 *   <li>{@code X}, {@code Y}, {@code Z} - the block's world cell, so the crumble sweep reads where it stands off
 *       the record alone.</li>
 * </ul>
 */
public final class TempBlock {

    @Nullable protected String block;
    @Nullable protected Long expiresAt;
    @Nullable protected String placer;
    @Nullable protected Integer x;
    @Nullable protected Integer y;
    @Nullable protected Integer z;

    public static final BuilderCodec<TempBlock> CODEC = BuilderCodec.builder(TempBlock.class, TempBlock::new)
            .append(new KeyedCodec<>("Block", Codec.STRING, false),
                    (r, v) -> r.block = v, r -> r.block)
            .documentation("The block type key that was placed; only that block is ever removed.")
            .add()
            .append(new KeyedCodec<>("ExpiresAt", Codec.LONG, false),
                    (r, v) -> r.expiresAt = v, r -> r.expiresAt)
            .documentation("When the block crumbles, in wall-clock milliseconds since the epoch.")
            .add()
            .append(new KeyedCodec<>("Placer", Codec.STRING, false),
                    (r, v) -> r.placer = v, r -> r.placer)
            .documentation("Uuid string of the player the block was placed for.")
            .add()
            .append(new KeyedCodec<>("X", Codec.INTEGER, false),
                    (r, v) -> r.x = v, r -> r.x)
            .documentation("The block's world X.")
            .add()
            .append(new KeyedCodec<>("Y", Codec.INTEGER, false),
                    (r, v) -> r.y = v, r -> r.y)
            .documentation("The block's world Y.")
            .add()
            .append(new KeyedCodec<>("Z", Codec.INTEGER, false),
                    (r, v) -> r.z = v, r -> r.z)
            .documentation("The block's world Z.")
            .add()
            .build();

    public TempBlock() {
    }

    /**
     * Record {@code block} placed at the world cell for {@code placer}, crumbling at {@code expiresAt}; the caller marks
     * the section dirty.
     */
    void set(@Nonnull String block, long expiresAt, @Nullable String placer, int x, int y, int z) {
        this.block = block;
        this.expiresAt = expiresAt;
        this.placer = placer;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Whether the record names its cell (a record written by this class always does). */
    boolean hasCell() {
        return x != null && y != null && z != null;
    }

    int x() {
        return x != null ? x : 0;
    }

    int y() {
        return y != null ? y : 0;
    }

    int z() {
        return z != null ? z : 0;
    }

    @Nullable
    public String getBlock() {
        return block;
    }

    /** When it crumbles; 0 when unrecorded, which reads as already due. */
    public long getExpiresAt() {
        return expiresAt != null ? expiresAt : 0L;
    }

    @Nullable
    public String getPlacer() {
        return placer;
    }

    /** Is it due to crumble at {@code nowMs}? */
    public boolean dueAt(long nowMs) {
        return getExpiresAt() <= nowMs;
    }
}
