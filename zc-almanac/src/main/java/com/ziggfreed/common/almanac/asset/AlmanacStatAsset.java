package com.ziggfreed.common.almanac.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;

/**
 * One line of a season page's {@code Stats} map: a tally the Almanac keeps for every player, counted
 * off the shared moment stream. It is written in the same words as a quest step (the shared objective
 * leaves: what counts, which one, how it is compared, a qualifier, where, and the line's
 * {@code TextKey}), plus how it reads on the page.
 *
 * <pre>{@code
 * "Stats": {
 *   "Bombs_Thrown": { "Kind": "USE_ITEM", "Target": "Lantern_Bomb_", "MatchMode": "PREFIX",
 *                     "Qualifier": "Throw", "TextKey": "almanac.bombs_thrown", "Icon": "Lantern_Bomb",
 *                     "Order": 10, "LiveOnly": false } }
 * }</pre>
 *
 * <p>The map KEY is the tally's name in every player's record, so renaming one starts it over; keep it
 * free of {@code / @ | :} and never start it with {@code $}. A line ADDS each moment's own amount (a
 * craft of five adds five), so write it on a kind that counts; {@code Amount} means nothing here.
 */
public final class AlmanacStatAsset extends ObjectiveLeafAsset {

    @Nullable protected String icon;
    @Nullable protected Integer order;
    @Nullable protected Boolean liveOnly;

    public static final BuilderCodec<AlmanacStatAsset> CODEC =
            appendLeaves(BuilderCodec.builder(AlmanacStatAsset.class, AlmanacStatAsset::new))
                    .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                            (o, v) -> o.icon = v, o -> o.icon, (o, p) -> o.icon = p.icon)
                    .metadata(EditorSchema.assetRef(Item.class))
                    .documentation("The item whose picture stands beside this line on the page.").add()
                    .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                            (o, v) -> o.order = v, o -> o.order, (o, p) -> o.order = p.order)
                    .documentation("Where this line reads among the page's others, lowest first. Unauthored "
                            + "sorts after every line that names one; leave gaps (10, 20, 30).").add()
                    .appendInherited(new KeyedCodec<>("LiveOnly", Codec.BOOLEAN, false),
                            (o, v) -> o.liveOnly = v, o -> o.liveOnly, (o, p) -> o.liveOnly = p.liveOnly)
                    .metadata(EditorSchema.defaultValue(true))
                    .documentation("Count only while the season is on; unauthored means true. False keeps the "
                            + "every-season tally running all year, for something a player can also do between "
                            + "seasons. The this-season tally only ever counts while the season is on.").add()
                    .build();

    public AlmanacStatAsset() {
    }

    /** The item pictured beside the line, or null when none is authored. */
    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** Sort key among the page's lines, or {@link Integer#MAX_VALUE} when unauthored. */
    public int orderOrLast() {
        return order == null ? Integer.MAX_VALUE : order;
    }

    /** Does this line count only while its season is on? True when unauthored. */
    public boolean isLiveOnly() {
        return liveOnly == null || liveOnly;
    }
}
