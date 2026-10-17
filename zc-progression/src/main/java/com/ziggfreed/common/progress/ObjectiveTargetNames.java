package com.ziggfreed.common.progress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a step's TARGET is called when its kind targets an id in one module's own registry, which
 * no shipped name catalogue knows: the module that owns those ids names them, by kind.
 *
 * <p>The same division the step pictures keep: the kind carries the fact (what its Target is an id
 * of), and the module owning the vocabulary carries the reading. A module whose kind targets its own
 * ids registers one {@link Namer} for that kind at setup; the neutral composer asks it before the
 * engine's name catalogues, and a consumer's own resolver may ask it the same way
 * ({@link #nameOf}), so a server running this library alone names the target as a consumer's does.
 *
 * <p>A namer answers null for an id it does not know (and for one it knows but cannot name), so the
 * caller keeps its own fallback, which is the prettified id. Kind ids match case-insensitively; a
 * second registration for one kind replaces the first; a namer that throws costs only its own
 * answer. Nothing is pre-seeded: an empty table names nothing.
 */
public final class ObjectiveTargetNames {

    /** Names the targets of one kind's steps; null for a target it does not know. */
    @FunctionalInterface
    public interface Namer {

        @Nullable
        Message nameOf(@Nonnull String target);
    }

    private static final Map<String, Namer> NAMERS = new ConcurrentHashMap<>();

    private ObjectiveTargetNames() {
    }

    /** Register {@code namer} for the steps of {@code kindId}, replacing an earlier one. Once, at setup. */
    public static void register(@Nullable String kindId, @Nullable Namer namer) {
        if (kindId == null || kindId.isBlank() || namer == null) {
            return;
        }
        NAMERS.put(RegistryLedger.normalize(kindId), namer);
    }

    /**
     * What {@code target} is called as the target of a {@code kindId} step, by that kind's namer;
     * null when the kind has none, the namer does not know the target, or it throws.
     */
    @Nullable
    public static Message nameOf(@Nullable String kindId, @Nullable String target) {
        if (kindId == null || kindId.isBlank() || target == null || target.isBlank()) {
            return null;
        }
        Namer namer = NAMERS.get(RegistryLedger.normalize(kindId));
        if (namer == null) {
            return null;
        }
        try {
            return namer.nameOf(target.trim());
        } catch (Throwable t) {
            SafeLog.warn("[progression] the namer for " + kindId + " steps failed on '" + target + "': "
                    + t.getMessage());
            return null;
        }
    }
}
