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
 * {@code Flag}); {@code Type} VALUES are plain data.
 *
 * <p>{@code Talk} and {@code OpenPage} are GENERIC carriers: they hold only data
 * (a target string) and route through consumer-supplied seams (a talk handler /
 * a {@link DialoguePageRouter}). A consumer may re-register the same {@code Type}
 * id with a richer handler to inject domain behavior (e.g. firing a quest
 * objective on {@code Talk}).
 */
public abstract class DialogueAction {

    /** Fire a "talk" signal. A generic carrier: the engine's default handler is a no-op;
     *  a consumer registers a handler that does something with {@code Target}/{@code Qualifier}. */
    public static final class Talk extends DialogueAction {
        public static final BuilderCodec<Talk> CODEC = BuilderCodec.builder(Talk.class, Talk::new)
                .append(new KeyedCodec<>("Target", Codec.STRING, false),
                        (a, v) -> a.target = v, a -> a.target).add()
                .append(new KeyedCodec<>("Qualifier", Codec.STRING, false),
                        (a, v) -> a.qualifier = v, a -> a.qualifier).add()
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

    /** Set a persistent per-player dialogue flag (dialogue-local memory) via the context's flag store. */
    public static final class SetFlag extends DialogueAction {
        public static final BuilderCodec<SetFlag> CODEC = BuilderCodec.builder(SetFlag.class, SetFlag::new)
                .append(new KeyedCodec<>("Flag", Codec.STRING, false),
                        (a, v) -> a.flag = v, a -> a.flag).add()
                .build();

        @Nullable protected String flag;

        @Nullable public String getFlag() { return flag; }
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
