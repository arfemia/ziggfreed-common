package com.ziggfreed.common.ui.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.lookup.CodecMapCodec;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The process-wide vocabulary of everything that can be opened, and the one place a mod adds to it.
 *
 * <p><b>Why this is shared while a page is not.</b> Content lives in ONE set of stores the server
 * reads once, so there is exactly one chance to understand a destination an author wrote - the SCHEMA
 * has to know every mod's types. What a type OPENS stays with the mod that registered it, so one
 * mod's content can never run another's code; only the ability to read the file is pooled. It is the
 * factor vocabulary's shape applied to screens: a namespaced id, an owner, a handler behind it, and
 * a validator that can ask whether anybody answers.
 *
 * <p><b>Register in your plugin's {@code setup()}, before assets load.</b> The server runs every
 * plugin's setup to completion and only then reads asset files, so a type registered during setup is
 * readable by the time the first file naming it decodes. Registering later is the one way to get this
 * wrong: the file is read first, names a {@code Type} nothing has registered, and fails to load. A
 * late registration still takes effect (the vocabulary re-assembles) and logs one line saying so.
 *
 * <p>Ids are matched case-insensitively for BOOKKEEPING (who owns it, how often it failed) and
 * SPELLED EXACTLY as registered in authored files, which is what makes a mis-cased {@code Type} a
 * loud read failure rather than a silent near-miss. Registration is last-write-wins, warned once per
 * id when two different mods claim one.
 */
public final class Destinations {

    private static final RegistryLedger<DestinationType<?>> LEDGER = new RegistryLedger<>("destination");

    /** Destinations already warned about, so a repeated dispatch to an unhandled one logs once. */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    @Nullable private static volatile Assembled assembled;
    private static volatile boolean decoded;

    private Destinations() {
    }

    // ==================== registration ====================

    /** Register {@code type} without naming an owner. Prefer {@link #register(String, DestinationType)}. */
    public static void register(@Nullable DestinationType<?> type) {
        register(RegistryLedger.UNATTRIBUTED, type);
    }

    /**
     * Register {@code type}, attributed to {@code owner} (your mod's name, which an admin listing and
     * an overwrite warning both name). Call once from your plugin's {@code setup()}.
     */
    public static synchronized void register(@Nullable String owner, @Nullable DestinationType<?> type) {
        if (type == null || type.typeId().isBlank()) {
            return;
        }
        LEDGER.put(type.typeId(), owner, type);
        invalidate();
    }

    private static synchronized void invalidate() {
        assembled = null;
        if (decoded) {
            SafeLog.warn("[destination] a destination type was registered after content had already been read."
                    + " It takes effect from now on, but any file that named it has already failed to load -"
                    + " register destination types in your plugin's setup, before assets load");
        }
    }

    // ==================== the assembled vocabulary ====================

    /**
     * The assembled {@code Type}-discriminated codec, for the paths that only WRITE or describe it
     * (an encode, an editor schema). Those run while a server is still coming up, so they must not
     * count as content having been read.
     */
    @Nonnull
    static CodecMapCodec<Destination> union() {
        return assembled().union;
    }

    /**
     * The same vocabulary, for a READ. An unregistered {@code Type} throws out of it, naming the
     * file being read; and reaching this marks the vocabulary as read, so a registration arriving
     * afterwards says so rather than looking like it was always there.
     */
    @Nonnull
    static CodecMapCodec<Destination> unionForRead() {
        decoded = true;
        return assembled().union;
    }

    @Nonnull
    private static Assembled assembled() {
        Assembled cached = assembled;
        if (cached != null) {
            return cached;
        }
        synchronized (Destinations.class) {
            Assembled again = assembled;
            if (again != null) {
                return again;
            }
            Assembled built = assemble();
            assembled = built;
            return built;
        }
    }

    @Nonnull
    private static Assembled assemble() {
        CodecMapCodec<Destination> union = new CodecMapCodec<>(Destination.TYPE_KEY);
        Map<Class<? extends Destination>, DestinationType<?>> byClass = new LinkedHashMap<>();
        for (String id : LEDGER.ids()) {
            DestinationType<?> type = LEDGER.get(id);
            if (type == null) {
                continue;
            }
            registerInto(union, type);
            byClass.put(type.destinationClass(), type);
        }
        return new Assembled(union, byClass);
    }

