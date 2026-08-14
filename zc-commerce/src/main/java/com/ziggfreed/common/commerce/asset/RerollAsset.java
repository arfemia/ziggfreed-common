package com.ziggfreed.common.commerce.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * What it costs a player to swap ONE slot of a rotating set for another, and how often they may.
 *
 * <pre>{@code
 * "Reroll": { "Cost": { "Currencies": { "Bounty_Token": 25 } }, "MaxPerPeriod": 3 }
 * }</pre>
 *
 * <p>Authoring this block AT ALL is what makes a set rerollable, so leaving it out means the set
 * stands as drawn until it turns over. An authored block with no {@code Cost} is a FREE reroll,
 * which is a real answer rather than an oversight.
 *
 * <p>The price is the shared {@link CostAsset} group, the same one an offer is priced with, so a
 * reroll payable in two wallets or in items needs nothing new. A terse "one currency and an amount"
 * pair is deliberately absent: one price vocabulary, everywhere.
 *
 * <p>Every leaf is {@code appendInherited}, so a file with a {@code Parent} can raise the price and
 * keep the allowance it did not mention.
 */
public final class RerollAsset {

    @Nullable protected CostAsset cost;
    @Nullable protected Integer maxPerPeriod;

    public static final BuilderCodec<RerollAsset> CODEC =
            BuilderCodec.builder(RerollAsset.class, RerollAsset::new)
                    .appendInherited(new KeyedCodec<>("Cost", CostAsset.CODEC, false),
                            (o, v) -> o.cost = v, o -> o.cost, (o, p) -> o.cost = p.cost)
                    .documentation("What one reroll costs, in the same price group an offer is priced with. "
                            + "Unauthored means free.").add()
                    .appendInherited(new KeyedCodec<>("MaxPerPeriod", Codec.INTEGER, false),
                            (o, v) -> o.maxPerPeriod = v, o -> o.maxPerPeriod,
                            (o, p) -> o.maxPerPeriod = p.maxPerPeriod)
                    .documentation("How many rerolls one player gets before the set turns over. 0 or unauthored "
                            + "means no limit, so pair a free reroll with a number unless you mean it to be "
                            + "endless.").add()
                    .build();

    public RerollAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static RerollAsset of(@Nullable CostAsset cost, @Nullable Integer maxPerPeriod) {
        RerollAsset r = new RerollAsset();
        r.cost = cost;
        r.maxPerPeriod = maxPerPeriod;
        return r;
    }

    /** The authored price, or null when a reroll is free. */
    @Nullable
    public CostAsset getCost() {
        return cost;
    }

    /** The price, never null, so a caller charges the same way whether or not one was authored. */
    @Nonnull
    public CostAsset costOrFree() {
        return cost == null ? CostAsset.FREE : cost;
    }

    /** The per-period allowance; 0 (unlimited) when unauthored or authored negative. */
    public int maxPerPeriod() {
        return maxPerPeriod == null || maxPerPeriod < 0 ? 0 : maxPerPeriod;
    }

    /** The authored allowance exactly as written, for an audit that must see a bad one. */
    @Nullable
    public Integer getMaxPerPeriod() {
        return maxPerPeriod;
    }
}
