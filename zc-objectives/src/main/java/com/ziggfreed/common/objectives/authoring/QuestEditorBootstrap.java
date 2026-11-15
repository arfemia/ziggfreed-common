package com.ziggfreed.common.objectives.authoring;

import com.ziggfreed.common.authoring.AuthoringTools;

/**
 * Wires the quest editor at the library's {@code setup()}: the {@code Quest} wizard and the Quest Tool that opens it
 * on what it points at (its permission, {@code ziggfreedcommon.author.quest}, joins the server editor group). The
 * tool's item is an asset this module ships; the progression admin page and {@code /zigauthor open Quest} open the
 * same wizard. A server running the library alone has all of it.
 */
public final class QuestEditorBootstrap {

    private QuestEditorBootstrap() {
    }

    /** At the library's {@code setup()}, after the authoring kit's own. */
    public static void install() {
        AuthoringTools.wizardTool(QuestWizard.ID, QuestWizard::new);
    }
}
