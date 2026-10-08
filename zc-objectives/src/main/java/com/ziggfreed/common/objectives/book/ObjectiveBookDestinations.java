package com.ziggfreed.common.objectives.book;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.objectives.book.achievement.AchievementBrowse;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationKind;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Objective Book's two screens in the shared vocabulary, unprefixed because the library owns the
 * book: {@code "Open": "Quest_Log"} opens it on Quests and {@code "Open": "Achievements"} on
 * Achievements, each inside the shared menu. Either takes an optional {@code "Select"}, the quest or
 * achievement id the book opens on ({@code { "Type": "Achievements", "Select": "Ghoul_Breaker_2026" }}).
 * {@code Achievements} also takes {@code Category} and {@code Subcategory}
 * ({@code { "Type": "Achievements", "Category": "Seasons", "Subcategory": "Hallows_Eve" }}): the book opens on
 * Browse filtered to the category, that subcategory's section open and the category's others closed.
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
                        (d, ctx) -> open(ObjectiveBookPage.TAB_QUESTS, d.getSelect(), ctx))
                .withKind(DestinationKind.QUEST));
        Destinations.register(OWNER, DestinationType.of(ACHIEVEMENTS_TYPE, Achievements.class,
                Achievements.CODEC, (d, ctx) -> openAchievements(d, ctx))
                .withKind(DestinationKind.TROPHY));
    }

    private static boolean open(@Nonnull String tab, @Nullable String select, @Nonnull DestinationContext ctx) {
        return ObjectiveBookPages.open(tab, select, ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** The book on Achievements: as today with no Category, else on Browse filtered to it, one subcategory in focus. */
    private static boolean openAchievements(@Nonnull Achievements d, @Nonnull DestinationContext ctx) {
        String category = d.getCategory();
        if (category == null) {
            return open(ObjectiveBookPage.TAB_ACHIEVEMENTS, d.getSelect(), ctx);
        }
        String subcategory = d.getSubcategory();
        Set<String> sections = subcategory == null ? Set.of()
                : AchievementBrowse.focus(catalogue(), category, subcategory);
        return ObjectiveBookPages.openAt(BookState.browsing(ObjectiveBookPage.TAB_ACHIEVEMENTS, category, sections,
                d.getSelect()), ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** Every achievement the shared runtime holds, or none while it is not built. */
    @Nonnull
    private static Collection<Achievement> catalogue() {
        try {
            return ProgressionRuntime.achievements().achievements();
        } catch (Throwable t) {
            SafeLog.fine("[progression] the book's focused open found no achievement catalogue: " + t.getMessage());
            return List.of();
        }
    }

    /** The documentation every {@code Select} leaf carries. */
    private static final String SELECT_DOC = "The quest or achievement id the book opens on, selected and shown. "
            + "Leave it out to open on the tab's landing.";

    /** The documentation of the {@code Category} leaf. */
    private static final String CATEGORY_DOC = "A category id the book opens on: the Browse list filtered to it. "
            + "Leave it out to open as Select says.";

    /** The documentation of the {@code Subcategory} leaf. */
    private static final String SUBCATEGORY_DOC = "With a Category, one of its subcategories: its section opens and "
            + "the category's other sections close, so the player lands on it and can open the rest. Ignored without "
            + "a Category.";

    @Nullable
    private static String clean(@Nullable String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** A category or subcategory id as the book files it: trimmed, lower case; null when blank. */
    @Nullable
    private static String folded(@Nullable String id) {
        String clean = clean(id);
        return clean == null ? null : clean.toLowerCase(Locale.ROOT);
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

    /** The book on its Achievements tab, optionally on one achievement, or on one category (and subcategory). */
    public static final class Achievements extends Destination {

        @Nullable protected String select;
        @Nullable protected String category;
        @Nullable protected String subcategory;

        public static final BuilderCodec<Achievements> CODEC =
                BuilderCodec.builder(Achievements.class, Achievements::new)
                        .append(new KeyedCodec<>("Select", Codec.STRING, false), (d, v) -> d.select = v,
                                d -> d.select)
                        .documentation(SELECT_DOC).add()
                        .append(new KeyedCodec<>("Category", Codec.STRING, false), (d, v) -> d.category = v,
                                d -> d.category)
                        .documentation(CATEGORY_DOC).add()
                        .append(new KeyedCodec<>("Subcategory", Codec.STRING, false), (d, v) -> d.subcategory = v,
                                d -> d.subcategory)
                        .documentation(SUBCATEGORY_DOC).add()
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

        /** Java-side construction: the book on {@code category}, one {@code subcategory}'s section open (null: none). */
        @Nonnull
        public static Achievements focused(@Nullable String category, @Nullable String subcategory) {
            Achievements d = new Achievements();
            d.category = category;
            d.subcategory = subcategory;
            return d;
        }

        /** The achievement to open on, or null. */
        @Nullable
        public String getSelect() {
            return clean(select);
        }

        /** The category to open Browse on, trimmed and lower case, or null. */
        @Nullable
        public String getCategory() {
            return folded(category);
        }

        /** The subcategory whose section opens (read only with a category), trimmed and lower case, or null. */
        @Nullable
        public String getSubcategory() {
            return folded(subcategory);
        }
    }
}
