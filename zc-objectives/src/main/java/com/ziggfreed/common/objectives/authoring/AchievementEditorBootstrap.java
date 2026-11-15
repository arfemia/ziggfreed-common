package com.ziggfreed.common.objectives.authoring;

import com.ziggfreed.common.authoring.AuthoringTools;

/**
 * Wires the achievement editor at the library's {@code setup()}: the {@code Achievement} wizard and the Achievement
 * Tool that opens it on what it points at (its permission, {@code ziggfreedcommon.author.achievement}, joins the
 * server editor group). The tool's item is an asset this module ships; the progression admin page and
 * {@code /zigauthor open Achievement} open the same wizard. A server running the library alone has all of it.
 */
public final class AchievementEditorBootstrap {

    private AchievementEditorBootstrap() {
    }

    /** At the library's {@code setup()}, after the authoring kit's own. */
    public static void install() {
        AuthoringTools.wizardTool(AchievementWizard.ID, AchievementWizard::new);
    }
}
