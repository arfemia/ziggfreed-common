package com.ziggfreed.common.commerce.fold;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.commerce.page.CurrencyText;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.subject.Subject;

/**
 * The wallet readings every vocabulary on the server resolves, contributed at the library's setup
 * ({@link CommerceDefaults#install}).
 *
 * <p>{@code ziggfreedcommon:currency_spendable} (Param a wallet id): what the context's player can spend of
 * that wallet right now, read through the installed currency engine ({@link CurrencyEngine#balance}): the
 * inventory count of an item-backed wallet, the stored balance of a counter one. Null for an unknown wallet,
 * and where there is no player to ask, so a gate on it stays shut. A lock line names the wallet by its own
 * name ({@link CurrencyText#nameOf}) through a {@link FactorNames.ParamNamer}. A price stays a {@code Cost},
 * charged with its own probe; this reading is for a gate that asks "has this much", never for the charge.
 */
public final class CurrencyFactors {

    /** The owner the readings are contributed under. */
    public static final String OWNER = "ziggfreedcommon";

    /** What the player can spend of the wallet the Param names. */
    public static final String SPENDABLE = "ziggfreedcommon:currency_spendable";

    private CurrencyFactors() {
    }

    /** Claim the readings process-wide and name their Params. Once, at setup. */
    public static void contribute() {
        FactorContributions.register(SPENDABLE, OWNER, CurrencyFactors::spendable);
        FactorNames.registerParamNamer(SPENDABLE, CurrencyFactors::walletName);
    }

    /** What the context's player can spend of the wallet the Param names; null when it cannot be told. */
    @Nullable
    static Double spendable(@Nonnull FactorContext ctx) {
        String id = ctx.param() == null ? null : ctx.param().trim();
        if (id == null || id.isEmpty()) {
            return null;
        }
        CurrencyEngine engine = CommerceDefaults.currencyEngine();
        CurrencyDef def = engine.definition(id);
        Subject subject = def == null ? null : playerOf(ctx);
        return subject == null ? null : (double) engine.balance(subject, def);
    }

    /** The wallet's own name for a lock line, or null to leave the line to the factor's name. */
    @Nullable
    static Message walletName(@Nonnull String param) {
        CurrencyDef def = CommerceDefaults.currencyEngine().definition(param.trim());
        return def == null ? null : CurrencyText.nameOf(def, CurrencyText.AUTHORED_ONLY);
    }

    /** The subject for the context's live player, or null when it carries none. */
    @Nullable
    private static Subject playerOf(@Nonnull FactorContext ctx) {
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> ref = ctx.subject();
        if (store == null || ref == null || !ref.isValid()) {
            return null;
        }
        return Subject.of(store.getComponent(ref, PlayerRef.getComponentType()),
                store.getComponent(ref, Player.getComponentType()));
    }
}
