package com.ziggfreed.common.track.source;

import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * A moment's payload that names the block it happened at (a block broken or placed) or the station it was worked at,
 * so a track source can count its moments per place: a diminishing-returns key's {@code Cell} and {@code Station}
 * read it ({@link DiminishKeyAsset}), and a source's {@code StandSeconds} waits on the block at {@link #spot()}.
 * zc-objectives' break and place payloads implement it; a fourth party's station moment may too, naming its station.
 */
public interface SpotPayload extends MomentPayload {

    /** The block the moment happened at, or null when it names none. */
    @Nullable
    Vector3i spot();

    /** The station the moment was worked at (any stable id: a bench's kind and place), or null for none. */
    @Nullable
    default String station() {
        return null;
    }
}
