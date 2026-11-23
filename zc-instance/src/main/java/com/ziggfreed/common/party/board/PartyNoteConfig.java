package com.ziggfreed.common.party.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack} fold of the party board's set notes ({@link PartyNoteAsset}), keyed by note id. No owner
 * file: a note is pack content, switched off or reworded by a same-id file.
 */
public final class PartyNoteConfig extends AbstractKeyedAssetConfig<PartyNoteAsset> {

    private static final PartyNoteConfig INSTANCE = new PartyNoteConfig();

    private PartyNoteConfig() {
    }

    @Nonnull
    public static PartyNoteConfig getInstance() {
        return INSTANCE;
    }

    /** The notes a poster is offered, in order: switched on, with words. */
    @Nonnull
    public List<PartyNoteAsset> offered() {
        List<PartyNoteAsset> out = new ArrayList<>();
        for (Map.Entry<String, PartyNoteAsset> entry : all().entrySet()) {
            PartyNoteAsset note = entry.getValue();
            if (note != null && note.getId() != null && note.offered()) {
                out.add(note);
            }
        }
        out.sort(Comparator.comparingInt(PartyNoteAsset::order)
                .thenComparing(note -> note.getId().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** The offered note of this id (any case), or null. */
    @Nullable
    public PartyNoteAsset offered(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        PartyNoteAsset note = resolve(id.trim());
        return note != null && note.offered() ? note : null;
    }
}
