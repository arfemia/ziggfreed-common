package com.ziggfreed.common.entity.performer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An immutable snapshot of a performer's binding back to its owning session - the pure value the
 * reconcile decision core ({@link PerformerReconciler}'s policies) operates on, decoupled from the
 * registered {@link PerformerIdentityComponent} so the decision logic is unit-testable with no live
 * server. {@link PerformerIdentityComponent#toIdentity()} produces one.
 *
 * @param ownerUuid   the UUID of the session that spawned the performer, or {@code null} when the
 *                    stored value could not be parsed (a corrupt/absent owner - a policy treats it
 *                    as "no live owner").
 * @param stationKey  the station block key the performer belongs to (the same
 *                    {@code "<worldUuid>:<x>:<y>:<z>"} key a station engine uses for exclusivity).
 * @param kind        which backend owns it.
 * @param spawnedAtMs epoch-millis the performer was spawned (for stale-orphan age heuristics).
 */
public record PerformerIdentity(@Nullable UUID ownerUuid, @Nonnull String stationKey,
        @Nonnull PerformerKind kind, long spawnedAtMs) {
}
