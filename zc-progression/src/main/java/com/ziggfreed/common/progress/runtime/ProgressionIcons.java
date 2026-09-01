package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.icon.Portraits;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.util.SafeLog;

/**
 * What one step LOOKS like, asked once for every surface that lists steps, the same way
 * {@link ProgressionTexts} answers what one step is called.
 *
 * <p>A picture beside a step is the difference between reading a list and recognising one, and it
 * is decided here rather than per page so a step cannot be pictured one way on a character's offer
 * screen and another in the objective book.
 *
 * <p>The ladder, most specific first:
 * <ol>
 *   <li>a registered {@link ProgressionIconSource}, which is a mod saying something about its own
 *       step that nothing else can recover;</li>
 *   <li>a picture the kind's own file gives that exact target, which is a server or a pack
 *       correcting one target's picture;</li>
 *   <li>the target drawn as itself - an item id as that item's own picture, a creature id as that
 *       creature's own portrait, another quest or achievement as its own icon - which is what covers
 *       the great majority of steps with nothing authored at all;</li>
 *   <li>the kind's own fallback picture.</li>
 * </ol>
 *
 * <p><b>A target is drawn from however it MATCHES, but an empty one never is.</b> A target written
 * to catch a family still NAMES the family - a hunt for {@code Boar} that also counts a piglet is
 * still a hunt for boar - so the name is tried first whether it is matched whole, by prefix or by
 * substring. What guards this is that a name nothing answers to draws nothing: an item is drawn only
 * when this server really ships one under that id, so a target like {@code Copper} standing in for a
 * family of ores finds no item called Copper and falls through to the kind fallback on its own. A
 * step with NO target matches everything and names nothing, so it goes straight to that fallback -
 * a picture of what the step is DOING rather than of what it is doing it to.
 *
 * <p>A step nothing can picture answers null and renders as its text alone, which is always better
 * than borrowing an unrelated thing's picture.
 */
public final class ProgressionIcons {

    private ProgressionIcons() {
    }

    /**
     * The picture for one step, or null when nothing can picture it.
     *
     * @param contentId the quest or achievement the step belongs to
     * @param objective the step to picture
     */
    @Nullable
    public static IconSpec forObjective(@Nonnull String contentId, @Nonnull ObjectiveDef objective) {
        IconSpec fromSource = ask(contentId, objective);
        if (fromSource != null && !fromSource.isEmpty()) {
            return fromSource;
        }

        ObjectiveKind kind = ProgressionRuntime.objectiveKinds().kind(objective.kind());
        if (kind == null) {
            return null;
        }
        String target = objective.target();

        if (target != null && !target.isBlank()) {
            IconSpec authored = kind.presentation().iconForTarget(target.trim());
            if (authored != null) {
                return authored;
            }
            IconSpec derived = drawTargetAsItself(kind, target.trim());
            if (derived != null) {
                return derived;
            }
        }

        return kind.presentation().icon();
    }

    /**
     * The target drawn as itself, for a kind that declared what its target names. An item is drawn
     * only when this server really ships one under that id - an id nothing ships would otherwise
     * paint the unknown-item picture, which reads as a promise of a thing that does not exist. A
     * creature is drawn as its portrait without such a check, because the still lives on the client
     * and the row's own fallback texture covers a creature that never had one rendered.
     */
    @Nullable
    private static IconSpec drawTargetAsItself(@Nonnull ObjectiveKind kind, @Nonnull String target) {
        if (kind.targetsItem() && ItemIds.exists(target)) {
            return IconSpec.ofItem(target);
        }
        if (kind.targetsEntity()) {
            return Portraits.forRole(target);
        }
        if (kind.targetsContent()) {
            return contentIcon(target);
        }
        return null;
    }

    /**
     * Another piece of content drawn with its OWN icon, so a step that says "finish that quest" shows
     * the picture that quest is listed under everywhere else. Both catalogues are asked, quests
     * first, because an id is one or the other and nothing forbids a consumer using one id for both.
     */
    @Nullable
    private static IconSpec contentIcon(@Nonnull String contentId) {
        try {
            Quest quest = ProgressionRuntime.quests().quest(contentId);
            if (quest != null && ItemIds.exists(quest.icon())) {
                return IconSpec.ofItem(quest.icon());
            }
            Achievement achievement = ProgressionRuntime.achievements().achievement(contentId);
            if (achievement != null && ItemIds.exists(achievement.icon())) {
                return IconSpec.ofItem(achievement.icon());
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] reading a step's content icon failed: " + t.getMessage());
        }
        return null;
    }

    @Nullable
    private static IconSpec ask(@Nonnull String contentId, @Nonnull ObjectiveDef objective) {
        for (ProgressionIconSource source : ProgressionRuntime.iconSources()) {
            try {
                IconSpec answer = source.objectiveIcon(contentId, objective);
                if (answer != null) {
                    return answer;
                }
            } catch (Throwable t) {
                SafeLog.warn("[progression] an icon source failed while picturing a step: " + t.getMessage());
            }
        }
        return null;
    }
}
