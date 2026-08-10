package com.ziggfreed.common.cast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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

/**
 * Per-caster transient combat state - one or more "armed" damage multipliers set by
 * Power-Strike-style abilities and consumed on the next outgoing melee hit (or expired
 * by timeout), plus a parallel invulnerability window driven by dash-style i-frames.
 *
 * <h2>Slots</h2>
 *
 * <p>State is keyed by {@code (casterId, slot)}. Casting two abilities that write to
 * <em>different</em> slots stacks them; casting two abilities that write to the
 * <em>same</em> slot overwrites. The {@code SLOT_*} constants name the conventional
 * slots (primary / aura / momentum / dodge); a caller may use any slot string.
 *
 * <p>{@code casterId} is supplied BY THE CALLER and is never derived here. The caller's
 * caster-context is the single key-derivation authority (it equals a player caster's
 * entity UUID); every reader/writer here takes the UUID as passed and must derive it
 * consistently through that one authority, never re-derive it another way.
 *
 * <h2>Opaque attribution payload</h2>
 *
 * <p>Each armed slot (and each invulnerability window) can carry an OPAQUE
 * {@code @Nullable Object attribution} the consumer attaches - anything the consumer needs
 * to attribute the eventual hit (its own XP-routing record, a source tag, ...). Common never
 * inspects it. {@link #consumeArmed(UUID)} synthesizes a single composite {@link ArmedState}
 * from all live slots: multipliers <em>multiply</em>, flats <em>sum</em>, onHit callbacks
 * <em>chain</em>, and every live slot's attribution is exposed in encounter order via
 * {@link ArmedState#attributions} for the CONSUMER to fold (union, pick-first, or whatever its
 * attribution semantics require - the fold is deliberately consumer-side so no consumer-domain
 * vocabulary lives in this class). The consumer applies the product multiplier to outgoing
 * damage; its own pre-defense multiplier cap prevents stack abuse.
 *
 * <h2>Invulnerability</h2>
 *
 * <p>Separate from armed slots, callers can grant a timed {@link InvulnerabilityState}
 * via {@link #armInvulnerability}. While the window is live, the consumer's combat system
 * cancels incoming damage. If a cancel lands inside a configured dodge sub-window AND the
 * victim qualifies for a counterattack reward, the consumer may auto-arm a "dodge"
 * multiplier on the player's next outgoing hit - a counterattack reward for skill timing.
 * The window carries only the originating ability id and the opaque attribution so a
 * counterattack hit can be attributed correctly; timing + magnitude are consumer policy.
 *
 * <p>Cleared on disconnect via {@link #clearPlayer(UUID)}.
 *
 * <p><b>Per-consumer instance:</b> this is an INSTANTIABLE store, not a static singleton.
 * Each consumer mod holds its own {@code ArmedStateStore} so two mods sharing this jar
 * never see each other's armed state.
 */
public final class ArmedStateStore {

    /** Default armed-slot id used by next-hit-buff and on-hit arm-next-hit chains. */
    public static final String SLOT_PRIMARY = "primary";

    /** Slot id used by a buff aura so party buffs stack with single-target next-hit buffs. */
    public static final String SLOT_AURA = "aura";

    /** Slot id used by a dash's post-cast offensive arm so dash bonuses stack with the primary + aura slots. */
    public static final String SLOT_MOMENTUM = "momentum";

    /** Slot id auto-armed when the i-frame guard cancels a hit and the victim qualifies for a counterattack reward. */
    public static final String SLOT_DODGE = "dodge";

    /** Default slot id when callers don't specify one. Alias for {@link #SLOT_PRIMARY}. */
    public static final String DEFAULT_SLOT = SLOT_PRIMARY;

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, ArmedState>> armed =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, InvulnerabilityState> invulnerable =
            new ConcurrentHashMap<>();

    public ArmedStateStore() {}

    /**
     * Arm a damage multiplier on this player's {@link #DEFAULT_SLOT} with no
     * on-hit callback and no attribution. Replaces any prior value in that slot.
     */
    public void armMultiplier(UUID casterId, double multiplier, long durationMs) {
        armMultiplier(casterId, null, DEFAULT_SLOT, multiplier, durationMs, null, null);
    }

