package com.ziggfreed.common.almanac.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.route.Destination;

/**
 * The season's achievements placed in its page's {@code Sections}: their count, their bar and the feats,
 * as the page draws them by default, and a button into the achievement book opened on this season.
 *
 * <pre>{@code
 * { "Achievements": { "ShowButton": true, "Button": { "TextKey": "almanac.spring_fair.book" } } }
 * }</pre>
 *
 * <p>Unlike a banner's or a collection's, this {@code Button} may write either half alone: words alone
 * relabel the button into the book, and a {@code Destination} alone sends it elsewhere with the library's
 * own words. So a half-written one is never reported.
 */
public final class AlmanacAchievementsAsset {

    @Nullable private Boolean showButton;
    @Nullable private AlmanacLinkAsset button;

    public static final BuilderCodec<AlmanacAchievementsAsset> CODEC =
            BuilderCodec.builder(AlmanacAchievementsAsset.class, AlmanacAchievementsAsset::new)
                    .append(new KeyedCodec<>("ShowButton", Codec.BOOLEAN, false),
                            (a, v) -> a.showButton = v, a -> a.showButton)
                    .metadata(EditorSchema.defaultValue(true))
                    .documentation("Whether the section offers a button into the achievement book, opened on "
                            + "this season; unauthored means true.").add()
                    .append(new KeyedCodec<>("Button", AlmanacLinkAsset.CODEC, false),
                            (a, v) -> a.button = v, a -> a.button)
                    .documentation("The button's words (TextKey) and where it goes (Destination). Either may be "
                            + "written alone: words alone relabel the button into the book, a Destination alone "
                            + "sends it elsewhere with the library's words.").add()
                    .build();

    public AlmanacAchievementsAsset() {
    }

    /** Whether the section offers its button; true when unauthored. */
    public boolean showButton() {
        return showButton == null || showButton;
    }

    /** The button's words as authored, or null for the library's own. */
    @Nullable
    public String buttonTextKey() {
        return button == null ? null : button.textKey();
    }

    /** Where the button goes as authored, or null for the book opened on this season. */
    @Nullable
    public Destination buttonDestination() {
        return button == null ? null : button.destination();
    }
}
