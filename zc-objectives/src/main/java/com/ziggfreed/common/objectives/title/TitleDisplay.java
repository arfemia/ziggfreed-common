package com.ziggfreed.common.objectives.title;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;

/**
 * What titles put into the display-name seam: one contribution placing the title a player shows
 * around the name built so far, when that title is on offer. It reads the process-wide record
 * ({@link ActiveTitles}) and the fold only, never an entity store, so a page on any world thread can
 * name any player. It answers the same whether that player is online or not (the record keeps a
 * player who left, across a restart too), so a title never tells a viewer who is online; a player
 * showing nothing and one whose title is switched off or unknown read plain either way.
 */
public final class TitleDisplay {

    /** The owner titles contribute under. */
    public static final String OWNER = "ziggfreedcommon:titles";

    /**
     * Where titles wrap: above the default slot, so a decorator's answer is wrapped too, with room
     * below for a mark that belongs inside the title and above for one that belongs outside it.
     */
    public static final int ORDER = 100;

    private TitleDisplay() {
    }

    /** The contribution the bootstrap adds to the seam, under {@link #OWNER} at {@link #ORDER}. */
    @Nonnull
    public static PlayerDisplayNames.Contribution contribution() {
        return TitleDisplay::wrap;
    }

    /** The same reading as a decorator over the plain name, for a caller filling the seam's default slot. */
    @Nonnull
    public static PlayerDisplayNames.Decorator decorator() {
        return TitleDisplay::decorate;
    }

    @Nullable
    static Message decorate(@Nonnull UUID playerId, @Nonnull String plainName) {
        return wrap(playerId, Msg.raw(plainName));
    }

    /** {@code name} with the title {@code playerId} shows around it, or null when they show none on offer. */
    @Nullable
    static Message wrap(@Nonnull UUID playerId, @Nonnull Message name) {
        String shown = ActiveTitles.of(playerId);
        if (shown == null) {
            return null;
        }
        TitleAsset title = TitleConfig.getInstance().shown(shown);
        return title == null ? null : TitleText.display(shown, title, name);
    }
}
