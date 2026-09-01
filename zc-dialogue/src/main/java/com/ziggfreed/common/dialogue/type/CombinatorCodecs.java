package com.ziggfreed.common.dialogue.type;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.lookup.CodecMapCodec;

/**
 * The boolean combinators' DECODE arm: {@code AllOf}/{@code AnyOf}/{@code Not}, each writing the
 * protected {@link DialogueCondition.Combinator#children} field its own subtype declares. Lives
 * beside {@link DialogueCondition} for exactly that reason - the field stays package-private, and
 * only code in this package ever writes it.
 *
 * <p>Their child-list codec is the very {@code conditionsArray} being assembled, which is why
 * registration happens at schema-assembly time rather than as a static field on each subtype (see
 * {@link DialogueCondition.Combinator}'s own javadoc). Their EVALUATION stays with each
 * {@code DialogueEngine}, which walks the children back through its own condition pass - only the
 * ability to READ a combinator is assembled here.
 */
public final class CombinatorCodecs {

    private CombinatorCodecs() {
    }

    /**
     * Register {@code AllOf} ({@code All}), {@code AnyOf} ({@code Any}) and {@code Not} ({@code Of})
     * into {@code conditionCodec}, each keyed on {@code conditionsArray} - the ONE decode vocabulary
     * a {@link com.ziggfreed.common.dialogue.schema.DialogueTypeTable} assembles calls this once its
     * array exists, rather than reaching into this package's field itself.
     */
    public static void registerCodecs(@Nonnull CodecMapCodec<DialogueCondition> conditionCodec,
            @Nonnull Codec<DialogueCondition[]> conditionsArray) {
        conditionCodec.register("AllOf", DialogueCondition.AllOf.class,
                BuilderCodec.builder(DialogueCondition.AllOf.class, DialogueCondition.AllOf::new)
                        .append(new KeyedCodec<>("All", conditionsArray, false),
                                (c, v) -> c.children = v, c -> c.children).add()
                        .build());
        conditionCodec.register("AnyOf", DialogueCondition.AnyOf.class,
                BuilderCodec.builder(DialogueCondition.AnyOf.class, DialogueCondition.AnyOf::new)
                        .append(new KeyedCodec<>("Any", conditionsArray, false),
                                (c, v) -> c.children = v, c -> c.children).add()
                        .build());
        conditionCodec.register("Not", DialogueCondition.Not.class,
                BuilderCodec.builder(DialogueCondition.Not.class, DialogueCondition.Not::new)
                        .append(new KeyedCodec<>("Of", conditionsArray, false),
                                (c, v) -> c.children = v, c -> c.children).add()
                        .build());
    }
}
