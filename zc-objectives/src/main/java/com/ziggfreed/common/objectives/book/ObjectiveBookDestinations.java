package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The Objective Book's two screens in the shared vocabulary, unprefixed because the library owns the
 * book: {@code "Open": "Quest_Log"} opens it on Quests and {@code "Open": "Achievements"} on
 * Achievements, each inside the shared menu. Opened on the player's own ref; the menu's Quests and
 * Achievements tabs open these.
 */
public final class ObjectiveBookDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String QUEST_LOG_TYPE = "Quest_Log";
    public static final String ACHIEVEMENTS_TYPE = "Achievements";

    public static final QuestLog QUEST_LOG = new QuestLog();
    public static final Achievements ACHIEVEMENTS = new Achievements();

    private ObjectiveBookDestinations() {
    }

    /** Seed both types, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(QUEST_LOG_TYPE, QuestLog.class, QuestLog.CODEC,
                (d, ctx) -> open(ObjectiveBookPage.TAB_QUESTS, ctx)));
        Destinations.register(OWNER, DestinationType.of(ACHIEVEMENTS_TYPE, Achievements.class,
                Achievements.CODEC, (d, ctx) -> open(ObjectiveBookPage.TAB_ACHIEVEMENTS, ctx)));
    }

    private static boolean open(@Nonnull String tab, @Nonnull DestinationContext ctx) {
        return ObjectiveBookPages.open(tab, ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** The book on its Quests tab. */
    public static final class QuestLog extends Destination {

        public static final BuilderCodec<QuestLog> CODEC = BuilderCodec.builder(QuestLog.class, QuestLog::new).build();
    }

    /** The book on its Achievements tab. */
    public static final class Achievements extends Destination {

        public static final BuilderCodec<Achievements> CODEC =
                BuilderCodec.builder(Achievements.class, Achievements::new).build();
    }
}
