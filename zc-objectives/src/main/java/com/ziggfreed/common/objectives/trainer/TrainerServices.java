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
 * The services a trainer offers for each track it teaches, beside the library's own respec: an open table a mod
 * fills, so the Services part shows a service only once something answers for it (R623). Three ids are reserved for
 * the services the design names, each with no entry and no UI until a consumer registers it:
 *
 * <ul>
 *   <li>{@link #PRESTIGE}: the prestige ceremony, entered at the trainer;</li>
 *   <li>{@link #RECHOOSE}: re-choosing a single milestone, priced a fraction of a full respec (R665);</li>
 *   <li>{@link #SUGGESTED_BUILD}: the update-day suggested build.</li>
 * </ul>
 *
 * <p>A mod's service of any other kind registers under its own id. Each service is asked per track, in registration
 * order, after the respec; one that throws costs its own entries and nobody else's ({@link #info} keeps its history).
 * Backed by the shared registry ledger: ids are case-insensitive and a second registration under one id replaces the
 * first.
 */
public final class TrainerServices {

    /** The prestige ceremony. */
    public static final String PRESTIGE = "Prestige";

    /** Re-choosing a single milestone. */
    public static final String RECHOOSE = "Rechoose";

    /** The update-day suggested build. */
    public static final String SUGGESTED_BUILD = "Suggested_Build";

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

    /** Register {@code service} under {@code id} ({@link #PRESTIGE} or your own), for {@code owner}. Call at setup. */
    public static void register(@Nullable String id, @Nullable String owner, @Nullable TrainerService service) {
        if (id == null || id.isBlank() || service == null) {
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

    /** What every registered service offers for {@code track} at {@code at}, in registration order. */
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
