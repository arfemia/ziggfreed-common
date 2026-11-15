package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.util.SafeLog;

/**
 * The services a trainer offers for each track it teaches, beside the library's own respec: an open table, each
 * service shown only where it can do something for the player (R623). The library fills the three the design names
 * by default ({@link #registerBuiltIns}, from the trainer's bootstrap), so a server running the library alone offers
 * them (M513):
 *
 * <ul>
 *   <li>{@link #PRESTIGE}: the track's next prestige, once the climb has reached the level it needs;</li>
 *   <li>{@link #RECHOOSE}: choosing again, in one go, the picks a Remember prestige put aside (R598, R641);</li>
 *   <li>{@link #SUGGESTED_BUILD}: the track's suggested build ({@code Starter}, R727), spent from where the player
 *       stands.</li>
 * </ul>
 *
 * <p>A mod replaces one of them by registering its own under the same id (said once at INFO, never warned, since
 * replacing the library's own is what the id is for), and adds a service of any other kind under its own id. Each
 * service is asked per track, sorted by id, after the respec; one that throws costs its own entries and nobody
 * else's ({@link #info} keeps its history). Backed by the shared registry ledger: ids are case-insensitive, and a
 * second registration of a mod's own id replaces the first with the ledger's one warning.
 */
public final class TrainerServices {

    /** The prestige. */
    public static final String PRESTIGE = "Prestige";

    /** Choosing again what a prestige put aside. */
    public static final String RECHOOSE = "Rechoose";

    /** The track's suggested build. */
    public static final String SUGGESTED_BUILD = "Suggested_Build";

    /** The owner the library's own services are registered under. */
    static final String LIBRARY = "ziggfreedcommon";

    /** One service: what it offers for one track at one trainer. World thread. */
    @FunctionalInterface
    public interface TrainerService {

        /** The entries this service offers for {@code track} at {@code at}; empty for none. */
        @Nonnull
        List<TrainerEntry> entries(@Nonnull TrainerAt at, @Nonnull TrackDef track);
    }

    private static final RegistryLedger<TrainerService> LEDGER = new RegistryLedger<>("trainer services");

    private TrainerServices() {
    }

    /** The library's own three, each under its id ({@link ClimbServices}). Registration only; at setup. */
    public static void registerBuiltIns() {
        register(PRESTIGE, LIBRARY, ClimbServices.PRESTIGE);
        register(RECHOOSE, LIBRARY, ClimbServices.RESPEND);
        register(SUGGESTED_BUILD, LIBRARY, ClimbServices.STARTER);
    }

    /**
     * Register {@code service} under {@code id} ({@link #PRESTIGE} and the others to replace the library's own, or
     * your own), for {@code owner}. Call at setup.
     */
    public static void register(@Nullable String id, @Nullable String owner, @Nullable TrainerService service) {
        if (id == null || id.isBlank() || service == null) {
            return;
        }
        RegistryLedger.RegistrationInfo held = LEDGER.info().get(RegistryLedger.normalize(id));
        if (held != null && LIBRARY.equals(held.owner()) && !LIBRARY.equals(owner)) {
            LEDGER.putQuietly(id, owner, service);
            SafeLog.info("[trainer] '" + owner + "' offers the trainer service '" + id.trim()
                    + "' in place of the library's own");
            return;
        }
        LEDGER.put(id, owner, service);
    }

    /** Whether anything answers for {@code id}. */
    public static boolean has(@Nullable String id) {
        return LEDGER.isRegistered(id);
    }

    /** Every registered service's owner and failure history, by id (an admin read). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /** What every registered service offers for {@code track} at {@code at}, sorted by id. */
    @Nonnull
    static List<TrainerEntry> entries(@Nonnull TrainerAt at, @Nonnull TrackDef track) {
        List<TrainerEntry> out = new ArrayList<>();
        for (String id : LEDGER.ids()) {
            TrainerService service = LEDGER.get(id);
            if (service == null) {
                continue;
            }
            try {
                out.addAll(service.entries(at, track));
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[trainer] the service '" + id + "' failed: " + t.getMessage());
            }
        }
        return out;
    }
}
