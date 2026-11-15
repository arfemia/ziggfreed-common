package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorPick;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.instance.reward.DeferredRewards;

/**
 * What one reward kind reads, described for the in-game Asset Editor: a sentence saying what a reward
 * of the kind pays, each parameter its {@code Params} may name ({@link RewardParam}) in the order the
 * form lists them, and whether a reward may write parameters beyond those (a {@code Command} line fills
 * in any it carries). A Java kind's owner registers one beside its handler
 * ({@link RewardKindRegistry#describe}); a kind file's declared {@code Params} are its own
 * ({@link #of(RewardKindAsset)}). Every form also offers the parameters any reward may write whatever its
 * kind ({@link #rewardWide()}).
 */
public record RewardParams(@Nullable String documentation, @Nonnull List<RewardParam> params,
                           boolean othersAllowed) {

    /**
     * The parameters every reward may write whatever its kind, each read by the reward layer rather than by
     * the kind's handler: {@code NameKey} and {@code Icon}, the reward's own name and picture wherever it is
     * shown ({@code RewardChips}, {@link DeferredRewards}), and {@code QueueIfOffline}, whether it waits for
     * the player when a payout finds them away ({@link RewardGrants#P_QUEUE_IF_OFFLINE}). Not {@code Source},
     * which the payout stamps, nor {@code Amount}, {@code Count} or {@code Quantity}, which a chip counts only
     * where the kind itself reads one.
     */
    private static final List<RewardParam> REWARD_WIDE = List.of(
            RewardParam.text(DeferredRewards.PARAM_NAME_KEY, "A language key naming this reward wherever it is "
                    + "shown, in place of its kind's own name."),
            RewardParam.text(DeferredRewards.PARAM_ICON, "The item drawn for this reward wherever it is shown, "
                    + "in place of its kind's own picture.").withPick(EditorPick.asset(Item.class)),
            RewardParam.bool("QueueIfOffline", "True keeps this reward for the player's next visit when a payout "
                    + "finds them away; false drops it then. Left out, the payout decides."));

    /** {@link #REWARD_WIDE}'s names, folded the way {@link RewardSpec} folds every parameter key. */
    private static final Set<String> REWARD_WIDE_NAMES = REWARD_WIDE.stream()
            .map(param -> param.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    public RewardParams {
        params = List.copyOf(params);
    }

    /**
     * The parameters every reward may write whatever its kind declares, in the order a form lists them after
     * the kind's own: {@code NameKey}, {@code Icon} and {@code QueueIfOffline}.
     */
    @Nonnull
    public static List<RewardParam> rewardWide() {
        return REWARD_WIDE;
    }

    /** Is {@code name} one of {@link #rewardWide()}, matched without regard to case or surrounding space? */
    public static boolean isRewardWide(@Nullable String name) {
        return name != null && REWARD_WIDE_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
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
     * must name marked so, then the parameters any reward may write ({@link #rewardWide()}, each one the kind
     * does not declare itself), the editorial keys ({@code $Comment}) an author may still write inside, and a
     * text box for any other parameter when the kind takes others.
     */
    @Nonnull
    public ObjectSchema toSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setTitle("Params");
        Map<String, Schema> properties = new LinkedHashMap<>(InheritMapCodec.editorialProperties());
        List<String> required = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        for (RewardParam param : params) {
            properties.put(param.name(), param.toSchema());
            declared.add(param.name().toLowerCase(Locale.ROOT));
            if (param.required()) {
                required.add(param.name());
            }
        }
        for (RewardParam wide : REWARD_WIDE) {
            if (!declared.contains(wide.name().toLowerCase(Locale.ROOT))) {
                properties.put(wide.name(), wide.toSchema());
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
