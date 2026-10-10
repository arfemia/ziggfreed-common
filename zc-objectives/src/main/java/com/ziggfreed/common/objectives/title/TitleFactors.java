package com.ziggfreed.common.objectives.title;

import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;

/**
 * The two title readings, contributed to every vocabulary on the server, so a dialogue line, a shop
 * offer or a quest can ask about a player's titles. {@code Param} names the title (its id, any case):
 * <ul>
 *   <li>{@value #EARNED}: 1 when the player has earned it, else 0;</li>
 *   <li>{@value #SHOWN}: 1 when it is the title the player shows, else 0.</li>
 * </ul>
 * Both read the process-wide record ({@link ActiveTitles}), never the player's entity, so a title
 * switched off or unknown still reads as held. Null (fail closed) for a blank {@code Param} or a
 * question about no player. Every authored condition carries a {@code Min}.
 */
public final class TitleFactors {

    public static final String OWNER = "ziggfreedcommon";

    public static final String EARNED = "ziggfreedcommon:title_earned";

    public static final String SHOWN = "ziggfreedcommon:title_shown";

    private TitleFactors() {
    }

    /** Claim both ids process-wide. Once, at library setup. */
    public static void contribute() {
        FactorContributions.register(EARNED, OWNER, ctx -> earned(playerIdOf(ctx), ctx.param()));
        FactorContributions.register(SHOWN, OWNER, ctx -> shown(playerIdOf(ctx), ctx.param()));
    }

    @Nullable
    static Double earned(@Nullable UUID playerId, @Nullable String param) {
        String titleId = titleIdOf(param);
        if (titleId == null || playerId == null) {
            return null;
        }
        return ActiveTitles.earned(playerId).contains(titleId) ? 1.0 : 0.0;
    }

    @Nullable
    static Double shown(@Nullable UUID playerId, @Nullable String param) {
        String titleId = titleIdOf(param);
        if (titleId == null || playerId == null) {
            return null;
        }
        return titleId.equals(ActiveTitles.of(playerId)) ? 1.0 : 0.0;
    }

    /** The player the question is about, or null when it is about no live player. World thread, like the context. */
    @Nullable
    private static UUID playerIdOf(@Nonnull FactorContext ctx) {
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (!ctx.hasLiveSubject() || store == null || subject == null) {
            return null;
        }
        PlayerRef playerRef = store.getComponent(subject, PlayerRef.getComponentType());
        return playerRef == null ? null : playerRef.getUuid();
    }

    @Nullable
    private static String titleIdOf(@Nullable String param) {
        return param == null || param.isBlank() ? null : param.trim().toLowerCase(Locale.ROOT);
    }
}
