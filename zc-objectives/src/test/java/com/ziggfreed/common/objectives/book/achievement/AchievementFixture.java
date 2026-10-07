package com.ziggfreed.common.objectives.book.achievement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.PlainText;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.SeenMarks;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ContentListingAsset.ChainMembership;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.LedgerRow;

/**
 * One player, one engine and one category table for the achievement readers' tests: achievements are added
 * with a title, their state is written straight into the engine's store, and every {@link Message} a reader
 * builds is read back through {@link #read} against a fixed English catalogue (no production lang file).
 */
final class AchievementFixture {

    static final long DAY = 86_400_000L;

    /** 2026-09-21, noon UTC. */
    static final long NOW = 1_790_000_000_000L;

    static final UUID VIEWER = new UUID(0, 9);

    static final Subject ALICE = new Subject(VIEWER, "Alice", null);

    private static final String A = "ziggfreedcommon.progression.book.achievements.";

    /** The fixed catalogue {@link #read} renders with: English wording, slots unformatted. */
    static final Map<String, String> CATALOGUE = Map.ofEntries(
            Map.entry(A + "state.done", "Done"),
            Map.entry(A + "state.progress", "In progress"),
            Map.entry(A + "state.collect", "Collect"),
            Map.entry(A + "state.feat", "Feat"),
            Map.entry(A + "row.points", "{0} pts"),
            Map.entry(A + "meta.steps", "{0} / {1} steps"),
            Map.entry(A + "tier", "Tier {0} of {1}"),
            Map.entry(A + "tier_short", "Tier {0}"),
            Map.entry(A + "hint.earned_on", "Earned on {0}"),
            Map.entry(A + "hint.to_go", "{0} to go"),
            Map.entry(A + "meta.ends_in", "Ends in {0} days"),
            Map.entry(A + "page.breadcrumb", "{0} > {1}"),
            Map.entry(A + "page.points", "{0} points"),
            Map.entry(A + "page.claimant", "First claimed by {0}"),
            Map.entry(A + "pill.server_first", "Server first"),
            Map.entry(A + "pill.seasonal", "Seasonal"),
            Map.entry(A + "pill.feat_since", "Feat of Strength since {0}"),
            Map.entry(A + "block.criteria", "Criteria"),
            Map.entry(A + "block.needs", "Needs"),
            Map.entry(A + "block.part_of", "Part of"),
            Map.entry(A + "block.ladder", "Ladder"),
            Map.entry(A + "block.rewards", "Rewards"),
            Map.entry(A + "block.more_in", "More in {0}"),
            Map.entry(A + "reward.auto", "Auto"),
            Map.entry(A + "reward.waiting", "Waiting"),
            Map.entry(A + "reward.collected", "Collected"),
            Map.entry(A + "reward.locked", "Locked"),
            Map.entry(A + "action.pin", "Pin"),
            Map.entry(A + "action.unpin", "Unpin"),
            Map.entry(A + "action.collect", "Collect"),
            Map.entry(A + "milestone.title", "{0} points: {1}"),
            Map.entry(A + "milestone.all_collected", "All milestones collected"),
            Map.entry(A + "milestone.more_after", "{0} more after this"),
            Map.entry(A + "tile.count", "{0} / {1}"),
            Map.entry(A + "tile.on_now", "On now"),
            Map.entry(A + "tile.feats", "Feats of Strength"),
            Map.entry(A + "tile.feats_count", "{0} earned"),
            Map.entry(A + "overview.pinned", "Pinned"),
            Map.entry(A + "category.uncategorised", "Other"),
            Map.entry(A + "criterion.untitled", "Unnamed step"),
            Map.entry("ziggfreedcommon.progression.book.tooltip.pin", "Pin this achievement"),
            Map.entry("ziggfreedcommon.ui.kit.count", "{0} / {1}"),
            Map.entry("ziggfreedcommon.fmt.cat", "{0}{1}"));