    /**
     * Arm a damage multiplier on the {@link #DEFAULT_SLOT}. Convenience
     * overload for callers that don't need a slot identity.
     */
    public void armMultiplier(UUID casterId, @Nullable String abilityId,
                              double multiplier, long durationMs,
                              @Nullable BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer,
                              @Nullable Object attribution) {
        armMultiplier(casterId, abilityId, DEFAULT_SLOT, multiplier, durationMs,
                onHitConsumer, attribution);
    }

    /**
     * Full-fat arm overload carrying the ability's identity, the slot key, an
     * on-hit callback, and the opaque attribution payload. When the armed state is
     * consumed by the weapon-swing damage event, the consumer applies the
     * multiplier, invokes {@code onHitConsumer} on the target, and reads
     * {@code attribution} back off the composite to attribute the hit.
     *
     * <p>Replaces any prior value in {@code slot} for this player.
     */
    public void armMultiplier(UUID casterId, @Nullable String abilityId, @Nonnull String slot,
                              double multiplier, long durationMs,
                              @Nullable BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer,
                              @Nullable Object attribution) {
        armNextHit(casterId, abilityId, slot, multiplier, 0.0, durationMs,
                onHitConsumer, attribution);
    }

    /**
     * Multiplier + flat-damage arm. Same shape as {@link #armMultiplier} but
     * also carries an additive flat-damage bonus that the consumer's combat system
     * applies AFTER the multiplier cap (so flats don't compound with multipliers and
     * stack additively with any flat-damage rewards). Either {@code multiplier > 1.0}
     * OR {@code flatDamage > 0.0} arms the slot - passing both zero (and no on-hit
     * consumer) is a no-op.
     *
     * <p>Replaces any prior value in {@code slot} for this player.
     */
    public void armNextHit(UUID casterId, @Nullable String abilityId, @Nonnull String slot,
                           double multiplier, double flatDamage, long durationMs,
                           @Nullable BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer,
                           @Nullable Object attribution) {
        if (casterId == null) return;
        // An armed slot is meaningful if it carries any of: a damage multiplier > 1,
        // additive flat damage > 0, or an on-hit chain (e.g. a stun payload).
        // Stun-only / status-only abilities arm with multiplier=1.0 and flatDamage=0,
        // relying entirely on the onHitConsumer payload.
        if (multiplier <= 1.0 && flatDamage <= 0.0 && onHitConsumer == null) return;
        ArmedState state = new ArmedState(
                abilityId,
                slot,
                multiplier <= 1.0 ? 1.0 : multiplier,
                Math.max(0.0, flatDamage),
                System.currentTimeMillis() + Math.max(1L, durationMs),
                onHitConsumer,
                attribution != null ? List.of(attribution) : Collections.emptyList());
        armed.computeIfAbsent(casterId, k -> new ConcurrentHashMap<>()).put(slot, state);
    }

