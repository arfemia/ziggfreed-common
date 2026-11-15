package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.ziggfreed.common.codec.InheritMapCodec;

/**
 * What one reward kind reads, described for the in-game Asset Editor: a sentence saying what a reward
 * of the kind pays, each parameter its {@code Params} may name ({@link RewardParam}) in the order the
 * form lists them, and whether a reward may write parameters beyond those (a {@code Command} line fills
 * in any it carries). A Java kind's owner registers one beside its handler
 * ({@link RewardKindRegistry#describe}); a kind file's declared {@code Params} are its own
 * ({@link #of(RewardKindAsset)}).
 */
public record RewardParams(@Nullable String documentation, @Nonnull List<RewardParam> params,
                           boolean othersAllowed) {

    public RewardParams {
        params = List.copyOf(params);
    }

    /** A kind's sentence and its parameters, in form order, and no others. */
    @Nonnull
    public static RewardParams of(@Nullable String documentation, @Nonnull RewardParam... params) {
        return new RewardParams(documentation, List.of(params), false);
    }

    /**
     * A kind file's own description: its {@code Name} as the sentence, and each declared parameter with
     * its {@code Type}, {@code Description}, {@code Pick}, {@code Required} and {@code Default}, in the
     * order the file declares them. The declared parameters are the whole surface its command can
     * substitute, so no others.
     */
    @Nonnull
    public static RewardParams of(@Nonnull RewardKindAsset kind) {
        List<RewardParam> out = new ArrayList<>();
        for (Map.Entry<String, RewardKindAsset.Param> entry : kind.paramsOrEmpty().entrySet()) {
            String name = entry.getKey();
            RewardKindAsset.Param declared = entry.getValue();
            if (name == null || name.isBlank() || name.startsWith("$") || declared == null) {
                continue;
            }
            out.add(new RewardParam(name, RewardParam.Type.fromAuthored(declared.getType()),
                    declared.getDescription(), declared.isRequired() && !declared.hasDefault(),
                    declared.getDefault(), declared.getPick()));
        }
        return new RewardParams(kind.getName(), out, false);
    }

    /** These parameters, a reward also free to write any other (each filled into the kind's line). */
    @Nonnull
    public RewardParams allowingOthers() {
        return new RewardParams(documentation, params, true);
    }

    /**
     * The form a reward's {@code Params} shows for this kind: one control per parameter, the ones a reward
     * must name marked so, the editorial keys ({@code $Comment}) an author may still write inside, and a
     * text box for any other parameter when the kind takes others.
     */
    @Nonnull
    public ObjectSchema toSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setTitle("Params");
        Map<String, Schema> properties = new LinkedHashMap<>(InheritMapCodec.editorialProperties());
        List<String> required = new ArrayList<>();
        for (RewardParam param : params) {
            properties.put(param.name(), param.toSchema());
            if (param.required()) {
                required.add(param.name());
            }
        }
        schema.setProperties(properties);
        if (othersAllowed) {
            StringSchema other = new StringSchema();
            other.setMarkdownDescription("Any other parameter, filled into the line where it names {Name}.");
            schema.setAdditionalProperties(other);
        } else {
            schema.setAdditionalProperties(false);
        }
        if (!required.isEmpty()) {
            schema.setRequired(required.toArray(String[]::new));
        }
        return schema;
    }
}
