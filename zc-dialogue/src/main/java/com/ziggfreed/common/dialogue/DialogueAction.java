package com.ziggfreed.common.dialogue;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One step a dialogue option executes, authored as a {@code Type}-discriminated
 * JSON object inside an option's {@code Actions} array. The polymorphic dispatch
 * codec is NOT a static field here (unlike a single-consumer design): each
 * {@link DialogueEngine} owns its OWN {@code CodecMapCodec<DialogueAction>},
 * pre-seeded with the generic action types below and then extended by the
 * consumer through {@link DialogueActionType} registrations. That keeps a
 * zero-dependency consumer (e.g. a minigame) free of another consumer's
 * domain actions and removes the decode-time registration race a shared mutable
 * registry would carry.
 *
 * <p>The base type is generic; the nested subtypes here are the mod-agnostic
 * actions every dialogue needs. Each subtype carries its own field
 * {@link BuilderCodec} (the engine registers it into the dispatch codec). Per the
 * Hytale rule every {@code KeyedCodec} field name is PascalCase ({@code Node},
 * {@code Memory}); {@code Type} VALUES are plain data.
 *
 * <p>{@code OpenPage} is a GENERIC carrier: it holds only data (a target string) and
 * routes through a consumer-supplied seam (a {@link DialoguePageRouter}). A consumer
 * may re-register the same {@code Type} id with a richer handler to inject domain
 * behavior.
 */
public abstract class DialogueAction {

    /**
     * Credit this conversation: {@code {"Type":"MarkTalked"}}, or with an explicit
     * {@code {"Type":"MarkTalked","Target":"blacksmith"}}.
     *
     * <p>This is the ONE way a conversation counts as having happened. Nothing credits because a
     * player pressed F, because a page opened, or because a dialogue re-rendered - an author puts the
     * beat on the option that IS the moment (the greeting, the hail, the "I have returned"), and only
     * that option credits. A discrete click is a decision a player made; a render is not.
     *
     * <p>What the engine gives you for free is the TARGET, not the trigger. An absent {@code Target}
     * means the character being talked to, alias set included, so an author never types an id to
     * credit the character they are already writing a conversation for. {@code @self} inside a target
     * substitutes the same id. {@code Qualifier} is an optional secondary label the crediting mod may
     * filter on.
     *
     * <p>Pair it with the option-level {@code Once} to credit only the first hail; the two are
     * independent knobs and neither implies the other.
     */
    public static final class MarkTalked extends DialogueAction {
        public static final BuilderCodec<MarkTalked> CODEC = BuilderCodec.builder(MarkTalked.class, MarkTalked::new)
                .append(new KeyedCodec<>("Target", Codec.STRING, false),
                        (a, v) -> a.target = v, a -> a.target)
                .documentation("Who to credit. Omit it for the character being talked to, which is what you "
                        + "want almost always; @self means the same thing inside a longer id.").add()
                .append(new KeyedCodec<>("Qualifier", Codec.STRING, false),
                        (a, v) -> a.qualifier = v, a -> a.qualifier)
                .documentation("An optional secondary label passed through to whoever counts the "
                        + "conversation.").add()
                .build();

        @Nullable protected String target;
        @Nullable protected String qualifier;

        @Nullable public String getTarget() { return target; }
        @Nullable public String getQualifier() { return qualifier; }
    }

    /**
     * Open another page / nav destination. A generic carrier: the engine routes
     * {@code Target} through the configured {@link DialoguePageRouter}; if it
     * opened a page the executor sets {@code openedOtherPage} so the dialogue page
     * does not re-open itself over the new one. {@code @self} in the target
     * resolves to the context id.
     */
    public static final class OpenPage extends DialogueAction {
        public static final BuilderCodec<OpenPage> CODEC = BuilderCodec.builder(OpenPage.class, OpenPage::new)
                .append(new KeyedCodec<>("Target", Codec.STRING, false),
                        (a, v) -> a.target = v, a -> a.target).add()
                .build();

        @Nullable protected String target;

        @Nullable public String getTarget() { return target; }
    }

    /**
     * The shared shape of the two memory actions: a bare {@code Memory} name, declared in the
     * dialogue's own {@code Memories} map (see {@link DialogueMemory}), which is where the scope
     * and lifetime of that name live. Nothing about the storage is repeated at the use site.
     */
    public abstract static class MemoryAction extends DialogueAction {
        @Nullable protected String memory;

        /** The declared memory name this action writes, or null when unauthored. */
        @Nullable public String getMemory() { return memory; }
    }

    /**
     * Remember something about this player: {@code {"Type":"Remember","Memory":"helped_refugees"}}
     * (option sugar {@code "Remember": "helped_refugees"}). From then on the {@code Remembered}
     * condition passes for that name and {@code NotRemembered} fails.
     */
    public static final class Remember extends MemoryAction {
        public static final BuilderCodec<Remember> CODEC = BuilderCodec.builder(Remember.class, Remember::new)
                .append(new KeyedCodec<>("Memory", Codec.STRING, false),
                        (a, v) -> a.memory = v, a -> a.memory).add()
                .build();
    }

    /**
     * Forget something previously remembered: {@code {"Type":"Forget","Memory":"helped_refugees"}}
     * (option sugar {@code "Forget": "helped_refugees"}). The mirror of {@link Remember}, for a
     * beat the player should be able to reach again.
     */
    public static final class Forget extends MemoryAction {
        public static final BuilderCodec<Forget> CODEC = BuilderCodec.builder(Forget.class, Forget::new)
                .append(new KeyedCodec<>("Memory", Codec.STRING, false),
                        (a, v) -> a.memory = v, a -> a.memory).add()
                .build();
    }

    /** Jump to another node; the page re-renders there. */
    public static final class Goto extends DialogueAction {
        public static final BuilderCodec<Goto> CODEC = BuilderCodec.builder(Goto.class, Goto::new)
                .append(new KeyedCodec<>("Node", Codec.STRING, false),
                        (a, v) -> a.node = v, a -> a.node).add()
                .build();

        @Nullable protected String node;

        @Nullable public String getNode() { return node; }
    }

    /** End the dialogue (close the page). */
    public static final class Close extends DialogueAction {
        public static final BuilderCodec<Close> CODEC = BuilderCodec.builder(Close.class, Close::new)
                .build();
    }
}
