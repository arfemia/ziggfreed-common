package com.ziggfreed.common.reputation.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.reputation.ReputationChange;
import com.ziggfreed.common.reputation.ReputationLadder;

/**
 * Where the reputation module's native events go: the shared engine bus, through one
 * {@link NativeEventSeam} (built only when something listens, never throwing). A test observes them with
 * {@code publishTo}.
 */
public final class ReputationEvents {

    /** The module's event family; a test routes it with {@code publishTo}. */
    public static final NativeEventSeam SEAM = new NativeEventSeam("[reputation]");

    private ReputationEvents() {
    }

    public static void fireChanged(@Nonnull ReputationChange change) {
        SEAM.fire("ZigReputationChanged", ZigReputationChangedEvent.class, () -> ZigReputationChangedEvent.of(change));
    }

    public static void fireRanksHeld(@Nonnull UUID playerId, @Nonnull String reputationId,
            @Nonnull List<ReputationLadder.Rank> held) {
        SEAM.fire("ZigReputationRanksHeld", ZigReputationRanksHeldEvent.class,
                () -> new ZigReputationRanksHeldEvent(playerId, reputationId,
                        held.stream().map(ReputationLadder.Rank::id).toList()));
    }
}
