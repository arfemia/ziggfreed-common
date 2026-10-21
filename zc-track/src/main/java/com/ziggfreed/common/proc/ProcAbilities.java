package com.ziggfreed.common.proc;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The seam a consumer with abilities of its own fills, so a proc's {@code Ability.Names} can ask for a hit or a cast
 * of one of them: it names the ability behind a hit, and says which RootInteractions start its own casts (those it
 * fires as Cast moments itself, through {@link Procs#cast}, once a cast truly goes off, so the chain its key starts
 * never counts as a second one). The engine knows no ability beyond a RootInteraction, which a proc asks through
 * {@code Ability.Roots} with no seam at all, so on a server with no consumer filling this, nothing is named and
 * every root still reads. Any thread for a registration; world thread for the reads.
 */
public final class ProcAbilities {

    /** What a consumer answers about its own moves. */
    public interface Namer {

        /**
         * The id of the consumer's own ability that dealt {@code damage} (a damage-over-time tick of it included), or
         * null for a hit none of its abilities dealt. World thread, inside the damage dispatch: read only.
         */
        @Nullable
        String hitAbility(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
                @Nonnull Damage damage);

        /**
         * Does a chain from {@code rootId} start one of the consumer's own casts, which it fires as Cast moments
         * itself? False unless said.
         */
        default boolean castsItself(@Nonnull String rootId) {
            return false;
        }
    }

    private static final Map<String, Namer> NAMERS = new ConcurrentHashMap<>();

    private ProcAbilities() {
    }

    /** Fill the seam for {@code owner} (one namer an owner; a second registration replaces the first). */
    public static void register(@Nonnull String owner, @Nonnull Namer namer) {
        NAMERS.put(owner.trim().toLowerCase(Locale.ROOT), namer);
    }

    /** The ability a consumer names for {@code damage}, or null. Never throws. */
    @Nullable
    static String hitAbility(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> attacker,
            @Nonnull Damage damage) {
        if (attacker == null || !attacker.isValid() || NAMERS.isEmpty()) {
            return null;
        }
        for (Namer namer : NAMERS.values()) {
            try {
                String named = namer.hitAbility(accessor, attacker, damage);
                if (named != null && !named.isBlank()) {
                    return named.trim();
                }
            } catch (Throwable t) {
                Procs.failed("naming a hit's ability", t);
            }
        }
        return null;
    }

    /** Does a consumer fire its own Cast moments for chains of {@code rootId}? Never throws. */
    static boolean castsItself(@Nullable String rootId) {
        if (rootId == null || NAMERS.isEmpty()) {
            return false;
        }
        for (Namer namer : NAMERS.values()) {
            try {
                if (namer.castsItself(rootId)) {
                    return true;
                }
            } catch (Throwable t) {
                Procs.failed("asking whose cast a chain is", t);
            }
        }
        return false;
    }

    /** Forget every namer (a test). */
    static void clear() {
        NAMERS.clear();
    }
}
