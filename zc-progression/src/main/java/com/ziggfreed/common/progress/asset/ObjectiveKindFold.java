package com.ziggfreed.common.progress.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns the authored {@link ObjectiveKindAsset}s into registered entries on an
 * {@link ObjectiveKindRegistry}, so a kind described in a file behaves exactly like one described in
 * Java.
 *
 * <h2>It runs LAST, and it MERGES</h2>
 *
 * <p>Call this after every Java registration a consumer makes, once the asset stores have loaded.
 * Unlike a reward kind - which is a command line, so a file either provides one or does not - an
 * objective kind is a handful of independent facts, and a file usually means to state ONE of them.
 * So an authored kind is merged leaf by leaf over whatever is registered under its id: a file that
 * gives a kind a picture keeps the arithmetic the mod that invented it registered, and a file that
 * turns a kind off keeps its picture. A leaf the file does not mention is not an instruction.
 *
 * <p>That is also why this is quiet. Merging is the expected relationship between a file and the
 * code that registered the same id, not a collision worth warning about; the ledger's own "two
 * owners wanted this id" line would be noise on every single kind. A file naming an id NOTHING
 * registered is a new kind and is registered as one, which is how a server adds a step kind its
 * producer already fires.
 */
public final class ObjectiveKindFold {

    /** The owner prefix authored kinds are attributed to in the registry ledger. */
    public static final String OWNER_PREFIX = "objectivekind:";

    /**
     * What one fold did: which ids it merged over an existing registration, and which it added as
     * new kinds. Ids are held as the FILE spells them, because that is what an owner has to find.
     */
    public record Result(@Nonnull List<String> merged, @Nonnull List<String> added) {

        /** A fold that had nothing to do. */
        public static final Result EMPTY = new Result(List.of(), List.of());
    }

    private ObjectiveKindFold() {
    }

    /** Fold every loaded kind asset into {@code kinds}. */
    @Nonnull
    public static Result foldInto(@Nullable ObjectiveKindRegistry kinds) {
        if (kinds == null) {
            return Result.EMPTY;
        }
        Map<String, ObjectiveKindAsset> rows = ObjectiveKindConfig.getInstance().all();
        if (rows.isEmpty()) {
            return Result.EMPTY;
        }

        List<String> merged = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, ObjectiveKindAsset> entry : rows.entrySet()) {
            ObjectiveKindAsset row = entry.getValue();
            if (row == null) {
                continue;
            }
            String authoredId = row.getId() != null ? row.getId() : entry.getKey();
            if (authoredId == null || authoredId.isBlank()) {
                continue;
            }
            String id = authoredId.trim().toUpperCase(Locale.ROOT);
            ObjectiveKind current = kinds.kind(id);
            kinds.registerQuietly(OWNER_PREFIX + authoredId, mergeOver(current, id, row));
            (current == null ? added : merged).add(authoredId);
        }

        if (!added.isEmpty()) {
            SafeLog.info("[progression] " + added.size() + " objective kind(s) added from files: "
                    + String.join(", ", added));
        }
        return new Result(List.copyOf(merged), List.copyOf(added));
    }

    /**
     * One authored file laid over what is registered, leaf by leaf. With nothing registered the
     * file stands alone, and an unmentioned leaf then takes the same default a bare Java
     * registration would have given it.
     */
    @Nonnull
    private static ObjectiveKind mergeOver(@Nullable ObjectiveKind current, @Nonnull String id,
            @Nonnull ObjectiveKindAsset row) {
        boolean valueBased = or(row.getValueBased(), current != null && current.valueBased());
        boolean atMost = or(row.getAtMost(), current != null && current.atMost());
        boolean producible = or(row.getProducible(), current == null || current.producible());

        ObjectiveKindAsset.TargetNames names = row.getTargetNames();
        boolean targetsPlace = or(names == null ? null : names.getPlace(),
                current != null && current.targetsPlace());
        boolean targetsItem = or(names == null ? null : names.getItem(),
                current != null && current.targetsItem());
        boolean targetsEntity = or(names == null ? null : names.getEntity(),
                current != null && current.targetsEntity());
        boolean targetsCurrency = or(names == null ? null : names.getCurrency(),
                current != null && current.targetsCurrency());
        boolean targetsContent = or(names == null ? null : names.getContent(),
                current != null && current.targetsContent());
        boolean targetsBoard = or(names == null ? null : names.getBoard(),
                current != null && current.targetsBoard());
        boolean targetsEncounter = or(names == null ? null : names.getEncounter(),
                current != null && current.targetsEncounter());

        return new ObjectiveKind(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity,
                targetsCurrency, targetsContent, targetsBoard, targetsEncounter, atMost,
                mergePresentation(current == null ? null : current.presentation(), row.getPresentation()));
    }

    /**
     * The authored presentation over the registered one, leaf by leaf, with the target pictures
     * merged per target so a file naming one creature does not drop the rest.
     */
    @Nonnull
    private static ObjectiveKind.Presentation mergePresentation(
            @Nullable ObjectiveKind.Presentation current,
            @Nullable ObjectiveKindAsset.Presentation authored) {
        if (authored == null) {
            return current == null ? ObjectiveKind.Presentation.NONE : current;
        }
        String textKey = authored.getTextKey() != null
                ? authored.getTextKey() : (current == null ? null : current.textKey());
        IconSpec icon = authored.getIcon() != null
                ? authored.getIcon() : (current == null ? null : current.icon());

        Map<String, IconSpec> targets = new LinkedHashMap<>();
        if (current != null) {
            targets.putAll(current.targetIcons());
        }
        targets.putAll(authored.getTargetIcons());
        return new ObjectiveKind.Presentation(textKey, icon, targets);
    }

    private static boolean or(@Nullable Boolean authored, boolean fallback) {
        return authored != null ? authored : fallback;
    }
}
