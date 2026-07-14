package com.ziggfreed.common.cast;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Registry of nested {@code onHit} builders for cast / ability params. A consumer
 * that exposes an optional {@code onHit} payload (a projectile, an AOE, a next-hit
 * buff, a dash, ...) calls {@link #fromParams(Object, Ref, UUID)} to convert the
 * JSON shape into a {@link BiConsumer} that runs after primary damage / cast effects
 * are applied on the hit target.
 *
 * <p>Two JSON shapes are accepted:
 * <ul>
 *   <li><b>Single object:</b> {@code "onHit": {"type": "STATUS", ...}} - resolves to
 *       one consumer.</li>
 *   <li><b>List:</b> {@code "onHit": [{...},{...}]} - each entry is built independently
 *       and the resulting consumers are chained via {@code consumer.andThen(consumer2)},
 *       firing in declaration order on the same target. {@link #NO_OP} entries are
 *       skipped so a malformed / unknown entry never dead-links the chain.</li>
 * </ul>
 *
 * <p>Adding a new on-hit type means registering a builder via
 * {@link #register(String, OnHitBuilder)} - no edits to the dispatch logic. A missing
 * or unknown type degrades to {@link #NO_OP} with a FINE log; a builder that throws
 * also degrades to {@link #NO_OP}.
 *
 * <p><b>Per-consumer instance (deliberate structural design):</b> this is an
 * INSTANTIABLE registry, not a static utility. Each consumer mod news up its own
 * {@code OnHitRegistry} so two mods sharing this jar never see each other's type
 * registrations. The registry ships EMPTY - every built-in type is consumer policy,
 * registered by the consumer at startup.
 *
 * <p><b>Two callback shapes.</b> The original {@link OnHitBuilder} -&gt; {@code BiConsumer<Store, Ref>}
 * path (above) stays for callers that only need the store + target. A parallel
 * {@link HitActionBuilder} -&gt; {@link HitAction} path ({@link #registerAction} / {@link #actionFromParams})
 * produces a {@link HitContext}-carrying action instead, so a caller that needs a
 * {@code CommandBuffer} accessor (to DEFER a component mutation), the damage amount, or the hit
 * position can route through this registry too. The two registrations live in separate tables and
 * never collide; a type registered on one path is invisible to the other.
 */
public final class OnHitRegistry {

    /** No-op on-hit callback - returned when {@code onHit} params are absent or invalid. */
    public static final BiConsumer<Store<EntityStore>, Ref<EntityStore>> NO_OP = (s, r) -> {};

    /**
     * No-op {@link HitAction} - the {@link HitContext}-shaped counterpart to {@link #NO_OP},
     * returned when {@link #actionFromParams} finds absent or invalid params. Aliases the canonical
     * {@link HitAction#NO_OP} so identity checks (`== NO_OP_ACTION`) still hold.
     */
    public static final HitAction NO_OP_ACTION = HitAction.NO_OP;

    /**
     * Builder contract for converting a single {@code onHit} entry into a runnable
     * callback. Receives the resolved type-specific params, the caster's {@code Ref}
     * (nullable when the caster is detached), and the caster's UUID (nullable when the
     * caster is not a player). Implementations must be pure functions of their inputs -
     * they are called once per entry at params-parse time, and the returned
     * {@link BiConsumer} is invoked once per hit.
     */
    @FunctionalInterface
    public interface OnHitBuilder {
        @Nonnull
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> build(
                @Nonnull Map<String, Object> params,
                @Nullable Ref<EntityStore> sourceRef,
                @Nullable UUID sourcePlayerId);
    }

    /**
     * The {@link HitContext}-shaped builder contract - the same shape as {@link OnHitBuilder} but
     * producing a {@link HitAction} (a rich {@link HitContext} carrier that can hold a
     * {@code CommandBuffer} accessor, a damage amount, a hit position, ...) instead of a
     * {@code BiConsumer<Store, Ref>}. A consumer that needs any of those on-hit fields registers via
     * {@link #registerAction} and resolves via {@link #actionFromParams}; the {@link OnHitBuilder}
     * path stays for callers that only need the store + target.
     */
    @FunctionalInterface
    public interface HitActionBuilder {
        @Nonnull
        HitAction build(
                @Nonnull Map<String, Object> params,
                @Nullable Ref<EntityStore> sourceRef,
                @Nullable UUID sourcePlayerId);
    }

    private final Map<String, OnHitBuilder> builders = new ConcurrentHashMap<>();
    private final Map<String, HitActionBuilder> actionBuilders = new ConcurrentHashMap<>();

    public OnHitRegistry() {}

    /**
     * Register a custom on-hit builder. Replaces any existing builder with the same
     * type id (case-insensitive, last write wins).
     */
    public void register(@Nonnull String type, @Nonnull OnHitBuilder builder) {
        builders.put(type.toUpperCase(), builder);
    }

    /**
     * Register a {@link HitContext}-shaped builder. The {@link HitAction} counterpart to
     * {@link #register}; case-insensitive, last write wins. Kept in a SEPARATE table from the
     * {@link OnHitBuilder} registrations, so a consumer can register some types as {@code BiConsumer}
     * and others as {@code HitAction} without the two colliding.
     */
    public void registerAction(@Nonnull String type, @Nonnull HitActionBuilder builder) {
        actionBuilders.put(type.toUpperCase(), builder);
    }

    /**
     * Build a callback from the {@code onHit} value of a cast's params. Accepts either
     * a single Map (simple shape) or a List of Maps (chained shape). Returns
     * {@link #NO_OP} if the value is missing, malformed, or every entry resolves to
     * NO_OP.
     */
    @Nonnull
    public BiConsumer<Store<EntityStore>, Ref<EntityStore>> fromParams(
            @Nullable Object onHit,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable UUID sourcePlayerId) {
        if (onHit == null) return NO_OP;
        if (onHit instanceof Map<?, ?> single) {
            return buildSingle(asStringKeyMap(single), sourceRef, sourcePlayerId);
        }
        if (onHit instanceof List<?> list) {
            BiConsumer<Store<EntityStore>, Ref<EntityStore>> chain = NO_OP;
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?>)) continue;
                BiConsumer<Store<EntityStore>, Ref<EntityStore>> next =
                        buildSingle(asStringKeyMap((Map<?, ?>) entry), sourceRef, sourcePlayerId);
                if (next == NO_OP) continue;
                chain = (chain == NO_OP) ? next : chain.andThen(next);
            }
            return chain;
        }
        return NO_OP;
    }

    /**
     * Back-compat overload - a caller that passes {@code def.getMap("onHit")} (which
     * returns an empty map for the chained-list form) hands the {@code Map} shape
     * directly to this overload.
     */
    @Nonnull
    public BiConsumer<Store<EntityStore>, Ref<EntityStore>> fromParams(
            @Nonnull Map<String, Object> onHit,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable UUID sourcePlayerId) {
        if (onHit.isEmpty()) return NO_OP;
        return buildSingle(onHit, sourceRef, sourcePlayerId);
    }

    @Nonnull
    private BiConsumer<Store<EntityStore>, Ref<EntityStore>> buildSingle(
            @Nonnull Map<String, Object> entry,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable UUID sourcePlayerId) {
        Object typeObj = entry.get("type");
        if (!(typeObj instanceof String typeStr)) return NO_OP;
        OnHitBuilder builder = builders.get(typeStr.toUpperCase());
        if (builder == null) {
            fine("OnHitRegistry: unknown onHit.type '" + typeStr + "'");
            return NO_OP;
        }
        try {
            return builder.build(entry, sourceRef, sourcePlayerId);
        } catch (Throwable t) {
            fine("OnHitRegistry: builder for '" + typeStr + "' threw: " + t.getMessage());
            return NO_OP;
        }
    }

    /**
     * The {@link HitAction} counterpart to {@link #fromParams(Object, Ref, UUID)}: convert an
     * {@code onHit} value into a {@link HitAction}, dispatching to the {@link #registerAction}
     * registrations. Accepts either a single Map or a List of Maps (chained in declaration order via
     * {@link HitAction#andThen}, {@link #NO_OP_ACTION} entries skipped). Returns {@link #NO_OP_ACTION}
     * if the value is missing, malformed, or every entry resolves to a no-op.
     */
    @Nonnull
    public HitAction actionFromParams(
            @Nullable Object onHit,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable UUID sourcePlayerId) {
        if (onHit == null) return NO_OP_ACTION;
        if (onHit instanceof Map<?, ?> single) {
            return buildSingleAction(asStringKeyMap(single), sourceRef, sourcePlayerId);
        }
        if (onHit instanceof List<?> list) {
            HitAction chain = NO_OP_ACTION;
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?>)) continue;
                HitAction next =
                        buildSingleAction(asStringKeyMap((Map<?, ?>) entry), sourceRef, sourcePlayerId);
                if (next == NO_OP_ACTION) continue;
                chain = (chain == NO_OP_ACTION) ? next : chain.andThen(next);
            }
            return chain;
        }
        return NO_OP_ACTION;
    }

    /** Back-compat {@code Map} overload of {@link #actionFromParams(Object, Ref, UUID)}. */
    @Nonnull
    public HitAction actionFromParams(
            @Nonnull Map<String, Object> onHit,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable UUID sourcePlayerId) {
        if (onHit.isEmpty()) return NO_OP_ACTION;
        return buildSingleAction(onHit, sourceRef, sourcePlayerId);
    }

    @Nonnull
    private HitAction buildSingleAction(
            @Nonnull Map<String, Object> entry,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable UUID sourcePlayerId) {
        Object typeObj = entry.get("type");
        if (!(typeObj instanceof String typeStr)) return NO_OP_ACTION;
        HitActionBuilder builder = actionBuilders.get(typeStr.toUpperCase());
        if (builder == null) {
            fine("OnHitRegistry: unknown onHit.type '" + typeStr + "' (action)");
            return NO_OP_ACTION;
        }
        try {
            return builder.build(entry, sourceRef, sourcePlayerId);
        } catch (Throwable t) {
            fine("OnHitRegistry: action builder for '" + typeStr + "' threw: " + t.getMessage());
            return NO_OP_ACTION;
        }
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(@Nonnull Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