    private static <D extends Destination> void registerInto(@Nonnull CodecMapCodec<Destination> union,
            @Nonnull DestinationType<D> type) {
        union.register(type.typeId(), type.destinationClass(), type.codec());
    }

    /** One frozen vocabulary over one snapshot of the registrations. */
    private record Assembled(@Nonnull CodecMapCodec<Destination> union,
                             @Nonnull Map<Class<? extends Destination>, DestinationType<?>> byClass) {
    }

    // ==================== the runtime ====================

    /**
     * Open {@code destination} for the player in {@code ctx}. True when a screen was actually taken
     * over, so a caller that gets false still owes the player its own response.
     *
     * <p>Guarded: a destination whose type nobody registered, and a handler that throws, each cost
     * their own open and nothing else. A failure is counted against the registering owner.
     */
    public static boolean open(@Nullable Destination destination, @Nonnull DestinationContext ctx) {
        if (destination == null) {
            return false;
        }
        DestinationType<?> type = assembled().byClass.get(destination.getClass());
        if (type == null) {
            warnOnce(destination.getClass().getName(),
                    "nothing is registered to open " + destination.getClass().getSimpleName()
                            + ", so that destination does nothing");
            return false;
        }
        try {
            return dispatch(type, destination, ctx);
        } catch (Throwable t) {
            LEDGER.recordFailure(type.typeId(), t.getMessage());
            SafeLog.warn("[destination] '" + type.typeId() + "' failed to open: " + t.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <D extends Destination> boolean dispatch(@Nonnull DestinationType<D> type,
            @Nonnull Destination destination, @Nonnull DestinationContext ctx) {
        return type.handler().open((D) destination, ctx);
    }

    /**
     * Run the registered type's own audit over {@code destination}, or return nothing when it
     * registered none. {@code sourceId} labels whatever authored it, for the finding to point at.
     */
    @Nonnull
    public static List<Finding> validate(@Nullable Destination destination, @Nonnull String sourceId) {
        if (destination == null) {
            return List.of();
        }
        DestinationType<?> type = assembled().byClass.get(destination.getClass());
        if (type == null || type.check() == null) {
            return List.of();
        }
        try {
            return new ArrayList<>(check(type, destination, sourceId));
        } catch (Throwable t) {
            LEDGER.recordFailure(type.typeId(), t.getMessage());
            SafeLog.warn("[destination] '" + type.typeId() + "' failed to audit '" + sourceId + "': " + t.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    private static <D extends Destination> List<Finding> check(@Nonnull DestinationType<D> type,
            @Nonnull Destination destination, @Nonnull String sourceId) {
        DestinationCheck<D> check = type.check();
        return check == null ? List.of() : check.check((D) destination, sourceId);
    }

    // ==================== reads ====================

    /** The {@code Type} id {@code destination} was registered under, or null when nothing was. */
    @Nullable
    public static String typeIdOf(@Nullable Destination destination) {
        if (destination == null) {
            return null;
        }
        DestinationType<?> type = assembled().byClass.get(destination.getClass());
        return type == null ? null : type.typeId();
    }

    /** Is {@code typeId} claimed? Matched case-insensitively, unlike the decode itself. */
    public static boolean isRegistered(@Nullable String typeId) {
        return LEDGER.isRegistered(typeId);
    }

    /** Every registered type, spelled as an author must write it, sorted (a diagnostic, a pick list). */
    @Nonnull
    public static List<String> registeredTypes() {
        List<String> out = new ArrayList<>();
        for (String id : LEDGER.ids()) {
            DestinationType<?> type = LEDGER.get(id);
            if (type != null) {
                out.add(type.typeId());
            }
        }
        return List.copyOf(out);
    }

    /** Every registration's owner + failure history, keyed by id (an admin listing reads it). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /** Drop every registration. Tests only; a live server registers once and never unregisters. */
    public static synchronized void clearForTests() {
        LEDGER.clear();
        WARNED.clear();
        assembled = null;
        decoded = false;
    }

    private static void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (WARNED.putIfAbsent(key, Boolean.TRUE) == null) {
            SafeLog.warn("[destination] " + message);
        }
    }
}