    /**
     * Read the product of all live armed multipliers WITHOUT consuming them.
     * Returns 1.0 if nothing is armed or all slots have expired (expired
     * entries are pruned in-place).
     */
    public double peekArmedMultiplier(UUID casterId) {
        if (casterId == null) return 1.0;
        ConcurrentHashMap<String, ArmedState> slots = armed.get(casterId);
        if (slots == null) return 1.0;
        long now = System.currentTimeMillis();
        double product = 1.0;
        Iterator<Map.Entry<String, ArmedState>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ArmedState> e = it.next();
            if (e.getValue().expiresAtMs <= now) {
                it.remove();
                continue;
            }
            product *= e.getValue().multiplier;
        }
        if (slots.isEmpty()) {
            armed.remove(casterId);
        }
        return product;
    }

    /**
     * Read AND consume the composite armed multiplier (product of all live
     * slots). Returns 1.0 if nothing armed or all expired. <b>Does not surface
     * any on-hit payload</b> - callers that want the full state should use
     * {@link #consumeArmed(UUID)}.
     */
    public double consumeArmedMultiplier(UUID casterId) {
        ArmedState state = consumeArmed(casterId);
        return state != null ? state.multiplier : 1.0;
    }

    /**
     * Read AND consume the composite armed state across all slots. Returns
     * a synthesized {@link ArmedState} whose {@code multiplier} is the product
     * of all live slots, {@code flatDamage} is the SUM of all live slots,
     * {@code onHitConsumer} is the chain (in iteration order) of every live
     * slot's callback, and {@link ArmedState#attributions} is every live slot's
     * opaque attribution in encounter order (nulls omitted) for the consumer to
     * fold. Cross-slot semantics: multipliers compose multiplicatively (existing
     * behavior), flats compose additively (so two slots with {@code +10} and
     * {@code +5} flat yield {@code +15} flat on the consumed swing). Returns
     * {@code null} when no slot has any of {@code multiplier > 1.0},
     * {@code flatDamage > 0}, or a non-null {@code onHitConsumer} (a stun-only chain
     * still keeps the composite live so the consumer fires on the next hit).
     */
    @Nullable
    public ArmedState consumeArmed(UUID casterId) {
        if (casterId == null) return null;
        ConcurrentHashMap<String, ArmedState> slots = armed.remove(casterId);
        if (slots == null || slots.isEmpty()) return null;
        long now = System.currentTimeMillis();

        double productMultiplier = 1.0;
        double summedFlat = 0.0;
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> chainedConsumer = null;
        List<Object> mergedAttributions = null;
        String firstAbilityId = null;
        boolean anyLive = false;

        for (ArmedState state : slots.values()) {
            if (state.expiresAtMs <= now) continue;
            anyLive = true;
            productMultiplier *= state.multiplier;
            summedFlat += state.flatDamage;
            if (firstAbilityId == null) firstAbilityId = state.abilityId;
            if (state.onHitConsumer != null) {
                chainedConsumer = (chainedConsumer == null)
                        ? state.onHitConsumer
                        : chainedConsumer.andThen(state.onHitConsumer);
            }
            if (!state.attributions.isEmpty()) {
                if (mergedAttributions == null) mergedAttributions = new ArrayList<>();
                mergedAttributions.addAll(state.attributions);
            }
        }

        if (!anyLive) return null;
        // Keep the composite alive if any slot carries an on-hit chain even when no
        // damage benefit is armed - stun-only / status-only abilities arm with
        // multiplier=1.0 and flatDamage=0 and rely entirely on the chained consumer.
        if (productMultiplier <= 1.0 && summedFlat <= 0.0 && chainedConsumer == null) return null;

        return new ArmedState(
                firstAbilityId,
                DEFAULT_SLOT,
                productMultiplier,
                summedFlat,
                Long.MAX_VALUE,
                chainedConsumer,
                mergedAttributions != null
                        ? Collections.unmodifiableList(mergedAttributions) : Collections.emptyList());
    }

    public void clearPlayer(UUID casterId) {
        if (casterId == null) return;
        armed.remove(casterId);
        invulnerable.remove(casterId);
    }

    // ==================== Invulnerability windows ====================

    /**
     * Grant the player a timed invulnerability window. While live, the consumer's
     * combat system cancels incoming damage events. If the cancel lands inside a
     * configured dodge window AND the victim qualifies for a counterattack reward,
     * the consumer may auto-arm the {@code "dodge"} slot on the player's next
     * outgoing hit. Counterattack timing + magnitude are consumer policy, not carried
     * here - only the originating ability id and the opaque attribution are preserved.
     *
     * <p>Replaces any prior invulnerability for this player.
     *
     * @param durationMs  length of the i-frame window
     * @param abilityId   ability that armed this - surfaces in the "armed consumed"
     *                    notification when the counterattack hit lands and as the
     *                    ability-id filter key for the counterattack reward query.
     * @param attribution opaque attribution payload the consumer reads back for the
     *                    counterattack hit; may be null.
     */
    public void armInvulnerability(@Nullable UUID casterId,
                                   long durationMs,
                                   @Nullable String abilityId,
                                   @Nullable Object attribution) {
        if (casterId == null || durationMs <= 0) return;
        long now = System.currentTimeMillis();
        InvulnerabilityState state = new InvulnerabilityState(
                now + durationMs,
                now,
                abilityId,
                attribution);
        invulnerable.put(casterId, state);
    }

    /**
     * @return {@code true} iff the player has a live invulnerability window.
     *         Expired entries are pruned in-place.
     */
    public boolean isInvulnerable(@Nullable UUID casterId) {
        if (casterId == null) return false;
        InvulnerabilityState state = invulnerable.get(casterId);
        if (state == null) return false;
        if (state.expiresAtMs <= System.currentTimeMillis()) {
            invulnerable.remove(casterId);
            return false;
        }
        return true;
    }

    /**
     * Read the live invulnerability state without consuming it. Returns
     * {@code null} if none exists or it expired (in which case the entry is
     * pruned). The consumer's combat system uses this to read counterattack params
     * on each cancelled hit.
     */
    @Nullable
    public InvulnerabilityState peekInvulnerability(@Nullable UUID casterId) {
        if (casterId == null) return null;
        InvulnerabilityState state = invulnerable.get(casterId);
        if (state == null) return null;
        if (state.expiresAtMs <= System.currentTimeMillis()) {
            invulnerable.remove(casterId);
            return null;
        }
        return state;
    }

    /** Drop the player's invulnerability window early. */
    public void clearInvulnerability(@Nullable UUID casterId) {
        if (casterId == null) return;
        invulnerable.remove(casterId);
    }

    /**
     * Check whether the given player has a live armed state in {@code slot}.
     * Used by a consumer's cast-condition evaluator for a "requires armed slot"
     * predicate. Expired entries are pruned.
     */
    public boolean hasArmedSlot(@Nullable UUID casterId, @Nonnull String slot) {
        if (casterId == null) return false;
        ConcurrentHashMap<String, ArmedState> slots = armed.get(casterId);
        if (slots == null) return false;
        ArmedState state = slots.get(slot);
        if (state == null) return false;
        if (state.expiresAtMs <= System.currentTimeMillis()) {
            slots.remove(slot);
            if (slots.isEmpty()) armed.remove(casterId);
            return false;
        }
        return true;
    }

    /**
     * Immutable view of a player's active invulnerability window. Counterattack
     * params (window tightness, magnitude, slot TTL) are not carried here - the
     * dodge window + magnitude are consumer policy. Only the originating ability
     * id and the opaque attribution are preserved so the counterattack hit can be
     * attributed correctly.
     */
    public static final class InvulnerabilityState {
        public final long expiresAtMs;
        public final long armedAtMs;
        @Nullable public final String abilityId;
        /** Opaque consumer attribution payload; may be null. */
        @Nullable public final Object attribution;

        public InvulnerabilityState(long expiresAtMs,
                                    long armedAtMs,
                                    @Nullable String abilityId,
                                    @Nullable Object attribution) {
            this.expiresAtMs = expiresAtMs;
            this.armedAtMs = armedAtMs;
            this.abilityId = abilityId;
            this.attribution = attribution;
        }
    }

    /** Immutable view of one slot of a player's armed state (final fields, constructed only internally). */
    public static final class ArmedState {
        /** Ability ID that armed this slot. {@code null} for callers without an ability context. */
        @Nullable public final String abilityId;
        /** Slot key. Synthesized composites use {@link #DEFAULT_SLOT}. */
        @Nonnull public final String slot;
        public final double multiplier;
        /**
         * Additive flat-damage bonus applied AFTER the multiplier (and the
         * pre-defense multiplier cap) so flat values stack additively across
         * slots and don't compound with multipliers. Default 0.0 keeps
         * multiplier-only callers working unchanged.
         */
        public final double flatDamage;
        public final long expiresAtMs;
        /**
         * Callback invoked on the consumed melee target. Built from the
         * ability's {@code onHit} params via {@link OnHitRegistry#fromParams}.
         * {@code null} when the ability has no on-hit payload.
         */
        @Nullable public final BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer;
        /**
         * The opaque consumer attribution payloads. For a single armed slot this holds
         * 0 or 1 element; for the {@link #consumeArmed} composite it holds every live
         * slot's attribution in encounter order (nulls omitted) for the consumer to fold.
         * Never null (empty when nothing was attributed).
         */
        @Nonnull public final List<Object> attributions;

        public ArmedState(@Nullable String abilityId,
                          @Nonnull String slot,
                          double multiplier,
                          double flatDamage,
                          long expiresAtMs,
                          @Nullable BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer,
                          @Nonnull List<Object> attributions) {
            this.abilityId = abilityId;
            this.slot = slot;
            this.multiplier = multiplier;
            this.flatDamage = flatDamage;
            this.expiresAtMs = expiresAtMs;
            this.onHitConsumer = onHitConsumer;
            this.attributions = attributions;
        }
    }
}
