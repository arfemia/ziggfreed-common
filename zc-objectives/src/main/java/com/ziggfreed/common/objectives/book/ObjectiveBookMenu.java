package com.ziggfreed.common.objectives.book;

import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * The book's two tabs in the shared menu. A tab shows while the catalogue it lists has something in it
 * and {@link ProgressionRuntime#systemEnabled} passes for the player, the one read that already carries
 * a consumer's per-player switches. Labels are the book's own tab words.
 */
public final class ObjectiveBookMenu {

    private static final String PREFIX = "ziggfreedcommon.";

    /** The Quests tab's picture: the brown grimoire, the Objective Book's own art. */
    public static final String QUESTS_ICON = "Weapon_Spellbook_Grimoire_Brown";

    /** The Achievements tab's picture: the base game's one trophy. */
    public static final String ACHIEVEMENTS_ICON = "Deco_Trophy_Harvest";

    private ObjectiveBookMenu() {
    }

    @Nonnull
    public static MenuEntry quests() {
        return MenuSlot.QUESTS.entry(Msg.tr(PREFIX, "progression.book.tab.quests"), IconSpec.ofItem(QUESTS_ICON),
                ObjectiveBookDestinations.QUEST_LOG, ObjectiveBookMenu::questsShow);
    }

    @Nonnull
    public static MenuEntry achievements() {
        return MenuSlot.ACHIEVEMENTS.entry(Msg.tr(PREFIX, "progression.book.tab.achievements"),
                IconSpec.ofItem(ACHIEVEMENTS_ICON), ObjectiveBookDestinations.ACHIEVEMENTS,
                ObjectiveBookMenu::achievementsShow);
    }

    static boolean questsShow(@Nonnull DestinationContext viewer) {
        return shows(ProgressionRuntime.quests().quests().size(),
                ProgressionRuntime.subjects().questSubject(viewer.store(), viewer.playerReference()),
                systemOn(ProgressionSystem.QUEST));
    }

    static boolean achievementsShow(@Nonnull DestinationContext viewer) {
        return shows(ProgressionRuntime.achievements().achievements().size(),
                ProgressionRuntime.subjects().achievementSubject(viewer.store(), viewer.playerReference()),
                systemOn(ProgressionSystem.ACHIEVEMENT));
    }

    /** The rule: something to list, somebody to list it for, and the system on for them. */
    static boolean shows(int catalogueSize, @Nullable Subject subject, @Nonnull Predicate<Subject> systemOn) {
        return catalogueSize > 0 && subject != null && systemOn.test(subject);
    }

    @Nonnull
    static Predicate<Subject> systemOn(@Nonnull ProgressionSystem system) {
        return subject -> ProgressionRuntime.systemEnabled(system, subject);
    }
}
