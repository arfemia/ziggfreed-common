package com.ziggfreed.common.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The open table of everyone who has something to offer at an NPC.
 *
 * <p>This closes the one gap the generic quest vocabulary always had. A conversation could ask
 * whether a quest was active, or ready to hand in, but never whether the character had anything to
 * GIVE - so every mod grew its own private answer, and a UI page could render a giver's quests only
 * if it was that mod's page. Register a provider and the generic surfaces answer for you: a
 * conversation's "have you anything for me" line, a fourth party's NPC panel, an at-NPC encounter.
 *
 * <p>Backed by the shared registry ledger: case-insensitive ids, idempotent per id with last-write
 * wins, per-provider owner and failure history through {@link #info()}. Each provider is individually
 * guarded, so one mod's broken catalogue costs its own offers and nobody else's.
 *
 * <p><b>Nothing is pre-seeded</b>, deliberately: an empty table offers nothing, which is the correct
 * answer on a server with no quest content, and it means a surface reading this never has to
 * distinguish "no providers" from "nothing to give".
 */
public final class NpcOfferProviders {

    private static final RegistryLedger<NpcOfferProvider> LEDGER = new RegistryLedger<>("offers");

    private NpcOfferProviders() {
    }

    /** Register {@code provider} under {@code id}, usually your mod's name. Call once from setup. */
    public static void register(@Nullable String id, @Nullable String owner,
            @Nullable NpcOfferProvider provider) {
        if (id == null || id.isBlank() || provider == null) {
            return;
        }
        LEDGER.put(id, owner, provider);
    }

    /** Is anything registered at all? The pre-check before resolving a character's answer set. */
    public static boolean hasAny() {
        return !LEDGER.ids().isEmpty();
    }

    /** Every registered provider's owner + failure history, keyed by id (an admin read). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /**
     * Everything every provider offers at this character, in registration order. Available and locked
     * alike; a caller wanting only the takeable ones filters on {@link NpcOffer#available()}.
     */
    @Nonnull
    public static List<NpcOffer> offersAt(@Nonnull Subject subject,
            @Nonnull Collection<String> answersTo) {
        if (answersTo.isEmpty()) {
            return List.of();
        }
        List<NpcOffer> out = new ArrayList<>();
        for (String id : LEDGER.ids()) {
            NpcOfferProvider provider = LEDGER.get(id);
            if (provider == null) {
                continue;
            }
            try {
                out.addAll(provider.offersAt(subject, answersTo));
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[offers] provider '" + id + "' failed: " + t.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Is anything AVAILABLE at this character? Stops at the first provider that says yes, so the
     * common "should this NPC show a quest marker" read costs one answer rather than every list.
     */
    public static boolean hasOffersAt(@Nonnull Subject subject, @Nonnull Collection<String> answersTo) {
        if (answersTo.isEmpty()) {
            return false;
        }
        for (String id : LEDGER.ids()) {
            NpcOfferProvider provider = LEDGER.get(id);
            if (provider == null) {
                continue;
            }
            try {
                if (provider.hasOffersAt(subject, answersTo)) {
                    return true;
                }
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[offers] provider '" + id + "' failed: " + t.getMessage());
            }
        }
        return false;
    }

    /**
     * Drop every registration, leaving a table that offers nothing. For a full content reload, and
     * for a test resetting between cases; the shared reward-kind registry exposes the same.
     */
    public static void clear() {
        LEDGER.clear();
    }
}
