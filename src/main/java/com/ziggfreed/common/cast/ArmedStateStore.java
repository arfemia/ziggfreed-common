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
 * <p>{@link #consumeArmed(UUID)} synthesizes a single composite {@link ArmedState} from
 * all live slots: multipliers <em>multiply</em>, onHit callbacks <em>chain</em>,
 * xpSkills/heldExclude lists <em>merge</em> (union, deduplicated). The consumer applies
 * the product to outgoing damage; the consumer's own pre-defense multiplier cap prevents
 * stack abuse.
 *
 * <h2>Invulnerability</h2>
 *
 * <p>Separate from armed slots, callers can grant a timed {@link InvulnerabilityState}
 * via {@link #armInvulnerability}. While the window is live, the consumer's combat system
 * cancels incoming damage. If a cancel lands inside a configured dodge sub-window AND the
 * victim qualifies for a counterattack reward, the consumer may auto-arm a "dodge"
 * multiplier on the player's next outgoing hit - a counterattack reward for skill timing.
 * The window carries only the originating ability id and XP-routing payload so a
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
     * on-hit callback. Replaces any prior value in that slot.
     */
    public void armMultiplier(UUID casterId, double multiplier, long durationMs) {
        armMultiplier(casterId, null, DEFAULT_SLOT, multiplier, durationMs, null,
                Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Arm a damage multiplier on the {@link #DEFAULT_SLOT}. Convenience
     * overload for callers that don't need a slot identity.
     */
    public void armMultiplier(UUID casterId, @Nullable String abilityId,
                              double multiplier, long durationMs,
                              @Nullable BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer,
                              @Nonnull List<String> xpSkills,
                              @Nonnull List<String> heldExclude) {
        armMultiplier(casterId, abilityId, DEFAULT_SLOT, multiplier, durationMs,
                onHitConsumer, xpSkills, heldExclude);
    }

    /**
     * Full-fat arm overload carrying the ability's identity, the slot key, an
     * on-hit callback, and XP-routing payload. When the armed state is
     * consumed by the weapon-swing damage event, the consumer applies the
     * multiplier, invokes {@code onHitConsumer} on the target, and installs
     * {@code xpSkills} so its XP system routes XP to {@code xpSkills} instead of
     * the held weapon / damage cause.
     *
     * <p>Replaces any prior value in {@code slot} for this player.
     */
    public void armMultiplier(UUID casterId, @Nullable String abilityId, @Nonnull String slot,
                              double multiplier, long durationMs,
                              @Nullable BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer,
                              @Nonnull List<String> xpSkills,
                              @Nonnull List<String> heldExclude) {
        armNextHit(casterId, abilityId, slot, multiplier, 0.0, durationMs,
                onHitConsumer, xpSkills, heldExclude);
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
                           @Nonnull List<String> xpSkills,
                           @Nonnull List<String> heldExclude) {
        if (casterId == null) return;
        // An armed slot is meaningful if it carries any of: a damage multiplier > 1,
        // additive flat damage > 0, or an on-hit chain (e.g. a stun payload).
        // Stun-only / status-only abilities arm with multiplier=1.0 and flatDamage=0,
        // relying entirely on the onHitConsumer payload.
        if (multiplier <= 1.0 && flatDamage <= 0.0 && onHitConsumer == null) return;
        ArmedState state = new ArmedState();
        state.abilityId = abilityId;
        state.slot = slot;
        state.multiplier = multiplier <= 1.0 ? 1.0 : multiplier;
        state.flatDamage = Math.max(0.0, flatDamage);
        state.expiresAtMs = System.currentTimeMillis() + Math.max(1L, durationMs);
        state.onHitConsumer = onHitConsumer;
        state.xpSkills = xpSkills;
        state.heldExclude = heldExclude;
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
     * slot's callback, and {@code xpSkills}/{@code heldExclude} are the
     * deduplicated union of every live slot's lists. Cross-slot semantics:
     * multipliers compose multiplicatively (existing behavior), flats compose
     * additively (so two slots with {@code +10} and {@code +5} flat yield
     * {@code +15} flat on the consumed swing). Returns {@code null} when no
     * slot has any of {@code multiplier > 1.0}, {@code flatDamage > 0}, or a
     * non-null {@code onHitConsumer} (a stun-only chain still keeps the
     * composite live so the consumer fires on the next hit).
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
        List<String> mergedXpSkills = null;
        List<String> mergedHeldExclude = null;
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
            mergedXpSkills = mergeUnique(mergedXpSkills, state.xpSkills);
            mergedHeldExclude = mergeUnique(mergedHeldExclude, state.heldExclude);
        }

        if (!anyLive) return null;
        // Keep the composite alive if any slot carries an on-hit chain even when no
        // damage benefit is armed - stun-only / status-only abilities arm with
        // multiplier=1.0 and flatDamage=0 and rely entirely on the chained consumer.
        if (productMultiplier <= 1.0 && summedFlat <= 0.0 && chainedConsumer == null) return null;

        ArmedState composite = new ArmedState();
        composite.abilityId = firstAbilityId;
        composite.slot = DEFAULT_SLOT;
        composite.multiplier = productMultiplier;
        composite.flatDamage = summedFlat;
        composite.expiresAtMs = Long.MAX_VALUE;
        composite.onHitConsumer = chainedConsumer;
        composite.xpSkills = mergedXpSkills != null
                ? Collections.unmodifiableList(mergedXpSkills) : Collections.emptyList();
        composite.heldExclude = mergedHeldExclude != null
                ? Collections.unmodifiableList(mergedHeldExclude) : Collections.emptyList();
        return composite;
    }

    @Nullable
    private static List<String> mergeUnique(@Nullable List<String> acc, @Nonnull List<String> add) {
        if (add.isEmpty()) return acc;
        if (acc == null) return new ArrayList<>(add);
        for (String s : add) {
            if (!acc.contains(s)) acc.add(s);
        }
        return acc;
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
     * here - only the originating ability id and XP-routing payload are preserved.
     *
     * <p>Replaces any prior invulnerability for this player.
     *
     * @param durationMs  length of the i-frame window
     * @param abilityId   ability that armed this - surfaces in the "armed consumed"
     *                    notification when the counterattack hit lands and as the
     *                    ability-id filter key for the counterattack reward query.
     * @param xpSkills    XP-routing skill list for the counterattack hit.
     * @param heldExclude {@code $HELD} exclusion list for XP routing.
     */
    public void armInvulnerability(@Nullable UUID casterId,
                                   long durationMs,
                                   @Nullable String abilityId,
                                   @Nonnull List<String> xpSkills,
                                   @Nonnull List<String> heldExclude) {
        if (casterId == null || durationMs <= 0) return;
        long now = System.currentTimeMillis();
        InvulnerabilityState state = new InvulnerabilityState(
                now + durationMs,
                now,
                abilityId,
                xpSkills,
                heldExclude);
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
     * id and XP-routing payload are preserved so the counterattack hit can be
     * attributed correctly.
     */
    public static final class InvulnerabilityState {
        public final long expiresAtMs;
        public final long armedAtMs;
        @Nullable public final String abilityId;
        @Nonnull public final List<String> xpSkills;
        @Nonnull public final List<String> heldExclude;

        public InvulnerabilityState(long expiresAtMs,
                                    long armedAtMs,
                                    @Nullable String abilityId,
                                    @Nonnull List<String> xpSkills,
                                    @Nonnull List<String> heldExclude) {
            this.expiresAtMs = expiresAtMs;
            this.armedAtMs = armedAtMs;
            this.abilityId = abilityId;
            this.xpSkills = xpSkills;
            this.heldExclude = heldExclude;
        }
    }

    /** Immutable view of one slot of a player's armed state. */
    public static final class ArmedState {
        /** Ability ID that armed this slot. {@code null} for callers without an ability context. */
        @Nullable public String abilityId;
        /** Slot key. Synthesized composites use {@link #DEFAULT_SLOT}. */
        @Nonnull public String slot = DEFAULT_SLOT;
        public double multiplier;
        /**
         * Additive flat-damage bonus applied AFTER the multiplier (and the
         * pre-defense multiplier cap) so flat values stack additively across
         * slots and don't compound with multipliers. Default 0.0 keeps
         * multiplier-only callers working unchanged.
         */
        public double flatDamage = 0.0;
        public long expiresAtMs;
        /**
         * Callback invoked on the consumed melee target. Built from the
         * ability's {@code onHit} params via {@link OnHitRegistry#fromParams}.
         * {@code null} when the ability has no on-hit payload.
         */
        @Nullable public BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHitConsumer;
        /** Ability's XP-routing skill list. */
        @Nonnull public List<String> xpSkills = Collections.emptyList();
        /** Ability's {@code $HELD} exclusion list. */
        @Nonnull public List<String> heldExclude = Collections.emptyList();
    }
}
