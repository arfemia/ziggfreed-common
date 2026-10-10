package com.ziggfreed.common.playerstats.asset;

import java.util.Locale;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;

/**
 * One entry of a stat's {@code From} list: which produced moments count toward it, written in the same
 * words as a quest step or an Almanac line (the shared objective leaves: {@code Kind}, {@code Target},
 * {@code MatchMode}, {@code Qualifier}, {@code QualifierMatchMode}, {@code Zone}), plus {@code Count},
 * how much one matching moment adds.
 *
 * <pre>{@code
 * "From": [ { "Kind": "KILL_ENTITY" },
 *           { "Kind": "CRAFT_ITEM", "Target": "Weapon_", "MatchMode": "PREFIX", "Count": "One" } ]
 * }</pre>
 *
 * <p>The leaf's {@code Amount} ("how many are needed") means nothing here; {@code Count} says what a
 * moment is worth instead.
 */
public final class StatSourceAsset extends ObjectiveLeafAsset {

    /** {@code Count}: add the moment's own amount (a craft of five adds five). */
    public static final String COUNT_MOMENT = "Moment";

    /** {@code Count}: add one per moment, whatever its amount. */
    public static final String COUNT_ONE = "One";

    @Nullable protected String count;

    public static final BuilderCodec<StatSourceAsset> CODEC =
            appendLeaves(BuilderCodec.builder(StatSourceAsset.class, StatSourceAsset::new))
                    .appendInherited(new KeyedCodec<>("Count", Codec.STRING, false),
                            (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                    .metadata(EditorSchema.oneOfDocumented(
                            COUNT_MOMENT, "Add the moment's own amount: a craft of five adds five",
                            COUNT_ONE, "Add one per moment, whatever its amount"))
                    .metadata(EditorSchema.defaultValue(COUNT_MOMENT))
                    .documentation("What one matching moment adds: Moment (its own amount) or One. Unauthored "
                            + "means Moment. For a Max or Min stat the moment's amount is the value it is "
                            + "compared against.").add()
                    .build();

    public StatSourceAsset() {
    }

    /** The authored {@code Count}, unparsed. */
    @Nullable
    public String getCount() {
        return count;
    }

    /** Does a matching moment add one, whatever its amount? False (its own amount) unless {@code One} is authored. */
    public boolean countsOne() {
        return count != null && COUNT_ONE.toLowerCase(Locale.ROOT).equals(count.trim().toLowerCase(Locale.ROOT));
    }

    /** Is the authored {@code Count} a word this entry knows (unauthored counts as known)? */
    public boolean countKnown() {
        if (count == null || count.isBlank()) {
            return true;
        }
        String folded = count.trim().toLowerCase(Locale.ROOT);
        return folded.equals(COUNT_MOMENT.toLowerCase(Locale.ROOT)) || folded.equals(COUNT_ONE.toLowerCase(Locale.ROOT));
    }
}
