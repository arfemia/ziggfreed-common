package com.ziggfreed.common.objectives.dialogue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.asset.DialogueAssetStore;
import com.ziggfreed.common.dialogue.quest.QuestCompletionDialogueValidator;
import com.ziggfreed.common.dialogue.quest.QuestDialogueActions;
import com.ziggfreed.common.dialogue.quest.QuestDialogueConditions;
import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueExtensionConfig;
import com.ziggfreed.common.dialogue.schema.DialogueNode;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueStart;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.objectives.book.BookVerbs;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The library's own audit of the server's conversations, so a server running this jar and a content pack,
 * and nothing else, still hears about a conversation that is wrong in a way the game never mentions: the
 * structural checks ({@link DialogueStructureValidator}: a dangling {@code Goto}, a {@code Season}, factor,
 * shop or {@code Exclude} id nothing answers, an {@code Open} that names nothing), a quest id no quest
 * answers on a {@code QuestState}, {@code ReadyToTurnIn}, {@code Accept} or {@code TurnIn} line, a
 * {@code Start} beat or a {@code Start.Quests} row ({@value #UNKNOWN_QUEST_ID}, a WARNING: the quest's owner
 * may be a mod this server does not run), and a quest whose {@code CompletionDialogue} nothing can open
 * ({@link QuestCompletionDialogueValidator}, filed under the quest family). A quest the shared store's mod
 * gate refused is absent by design and is never reported unknown.
 *
 * <p><b>Once per boot, at the first player ready</b> ({@link #runLateAudit()}): only by then has every
 * store folded and every mod's {@code setup()} registered the vocabulary a line is judged against. zc's
 * boot audit takes that same one run at the boot event ({@link #claimLateFindings()}). A consumer that
 * reports these findings in its own content report claims the run ({@link #claimLateAudit(String)}) and
 * calls the pure core ({@link #auditConversations}) and {@link #completionFindings} itself, with its own
 * quest vocabulary and the lines it holds shut, so a server running both hears each finding once.
 */
public final class DialogueAudit {

    /** The label this audit's lines carry, at the first player ready and in zc's boot audit. */
    public static final String LOG_LABEL = "[dialogue]";

    /** A conversation names a quest no quest answers to (a WARNING). */
    public static final String UNKNOWN_QUEST_ID = "UNKNOWN_QUEST_ID";

    /** Whether the late audit has had its one run this boot. */
    private static final AtomicBoolean LATE_AUDITED = new AtomicBoolean();

    /** Who reports these findings, when it is not this library. */
    @Nullable
    private static volatile String lateAuditOwner;

    private DialogueAudit() {
    }

    // ==================== the audit ====================

    /**
     * Every finding over the live stores: the conversations, read against the shared dialogue engine and the
     * shared quest runtime (the reading an accept and a hand-in make), then the quests' completion
     * conversations. Builds the progression runtime first, so call it only past every plugin's
     * {@code start()}. Never throws.
     */
    @Nonnull
    public static List<Finding> audit() {
        List<Finding> out = new ArrayList<>();
        try {
            ProgressionRuntime.ensureBuilt();
            out.addAll(auditConversations(DialogueAssetStore.getInstance().dialogues().values(),
                    DialogueEngine.shared(), DialogueAudit::runtimeKnows, option -> false));
            out.addAll(completionFindings(ProgressionDefaults.questPool()));
        } catch (Throwable t) {
            SafeLog.warn(LOG_LABEL + " the dialogue audit failed: " + t.getMessage());
        }
        return out;
    }

    /**
     * The conversations' findings over explicit inputs: the structural checks against {@code engine} (its
     * factor vocabulary and the actions and conditions it can serve; null skips those checks) and every quest
     * id a line, a {@code Start} beat, a {@code Start.Quests} row or a dialogue extension's line names,
     * against {@code questKnown} widened by the quest store's mod gate.
     *
     * <p>{@code heldShut} names the lines a caller knows cannot render on this server (a consumer's own gate
     * on a feature it has switched off): their conditions' and actions' quest ids are not this server's
     * content, so they are not checked. The library holds no line shut itself.
     */
    @Nonnull
    public static List<Finding> auditConversations(@Nonnull Collection<NpcDialogue> dialogues,
            @Nullable DialogueEngine engine, @Nonnull Predicate<String> questKnown,
            @Nonnull Predicate<DialogueOption> heldShut) {
        List<Finding> out = new ArrayList<>(DialogueStructureValidator.validateAll(dialogues,
                engine == null ? null : engine.factors(), engine));
        Predicate<String> resolves = questId -> questKnown.test(questId) || modGateRefused(questId);
        for (NpcDialogue dialogue : dialogues) {
            if (dialogue != null) {
                checkQuestRefs(dialogue, resolves, heldShut, out);
            }
        }
        checkExtensionQuestRefs(resolves, heldShut, out);
        return out;
    }

    /**
     * One warning per quest in {@code pool} whose {@code CompletionDialogue} no surface on this server can
     * open ({@link QuestCompletionDialogueValidator}, the same question the hand-off asks), filed under the
     * quest family, since the quest is the file to edit.
     */
    @Nonnull
    public static List<Finding> completionFindings(@Nonnull QuestPool pool) {
        return QuestCompletionDialogueValidator.validate(pool);
    }

    // ==================== once per boot ====================

    /**
     * Claim this audit's once-per-boot run for {@code owner}, a consumer that reports the same findings in its
     * own content report, so the library's run stands down and each finding is heard once. Call it from
     * {@code setup()}: the claim is read at the first player ready, which is always later.
     */
    public static void claimLateAudit(@Nonnull String owner) {
        String name = owner.trim();
        if (name.isEmpty()) {
            return;
        }
        lateAuditOwner = name;
    }

    /** Who claimed this audit's run, or {@code null} while this library still owns it. */
    @Nullable
    public static String lateAuditOwner() {
        return lateAuditOwner;
    }

    /**
     * Run and log the audit, once per boot, at the first player ready. Does nothing on later calls; where a
     * consumer claimed the run, logs only who reports the findings.
     */
    public static void runLateAudit() {
        runLateAudit(SafeLog::warn, SafeLog::info);
    }

    /**
     * {@link #runLateAudit()} against caller-supplied sinks: an error to {@code errorSink}, everything else,
     * the line naming a claimant included, to {@code noteSink}.
     */
    static void runLateAudit(@Nullable Consumer<String> errorSink, @Nullable Consumer<String> noteSink) {
        if (!LATE_AUDITED.compareAndSet(false, true)) {
            return;
        }
        String owner = lateAuditOwner;
        if (owner != null) {
            if (noteSink != null) {
                noteSink.accept(LOG_LABEL + " content findings are reported by " + owner);
            }
            return;
        }
        ValidationReport.logAll(LOG_LABEL, audit(), errorSink, noteSink);
    }

    /**
     * The late audit's findings, claimed exactly as {@link #runLateAudit()} claims its one run, for a caller
     * that logs them itself (zc-core's {@code BootAudit}, at the boot event): empty when the run is already
     * spent this boot. Empty too, with the run left unspent, where a consumer claimed it: that consumer
     * reports these findings itself, and the first join still names it.
     */
    @Nonnull
    public static List<Finding> claimLateFindings() {
        if (lateAuditOwner != null || !LATE_AUDITED.compareAndSet(false, true)) {
            return List.of();
        }
        return audit();
    }

    /** Re-arm the late audit and drop any claim, so a test can drive it more than once. */
    static void clearLateAuditForTests() {
        LATE_AUDITED.set(false);
        lateAuditOwner = null;
    }

    // ==================== quest references ====================

    private static void checkQuestRefs(@Nonnull NpcDialogue dialogue, @Nonnull Predicate<String> resolves,
            @Nonnull Predicate<DialogueOption> heldShut, @Nonnull List<Finding> out) {
        String id = dialogue.getId();
        String subject = "Dialogue '" + id + "' ";
        DialogueStart start = dialogue.getStart();
        checkBeats(start.first(), "Start First", subject, id, resolves, out);
        checkBeats(start.then(), "Start Then", subject, id, resolves, out);
        for (String questId : start.quests().keySet()) {
            checkQuestId(questId, subject + "Start quest row", id, resolves, out);
        }
        for (var nodeEntry : dialogue.getNodes().entrySet()) {
            String nodeId = nodeEntry.getKey();
            DialogueNode node = nodeEntry.getValue();
            if (node == null) {
                continue;
            }
            checkConditions(node.getConditions(), subject + "node '" + nodeId + "'", id, resolves, out);
            List<DialogueOption> options = node.getOptions();
            for (int i = 0; i < options.size(); i++) {
                DialogueOption option = options.get(i);
                // A line an extension added is checked once, against its extension, never per host.
                if (option == null || option.isInjected()) {
                    continue;
                }
                checkOption(option, subject + "node '" + nodeId + "' option " + i, id, resolves, heldShut, out);
            }
        }
    }

    private static void checkBeats(@Nonnull List<DialogueStart.Beat> beats, @Nonnull String section,
            @Nonnull String subject, @Nonnull String id, @Nonnull Predicate<String> resolves,
            @Nonnull List<Finding> out) {
        for (DialogueStart.Beat beat : beats) {
            if (beat == null) {
                continue;
            }
            checkConditions(beat.getWhen(), subject + section, id, resolves, out);
            checkActions(beat.getActions(), subject + section, id, resolves, out);
        }
    }

    /** A dialogue extension's lines, each once, against the extension that adds it. */
    private static void checkExtensionQuestRefs(@Nonnull Predicate<String> resolves,
            @Nonnull Predicate<DialogueOption> heldShut, @Nonnull List<Finding> out) {
        for (DialogueExtension extension : DialogueExtensionConfig.getInstance().ordered()) {
            if (extension == null || !extension.isEnabled()) {
                continue;
            }
            String id = extension.getId();
            List<DialogueOption> lines = extension.getOptions();
            for (int i = 0; i < lines.size(); i++) {
                DialogueOption line = lines.get(i);
                if (line != null) {
                    checkOption(line, "Dialogue extension '" + id + "' line " + i, id, resolves, heldShut, out);
                }
            }
        }
    }

    private static void checkOption(@Nonnull DialogueOption option, @Nonnull String where, @Nonnull String id,
            @Nonnull Predicate<String> resolves, @Nonnull Predicate<DialogueOption> heldShut,
            @Nonnull List<Finding> out) {
        if (heldShut(option, heldShut)) {
            return;
        }
        checkConditions(option.getConditions(), where, id, resolves, out);
        checkActions(option.getActions(), where, id, resolves, out);
    }

    /** Whether the caller holds {@code option} shut; a rule that throws holds nothing shut. */
    private static boolean heldShut(@Nonnull DialogueOption option, @Nonnull Predicate<DialogueOption> heldShut) {
        try {
            return heldShut.test(option);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Every quest line in {@code conditions}, through the boolean combinators. */
    private static void checkConditions(@Nullable List<DialogueCondition> conditions, @Nonnull String where,
            @Nonnull String id, @Nonnull Predicate<String> resolves, @Nonnull List<Finding> out) {
        if (conditions == null) {
            return;
        }
        for (DialogueCondition condition : conditions) {
            if (condition instanceof DialogueCondition.Combinator combinator) {
                checkConditions(combinator.getChildren(), where, id, resolves, out);
            } else if (condition instanceof QuestDialogueConditions.QuestRef ref) {
                checkQuestId(ref.getQuest(), where, id, resolves, out);
            }
        }
    }

    private static void checkActions(@Nullable List<DialogueAction> actions, @Nonnull String where,
            @Nonnull String id, @Nonnull Predicate<String> resolves, @Nonnull List<Finding> out) {
        if (actions == null) {
            return;
        }
        for (DialogueAction action : actions) {
            if (action instanceof QuestDialogueActions.QuestRef ref) {
                checkQuestId(ref.getQuest(), where, id, resolves, out);
            }
        }
    }

    /**
     * A quest id a line names, when it names one: a blank id is the structural audit's
     * ({@code QUEST_CONDITION_NO_ID}, {@code QUEST_ACTION_NO_ID}, {@code START_QUEST_NO_ID}).
     */
    private static void checkQuestId(@Nullable String questId, @Nonnull String where, @Nonnull String id,
            @Nonnull Predicate<String> resolves, @Nonnull List<Finding> out) {
        if (questId == null || questId.isBlank() || resolves(questId, resolves)) {
            return;
        }
        out.add(Finding.warning(DialogueStructureValidator.DOMAIN, UNKNOWN_QUEST_ID,
                where + " references unknown quest '" + questId + "'", id));
    }

    /** Whether {@code questId} resolves; a vocabulary that throws cannot tell, which reports nothing. */
    private static boolean resolves(@Nonnull String questId, @Nonnull Predicate<String> resolves) {
        try {
            return resolves.test(questId);
        } catch (Throwable t) {
            return true;
        }
    }

    /** The shared quest runtime's answer, the one an accept and a hand-in read. */
    private static boolean runtimeKnows(@Nonnull String questId) {
        return BookVerbs.quest(questId) != null;
    }

    /** A quest the shared store's mod gate refused is absent by design; an unreadable store refuses nothing. */
    private static boolean modGateRefused(@Nonnull String questId) {
        try {
            return QuestAssetStore.getInstance().modGateRefused(questId);
        } catch (Throwable t) {
            return false;
        }
    }
}
