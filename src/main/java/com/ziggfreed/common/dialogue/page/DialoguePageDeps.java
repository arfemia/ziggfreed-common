package com.ziggfreed.common.dialogue.page;

import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueHeaderAnnotation;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.dialogue.NpcNameProvider;
import com.ziggfreed.common.dialogue.i18n.DialogueI18n;

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

    public DialoguePageDeps(@Nonnull DialogueEngine engine,
                            @Nonnull Function<String, NpcDialogue> dialogueResolver,
                            @Nonnull DialogueContextFactory contextFactory,
                            @Nonnull DialogueI18n i18n,
                            @Nullable NpcNameProvider npcName,
                            @Nullable DialogueHeaderAnnotation headerAnnotation) {
        this.engine = engine;
        this.dialogueResolver = dialogueResolver;
        this.contextFactory = contextFactory;
        this.i18n = i18n;
        this.npcName = npcName != null ? npcName : NpcNameProvider.NONE;
        this.headerAnnotation = headerAnnotation != null ? headerAnnotation : DialogueHeaderAnnotation.NONE;
    }

    @Nonnull public DialogueEngine engine() { return engine; }

    @Nonnull public Function<String, NpcDialogue> dialogueResolver() { return dialogueResolver; }

    @Nonnull public DialogueContextFactory contextFactory() { return contextFactory; }

    @Nonnull public DialogueI18n i18n() { return i18n; }

    @Nonnull public NpcNameProvider npcName() { return npcName; }

    @Nonnull public DialogueHeaderAnnotation headerAnnotation() { return headerAnnotation; }
}
