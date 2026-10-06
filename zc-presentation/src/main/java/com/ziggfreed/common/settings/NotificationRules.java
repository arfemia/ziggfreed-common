package com.ziggfreed.common.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * What a server owner says about players' quest and achievement notices: the level a player who has not
 * chosen gets, and whether it is fixed for everyone. Every leaf is optional and folds leaf by leaf.
 */
public final class NotificationRules {

    /** Nothing said: every update, nothing fixed. */
    public static final NotificationRules NONE = new NotificationRules();

    @Nullable protected LevelRule level;

    public static final BuilderCodec<NotificationRules> CODEC = BuilderCodec
            .builder(NotificationRules.class, NotificationRules::new)
            .appendInherited(new KeyedCodec<>("Level", LevelRule.CODEC, false),
                    (o, v) -> o.level = v, o -> o.level, (o, p) -> o.level = p.level)
            .documentation("How chatty quest and achievement notices are for a player who has not chosen, "
                    + "and whether players may change it.")
            .add()
            .build();

    public NotificationRules() {
    }

    /** The {@code Level} group, never null. */
    @Nonnull
    public LevelRule level() {
        return level != null ? level : LevelRule.NONE;
    }

    /** The {@code Level} group. */
    public static final class LevelRule {

        /** Nothing said. */
        public static final LevelRule NONE = new LevelRule();

        @Nullable protected String defaultLevel;
        @Nullable protected Boolean locked;

        public static final BuilderCodec<LevelRule> CODEC = BuilderCodec
                .builder(LevelRule.class, LevelRule::new)
                .appendInherited(new KeyedCodec<>("Default", Codec.STRING, false),
                        (o, v) -> o.defaultLevel = v, o -> o.defaultLevel,
                        (o, p) -> o.defaultLevel = p.defaultLevel)
                .metadata(EditorSchema.oneOf(NotificationLevel.ids()))
                .metadata(EditorSchema.defaultValue(NotificationLevel.DEFAULT.id()))
                .documentation("The level a player who has not chosen gets. EveryUpdate shows each step as it "
                        + "moves; Milestones shows a step only at the marks its notice sets and when it is "
                        + "done; Finishes shows a step only when it is done; None shows none of these notices. "
                        + "Finished quests, rewards and unlocks show at every level but None.")
                .add()
                .appendInherited(new KeyedCodec<>("Locked", Codec.BOOLEAN, false),
                        (o, v) -> o.locked = v, o -> o.locked, (o, p) -> o.locked = p.locked)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True fixes the level at Default for everyone and takes the choice off the "
                        + "Settings tab. A player's own choice is kept for when you unlock it.")
                .add()
                .build();

        public LevelRule() {
        }

        public LevelRule(@Nullable String defaultLevel, @Nullable Boolean locked) {
            this.defaultLevel = defaultLevel;
            this.locked = locked;
        }

        /** The default level; a word nobody knows reads as {@link NotificationLevel#DEFAULT}. */
        @Nonnull
        public NotificationLevel defaultLevel() {
            NotificationLevel parsed = NotificationLevel.parse(defaultLevel);
            return parsed != null ? parsed : NotificationLevel.DEFAULT;
        }

        /** Whether the owner fixed the level for everyone. */
        public boolean locked() {
            return Boolean.TRUE.equals(locked);
        }
    }
}
