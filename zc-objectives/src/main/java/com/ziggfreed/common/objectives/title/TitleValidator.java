package com.ziggfreed.common.objectives.title;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.entity.title.ZigTitleComponent;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The content audit over the folded titles and the {@code Title} rewards that grant them, filed under the
 * {@value #DOMAIN} domain: what an author got wrong in a title file, or in a reward naming one, said while
 * the file is still open. A title switched off is skipped, yet a reward naming it is not naming an unknown
 * one: switching a title off hides it without taking it from anybody.
 *
 * <p>Split the way every validator in this family is: {@link #audit()} is the engine walk (it never throws;
 * it reads the achievement and quest catalogues only once the progression runtime is built, since before
 * that nothing has published any) and the second {@code audit} is the pure core a test drives.
 *
 * <p>The codes, each a stable machine token a consumer may filter on:
 * <ul>
 *   <li>ERROR {@link #ID_UNSAVABLE} (the save format refuses the id, so nobody can ever hold it),
 *       {@link #UNUSABLE_TITLE_REWARD} (a grant refuses it: no title named, or a reserved character);</li>
 *   <li>WARNING {@link #UNKNOWN_TITLE_REWARD} (granted, but no file shows it, so it never shows), and
 *       {@link TextKeyAudit#UNKNOWN_TEXT_KEY} for a {@code Text} key no loaded lang file ships;</li>
 *   <li>INFO {@link #UNNAMED_TITLE} (nothing names it, so it reads as its id spelled out).</li>
 * </ul>
 */
public final class TitleValidator {

    public static final String DOMAIN = "title";

    /** The label the title audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[title] audit";

    public static final String ID_UNSAVABLE = "TITLE_ID_UNSAVABLE";
    public static final String UNNAMED_TITLE = "UNNAMED_TITLE";
    public static final String UNKNOWN_TITLE_REWARD = "UNKNOWN_TITLE_REWARD";
    public static final String UNUSABLE_TITLE_REWARD = "UNUSABLE_TITLE_REWARD";

    private TitleValidator() {
    }

    /** The engine walk over every folded title and the live achievement and quest catalogues. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            boolean built = ProgressionRuntime.isBuilt();
            return audit(TitleConfig.getInstance().all().values(),
                    built ? ProgressionRuntime.achievements().achievements() : List.of(),
                    built ? ProgressionRuntime.quests().quests() : List.of(),
                    TextKeyAudit.liveCatalogue());
        } catch (Throwable t) {
            SafeLog.warn("[title] the title audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core: the titles in id order, then every achievement's rewards and every quest's, in id
     * order, so a report reads the same twice running.
     *
     * @param titles     every folded title, switched on or off
     * @param keyShipped whether a loaded lang file ships a key
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<TitleAsset> titles,
            @Nonnull Collection<Achievement> achievements, @Nonnull Collection<Quest> quests,
            @Nonnull Predicate<String> keyShipped) {
        List<Finding> out = new ArrayList<>();
        List<TitleAsset> ordered = new ArrayList<>();
        Set<String> defined = new HashSet<>();
        for (TitleAsset title : titles) {
            if (title != null && title.getId() != null) {
                ordered.add(title);
                defined.add(title.getId().toLowerCase(Locale.ROOT));
            }
        }
        ordered.sort(Comparator.comparing(TitleAsset::getId));
        for (TitleAsset title : ordered) {
            if (title.enabled()) {
                auditTitle(title, keyShipped, out);
            }
        }
        List<Achievement> byAchievement = new ArrayList<>(achievements);
        byAchievement.removeIf(achievement -> achievement == null);
        byAchievement.sort(Comparator.comparing(Achievement::id));
        for (Achievement achievement : byAchievement) {
            auditRewards("the achievement '" + achievement.id() + "'", achievement.id(), achievement.autoRewards(),
                    defined, out);
            auditRewards("the achievement '" + achievement.id() + "'", achievement.id(), achievement.claimRewards(),
                    defined, out);
        }
        List<Quest> byQuest = new ArrayList<>(quests);
        byQuest.removeIf(quest -> quest == null);
        byQuest.sort(Comparator.comparing(Quest::id));
        for (Quest quest : byQuest) {
            auditRewards("the quest '" + quest.id() + "'", quest.id(), quest.rewards(), defined, out);
        }
        return out;
    }

    private static void auditTitle(@Nonnull TitleAsset title, @Nonnull Predicate<String> keyShipped,
            @Nonnull List<Finding> out) {
        String id = title.getId();
        String where = "the title '" + id + "'";
        if (ZigTitleComponent.usesReservedDelimiter(id)) {
            out.add(Finding.error(DOMAIN, ID_UNSAVABLE, where + " carries '|' or ':', which a player's saved titles "
                    + "reserve, so no grant can ever give it; rename its file", id));
        }
        ContentTextAsset text = title.text();
        String titleKey = text == null ? null : text.getTitleKey();
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.TitleKey", titleKey, keyShipped,
                "its name falls back to title." + id + ".name, a DisplayName or its id spelled out");
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.FlavorKey", text == null ? null : text.getFlavorKey(),
                keyShipped, "the line about it falls back to title." + id + ".flavor, or to none");
        if (!named(id, text, keyShipped)) {
            out.add(Finding.info(DOMAIN, UNNAMED_TITLE, where + " has no shipped Text.TitleKey, no shipped "
                    + TitleText.nameKey(id) + " and no Text.DisplayName, so players read it as its id spelled out",
                    id));
        }
    }

    /** Does any rung of the name ladder above the spelled-out id name it ({@link TitleText#nameOf})? */
    private static boolean named(@Nonnull String id, @Nullable ContentTextAsset text,
            @Nonnull Predicate<String> keyShipped) {
        String titleKey = text == null ? null : text.getTitleKey();
        String typed = text == null ? null : text.getDisplayName();
        return (titleKey != null && !titleKey.isBlank() && TextKeyAudit.shipped(titleKey.trim(), keyShipped))
                || TextKeyAudit.shipped(TitleText.nameKey(id), keyShipped)
                || (typed != null && !typed.isBlank());
    }

    private static void auditRewards(@Nonnull String where, @Nonnull String sourceId,
            @Nonnull List<RewardSpec> rewards, @Nonnull Set<String> defined, @Nonnull List<Finding> out) {
        for (RewardSpec reward : rewards) {
            if (!TitleRewardKind.isTitleReward(reward)) {
                continue;
            }
            String titleId = TitleRewardKind.titleOf(reward);
            if (titleId.isEmpty() || ZigTitleComponent.usesReservedDelimiter(titleId)) {
                out.add(Finding.error(DOMAIN, UNUSABLE_TITLE_REWARD, where + " has a Title reward "
                        + (titleId.isEmpty() ? "naming no title"
                                : "naming '" + titleId + "', which carries '|' or ':'")
                        + ", so the grant refuses it and the title is never given", sourceId));
            } else if (!defined.contains(titleId.toLowerCase(Locale.ROOT))) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_TITLE_REWARD, where + " grants the title '" + titleId
                        + "', which no title file defines, so the player holds it but it never shows", sourceId));
            }
        }
    }
}
