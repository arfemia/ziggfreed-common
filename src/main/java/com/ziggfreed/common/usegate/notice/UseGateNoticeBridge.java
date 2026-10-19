package com.ziggfreed.common.usegate.notice;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;

import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.usegate.ZigUseGateRefusedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Tells a refused player why: a use gate's refusal ({@link ZigUseGateRefusedEvent}, zc-entity, which may never
 * import presentation) as a danger notice carrying the gate's one line (zc-presentation's {@link Notify}).
 * Registered by the wiring root on the engine bus, the one place that sees both. Tagged per gate, so a refused
 * swing repeated at the swing rate rewrites one notice instead of filling the feed, while two different gates
 * still each get their own line.
 *
 * <p>REGISTRATION ONLY, like every root file: no loop, switch or else here, pinned by
 * {@code RootRegistrationOnlyTest}.
 */
public final class UseGateNoticeBridge {

    /** The notice tag prefix; the refusing gate's id, lower-cased, follows it. */
    public static final String TAG_PREFIX = "zc:usegate:";

    private UseGateNoticeBridge() {
    }

    /** Tell {@code event}'s player the line its gate refused with. */
    public static void onRefused(@Nonnull ZigUseGateRefusedEvent event) {
        try {
            Notify.send(event.playerRef(), event.line(),
                    NotificationStyle.Danger,
                    TAG_PREFIX + event.gateId().toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            SafeLog.fine("[usegate] the refusal notice failed: " + t.getMessage());
        }
    }
}
