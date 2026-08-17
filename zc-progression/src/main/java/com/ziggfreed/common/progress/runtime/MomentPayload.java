package com.ziggfreed.common.progress.runtime;

/**
 * What a producer knows about a moment beyond the tuple every moment carries: the native event it
 * came off, the entity that died, the recipe that was crafted.
 *
 * <p>A marker, on purpose, and deliberately NOT sealed. The library's own producers each ship one
 * typed record beside them, and a reaction reads the one it needs with an {@code instanceof}
 * pattern; a mod firing a net-new moment through the shared dispatch adds its own record the same
 * way, so a fourth-party reaction to a fourth-party moment gets typed access on equal terms. A bare
 * {@code Object} would give it nowhere to hang that; a sealed hierarchy would close the surface the
 * open producer registry exists to keep open.
 *
 * <p>Read on the world thread, inside the dispatch that produced it. Nothing here outlives the
 * moment.
 */
public interface MomentPayload {
}
