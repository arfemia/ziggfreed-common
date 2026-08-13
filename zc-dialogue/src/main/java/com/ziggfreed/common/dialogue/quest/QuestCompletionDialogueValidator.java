package com.ziggfreed.common.dialogue.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.quest.asset.QuestPoolValidator;
import com.ziggfreed.common.validation.Finding;

/**
 * Audits the one thing a quest's {@code CompletionDialogue} can get wrong silently: naming a
 * conversation nothing on this server can open. The quest still finishes and still pays out, so
 * nothing ever throws and nothing ever logs; the giver simply says nothing afterwards, which reads
 * like a missing beat rather than a mistyped id.
 *
 * <p><b>Why it lives beside the routing rather than with the rest of the quest audit.</b> The quest
 * authoring layer sits below this module and can see no conversation store at all, so a check there
 * could only be given a probe to call - and a second probe is a second answer, free to drift from the
 * one the runtime uses. This calls the SAME {@link QuestDialogueHosts#knows} the hand-off calls, so
 * the audit and the runtime can never disagree.
 *
 * <p>Findings are reported under the QUEST family, because the file an author will go and open is the
 * quest.
 */
public final class QuestCompletionDialogueValidator {

    /** Reported under the quest family: that is the file an author has to edit. */
    public static final String DOMAIN = QuestPoolValidator.DOMAIN;

    /** The stable code for a completion conversation no registered host can open. */
    public static final String CODE = "UNKNOWN_COMPLETION_DIALOGUE";

    private QuestCompletionDialogueValidator() {
    }

    /**
     * One quest's check, for a consumer walking its own catalogue. Null when there is nothing wrong -
     * which includes a quest that names no conversation at all.
     *
     * <p>A WARNING rather than an error, by the rule the shared validators all follow: whoever owns
     * the conversation may register its host after this audit runs, or be a mod the author expects
     * some servers not to install.
     */
    @Nullable
    public static Finding check(@Nullable String dialogueId, @Nonnull String questId) {
        if (dialogueId == null || dialogueId.isBlank()) {
            return null;
        }
        String id = dialogueId.trim();
        if (QuestDialogueHosts.knows(id)) {
            return null;
        }
        return Finding.warning(DOMAIN, CODE,
                "CompletionDialogue names '" + id + "', which no surface on this server can open; the quest "
                        + "still finishes and still pays out, but the conversation after it is skipped",
                questId);
    }

    /** Every quest in {@code pool}, in resolution order. */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool) {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, QuestDefinition> entry : pool.definitions().entrySet()) {
            QuestDefinition definition = entry.getValue();
            if (definition == null) {
                continue;
            }
            Finding finding = check(definition.completionDialogue(), entry.getKey());
            if (finding != null) {
                out.add(finding);
            }
        }
        return out;
    }
}
