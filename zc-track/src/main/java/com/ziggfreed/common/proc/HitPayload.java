package com.ziggfreed.common.proc;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.ziggfreed.common.track.source.VictimPayload;

/**
 * What a moment about a hit carries beyond its victim: the engine's own damage, as applied, which a proc's
 * {@code Hit} group and {@code Like} read. zc-objectives' {@code DEAL_DAMAGE} payload implements it, so the procs
 * hear a player's hit from the one producer of it ({@link ProcMoments}) and never from a second damage system.
 */
public interface HitPayload extends VictimPayload {

    /** The engine's damage. */
    @Nonnull
    Damage damage();
}
