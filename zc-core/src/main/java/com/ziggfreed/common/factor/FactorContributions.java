package com.ziggfreed.common.factor;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The process-wide way a mod contributes a factor id to EVERY vocabulary on the server.
 *
 * <p>A {@link FactorRegistry} is per consumer on purpose: one instance is one mod's vocabulary,
 * populated at its own setup and handed to its own engine. That is right for a mod's own readings
 * and wrong for the cross-mod case, which is the whole reason this facade exists: a mob-difficulty
 * mod knows how rare the mob standing in front of you is, and a loot table in a THIRD mod wants to
 * read that number. Without a shared door the reader would have to depend on the writer, and every
 * pair of mods that wanted to compose would need a bespoke bridge in between.
 *
 * <p><b>Contributing is a claim on an id, not a hand-off to a particular consumer.</b> One
 * {@code register} call per factor id, at the contributing mod's {@code setup()}, tagged with the
 * mod's own name; from then on every {@link FactorRegistry} in the process resolves that id exactly
 * as if it had registered the provider itself. Nothing has to be wired in the other direction, and
 * no consumer has to be built before the contributor runs - a registry consults this table live, so
 * setup order between two mods does not matter.
 *
 * <p><b>Namespace the id after the vocabulary's OWNER</b>
 * ({@code mmomobscaling:mob_rarity_tier}), the same rule the portable {@code hytale:} standard
 * library follows. An author can then tell from the id alone which mod has to be installed for it
 * to mean anything.
 *
 * <p><b>An absent contributor changes nothing about how content behaves.</b> A server without the
 * contributing mod simply has nobody registering the id, so it resolves to nothing: a
 * {@link FactorCondition} on it fails closed (gate shut, whatever its bounds say) and a
 * {@link FactorFormula} term on it contributes zero to the sum. That is the standing vocabulary
 * rule, not a special case for contributions, which is what lets one authored file be correct on a
 * server with the mod and on a server without it.
 *
 * <p>A consumer's OWN registration always wins: {@link FactorRegistry} reads its own ledger first,
 * so a mod that deliberately answers a shared id differently in its own context (a work session
 * holding a tool snapshot rather than reading the live hand) keeps doing so. Registration is
 * idempotent per id by provider identity, ids match case-insensitively, and the first claim of an
 * id logs one line naming the owner, so a server owner can read the contributed vocabulary out of
 * the boot log.
 */
public final class FactorContributions {

    @Nonnull
    private static final RegistryLedger<FactorProvider> LEDGER = new RegistryLedger<>("factor");

    private FactorContributions() {
    }

    /**
     * Contribute {@code provider} as the answer for {@code factorId} across every vocabulary on the
     * server, attributed to {@code owner} (the contributing mod's name). Call once per id from that
     * mod's {@code setup()}. A blank id or a null provider is ignored.
     *
     * <p>Re-registering the SAME provider instance is silent and keeps the id's failure history, so
     * a mod re-running its own setup costs nothing; replacing it with a DIFFERENT instance warns
     * once, naming both owners, because that is two mods claiming one id.
     */
    public static void register(@Nullable String factorId, @Nullable String owner,
            @Nullable FactorProvider provider) {
        if (factorId == null || factorId.isBlank() || provider == null) {
            return;
        }
        boolean claimed = LEDGER.isRegistered(factorId);
        LEDGER.put(factorId, owner, provider);
        if (!claimed) {
            SafeLog.info("[factor] '" + ownerLabel(owner) + "' contributed factor '"
                    + RegistryLedger.normalize(factorId) + "'");
        }
    }

    /** The contributed provider for {@code factorId}, or {@code null} when nobody contributed one. */
    @Nullable
    public static FactorProvider provider(@Nullable String factorId) {
        return LEDGER.get(factorId);
    }

    /** Has any mod contributed {@code factorId}? */
    public static boolean isContributed(@Nullable String factorId) {
        return LEDGER.isRegistered(factorId);
    }

    /** Every contributed factor id, sorted (diagnostics, an authoring dropdown, a validator hint). */
    @Nonnull
    public static List<String> ids() {
        return List.copyOf(LEDGER.ids());
    }

    /** Every contributed id's owner + failure history, keyed by id (an admin registry listing). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /**
     * Who contributed what: owner name to that owner's sorted factor ids. The shape a boot
     * diagnostic or an admin listing prints, so the question "which mod do I need installed for
     * this id to mean anything" is answerable without reading any mod's source.
     */
    @Nonnull
    public static Map<String, List<String>> contributors() {
        Map<String, TreeSet<String>> byOwner = new TreeMap<>();
        LEDGER.info().forEach((id, entry) ->
                byOwner.computeIfAbsent(entry.owner(), o -> new TreeSet<>()).add(id));
        Map<String, List<String>> out = new TreeMap<>();
        byOwner.forEach((owner, ids) -> out.put(owner, List.copyOf(ids)));
        return out;
    }

    /**
     * Record that a contributed provider just failed, so the failure counts against the mod that
     * contributed it rather than against whichever vocabulary happened to be asking.
     */
    static void recordFailure(@Nullable String factorId, @Nullable String message) {
        LEDGER.recordFailure(factorId, message);
    }

    /** Drop every contribution. Tests only; a live server contributes once and never withdraws. */
    static void clearForTests() {
        LEDGER.clear();
    }

    @Nonnull
    private static String ownerLabel(@Nullable String owner) {
        return owner == null || owner.isBlank() ? RegistryLedger.UNATTRIBUTED : owner.trim();
    }
}
