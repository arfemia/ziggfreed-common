package com.ziggfreed.common.dialogue.page;

import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueHeaderAnnotation;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.dialogue.NpcNameProvider;
import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.npc.NpcNames;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The immutable bundle of behavior a consumer wires into {@link DialoguePage}: the
 * built {@link DialogueEngine}, a resolver from dialogue id to {@link NpcDialogue}
 * (so the page re-reads fresh state on every render/click), the per-player
 * {@link DialogueContextFactory}, the {@link ContentI18n} namespace, and the
 * optional name-header / annotation providers. Build it once at startup and reuse
 * it for every dialogue page (an MMO: a static MMO instance; a minigame: a static
 * all-defaults instance).
 */
public final class DialoguePageDeps {

    /**
     * The library default header-name provider: the character's name is authored in exactly one
     * place, the NPC role's {@code NameTranslationKey}, read through {@link NpcNames}. The
     * three-arg overload reaches {@link NpcNames}'s live-entity form when the page has a ref and a
     * store in hand (the byte-for-byte answer the nameplate already renders), and both overloads
     * fall back to the id-only static walk when there is no entity to read.
     */
    private static final NpcNameProvider DEFAULT_NPC_NAME = new NpcNameProvider() {
        @Override
        @Nullable
        public Message nameFor(@Nullable String contextId) {
            return NpcNames.nameFor(contextId);
        }

        @Override
        @Nullable
        public Message nameFor(@Nullable String contextId, @Nullable Ref<EntityStore> npcRef,
                @Nullable Store<EntityStore> store) {
            return NpcNames.nameFor(contextId, npcRef, store);
        }
    };

    private final DialogueEngine engine;
    private final Function<String, NpcDialogue> dialogueResolver;
    private final DialogueContextFactory contextFactory;
    private final ContentI18n i18n;
    private final NpcNameProvider npcName;
    private final DialogueHeaderAnnotation headerAnnotation;
    private final Function<String, ToastSpec> completionToast;

    public DialoguePageDeps(@Nonnull DialogueEngine engine,
                            @Nonnull Function<String, NpcDialogue> dialogueResolver,
                            @Nonnull DialogueContextFactory contextFactory,
                            @Nonnull ContentI18n i18n,
                            @Nullable NpcNameProvider npcName,
                            @Nullable DialogueHeaderAnnotation headerAnnotation) {
        this(engine, dialogueResolver, contextFactory, i18n, npcName, headerAnnotation, null);
    }

    /**
     * @param completionToast optional completed-id -> completion {@link ToastSpec} (returns null for
     *        "no toast"); when a dialogue action reports a just-completed thing, the page shows this
     *        toast in-menu. Pass null to disable (no completion toasts, the default).
     */
    public DialoguePageDeps(@Nonnull DialogueEngine engine,
                            @Nonnull Function<String, NpcDialogue> dialogueResolver,
                            @Nonnull DialogueContextFactory contextFactory,
                            @Nonnull ContentI18n i18n,
                            @Nullable NpcNameProvider npcName,
                            @Nullable DialogueHeaderAnnotation headerAnnotation,
                            @Nullable Function<String, ToastSpec> completionToast) {
        this.engine = engine;
        this.dialogueResolver = dialogueResolver;
        this.contextFactory = contextFactory;
        this.i18n = i18n;
        // The default header name is the ONE authored source every other surface reads: the
        // role's NameTranslationKey through NpcNames. A consumer passes its own provider only
        // to OVERRIDE that (a themed header, a per-mod naming scheme), never to make names work.
        this.npcName = npcName != null ? npcName : DEFAULT_NPC_NAME;
        this.headerAnnotation = headerAnnotation != null ? headerAnnotation : DialogueHeaderAnnotation.NONE;
        this.completionToast = completionToast != null ? completionToast : id -> null;
    }

    @Nonnull public DialogueEngine engine() { return engine; }

    @Nonnull public Function<String, NpcDialogue> dialogueResolver() { return dialogueResolver; }

    @Nonnull public DialogueContextFactory contextFactory() { return contextFactory; }

    @Nonnull public ContentI18n i18n() { return i18n; }

    @Nonnull public NpcNameProvider npcName() { return npcName; }

    @Nonnull public DialogueHeaderAnnotation headerAnnotation() { return headerAnnotation; }

    /** The completion toast for {@code completedId}, or null when none is configured / wanted. */
    @Nullable public ToastSpec completionToast(@Nonnull String completedId) {
        return completionToast.apply(completedId);
    }
}
