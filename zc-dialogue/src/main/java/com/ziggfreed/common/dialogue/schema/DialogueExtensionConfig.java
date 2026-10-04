package com.ziggfreed.common.dialogue.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The server's dialogue extensions, folded {@code defaults < pack < owner} like every keyed store,
 * plus the one derived view the splice needs: every extension in id order, so two extensions
 * landing on one screen always show their lines in the same order whichever file loaded first.
 *
 * <p>{@link NpcDialogue#spliceFragments()} reads it as each conversation is decoded. A reload of the
 * extension files therefore has to re-splice the conversations already in circulation, which
 * {@code asset.DialogueAssetStore#mergeExtensions} does; folding a layer here alone does not.
 */
public final class DialogueExtensionConfig extends AbstractKeyedAssetConfig<DialogueExtension> {

    private static final DialogueExtensionConfig INSTANCE = new DialogueExtensionConfig();

    /** Every extension, by id. Rebuilt whole by each merge. */
    @Nonnull private volatile List<DialogueExtension> ordered = List.of();

    private DialogueExtensionConfig() {
    }

    @Nonnull
    public static DialogueExtensionConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, DialogueExtension> jarDefaults) {
        super.loadDefaults(jarDefaults);
        reorder();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, DialogueExtension> layer) {
        super.mergePackLayer(layer);
        reorder();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, DialogueExtension> layer) {
        super.mergeOwnerLayer(layer);
        reorder();
    }

    /** True when no extension is loaded at all, so a splice can skip the walk. */
    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /** Every extension, enabled or not, in id order. */
    @Nonnull
    public List<DialogueExtension> ordered() {
        return ordered;
    }

    /** The lines every extension lands on this screen, in extension id order then line order. */
    @Nonnull
    public List<DialogueOption> linesFor(@Nonnull String dialogueId, @Nonnull String nodeId,
                                         @Nonnull Collection<String> nodeTags, boolean opening) {
        List<DialogueExtension> current = ordered;
        if (current.isEmpty()) {
            return List.of();
        }
        List<DialogueOption> out = new ArrayList<>();
        for (DialogueExtension extension : current) {
            if (extension.lands(dialogueId, nodeId, nodeTags, opening)) {
                out.addAll(extension.getOptions());
            }
        }
        return out;
    }

    private void reorder() {
        Map<String, DialogueExtension> all = all();
        List<String> ids = new ArrayList<>(all.keySet());
        Collections.sort(ids);
        List<DialogueExtension> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(all.get(id));
        }
        ordered = List.copyOf(out);
    }
}
