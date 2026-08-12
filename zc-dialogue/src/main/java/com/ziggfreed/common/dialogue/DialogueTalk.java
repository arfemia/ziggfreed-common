package com.ziggfreed.common.dialogue;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where a {@code MarkTalked} beat goes once the conversation has decided WHO it is about.
 *
 * <p>It exists so the conversation engine never has to know what crediting a character means. The
 * engine's whole job is to resolve the target - a blank one is the character in front of the player,
 * {@code @self} substitutes the same - and hand it over; who counts it, how a re-trigger is absorbed,
 * and which ids an alias set spreads it across all live with NPC identity, one layer up.
 *
 * <p>Ziggfreed Common installs the real sink at plugin setup, so a consumer wires nothing. With none
 * installed - a bare unit test, a mod embedding the engine on its own - a {@code MarkTalked} beat is
 * a no-op rather than an error, because a conversation that cannot credit should still be readable.
 */
public final class DialogueTalk {

    private static final AtomicReference<Sink> INSTALLED = new AtomicReference<>();

    private DialogueTalk() {
    }

    /** What actually credits a conversation. */
    @FunctionalInterface
    public interface Sink {

        /** Credit {@code npcId} for this player; answer false when nothing was credited. */
        boolean credit(@Nonnull DialogueExecContext ctx, @Nonnull String npcId, @Nullable String qualifier);
    }

    /** Install the sink every conversation credits through. Called once, from plugin setup. */
    public static void install(@Nullable Sink sink) {
        INSTALLED.set(sink);
    }

    /** Is anything installed to credit through? */
    public static boolean installed() {
        return INSTALLED.get() != null;
    }

    /**
     * Credit {@code npcId}, or answer false when there is nobody to credit, nothing installed, or the
     * sink refused. Guarded whole: a credit failing must not take the rest of the option's actions
     * down with it.
     */
    public static boolean credit(@Nonnull DialogueExecContext ctx, @Nullable String npcId,
            @Nullable String qualifier) {
        Sink sink = INSTALLED.get();
        if (sink == null || npcId == null || npcId.isBlank()) {
            return false;
        }
        try {
            return sink.credit(ctx, npcId.trim(), qualifier);
        } catch (Throwable t) {
            return false;
        }
    }
}
