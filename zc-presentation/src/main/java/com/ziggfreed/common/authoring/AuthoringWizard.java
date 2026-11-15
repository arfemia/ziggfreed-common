package com.ziggfreed.common.authoring;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.content.ServerContent;

/**
 * One in-game editor, as the authoring wizard frame ({@link AuthoringWizardPage}) shows it: its steps, what the
 * preview pane says about what is being made, why a step may not go on, and the save. One instance per open
 * ({@link AuthoringWizards.Factory} makes it), so it may keep its own state beside the session's values. Every method
 * runs on the admin's world thread; one that throws is logged and read as its default.
 *
 * <p><b>Saving.</b> The frame calls {@link #save} only for a player holding the wizard's author node,
 * {@code ziggfreedcommon.author.<wizard id>}, asked on the server at Save whatever surface opened the page. {@link #save}
 * writes through {@link ServerContent} (an asset with {@code write}, the words with {@code setText} or
 * {@code setTexts}) and hands back every result; the frame shows the slowest result's
 * {@code AppliesWhen.notice()} once all have landed, or the first refusal's {@code ContentProblem.message()}. The id
 * of a new asset is minted once through {@link WizardSession#idOr} ({@code ServerContent.mintId}) and kept for the
 * session, so a second save writes the same asset; an editor opened on a saved asset keeps its id
 * ({@link WizardSession#keepId}) and never renames it.
 */
public interface AuthoringWizard {

    /** The wizard's name, at the top of the page. */
    @Nonnull
    Message title();

    /** The steps as they stand for {@code session} (a step may come and go with what was chosen). At least one. */
    @Nonnull
    List<WizardStep> steps(@Nonnull WizardSession session);

    /**
     * Once, when the wizard opens: seed the session's values from what the tool pointed at ({@code use.pick()},
     * {@link WorldPick#NOTHING} from a command), and pick the step to start on.
     */
    default void begin(@Nonnull WizardSession session, @Nonnull AuthoringUse use) {
    }

    /**
     * A pick from the world just filled {@code fieldId} (its value is already in the session): read anything more the
     * wizard wants off {@code pick}, at once, while its entity ref is good.
     */
    default void picked(@Nonnull WizardSession session, @Nonnull String fieldId, @Nonnull WorldPick pick) {
    }

    /** The preview pane's lines for what the session holds now; repainted as the admin types. */
    @Nonnull
    default List<Message> preview(@Nonnull WizardSession session) {
        return List.of();
    }

    /** Why the admin may not leave step {@code step} by Next or Save, or null when they may. */
    @Nullable
    default Message check(@Nonnull WizardSession session, int step) {
        return null;
    }

    /** True for a wizard with a Save button (on its last step). */
    default boolean saves() {
        return false;
    }

    /** Save what the session holds through {@link ServerContent}; every result it started. */
    @Nonnull
    default List<ServerContent.Result> save(@Nonnull WizardSession session) {
        return List.of();
    }
}
