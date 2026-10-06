package com.ziggfreed.common.settings;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * What a server owner says about one surface a player may change for themselves (a HUD panel, the quest
 * tracker): whether it shows for a player who has not chosen, whether that is fixed, and whether where it
 * sits is fixed. One group, shared by a HUD panel file's {@code Player} leaf and the player-settings
 * record's {@code QuestTracker}, so an owner learns it once.
 *
 * <p>Every leaf is optional and folds leaf by leaf: a child under {@code Parent}, or an owner entry over
 * the shipped file, keeps whatever it does not restate. Unauthored, the surface shows and nothing is
 * fixed. A lock never deletes a player's own choice: reads answer the owner's value while it holds, and
 * the stored choice is back the moment it lifts.
 */
public final class SurfaceRules {

    /** A surface nothing was said about: shown, nothing fixed. */
    public static final SurfaceRules NONE = new SurfaceRules();

    @Nullable protected ShowRule show;
    @Nullable protected SpotRule spot;

    public static final BuilderCodec<SurfaceRules> CODEC = BuilderCodec
            .builder(SurfaceRules.class, SurfaceRules::new)
            .appendInherited(new KeyedCodec<>("Show", ShowRule.CODEC, false),
                    (o, v) -> o.show = v, o -> o.show, (o, p) -> o.show = p.show)
            .documentation("Whether it shows for a player who has not chosen, and whether players may change "
                    + "that on their Settings tab.")
            .add()
            .appendInherited(new KeyedCodec<>("Spot", SpotRule.CODEC, false),
                    (o, v) -> o.spot = v, o -> o.spot, (o, p) -> o.spot = p.spot)
            .documentation("Whether players may pick where it sits on their Settings tab.")
            .add()
            .build();

    public SurfaceRules() {
    }

    /** A rule set with these leaves, for code and tests; a null leaf stays unauthored. */
    public SurfaceRules(@Nullable Boolean showDefault, @Nullable Boolean showLocked, @Nullable Boolean spotLocked) {
        this.show = showDefault == null && showLocked == null ? null : new ShowRule(showDefault, showLocked);
        this.spot = spotLocked == null ? null : new SpotRule(spotLocked);
    }

    /** Whether the surface shows for a player who has not chosen; true unless the file says otherwise. */
    public boolean showDefault() {
        return show == null || !Boolean.FALSE.equals(show.defaultShown);
    }

    /** Whether the owner fixed it at {@link #showDefault()} for everyone. */
    public boolean showLocked() {
        return show != null && Boolean.TRUE.equals(show.locked);
    }

    /** Whether the owner fixed where it sits, so every player gets the server's spot. */
    public boolean spotLocked() {
        return spot != null && Boolean.TRUE.equals(spot.locked);
    }

    /** The {@code Show} group. */
    public static final class ShowRule {

        @Nullable protected Boolean defaultShown;
        @Nullable protected Boolean locked;

        public static final BuilderCodec<ShowRule> CODEC = BuilderCodec
                .builder(ShowRule.class, ShowRule::new)
                .appendInherited(new KeyedCodec<>("Default", Codec.BOOLEAN, false),
                        (o, v) -> o.defaultShown = v, o -> o.defaultShown,
                        (o, p) -> o.defaultShown = p.defaultShown)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether it shows for a player who has not chosen. A player may still turn it "
                        + "on or off unless Locked.")
                .add()
                .appendInherited(new KeyedCodec<>("Locked", Codec.BOOLEAN, false),
                        (o, v) -> o.locked = v, o -> o.locked, (o, p) -> o.locked = p.locked)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True fixes it at Default for everyone and takes the switch off the Settings "
                        + "tab. A player's own choice is kept, so it comes back when you unlock it.")
                .add()
                .build();

        public ShowRule() {
        }

        public ShowRule(@Nullable Boolean defaultShown, @Nullable Boolean locked) {
            this.defaultShown = defaultShown;
            this.locked = locked;
        }
    }

    /** The {@code Spot} group. */
    public static final class SpotRule {

        @Nullable protected Boolean locked;

        public static final BuilderCodec<SpotRule> CODEC = BuilderCodec
                .builder(SpotRule.class, SpotRule::new)
                .appendInherited(new KeyedCodec<>("Locked", Codec.BOOLEAN, false),
                        (o, v) -> o.locked = v, o -> o.locked, (o, p) -> o.locked = p.locked)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True fixes where it sits at the server's own spot for everyone and takes the "
                        + "choice off the Settings tab. A player's pick is kept for when you unlock it.")
                .add()
                .build();

        public SpotRule() {
        }

        public SpotRule(@Nullable Boolean locked) {
            this.locked = locked;
        }
    }
}
