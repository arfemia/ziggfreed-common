package com.ziggfreed.common.dialogue.page;

import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueHeaderAnnotation;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.dialogue.NpcNameProvider;
import com.ziggfreed.common.dialogue.i18n.DialogueI18n;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The immutable bundle of behavior a consumer wires into {@link DialoguePage}: the
 * built {@link DialogueEngine}, a resolver from dialogue id to {@link NpcDialogue}
 * (so the page re-reads fresh state on every render/click), the per-player
 * {@link DialogueContextFactory}, the {@link DialogueI18n} namespace, and the
 * optional name-header / annotation providers. Build it once at startup and reuse
 * it for every dialogue page (an MMO: a static MMO instance; a minigame: a static
 * all-defaults instance).
 */
public final class DialoguePageDeps {

    private final DialogueEngine engine;
    private final Function<String, NpcDialogue> dialogueResolver;
    private final DialogueContextFactory contextFactory;
    private final DialogueI18n i18n;
    private final NpcNameProvider npcName;
    private final DialogueHeaderAnnotation headerAnnotation;
    private final Function<String, ToastSpec> questCompletedToast;

    public DialoguePageDeps(@Nonnull DialogueEngine engine,
                            @Nonnull Function<String, NpcDialogue> dialogueResolver,
                            @Nonnull DialogueContextFactory contextFactory,
                            @Nonnull DialogueI18n i18n,
                            @Nullable NpcNameProvider npcName,
                            @Nullable DialogueHeaderAnnotation headerAnnotation) {
        this(engine, dialogueResolver, contextFactory, i18n, npcName, headerAnnotation, null);
    }

    /**
     * @param questCompletedToast optional quest-id -> completion {@link ToastSpec} (returns null for
     *        "no toast"); when a dialogue action reports a just-completed quest, the page shows this
     *        toast in-menu. Pass null to disable (no completion toasts, the default).
     */
    public DialoguePageDeps(@Nonnull DialogueEngine engine,
                            @Nonnull Function<String, NpcDialogue> dialogueResolver,
                            @Nonnull DialogueContextFactory contextFactory,
                            @Nonnull DialogueI18n i18n,
                            @Nullable NpcNameProvider npcName,
                            @Nullable DialogueHeaderAnnotation headerAnnotation,
                            @Nullable Function<String, ToastSpec> questCompletedToast) {
        this.engine = engine;
        this.dialogueResolver = dialogueResolver;
        this.contextFactory = contextFactory;
        this.i18n = i18n;
        this.npcName = npcName != null ? npcName : NpcNameProvider.NONE;
        this.headerAnnotation = headerAnnotation != null ? headerAnnotation : DialogueHeaderAnnotation.NONE;
        this.questCompletedToast = questCompletedToast != null ? questCompletedToast : id -> null;
    }

    @Nonnull public DialogueEngine engine() { return engine; }

    @Nonnull public Function<String, NpcDialogue> dialogueResolver() { return dialogueResolver; }

    @Nonnull public DialogueContextFactory contextFactory() { return contextFactory; }

    @Nonnull public DialogueI18n i18n() { return i18n; }

    @Nonnull public NpcNameProvider npcName() { return npcName; }

    @Nonnull public DialogueHeaderAnnotation headerAnnotation() { return headerAnnotation; }

    /** The completion toast for {@code questId}, or null when none is configured / wanted. */
    @Nullable public ToastSpec questCompletedToast(@Nonnull String questId) {
        return questCompletedToast.apply(questId);
    }
}
