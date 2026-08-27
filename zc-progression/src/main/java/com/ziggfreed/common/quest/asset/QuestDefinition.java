package com.ziggfreed.common.quest.asset;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.ziggfreed.common.progress.ContentText;
import com.ziggfreed.common.progress.ObjectiveComposer;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ContentListingAsset.ChainMembership;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestTurnInSite;

/**
 * A folded quest: the {@link Quest} the engine runs, plus everything an authoring layer carries
 * that the engine deliberately has no opinion about - what the player reads, where it is listed,
 * who offers it, and what has to be true before it may be taken.
 *
 * <p>Splitting it this way is what keeps the engine free of presentation and free of any gate
 * vocabulary: it is handed {@link #quest()} and nothing else, while a consumer's UI reads the keys
 * here and the shared {@code RequiresGates} reads {@link #requires()}.
 *
 * @param id               the quest id, lower-cased (the asset filename, or the generated id)
 * @param quest            the engine's runnable definition
 * @param titleKey         localization key for the name, or null
 * @param flavorKey        localization key for the description, or null
 * @param displayName      a plain fallback name for a quest whose key is not written yet, or null
 * @param titleArgs        what fills the title key's numbered slots, in order
 * @param flavorArgs       what fills the flavor key's numbered slots, in order
 * @param category         free grouping label, or null
 * @param sortOrder        lower sorts first within a category
 * @param chains           the ladders this is a rung of, in authored order; the first is primary
 * @param icon             an item id to illustrate it with, or null
 * @param npcViewId        who offers the quest, or null
 * @param turnInNpcId      where it is handed in, sentinel already resolved, or null for anywhere
 * @param completionDialogue the conversation that follows this quest settling at a character, or
 *                         null when it names none
 * @param requires         what must be true first; never null, {@link GateSpec#OPEN} when open
 * @param objectiveTextKeys objective id to its localization key, for the steps that carry one
 * @param resetsOnComplete quest ids wiped when this one finishes
 * @param generatedBy      the generator that produced it, or null when it was authored by hand
 * @param meta             per-namespace extra facts, verbatim; see {@link ContentMeta}
 */
public record QuestDefinition(@Nonnull String id, @Nonnull Quest quest, @Nullable String titleKey,
                              @Nullable String flavorKey, @Nullable String displayName,
                              @Nonnull List<String> titleArgs, @Nonnull List<String> flavorArgs,
                              @Nullable String category, int sortOrder,
                              @Nonnull List<ChainMembership> chains, @Nullable String icon,
                              @Nullable String npcViewId,
                              @Nullable String turnInNpcId, @Nullable String completionDialogue,
                              @Nonnull GateSpec requires,
                              @Nonnull Map<String, String> objectiveTextKeys,
                              @Nonnull List<String> resetsOnComplete,
                              @Nullable String generatedBy,
                              @Nonnull Map<String, JsonElement> meta) {

    public QuestDefinition {
        titleArgs = List.copyOf(titleArgs);
        flavorArgs = List.copyOf(flavorArgs);
        chains = List.copyOf(chains);
        objectiveTextKeys = Map.copyOf(objectiveTextKeys);
        resetsOnComplete = List.copyOf(resetsOnComplete);
        meta = Map.copyOf(meta);
        // The engine object carries what the SHARED parts need to answer for this quest without
        // ever seeing this record: one gate reads the requirement block off it, one text source
        // reads the words, one offer provider reads the giver. Stamped here, at the one place every
        // folded definition passes through, so a definition built by a generator or by a consumer's
        // own adapter cannot arrive without them.
        quest = quest.withAuthoring(requires,
                textOf(quest, titleKey, flavorKey, displayName, titleArgs, flavorArgs,
                        objectiveTextKeys),
                npcViewId, sortOrder, category, icon);
    }

    /** The words this quest carries, as the shared runtime object holds them. */
    @Nonnull
    private static ContentText textOf(@Nonnull Quest quest, @Nullable String titleKey,
            @Nullable String flavorKey, @Nullable String displayName,
            @Nonnull List<String> titleArgs, @Nonnull List<String> flavorArgs,
            @Nonnull Map<String, String> objectiveTextKeys) {
        long amount = quest.objectives().isEmpty() ? 0L : quest.objectives().get(0).amount();
        ContentText.Builder text = ContentText.builder()
                .titleKey(titleKey)
                .displayName(displayName)
                .titleArgs(ContentText.amountArgs(titleArgs, amount))
                .flavorKey(flavorKey)
                .flavorArgs(ContentText.amountArgs(flavorArgs, amount));
        for (Map.Entry<String, String> entry : objectiveTextKeys.entrySet()) {
            text.objectiveKey(entry.getKey(), entry.getValue());
        }
        // EVERY step also carries a composed line, stamped LAZILY: a consumer's composer is
        // installed during its startup, which can be after this fold runs, and the library's own
        // neutral sentence family answers where nothing richer is installed. The authored key stays
        // on the group above as the fallback rung, and the composer receives it too, so the
        // author's own line is resolved WITH its arguments rather than painted with bare slots.
        for (ObjectiveDef objective : quest.objectives()) {
            String authoredKey = objectiveTextKeys.get(objective.id());
            text.objectiveLine(objective.id(), () -> ObjectiveComposer.line(objective, authoredKey));
        }
        return text.build();
    }

    /**
     * The block one namespace authored under {@code Meta}, exactly as written, or null when this
     * quest carries none for it.
     */
    @Nullable
    public JsonElement meta(@Nonnull String namespace) {
        return ContentMeta.block(meta, namespace);
    }

    /** Was this quest produced by a generator rather than authored as its own file? */
    public boolean isGenerated() {
        return generatedBy != null;
    }

    /**
     * Where the finished quest may be collected, or null for anywhere. It lives on {@link #quest()}
     * rather than beside it, because unlike the rest of what this record carries the ENGINE enforces
     * it; this is the read for a surface or an audit that has the folded definition in hand.
     */
    @Nullable
    public QuestTurnInSite turnInAt() {
        return quest.turnInAt();
    }

    /**
     * This definition with a different collection site, for a consumer whose content declares one by
     * POLICY rather than per file - a whole family collected wherever it was taken, say. It is the
     * one-line stamp such an adapter applies as it folds, and it needs no authored leaf, which is
     * what keeps the policy out of every one of those files.
     */
    @Nonnull
    public QuestDefinition withTurnInAt(@Nullable QuestTurnInSite site) {
        return new QuestDefinition(id, quest.withTurnInAt(site), titleKey, flavorKey, displayName,
                titleArgs, flavorArgs, category, sortOrder, chains, icon, npcViewId, turnInNpcId,
                completionDialogue, requires, objectiveTextKeys, resetsOnComplete, generatedBy, meta);
    }

    /** The localization key for one step's line, or null when it authored none. */
    @Nullable
    public String objectiveTextKey(@Nullable String objectiveId) {
        return objectiveId == null ? null : objectiveTextKeys.get(objectiveId);
    }
}
