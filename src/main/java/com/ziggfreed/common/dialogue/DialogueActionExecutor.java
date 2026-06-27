package com.ziggfreed.common.dialogue;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs a dialogue option's ordered {@link DialogueAction} list by dispatching
 * each action to its registered {@link DialogueActionHandler} (a handler map, not
 * an {@code instanceof} ladder - that is what lets a consumer add an action type
 * without editing the engine). Stateless; the page consumes the {@link Outcome}
 * to decide what to render next (jump to a node, close, or stand pat). When a
 * handler opened another page the dialogue page must NOT re-open itself.
 *
 * <p>Built by {@link DialogueEngine}; not constructed directly.
 */
public final class DialogueActionExecutor {

    /**
     * What the page should do after the action list ran. {@code completedQuestId} is the
     * id of a quest a handler reports as having JUST transitioned to completed (turn-in /
     * force-complete), so a toast-capable page can surface an in-menu completion toast; it
     * is purely advisory feedback and never affects control flow.
     */
    public record Outcome(@Nullable String gotoNode, boolean close, boolean openedOtherPage,
                          @Nullable String completedQuestId) {
        public static final Outcome STAY = new Outcome(null, false, false, null);
    }

    /** Mutable accumulator handlers contribute to across an option's action list. */
    public static final class Mut {
        @Nullable private String gotoNode;
        private boolean close;
        private boolean openedOtherPage;
        @Nullable private String completedQuestId;

        /** Request a jump to {@code node} after the list runs. */
        public void goTo(@Nullable String node) { this.gotoNode = node; }

        /** Request the dialogue close. */
        public void requestClose() { this.close = true; }

        /** Signal that a handler already opened another page (do not re-open the dialogue). */
        public void markOpenedOtherPage() { this.openedOtherPage = true; }

        /**
         * Report that {@code questId} just transitioned to completed (the page may show a
         * completion toast). Advisory only; the last reporter in an action list wins.
         */
        public void completedQuest(@Nullable String questId) { this.completedQuestId = questId; }

        @Nonnull Outcome build() { return new Outcome(gotoNode, close, openedOtherPage, completedQuestId); }
    }

    private final Map<Class<? extends DialogueAction>, DialogueActionHandler<?>> handlers;
    private final Consumer<String> warn;

    DialogueActionExecutor(@Nonnull Map<Class<? extends DialogueAction>, DialogueActionHandler<?>> handlers,
                           @Nonnull Consumer<String> warn) {
        this.handlers = handlers;
        this.warn = warn;
    }

    @Nonnull
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Outcome execute(@Nonnull List<DialogueAction> actions, @Nonnull DialogueExecContext ctx) {
        Mut out = new Mut();
        for (DialogueAction action : actions) {
            DialogueActionHandler handler = handlers.get(action.getClass());
            if (handler == null) {
                warn.accept("No handler registered for dialogue action " + action.getClass().getName());
                continue;
            }
            try {
                handler.handle(action, ctx, out);
            } catch (Exception e) {
                warn.accept("Dialogue action " + action.getClass().getSimpleName()
                        + " failed (dialogue=" + ctx.dialogue().getId() + ", node=" + ctx.nodeId()
                        + "): " + e.getMessage());
            }
        }
        return out.build();
    }

    /**
     * Resolve an action target: a null/blank target defaults to the context id,
     * and {@code @self} substitutes the context id so a dialogue shared by several
     * NPCs stays NPC-aware. Pure string transform.
     */
    @Nullable
    public static String resolveTarget(@Nullable String target, @Nullable String contextId) {
        if (target == null || target.isBlank()) {
            return contextId;
        }
        if (contextId != null && target.contains("@self")) {
            return target.replace("@self", contextId);
        }
        return target;
    }

    /**
     * The implicit once-only guard flag for an option, keyed on the DIALOGUE id
     * (not the context id), so a shared gift cannot be farmed across the NPCs a
     * dialogue is attached to. Pure string key builder.
     */
    @Nonnull
    public static String rewardOnceKey(@Nonnull String dialogueId, @Nonnull String nodeId, int optionIndex) {
        return "reward:" + dialogueId + ":" + nodeId + ":" + optionIndex;
    }
}
