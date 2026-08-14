package com.ziggfreed.common.commerce;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The persisted per-player state behind the library's DEFAULT commerce store: one component holding
 * everything the economy knows about a player.
 *
 * <p><b>Packed string leaves, not map codecs.</b> Each map travels as one string through
 * {@link CommerceBlob}, which is the shape a codec-persisted ECS component reliably supports. The
 * component is saved into every world, so the wire format is a contract and a plain string is the
 * least surprising one to keep.
 *
 * <p><b>The state machine lives here; {@link ComponentCommerceStore} is thin.</b> That is what keeps
 * the behaviour - a day rollover, a period rollover, the atomic reroll cap - unit-testable with no
 * server anywhere near it.
 *
 * <p><b>Registration.</b> A library component has no plugin of its own, so
 * {@code ZiggfreedCommonPlugin} registers it once at {@code setup()} via
 * {@link #register(ComponentRegistryProxy)}: a component type registered after a world has loaded
 * cannot be read off entities saved carrying it, so it cannot wait to find out whether a consumer
 * brings its own store. Registering an unused type costs nothing, since no entity carries one unless
 * the player hook attaches it, and every read and write site guards on {@code TYPE != null}.
 *
 * <p><b>A new leaf is APPENDED, never inserted, and every leaf reads as absent-means-nothing.</b> A
 * blob saved before a leaf existed simply has no value for it and decodes to an empty map, which
 * reads as "this player has none of that" everywhere. That is what lets the schema grow without a
 * migration.
 */
public final class CommerceComponent implements Component<EntityStore> {

    /** The registration id (namespaced, stable - it is persisted in every saved world). */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Commerce";

    /** The registered type, or {@code null} until {@link #register} runs. */
    @Nullable
    public static ComponentType<EntityStore, CommerceComponent> TYPE;

    /** Joins a pool id to a position in the keys of the three per-position leaves. */
    private static final char POSITION_SEPARATOR = '#';

    /** Joins the three numbers of one offer's purchase record. */
    private static final char FIELD_SEPARATOR = ',';

    @Nonnull
    public static final BuilderCodec<CommerceComponent> CODEC = BuilderCodec
            .builder(CommerceComponent.class, CommerceComponent::new)
            .append(new KeyedCodec<>("Balances", Codec.STRING),
                    (c, v) -> c.balances = CommerceBlob.deserializeLongs(v),
                    c -> CommerceBlob.serializeLongs(CommerceBlob.ordered(c.balances))).add()
            .append(new KeyedCodec<>("LifetimeSpent", Codec.STRING),
                    (c, v) -> c.lifetimeSpent = CommerceBlob.deserializeLongs(v),
                    c -> CommerceBlob.serializeLongs(CommerceBlob.ordered(c.lifetimeSpent))).add()
            .append(new KeyedCodec<>("Purchases", Codec.STRING),
                    (c, v) -> c.purchases = CommerceBlob.deserializeStrings(v),
                    c -> CommerceBlob.serializeStrings(CommerceBlob.ordered(c.purchases))).add()
            .append(new KeyedCodec<>("RerollPeriods", Codec.STRING),
                    (c, v) -> c.rerollPeriods = CommerceBlob.deserializeLongs(v),
                    c -> CommerceBlob.serializeLongs(CommerceBlob.ordered(c.rerollPeriods))).add()
            .append(new KeyedCodec<>("RerollSpent", Codec.STRING),
                    (c, v) -> c.rerollSpent = CommerceBlob.deserializeLongs(v),
                    c -> CommerceBlob.serializeLongs(CommerceBlob.ordered(c.rerollSpent))).add()
            .append(new KeyedCodec<>("RerollOverrides", Codec.STRING),
                    (c, v) -> c.rerollOverrides = CommerceBlob.deserializeStrings(v),
                    c -> CommerceBlob.serializeStrings(CommerceBlob.ordered(c.rerollOverrides))).add()
            .append(new KeyedCodec<>("RerollCounts", Codec.STRING),
                    (c, v) -> c.rerollCounts = CommerceBlob.deserializeLongs(v),
                    c -> CommerceBlob.serializeLongs(CommerceBlob.ordered(c.rerollCounts))).add()
            .append(new KeyedCodec<>("RerollSeen", Codec.STRING),
                    (c, v) -> c.rerollSeen = CommerceBlob.deserializeStrings(v),
                    c -> CommerceBlob.serializeStrings(CommerceBlob.ordered(c.rerollSeen))).add()
            .append(new KeyedCodec<>("Migrations", Codec.STRING),
                    (c, v) -> c.migrations = CommerceBlob.deserializeSet(v),
                    c -> CommerceBlob.serializeSet(c.migrations)).add()
            .build();

    /** currencyId -> this player's counter balance. An item-backed wallet never appears here. */
    @Nonnull
    private Map<String, Long> balances = new ConcurrentHashMap<>();

    /** currencyId -> how much of it this player has genuinely spent, ever. */
    @Nonnull
    private Map<String, Long> lifetimeSpent = new ConcurrentHashMap<>();

    /** offerId -> {@code "<epochDay>,<today>,<total>"}. */
    @Nonnull
    private Map<String, String> purchases = new ConcurrentHashMap<>();

    /** poolId -> the ONE period its reroll state belongs to. */
    @Nonnull
    private Map<String, Long> rerollPeriods = new ConcurrentHashMap<>();

    /** poolId -> how many rerolls have been spent in that period. */
    @Nonnull
    private Map<String, Long> rerollSpent = new ConcurrentHashMap<>();

    /** {@code "<poolId>#<position>"} -> the id shown there instead of the base draw's. */
    @Nonnull
    private Map<String, String> rerollOverrides = new ConcurrentHashMap<>();

    /** {@code "<poolId>#<position>"} -> how often that position has been re-rolled. */
    @Nonnull
    private Map<String, Long> rerollCounts = new ConcurrentHashMap<>();

    /** {@code "<poolId>#<position>"} -> the packed set of ids it has already shown. */
    @Nonnull
    private Map<String, String> rerollSeen = new ConcurrentHashMap<>();

    /** Every one-time migration already claimed for this player. */
    @Nonnull
    private Set<String> migrations = new HashSet<>();

    public CommerceComponent() {
    }

    /**
     * Register this component type on {@code registry}. Call ONCE at plugin {@code setup()}.
     * Never throws: a failure logs and leaves {@link #TYPE} unset.
     *
     * @return the registered type, or {@code null} on failure
     */
    @Nullable
    public static ComponentType<EntityStore, CommerceComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(CommerceComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[commerce] CommerceComponent register failed", t);
            return null;
        }
    }

    /** The registered type, or {@code null} when not yet registered. */
    @Nullable
    public static ComponentType<EntityStore, CommerceComponent> getComponentType() {
        return TYPE;
    }

    // ==================== the wallet ====================

    /** This player's counter balance for {@code currencyId}, or 0. */
    public long balance(@Nonnull String currencyId) {
        Long held = balances.get(currencyId);
        return held == null ? 0L : held.longValue();
    }

    /** Write a counter balance. Zero or less is stored as absence, so an emptied wallet leaves nothing. */
    public void setBalance(@Nonnull String currencyId, long value) {
        if (value <= 0L) {
            balances.remove(currencyId);
            return;
        }
        balances.put(currencyId, Long.valueOf(value));
    }

    /** Every counter balance held, for a wallet listing. */
    @Nonnull
    public Map<String, Long> balances() {
        return Map.copyOf(balances);
    }

    /** How much of {@code currencyId} this player has spent in their lifetime, or 0. */
    public long lifetimeSpent(@Nonnull String currencyId) {
        Long spent = lifetimeSpent.get(currencyId);
        return spent == null ? 0L : spent.longValue();
    }

    /** Add to the lifetime tally. A non-positive amount changes nothing. */
    public void addLifetimeSpent(@Nonnull String currencyId, long amount) {
        if (amount <= 0L) {
            return;
        }
        setLifetimeSpent(currencyId, lifetimeSpent(currencyId) + amount);
    }

    /** Take back part of the tally, never below zero. */
    public void refundLifetimeSpent(@Nonnull String currencyId, long amount) {
        if (amount <= 0L) {
            return;
        }
        setLifetimeSpent(currencyId, lifetimeSpent(currencyId) - amount);
    }

    /** Write the lifetime tally outright. Zero or less is stored as absence. */
    public void setLifetimeSpent(@Nonnull String currencyId, long amount) {
        if (amount <= 0L) {
            lifetimeSpent.remove(currencyId);
            return;
        }
        lifetimeSpent.put(currencyId, Long.valueOf(amount));
    }

    // ==================== purchase limits ====================

    /**
     * How often {@code offerId} was bought on the day numbered {@code epochDay}. A record carrying a
     * different day answers 0, which is what makes a daily limit reset with no sweep.
     */
    public int purchasesToday(@Nonnull String offerId, long epochDay) {
        long[] record = purchaseRecord(offerId);
        return (record == null || record[0] != epochDay) ? 0 : (int) record[1];
    }

    /** How often {@code offerId} has been bought, ever. */
    public int purchasesTotal(@Nonnull String offerId) {
        long[] record = purchaseRecord(offerId);
        return record == null ? 0 : (int) record[2];
    }

    /** Record one purchase of {@code offerId} on the day numbered {@code epochDay}. */
    public synchronized void recordPurchase(@Nonnull String offerId, long epochDay) {
        long[] record = purchaseRecord(offerId);
        long today = (record == null || record[0] != epochDay) ? 0L : record[1];
        long total = record == null ? 0L : record[2];
        writePurchases(offerId, epochDay, today + 1L, total + 1L);
    }

    /** Write {@code offerId}'s counts outright. All-zero counts are stored as absence. */
    public synchronized void setPurchases(@Nonnull String offerId, long epochDay, int today, int total) {
        writePurchases(offerId, epochDay, Math.max(0, today), Math.max(0, total));
    }

    /** Forget every purchase of everything. */
    public void clearPurchases() {
        purchases.clear();
    }

    /** Every offer id with a purchase record, for an admin listing. */
    @Nonnull
    public Set<String> purchasedOfferIds() {
        return Set.copyOf(purchases.keySet());
    }

    private void writePurchases(@Nonnull String offerId, long epochDay, long today, long total) {
        if (today <= 0L && total <= 0L) {
            purchases.remove(offerId);
            return;
        }
        purchases.put(offerId, epochDay + "" + FIELD_SEPARATOR + today + FIELD_SEPARATOR + total);
    }

    /** {@code {epochDay, today, total}}, or null when there is no record or it is malformed. */
    @Nullable
    private long[] purchaseRecord(@Nonnull String offerId) {
        String packed = purchases.get(offerId);
        if (packed == null) {
            return null;
        }
        String[] fields = packed.split(String.valueOf(FIELD_SEPARATOR), -1);
        if (fields.length != 3) {
            return null;
        }
        try {
            return new long[] {Long.parseLong(fields[0].trim()), Long.parseLong(fields[1].trim()),
                    Long.parseLong(fields[2].trim())};
        } catch (NumberFormatException malformed) {
            // A malformed triple costs that offer's counts alone, never the login.
            return null;
        }
    }

    // ==================== rotating-pool rerolls ====================

    /** This player's position overrides for {@code (poolId, period)}, or empty. */
    @Nonnull
    public synchronized Map<Integer, String> rerollOverrides(@Nonnull String poolId, long period) {
        if (!isCurrentPeriod(poolId, period)) {
            return Map.of();
        }
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<String, String> entry : rerollOverrides.entrySet()) {
            Integer position = positionOf(entry.getKey(), poolId);
            if (position != null) {
                out.put(position, entry.getValue());
            }
        }
        return out;
    }

    /** How many rerolls have been spent in {@code (poolId, period)}. */
    public synchronized int rerollsSpent(@Nonnull String poolId, long period) {
        if (!isCurrentPeriod(poolId, period)) {
            return 0;
        }
        Long spent = rerollSpent.get(poolId);
        return spent == null ? 0 : (int) spent.longValue();
    }

    /** Every id {@code position} has already shown this period. */
    @Nonnull
    public synchronized Set<String> rerollSeenAt(@Nonnull String poolId, long period, int position) {
        if (!isCurrentPeriod(poolId, period)) {
            return Set.of();
        }
        return CommerceBlob.deserializeSet(rerollSeen.get(positionKey(poolId, position)));
    }

    /** The count {@code position} will have AFTER its next reroll, which seeds the replacement draw. */
    public synchronized int rerollNextCount(@Nonnull String poolId, long period, int position) {
        if (!isCurrentPeriod(poolId, period)) {
            return 1;
        }
        Long count = rerollCounts.get(positionKey(poolId, position));
        return (count == null ? 0 : (int) count.longValue()) + 1;
    }

    /**
     * Commit one successful single-position reroll, cap-checked ATOMICALLY: false without mutating
     * anything when the period's cap is already reached.
     */
    public synchronized boolean commitReroll(@Nonnull String poolId, long period, int maxPerPeriod,
            int position, @Nullable String replacedId, @Nonnull String newId) {
        int spent = rerollsSpent(poolId, period);
        if (maxPerPeriod > 0 && spent >= maxPerPeriod) {
            return false;
        }
        armPeriod(poolId, period);
        String key = positionKey(poolId, position);
        rerollOverrides.put(key, newId);
        rerollCounts.put(key, Long.valueOf(rerollNextCount(poolId, period, position)));
        Set<String> seen = new HashSet<>(CommerceBlob.deserializeSet(rerollSeen.get(key)));
        if (replacedId != null && !replacedId.isBlank()) {
            seen.add(replacedId);
        }
        seen.add(newId);
        rerollSeen.put(key, CommerceBlob.serializeSet(seen));
        rerollSpent.put(poolId, Long.valueOf(spent + 1L));
        return true;
    }

    /** This pool's whole reroll state for {@code period}, or an empty one. */
    @Nonnull
    public synchronized RerollState rerollState(@Nonnull String poolId, long period) {
        if (!isCurrentPeriod(poolId, period)) {
            return RerollState.none(period);
        }
        Map<Integer, String> overrides = rerollOverrides(poolId, period);
        Map<Integer, Integer> counts = new HashMap<>();
        Map<Integer, Set<String>> seen = new HashMap<>();
        for (Integer position : overrides.keySet()) {
            String key = positionKey(poolId, position.intValue());
            Long count = rerollCounts.get(key);
            counts.put(position, Integer.valueOf(count == null ? 0 : (int) count.longValue()));
            seen.put(position, CommerceBlob.deserializeSet(rerollSeen.get(key)));
        }
        return new RerollState(period, rerollsSpent(poolId, period), overrides, counts, seen);
    }

    /** Write this pool's reroll state outright, replacing whatever was held for it. */
    public synchronized void setRerolls(@Nonnull String poolId, @Nonnull RerollState state) {
        forgetPool(poolId);
        if (state.isEmpty()) {
            return;
        }
        rerollPeriods.put(poolId, Long.valueOf(state.period()));
        rerollSpent.put(poolId, Long.valueOf(state.spent()));
        for (Map.Entry<Integer, String> entry : state.overrides().entrySet()) {
            rerollOverrides.put(positionKey(poolId, entry.getKey().intValue()), entry.getValue());
        }
        for (Map.Entry<Integer, Integer> entry : state.counts().entrySet()) {
            rerollCounts.put(positionKey(poolId, entry.getKey().intValue()),
                    Long.valueOf(entry.getValue().longValue()));
        }
        for (Map.Entry<Integer, Set<String>> entry : state.seen().entrySet()) {
            rerollSeen.put(positionKey(poolId, entry.getKey().intValue()),
                    CommerceBlob.serializeSet(entry.getValue()));
        }
    }

    /** Forget every reroll in every pool. */
    public synchronized void clearRerolls() {
        rerollPeriods.clear();
        rerollSpent.clear();
        rerollOverrides.clear();
        rerollCounts.clear();
        rerollSeen.clear();
    }

    /** Every pool id with reroll state, for an admin listing. */
    @Nonnull
    public Set<String> rerolledPoolIds() {
        return Set.copyOf(rerollPeriods.keySet());
    }

    /** The period this pool's state belongs to, or 0 when it holds none. */
    public long rerollPeriod(@Nonnull String poolId) {
        Long period = rerollPeriods.get(poolId);
        return period == null ? 0L : period.longValue();
    }

    /**
     * Make {@code period} this pool's live period, dropping a previous one's state wholesale. A
     * rollover therefore needs no sweep: the old record is discarded the first time the new period
     * is written to, and until then it simply stops matching.
     */
    private void armPeriod(@Nonnull String poolId, long period) {
        Long held = rerollPeriods.get(poolId);
        if (held != null && held.longValue() == period) {
            return;
        }
        forgetPool(poolId);
        rerollPeriods.put(poolId, Long.valueOf(period));
    }

    private boolean isCurrentPeriod(@Nonnull String poolId, long period) {
        Long held = rerollPeriods.get(poolId);
        return held != null && held.longValue() == period;
    }

    private void forgetPool(@Nonnull String poolId) {
        rerollPeriods.remove(poolId);
        rerollSpent.remove(poolId);
        String prefix = poolId + POSITION_SEPARATOR;
        rerollOverrides.keySet().removeIf(key -> key.startsWith(prefix));
        rerollCounts.keySet().removeIf(key -> key.startsWith(prefix));
        rerollSeen.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Nonnull
    private static String positionKey(@Nonnull String poolId, int position) {
        return poolId + POSITION_SEPARATOR + position;
    }

    /**
     * The position {@code key} names within {@code poolId}, or null when it belongs to another pool
     * or carries no readable position. Split at the LAST separator, because the position is a number
     * and a pool id is not ours to constrain.
     */
    @Nullable
    private static Integer positionOf(@Nonnull String key, @Nonnull String poolId) {
        int split = key.lastIndexOf(POSITION_SEPARATOR);
        if (split != poolId.length() || !key.startsWith(poolId)) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(key.substring(split + 1)));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    // ==================== one-time migrations ====================

    /**
     * Claim {@code migrationId} for this player: true the first time, false ever after, with the
     * claim recorded before the caller acts.
     */
    public synchronized boolean claimMigration(@Nonnull String migrationId) {
        return migrations.add(migrationId);
    }

    /** True when {@code migrationId} has already been claimed for this player. */
    public boolean hasMigrated(@Nonnull String migrationId) {
        return migrations.contains(migrationId);
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public CommerceComponent clone() {
        CommerceComponent c = new CommerceComponent();
        c.balances = CommerceBlob.copy(this.balances);
        c.lifetimeSpent = CommerceBlob.copy(this.lifetimeSpent);
        c.purchases = CommerceBlob.copy(this.purchases);
        c.rerollPeriods = CommerceBlob.copy(this.rerollPeriods);
        c.rerollSpent = CommerceBlob.copy(this.rerollSpent);
        c.rerollOverrides = CommerceBlob.copy(this.rerollOverrides);
        c.rerollCounts = CommerceBlob.copy(this.rerollCounts);
        c.rerollSeen = CommerceBlob.copy(this.rerollSeen);
        c.migrations = new HashSet<>(this.migrations);
        return c;
    }
}
