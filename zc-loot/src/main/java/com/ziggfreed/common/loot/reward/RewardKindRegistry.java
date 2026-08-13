package com.ziggfreed.common.loot.reward;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The open reward VOCABULARY: which reward kinds exist and what each one does. A consumer registers
 * its own kinds at setup and hands ONE registry to every engine that pays out - a quest runtime, an
 * instance's end-of-round spoils, an achievement unlock. Content then authors those kinds with no
 * code, and a reward kind written for one payout site works at all of them.
 *
 * <p><b>Nothing is pre-seeded.</b> There is no such thing as a generic reward - every payout reaches
 * into some system this layer deliberately knows nothing about. A registry with nothing registered
 * simply grants nothing, and each unhandled kind is reported once so an owner can see which mod was
 * expected to provide it.
 *
 * <p>Registration bookkeeping (who owns a kind, how often its handler has thrown) lives in the shared
 * {@link RegistryLedger}; ids are matched case-insensitively and registration is idempotent per id
 * with last-write-wins.
 *
 * <h2>One table, two facets</h2>
 *
 * <p>A kind has a runtime half - the {@link RewardHandler} that pays it out - and, for the terse
 * authoring formats that need it, an authoring half: the {@link RewardAuthoring} that turns a single
 * written token into a spec. Both live HERE, on the same registry, keyed by the same id. They used to
 * be two separate tables, and the cost of that was exactly what a split registry always costs: a
 * kind could be authorable and unpayable, or payable and unwritable, with nothing in either table
 * able to notice. Registering the two facets against one id makes the mismatch visible.
 *
 * <p>The authoring facet is optional and independent. A kind only ever written as a structured
 * object needs none; a token that expands into another kind entirely registers only that.
 */
public final class RewardKindRegistry {

    @Nonnull
    private final RegistryLedger<RewardHandler> ledger;

    @Nonnull
    private final Map<String, RewardAuthoring> authoring = new ConcurrentHashMap<>();

    /** A registry logging under a generic prefix. */
    public RewardKindRegistry() {
        this(null);
    }

    /** A registry whose ledger log lines are prefixed {@code [label]}. */
    public RewardKindRegistry(@Nullable String label) {
        this.ledger = new RegistryLedger<>(label == null || label.isBlank() ? "reward-kind" : label);
    }

    /** Register (or replace) the handler for {@code kindId}, unattributed. A blank id is ignored. */
    public void register(@Nullable String kindId, @Nullable RewardHandler handler) {
        register(kindId, null, handler);
    }

    /** As {@link #register(String, RewardHandler)}, attributing the claim to {@code owner}. */
    public void register(@Nullable String kindId, @Nullable String owner, @Nullable RewardHandler handler) {
        ledger.put(kindId, owner, handler);
    }

    /**
     * As {@link #register(String, String, RewardHandler)}, but without the ledger's own
     * "two owners wanted this id" warning, for a caller that reports the replacement itself.
     *
     * <p>{@link RewardKindFold} is the one caller: an authored kind file taking over a Java kind is a
     * deliberate, documented swap, and the fold's warning names the file AND says which engine
     * services the owner just gave up. Letting the ledger warn as well would print two lines for one
     * event, the less useful one first.
     */
    public void registerQuietly(@Nullable String kindId, @Nullable String owner,
            @Nullable RewardHandler handler) {
        ledger.putQuietly(kindId, owner, handler);
    }

    /** The handler for {@code kindId} (case-insensitive), or null when nothing is registered. */
    @Nullable
    public RewardHandler handler(@Nullable String kindId) {
        return ledger.get(kindId);
    }

    /** Is {@code kindId} handled? A validator uses this to reject a reward nothing will pay out. */
    public boolean isRegistered(@Nullable String kindId) {
        return ledger.isRegistered(kindId);
    }

    /** Every registered kind, sorted (diagnostics, an authoring hint, a validator message). */
    @Nonnull
    public List<String> ids() {
        return List.copyOf(ledger.ids());
    }

    /** Every registered kind's owner plus failure history, keyed by id. */
    @Nonnull
    public Map<String, RegistryLedger.RegistrationInfo> info() {
        return ledger.info();
    }

    /** Count a handler failure against its owner, so a persistently broken kind is visible. */
    public void recordFailure(@Nullable String kindId, @Nullable String message) {
        ledger.recordFailure(kindId, message);
    }

    // ==================== the authoring facet ====================

    /**
     * Teach the compact authoring formats to write {@code token}. A blank token or a null adapter is
     * ignored; registering again replaces.
     *
     * <p>Register at plugin setup. Asset JSON is decoded later, so a token registered in setup is
     * always in place before any line that uses it is read. A line whose token nobody taught simply
     * does not parse and is skipped - which is what keeps content written for an absent mod from
     * paying out a phantom reward.
     */
    public void registerAuthoring(@Nullable String token, @Nullable RewardAuthoring adapter) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = token.trim().toLowerCase(Locale.ROOT);
        if (adapter == null) {
            authoring.remove(key);
        } else {
            authoring.put(key, adapter);
        }
    }

    /** The authoring adapter for {@code token} (case-insensitive), or null when none is registered. */
    @Nullable
    public RewardAuthoring authoring(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return authoring.get(token.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Expand a compact {@code token} plus its {@code arg} into the spec it means, or null when no
     * adapter is registered for the token (or the adapter refused the argument).
     */
    @Nullable
    public RewardSpec expand(@Nullable String token, @Nonnull String arg) {
        RewardAuthoring adapter = authoring(token);
        if (adapter == null) {
            return null;
        }
        try {
            return adapter.expand(arg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Every token the compact formats can write, sorted (diagnostics, an authoring hint). */
    @Nonnull
    public List<String> authoringTokens() {
        return authoring.keySet().stream().sorted().toList();
    }

    /** Drop every registration, both facets. */
    public void clear() {
        ledger.clear();
        authoring.clear();
    }
}
