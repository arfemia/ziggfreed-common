package com.ziggfreed.common.progress.asset;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.JsonTreeCodec;

/**
 * One axis of a generator's walk: a token name plus where its values come from - a list written
 * here, or a source some mod enumerates.
 *
 * <p>Author {@code Values} for a set that belongs to this content ("the three ores this line is
 * about") and {@code Source} for one that belongs to a mod and will grow ("every ore installed").
 * Authoring both uses {@code Values}, since an explicit list is the more specific statement.
 *
 * <p>The group is declared ONCE here because every store that writes a family from one file walks
 * axes the same way. A second spelling of "the values to vary over" would mean an author learning
 * which content type wanted which key, and two substitution contracts drifting apart one edit at a
 * time.
 */
public final class GeneratorAxisAsset {

    @Nullable protected String token;
    @Nullable protected JsonElement values;
    @Nullable protected String source;
    @Nullable protected Map<String, String> filter;

    public static final BuilderCodec<GeneratorAxisAsset> CODEC =
            BuilderCodec.builder(GeneratorAxisAsset.class, GeneratorAxisAsset::new)
                    .appendInherited(new KeyedCodec<>("Token", Codec.STRING, false),
                            (o, v) -> o.token = v, o -> o.token, (o, p) -> o.token = p.token)
                    .documentation("The placeholder name this axis fills in, written {token} wherever it is used. "
                            + "A row that names its own tokens does not need it.").add()
                    .appendInherited(new KeyedCodec<>("Values", JsonTreeCodec.array(), false),
                            (o, v) -> o.values = v, o -> o.values, (o, p) -> o.values = p.values)
                    .documentation("The values, written out. A plain entry fills this axis's Token; an object entry "
                            + "binds several tokens at once, which is how values that belong together stay together.").add()
                    .appendInherited(new KeyedCodec<>("Source", Codec.STRING, false),
                            (o, v) -> o.source = v, o -> o.source, (o, p) -> o.source = p.source)
                    .documentation("The id of a list some mod enumerates, used when Values is not authored. A source "
                            + "nothing registered produces no rows, so the whole generator falls silent.").add()
                    .appendInherited(new KeyedCodec<>("Filter", new InheritMapCodec<>(Codec.STRING), false),
                            (o, v) -> o.filter = v, o -> o.filter, (o, p) -> o.filter = p.filter)
                    .documentation("Arguments handed to the Source, meaning whatever that source documents. Nothing "
                            + "here reads them.").add()
                    .build();

    public GeneratorAxisAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static GeneratorAxisAsset of(@Nullable String token, @Nullable JsonElement values,
            @Nullable String source, @Nullable Map<String, String> filter) {
        GeneratorAxisAsset a = new GeneratorAxisAsset();
        a.token = token;
        a.values = values;
        a.source = source;
        a.filter = filter == null ? null : new LinkedHashMap<>(filter);
        return a;
    }

    @Nullable
    public String getToken() {
        return token == null || token.isBlank() ? null : token.trim();
    }

    /** The authored value list, or null when this axis reads a source instead. */
    @Nullable
    public JsonElement getValues() {
        return values;
    }

    @Nullable
    public String getSource() {
        return source == null || source.isBlank() ? null : source.trim();
    }

    @Nonnull
    public Map<String, String> filterOrEmpty() {
        return filter == null ? Map.of() : filter;
    }

    /** How this axis is named in a finding, so an author can find the line that is wrong. */
    @Nonnull
    public String describe() {
        String named = getToken();
        if (named != null) {
            return named;
        }
        String from = getSource();
        return from != null ? from : "(unnamed)";
    }
}
