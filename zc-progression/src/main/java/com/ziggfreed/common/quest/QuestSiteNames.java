package com.ziggfreed.common.quest;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a quest's collection site is called when it is not a character: a notice board, a terminal, any
 * place a {@link QuestTurnInSite#ACCEPT_SITE} quest was taken from. A surface telling the player where to
 * collect a finished quest asks the character names first and this table second, so "Collect it at
 * Your Notice Board" names the place instead of "where you handed it in".
 *
 * <p>The module that owns the places registers one {@link Namer}, which answers null for an id it does not
 * own. Backed by the shared registry ledger: case-insensitive ids, last write wins, each namer guarded so
 * one that throws costs its own answer. Nothing is pre-seeded: an empty table names nothing.
 */
public final class QuestSiteNames {

    /** Names the sites one module owns; null for an id it does not know. */
    @FunctionalInterface
    public interface Namer {

        @Nullable
        Message nameOf(@Nonnull String siteId);
    }

    private static final RegistryLedger<Namer> LEDGER = new RegistryLedger<>("site-names");

    private QuestSiteNames() {
    }

    /** Register {@code namer} under {@code id}, usually the owning module's name. Call once from setup. */
    public static void register(@Nullable String id, @Nullable String owner, @Nullable Namer namer) {
        if (id == null || id.isBlank() || namer == null) {
            return;
        }
        LEDGER.put(id, owner, namer);
    }

    /** What {@code siteId} is called, by the first namer that knows it; null when none does. */
    @Nullable
    public static Message nameOf(@Nullable String siteId) {
        if (siteId == null || siteId.isBlank()) {
            return null;
        }
        for (String id : LEDGER.ids()) {
            Namer namer = LEDGER.get(id);
            if (namer == null) {
                continue;
            }
            try {
                Message name = namer.nameOf(siteId.trim());
                if (name != null) {
                    return name;
                }
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[site-names] namer '" + id + "' failed: " + t.getMessage());
            }
        }
        return null;
    }

    /** Every registered namer's owner and failure history, keyed by id (an admin read). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /** Drop every registration. For a test resetting between cases. */
    public static void clear() {
        LEDGER.clear();
    }
}
