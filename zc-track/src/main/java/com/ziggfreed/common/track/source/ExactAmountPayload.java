package com.ziggfreed.common.track.source;

import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * A moment's payload that carries its amount exactly, where the moment's own whole-number amount would round it (a
 * hit's damage): what a track source's {@code PerAmount} multiplies. A moment without one pays on its whole amount.
 */
public interface ExactAmountPayload extends MomentPayload {

    /** The moment's amount, unrounded; 0 or more. */
    double exactAmount();
}
