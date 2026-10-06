package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The Objective Book's two screens in the shared vocabulary, unprefixed because the library owns the
 * book: {@code "Open": "Quest_Log"} opens it on Quests and {@code "Open": "Achievements"} on
 * Achievements, each inside the shared menu. Either takes an optional {@code "Select"}, the quest or
 * achievement id the book opens on ({@code { "Type": "Achievements", "Select": "Ghoul_Breaker_2026" }}).
 * Opened on the player's own ref; the menu's Quests and Achievements tabs open the bare two.
 */
public final class ObjectiveBookDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String QUEST_LOG_TYPE = "Quest_Log";
    public static final String ACHIEVEMENTS_TYPE = "Achievements";

    public static final QuestLog QUEST_LOG = QuestLog.of(null);
    public static final Achievements ACHIEVEMENTS = Achievements.of(null);

    private ObjectiveBookDestinations() {
    }

    /** Seed both types, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(QUEST_LOG_TYPE, QuestLog.class, QuestLog.CODEC,
                (d, ctx) -> open(ObjectiveBookPage.TAB_QUESTS, d.getSelect(), ctx)));
        Destinations.register(OWNER, DestinationType.of(ACHIEVEMENTS_TYPE, Achievements.class,
                Achievements.CODEC, (d, ctx) -> open(ObjectiveBookPage.TAB_ACHIEVEMENTS, d.getSelect(), ctx)));
    }

    private static boolean open(@Nonnull String tab, @Nullable String select, @Nonnull DestinationContext ctx) {
        return ObjectiveBookPages.open(tab, select, ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** The documentation every {@code Select} leaf carries. */
    private static final String SELECT_DOC = "The quest or achievement id the book opens on, selected and shown. "
            + "Leave it out to open on the tab's landing.";

    @Nullable
    private static String clean(@Nullable String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** The book on its Quests tab, optionally on one quest. */
    public static final class QuestLog extends Destination {

        @Nullable protected String select;

        public static final BuilderCodec<QuestLog> CODEC = BuilderCodec.builder(QuestLog.class, QuestLog::new)
                .append(new KeyedCodec<>("Select", Codec.STRING, false), (d, v) -> d.select = v, d -> d.select)
                .documentation(SELECT_DOC).add()
                .build();

        public QuestLog() {
        }

        /** Java-side construction; a null id opens on the journal's landing. */
        @Nonnull
        public static QuestLog of(@Nullable String questId) {
            QuestLog d = new QuestLog();
            d.select = questId;
            return d;
        }

        /** The quest to open on, or null. */
        @Nullable
        public String getSelect() {
            return clean(select);
        }
    }

    /** The book on its Achievements tab, optionally on one achievement. */
    public static final class Achievements extends Destination {

        @Nullable protected String select;

        public static final BuilderCodec<Achievements> CODEC =
                BuilderCodec.builder(Achievements.class, Achievements::new)
                        .append(new KeyedCodec<>("Select", Codec.STRING, false), (d, v) -> d.select = v,
                                d -> d.select)
                        .documentation(SELECT_DOC).add()
                        .build();

        public Achievements() {
        }

        /** Java-side construction; a null id opens on the overview. */
        @Nonnull
        public static Achievements of(@Nullable String achievementId) {
            Achievements d = new Achievements();
            d.select = achievementId;
            return d;
        }

        /** The achievement to open on, or null. */
        @Nullable
        public String getSelect() {
            return clean(select);
        }
    }
}
