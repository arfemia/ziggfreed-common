package com.ziggfreed.common.objectives.title;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.subject.Subject;

/**
 * Fires the title family's native event under the library-wide contract ({@link NativeEventSeam}):
 * built only when somebody listens, dispatched on the calling (world) thread, never able to take
 * the write that caused it down. Fired by {@link TitleUnlocks} alone, and only for a REAL change.
 */
public final class TitleEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[title]");

    private TitleEvents() {
    }

    /** Route every fire through {@code publisher} instead of the engine bus; null restores the bus. For a host or a test. */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    static void fireChanged(@Nonnull Subject who, @Nonnull String titleId,
                            @Nonnull TitleUnlocks.Outcome change, @Nullable String active) {
        SEAM.fire("ZigTitleChanged", ZigTitleChangedEvent.class,
                () -> new ZigTitleChangedEvent(who.id(), liveRef(who), titleId, change, active));
    }

    @Nonnull
    private static PlayerRef liveRef(@Nonnull Subject who) {
        PlayerRef playerRef = who.handleAs(PlayerRef.class);
        if (playerRef == null) {
            throw new IllegalStateException("the subject '" + who.name()
                    + "' carries no live player reference, so the title change cannot be announced");
        }
        return playerRef;
    }
}
