package com.ziggfreed.common.dialogue.type;

import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.DialogueTalk;
import com.ziggfreed.common.dialogue.schema.DialogueSugar;
import com.ziggfreed.common.dialogue.schema.DialogueSugarValues;
import com.ziggfreed.common.dialogue.style.DialogueOptionStyle;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The generic action vocabulary every {@link DialogueEngine} seeds before a consumer adds its own:
 * {@code Goto}/{@code Close}/{@code Remember}/{@code Forget}/{@code MarkTalked} plus the
 * {@code OpenPage} carrier. Lives beside {@link DialogueAction} because its sugar lambdas construct
 * those nested types directly, writing the {@code node}/{@code memory}/{@code target} fields that
 * stay package-private on purpose - only the type they belong to (and the seeding here) ever sets
 * them, never an engine reaching across a package boundary.
 *
 * <p>{@link #seedActions} takes {@code memoryKey} as an injected lookup rather than reaching back
 * into {@link DialogueEngine} itself: {@code Remember}/{@code Forget} need the same declared-memory
 * resolution the engine already does for {@code Remembered}/{@code NotRemembered}, and handing over
 * a reference to that resolution (built inside {@code DialogueEngine}, where it has access to its own
 * package-private method) keeps that method from having to become part of this package's contract.
 */
public final class GenericActions {

    private GenericActions() {
    }

    /**
     * Seed {@code Goto}/{@code Close}/{@code Remember}/{@code Forget}/{@code MarkTalked} into
     * {@code action}. {@code memoryKey} resolves a declared memory's storage key for this player, the
     * same way the engine resolves it for the mirror conditions.
     */
    public static void seedActions(@Nonnull Consumer<DialogueActionType<?>> action,
            @Nonnull BiFunction<String, DialogueContext, String> memoryKey) {
        action.accept(DialogueActionType.of("Goto", DialogueAction.Goto.class, DialogueAction.Goto.CODEC,
                        (DialogueAction.Goto a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                out.goTo(a.getNode()))
                .withStyle(DialogueOptionStyle.CONTINUE)
                .withSugar(DialogueSugar.string("Goto", 60, node -> {
                    DialogueAction.Goto go = new DialogueAction.Goto();
                    go.node = node;
                    return go;
                })));

        action.accept(DialogueActionType.of("Close", DialogueAction.Close.class, DialogueAction.Close.CODEC,
                        (DialogueAction.Close a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                out.requestClose())
                .withStyle(DialogueOptionStyle.FAREWELL)
                .withSugar(DialogueSugar.close("Close", 70)));

        // Remember / Forget write a memory the dialogue DECLARED in its Memories map; the
        // scope and lifetime live in that declaration, so the use site is just the name.
        // A null key means the memory does not exist here (per world family, wrong world),
        // which makes the write a deliberate no-op. Orders 32/33 keep the memory writes clear
        // of the quest band (20/30), so a bare {"TurnIn": ..., "Remember": ...} records the
        // memory AFTER the turn-in that justifies it rather than before.
        action.accept(DialogueActionType.of("Remember", DialogueAction.Remember.class,
                        DialogueAction.Remember.CODEC,
                        (DialogueAction.Remember a, DialogueExecContext ctx,
                         DialogueActionExecutor.Mut out) -> {
                            String key = memoryKey.apply(a.getMemory(), ctx);
                            if (key != null) {
                                ctx.flags().set(key);
                            }
                        })
                .withSugar(DialogueSugar.string("Remember", 32, name -> {
                    DialogueAction.Remember remember = new DialogueAction.Remember();
                    remember.memory = name;
                    return remember;
                })));

        action.accept(DialogueActionType.of("Forget", DialogueAction.Forget.class,
                        DialogueAction.Forget.CODEC,
                        (DialogueAction.Forget a, DialogueExecContext ctx,
                         DialogueActionExecutor.Mut out) -> {
                            String key = memoryKey.apply(a.getMemory(), ctx);
                            if (key != null) {
                                ctx.flags().clear(key);
                            }
                        })
                .withSugar(DialogueSugar.string("Forget", 33, name -> {
                    DialogueAction.Forget forget = new DialogueAction.Forget();
                    forget.memory = name;
                    return forget;
                })));

        // MarkTalked is the credit beat, and it has NO sugar on purpose: crediting a conversation
        // is a deliberate statement about the story, so it is written out in full rather than
        // hidden inside a one-word shorthand that reads like a flag. Order 10 keeps it with the
        // other "record what just happened" writes, ahead of the quest band.
        action.accept(DialogueActionType.of("MarkTalked", DialogueAction.MarkTalked.class,
                DialogueAction.MarkTalked.CODEC,
                (DialogueAction.MarkTalked a, DialogueExecContext ctx,
                 DialogueActionExecutor.Mut out) ->
                        DialogueTalk.credit(ctx,
                                DialogueActionExecutor.resolveTarget(a.getTarget(), ctx.contextId()),
                                a.getQualifier())));
    }

    /**
     * The generic {@code OpenPage} action: the option says WHAT it opens in the shared routing
     * vocabulary, and whichever mod registered that {@code Type} opens it. Nothing here parses a
     * target string.
     */
    @Nonnull
    public static DialogueActionType<DialogueAction.OpenPage> openPageType() {
        return DialogueActionType.of("OpenPage",
                        DialogueAction.OpenPage.class, DialogueAction.OpenPage.CODEC,
                        (DialogueAction.OpenPage a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> {
                            if (openDestination(a.getTarget(), ctx)) {
                                out.markOpenedOtherPage();
                            }
                        })
                .withStyle(DialogueOptionStyle.NEUTRAL)
                .withSugar(DialogueSugar.of("Open", 50, Destination.CODEC,
                        (Destination target, DialogueSugarValues values) -> {
                            DialogueAction.OpenPage open = new DialogueAction.OpenPage();
                            open.target = target;
                            return open;
                        }));
    }

    /**
     * Hand a destination to whichever mod registered its {@code Type}, with the character the
     * conversation is about travelling in the context so a per-character screen never has to be
     * told who it is for a second time.
     *
     * <p>The page is opened on the PLAYER: an option click comes back on the player's own ref, and
     * the NPC's entity is not something a conversation still holds by then.
     */
    private static boolean openDestination(@Nullable Destination destination,
            @Nonnull DialogueExecContext ctx) {
        if (destination == null) {
            return false;
        }
        DestinationContext target = new DestinationContext(ctx.store(), ctx.ref(),
                ctx.player(), null, ctx.contextId(), null);
        return Destinations.open(destination, target);
    }
}
