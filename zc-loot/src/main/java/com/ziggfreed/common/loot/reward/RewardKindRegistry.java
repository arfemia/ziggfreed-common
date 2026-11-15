package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
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
 *
 * <p>So is the third, the DESCRIPTION ({@link #describe}): the parameters a reward of the kind may name,
 * which the in-game Asset Editor shows as a reward row's form. Keyed by the same id, so a kind's
 * handler, its token and its form can never belong to two different tables.
 */
public final class RewardKindRegistry {

    @Nonnull
    private final RegistryLedger<RewardHandler> ledger;

    @Nonnull
    private final Map<String, RewardAuthoring> authoring = new ConcurrentHashMap<>();

    @Nonnull
    private final Map<String, RewardParams> described = new ConcurrentHashMap<>();

    /** Each registered id's spelling as its owner wrote it, keyed by the normalized id. */
    @Nonnull
    private final Map<String, String> spellings = new ConcurrentHashMap<>();

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
        rememberSpelling(kindId, handler);
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
        rememberSpelling(kindId, handler);
    }

    /**
     * Keep the spelling an id was registered under, so a list an author picks from writes it the way the
     * kind's owner does ({@code Item}, {@code Mmo_Xp}); a later all-lower-case registration of the same id
     * keeps the cased one.
     */
    private void rememberSpelling(@Nullable String kindId, @Nullable RewardHandler handler) {
        if (kindId == null || kindId.isBlank() || handler == null) {
            return;
        }
        String given = kindId.trim();
        spellings.merge(RegistryLedger.normalize(given), given,
                (kept, now) -> now.equals(now.toLowerCase(Locale.ROOT)) ? kept : now);
    }

    /**
     * {@code kindId} spelled the way its owner writes it: an authored file's own spelling for a kind a file
     * pays out, else the spelling it was registered under, else the id lower-cased.
     */
    @Nonnull
    public String spelling(@Nonnull String kindId) {
        if (handler(kindId) instanceof CommandRewardKind authored) {
            return authored.kind().authoredId();
        }
        String key = RegistryLedger.normalize(kindId);
        return spellings.getOrDefault(key, key);
    }

    /** Every registered kind spelled the way its owner writes it ({@link #spelling}), sorted without regard to case. */
    @Nonnull
    public List<String> spelledIds() {
        List<String> out = new ArrayList<>();
        for (String id : ledger.ids()) {
            out.add(spelling(id));
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
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

    // ==================== the description facet ====================

    /**
     * Describe the parameters {@code kindId}'s rewards may name, for the in-game Asset Editor: a reward
     * row of the kind then shows a form, one box per parameter with its picker, instead of an open map.
     * Register it beside the handler, at setup; registering again replaces, and null forgets it. A blank
     * id is ignored. Description only: nothing here changes what a reward may write or how it pays.
     */
    public void describe(@Nullable String kindId, @Nullable RewardParams params) {
        if (kindId == null || kindId.isBlank()) {
            return;
        }
        String key = kindId.trim().toLowerCase(Locale.ROOT);
        if (params == null) {
            described.remove(key);
        } else {
            described.put(key, params);
        }
    }

    /**
     * What {@code kindId}'s rewards may name, or null when nothing describes the kind. A kind an authored
     * file pays out ({@link CommandRewardKind}) is described by that file's own declared {@code Params};
     * any other by what its owner registered through {@link #describe}.
     */
    @Nullable
    public RewardParams described(@Nullable String kindId) {
        if (kindId == null || kindId.isBlank()) {
            return null;
        }
        if (handler(kindId) instanceof CommandRewardKind authored) {
            return RewardParams.of(authored.kind());
        }
        return described.get(kindId.trim().toLowerCase(Locale.ROOT));
    }

    /** Drop every registration, every facet. */
    public void clear() {
        ledger.clear();
        authoring.clear();
        described.clear();
        spellings.clear();
    }
}
