package com.ziggfreed.common.objectives.title;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;

/**
 * What titles put into the display-name seam: the name with the title a player shows placed around
 * it, when that title is on offer. It reads the process-wide record ({@link ActiveTitles}) and the
 * fold only, never an entity store, so a page on any world thread can name any player. It answers
 * the same whether that player is online or not (the record keeps a player who left, across a
 * restart too), so a title never tells a viewer who is online; a player showing nothing and one
 * whose title is switched off or unknown read plain either way.
 */
public final class TitleDisplay {

    private TitleDisplay() {
    }

    /** The decorator the bootstrap fills the seam with. */
    @Nonnull
    public static PlayerDisplayNames.Decorator decorator() {
        return TitleDisplay::decorate;
    }

    @Nullable
    static Message decorate(@Nonnull UUID playerId, @Nonnull String plainName) {
        String shown = ActiveTitles.of(playerId);
        if (shown == null) {
            return null;
        }
        TitleAsset title = TitleConfig.getInstance().shown(shown);
        return title == null ? null : TitleText.display(shown, title, plainName);
    }
}
