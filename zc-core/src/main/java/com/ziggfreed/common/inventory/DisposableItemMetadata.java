package com.ziggfreed.common.inventory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The ONE server-wide list of item-stack METADATA keys that some mod has declared safe to destroy
 * together with the item carrying them: data that means nothing once the item is consumed (a stamped
 * stat record, a tooltip written for it, a per-instance modifier a mod hangs on it).
 *
 * <p><b>What it is for.</b> Anything that CONSUMES a stack (breaks it down, melts it, feeds it into
 * something) destroys whatever the stack carries in its metadata. A key some mod declared here is
 * data its owner has said may go with the item; a key nobody declared belongs to someone who has not
 * said so, and a careful consumer refuses the stack (or asks the player) rather than silently
 * destroying it. {@link #undeclared} answers that question in one pass over a stack's keys; the
 * zc-entity {@code ItemReadings.undeclaredMetadataKeys} reads the stack's keys and asks it in one
 * call. Deciding what to DO about an undeclared key is the consumer's policy, never this list's.
 *
 * <p><b>Who declares.</b> The mod that WRITES a key, once, during its plugin setup:
 * <ul>
 *   <li>a mod writing its own key calls {@link #declare(String...)};</li>
 *   <li>a stat stamper declares every key it writes by answering
 *       {@code Stamper.metadataKeys()}, which {@code StamperRegistry.register} declares here, so a
 *       third-party stamper needs no second call. The library's own {@code StackStatsStamper} (its
 *       {@code ZigStackStats} record and the engine's {@code ItemDisplay} tooltip it writes beside
 *       it) is declared that way when the library registers it.</li>
 * </ul>
 *
 * <p><b>A declared key is disposable whoever wrote it.</b> The list names KEYS, not writers: once a
 * key is declared, a stack carrying it may go whichever mod put the value there. That matters for
 * the engine's shared {@code ItemDisplay} key, which the library's stamper declares because its
 * stamp tooltip lives there: an {@code ItemDisplay} override goes with its item whether the library's
 * tooltip or another mod's custom name wrote it, because display text means nothing once the item
 * is consumed. A mod whose data must NOT go with the item keeps it under a key of its own and never
 * declares it.
 *
 * <p><b>Additive and never retracted.</b> A declaration outlives the stamper or feature that made
 * it: items written earlier still carry the key, and they are still safe to consume. Keys are matched
 * EXACTLY, case included, because metadata keys are the engine's BSON document keys and a key spelled
 * in another case is a different key. A null or blank key is ignored. Every method is thread-safe and
 * none throws.
 */
public final class DisposableItemMetadata {

    private static final Set<String> DECLARED = ConcurrentHashMap.newKeySet();

    private DisposableItemMetadata() {
    }

    /** Declare {@code keys} safe to destroy with their item; null or blank entries are ignored. */
    public static void declare(@Nullable String... keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            add(key);
        }
    }

    /** {@link #declare(String...)} over a collection, the shape a stamper's key set arrives in. */
    public static void declare(@Nullable Collection<String> keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            add(key);
        }
    }

    /** Whether some mod has declared {@code key}; exact match, case included. */
    public static boolean isDeclared(@Nullable String key) {
        return key != null && DECLARED.contains(key);
    }

    /** Every declared key, an immutable snapshot. */
    @Nonnull
    public static Set<String> declared() {
        return Set.copyOf(DECLARED);
    }

    /**
     * The keys in {@code keys} that NOBODY declared, in the order given, as an immutable set: empty
     * when every key is declared (a bare stack's empty key set included). One set lookup per key.
     */
    @Nonnull
    public static Set<String> undeclared(@Nonnull Collection<String> keys) {
        Set<String> out = null;
        for (String key : keys) {
            if (!isDeclared(key)) {
                if (out == null) {
                    out = new LinkedHashSet<>();
                }
                out.add(key);
            }
        }
        return out == null ? Set.of() : Collections.unmodifiableSet(out);
    }

    private static void add(@Nullable String key) {
        if (key != null && !key.isBlank()) {
            DECLARED.add(key);
        }
    }
}
