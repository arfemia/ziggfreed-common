package com.ziggfreed.common.dialogue;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One styled value that fills a {@code {N}} placeholder in a {@link DialogueNode}'s
 * text, so a node renders PER-LOCALE rich text the native Hytale way: a
 * {@code Message.translation(nodeKey).param("N", styledSub)} tree set on the Label's
 * {@code TextSpans} (the mechanism the engine's own {@code PortalDeviceActivePage}
 * uses). The {@code N}-th authored param fills {@code {N}}.
 *
 * <p>The text is a {@link #key} (a translation key, the consumer's namespace is
 * prepended) so it stays client-resolved per-locale; {@link #text} is a raw literal
 * for data that is the same in every language. {@link #color} (a {@code #rrggbb} hex)
 * / {@link #bold} / {@link #italic} style just this span. A flat value object: keep
 * each colored word a top-level node param rather than nesting, so the codec stays
 * non-recursive.
 */
public class DialogueTextParam {

    @Nullable String key;
    @Nullable String text;
    @Nullable String color;
    @Nullable Boolean bold;
    @Nullable Boolean italic;

    public static final BuilderCodec<DialogueTextParam> CODEC =
            BuilderCodec.builder(DialogueTextParam.class, DialogueTextParam::new)
                    .append(new KeyedCodec<>("Key", Codec.STRING, false),
                            (p, v) -> p.key = v, p -> p.key).add()
                    .append(new KeyedCodec<>("Text", Codec.STRING, false),
                            (p, v) -> p.text = v, p -> p.text).add()
                    .append(new KeyedCodec<>("Color", Codec.STRING, false),
                            (p, v) -> p.color = v, p -> p.color).add()
                    .append(new KeyedCodec<>("Bold", Codec.NULLABLE_BOOLEAN, false),
                            (p, v) -> p.bold = v, p -> p.bold).add()
                    .append(new KeyedCodec<>("Italic", Codec.NULLABLE_BOOLEAN, false),
                            (p, v) -> p.italic = v, p -> p.italic).add()
                    .build();

    public DialogueTextParam() {
    }

    /** The translation key for this span (consumer namespace prepended), or null for {@link #getText}. */
    @Nullable public String getKey() {
        return key;
    }

    /** A raw literal for this span (locale-independent data), or null when {@link #getKey} is used. */
    @Nullable public String getText() {
        return text;
    }

    /** A {@code #rrggbb} hex colour for this span, or null for the Label's default. */
    @Nullable public String getColor() {
        return color;
    }

    @Nullable public Boolean getBold() {
        return bold;
    }

    @Nullable public Boolean getItalic() {
        return italic;
    }
}
