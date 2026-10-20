package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.objectives.book.ObjectiveBookMenu;
import com.ziggfreed.common.objectives.questlist.NpcQuestPages;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.MetaGlyph;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.RowAccent;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.page.PageSection;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Quests part: the quests this character gives, read where every surface reads a giver's offers
 * ({@code NpcOfferProviders} over the character's whole answer set, the quest engine's own giver index), each in
 * whatever state the player has it. Its button opens the character's own quest list on that quest, where it is taken,
 * handed in and collected: the trainer page keeps no second copy of those verbs.
 */
final class QuestOffers {

    private QuestOffers() {
    }

    /** The quests the trainer's character gives; null when it gives none or the quests cannot be read. */
    @Nullable
    static TrainerOffers offers(@Nonnull TrainerAt at, @Nullable PageSection entry) {
        String npcId = at.npcId();
        if (npcId == null || !ProgressionRuntime.isBuilt()) {
            return null;
        }
        Subject subject;
        Set<String> answersTo;
        List<NpcOffer> offers;
        try {
            subject = ProgressionRuntime.subjects().questSubject(at.store(), at.ref());
            if (subject == null) {
                return null;
            }
            answersTo = NpcQuestPages.resolvedDeps().answerSetOrOwn(npcId);
            offers = NpcOfferProviders.offersAt(subject, answersTo);
        } catch (Throwable t) {
            SafeLog.warn("[trainer] the quests at a trainer could not be read: " + t.getMessage());
            return null;
        }
        if (offers.isEmpty()) {
            return null;
        }
        List<TrainerEntry> entries = new ArrayList<>(offers.size());
        for (NpcOffer offer : offers) {
            entries.add(entry(offer, status(subject, offer.id())));
        }
        return new TrainerOffers(TrainerText.line("part.quests"), entries);
    }

    /** One quest's row and page; its button opens the character's quest list on it. */
    @Nonnull
    private static TrainerEntry entry(@Nonnull NpcOffer offer, @Nullable QuestStatus status) {
        String questId = offer.id();
        Message title = ProgressionTexts.titleOrUntitled(questId);
        Tone tone = tone(offer, status);
        Message word = word(offer, status);
        Picture picture = Picture.item(ObjectiveBookMenu.QUESTS_ICON);
        LedgerRow row = new LedgerRow(questId, title, TrainerText.line("quest.meta"), picture, tone, word, null, null,
                Mark.NONE, status == QuestStatus.COMPLETED, MetaGlyph.SCROLL, RowAccent.TONE);
        DetailAction open = new DetailAction(ActionSlot.PRIMARY, TrainerText.line("quest.action"), ActionLook.NORMAL,
                "quest", null, true, null);
        DetailView page = new DetailView(picture, title, TrainerText.line("quest.meta"), null,
                List.of(Pill.of(word, tone)), null, null, null, ProgressionTexts.flavor(questId), List.of(),
                List.of(open), TrainerText.line("quest.hint"));
        return TrainerEntry.fromAnywhere(row, page, at -> open(at, questId));
    }

    /** Open the character's quest list with {@code questId} singled out. */
    @Nonnull
    private static TrainerResult open(@Nonnull TrainerAt at, @Nonnull String questId) {
        if (at.npcId() != null && NpcQuestPages.open(at.npcId(), questId, at.store(), at.ref(), at.player())) {
            return TrainerResult.TAKEN;
        }
        return TrainerResult.toast(ToastKind.WARNING, TrainerText.line("quest.refused"));
    }

    @Nullable
    private static QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
        try {
            return ProgressionRuntime.quests().status(subject, questId);
        } catch (Throwable t) {
            SafeLog.fine("[trainer] could not read a quest's state: " + t.getMessage());
            return null;
        }
    }

    @Nonnull
    private static Tone tone(@Nonnull NpcOffer offer, @Nullable QuestStatus status) {
        if (offer.available()) {
            return Tone.AVAILABLE;
        }
        if (status == null) {
            return Tone.BLOCKED;
        }
        return switch (status) {
            case ACTIVE -> Tone.ACTIVE;
            case COMPLETED_UNCLAIMED -> Tone.COLLECT;
            case COMPLETED -> Tone.DONE;
            case ON_COOLDOWN -> Tone.WAITING;
            default -> Tone.BLOCKED;
        };
    }

    @Nonnull
    private static Message word(@Nonnull NpcOffer offer, @Nullable QuestStatus status) {
        if (offer.available()) {
            return TrainerText.line("quest.state.available");
        }
        if (status == null) {
            return TrainerText.line("quest.state.locked");
        }
        return switch (status) {
            case ACTIVE -> TrainerText.line("quest.state.active");
            case COMPLETED_UNCLAIMED -> TrainerText.line("quest.state.collect");
            case COMPLETED -> TrainerText.line("quest.state.done");
            case ON_COOLDOWN -> TrainerText.line("quest.state.waiting");
            default -> TrainerText.line("quest.state.locked");
        };
    }
}