    final AchievementEngine engine = AchievementEngine.builder().nativeEvents(false).clock(() -> NOW).build();
    final Map<String, AchievementCategoryAsset> categories = new HashMap<>();
    final Map<String, String> titles = new HashMap<>();
    final Map<String, String> flavors = new HashMap<>();
    private final Map<String, Achievement> catalogue = new LinkedHashMap<>();
    OccurrenceSource calendar = OccurrenceSource.NONE;
    SeenMarks seen = SeenMarks.NONE;
    ObjectiveBookDeps deps = ObjectiveBookDeps.builder()
            .rewardChips(spec -> RewardChip.of(spec.param("Item"), Msg.raw(spec.paramOr("Name", spec.kind()))))
            .build();

    AchievementFixture() {
        ProgressionRuntime.resetForTests();
        LangCatalog.overrideForTests(null);
        ProgressionRuntime.registrar("fixture").textSource(new ProgressionTextSource() {
            @Nullable
            @Override
            public Message title(@Nonnull String contentId) {
                String title = titles.get(contentId);
                return title == null ? null : Msg.raw(title);
            }

            @Nullable
            @Override
            public Message flavor(@Nonnull String contentId) {
                String flavor = flavors.get(contentId);
                return flavor == null ? null : Msg.raw(flavor);
            }

            @Nullable
            @Override
            public Message objective(@Nonnull String contentId, @Nonnull String objectiveId) {
                return Msg.raw(contentId + " step " + objectiveId);
            }
        });
    }

    /** Undo every global the fixture touched. */
    void close() {
        ProgressionRuntime.resetForTests();
        LangCatalog.overrideForTests(null);
    }

    /** An achievement in {@code category} / {@code sub} with one criterion of {@code amount}. */
    @Nonnull
    static Achievement.Builder ach(@Nonnull String id, @Nullable String category, @Nullable String sub, long amount) {
        return Achievement.builder(id).category(category).subcategory(sub)
                .criterion(ObjectiveDef.builder("0", "BREAK_BLOCK").amount(amount).build());
    }

    /** A rung of {@code chain} at {@code tier}. */
    @Nonnull
    static Achievement.Builder rung(@Nonnull String id, @Nonnull String category, @Nonnull String chain, int tier) {
        return ach(id, category, null, 10L * tier).chains(List.of(ChainMembership.of(chain, tier)));
    }

    /** Catalogue {@code builder} under {@code title}. */
    @Nonnull
    Achievement add(@Nonnull Achievement.Builder builder, @Nonnull String title) {
        Achievement a = builder.build();
        catalogue.put(a.id(), a);
        titles.put(a.id(), title);
        engine.setAchievements(new ArrayList<>(catalogue.values()));
        return a;
    }

    /** Describe a category. */
    void category(@Nonnull AchievementCategoryAsset asset) {
        categories.put(asset.getId(), asset);
    }

    void progress(@Nonnull Achievement a, int criterion, long value) {
        engine.store().setCriterionProgress(ALICE, a.id(), a.criteria().get(criterion).id(), value);
    }

    /** Earned at {@code at}: waiting when it pays on collect, else settled. */
    void earn(@Nonnull Achievement a, long at) {
        engine.store().setStatus(ALICE, a.id(), a.requiresClaim() ? AchievementStatus.UNLOCKED : AchievementStatus.CLAIMED);
        engine.store().setUnlockedAt(ALICE, a.id(), at);
    }

    void collect(@Nonnull Achievement a) {
        engine.store().setStatus(ALICE, a.id(), AchievementStatus.CLAIMED);
    }

    void pin(@Nonnull Achievement a, long at) {
        engine.store().setPin(ALICE, a.id(), at);
    }

    @Nonnull
    AchievementReader reader() {
        return AchievementReader.of(engine, ALICE, deps, VIEWER, calendar, seen, NOW, id -> id == null ? null
                : categories.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /** {@code m} read in the fixture's English; null reads as null. */
    @Nullable
    static String read(@Nullable Message m) {
        return m == null ? null : PlainText.render(m.getFormattedMessage(), id -> CATALOGUE.getOrDefault(id, id));
    }

    /** The row ids in {@code rows}, in order. */
    @Nonnull
    static List<String> ids(@Nonnull List<LedgerRow> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (LedgerRow row : rows) {
            out.add(row.id());
        }
        return out;
    }
}
