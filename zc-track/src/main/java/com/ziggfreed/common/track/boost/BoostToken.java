package com.ziggfreed.common.track.boost;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * The numbers a boost token's stack carries, under the {@value #KEY} metadata key: which boost it starts, and in place
 * of the boost's own its percent, its length and the one track it lifts. A token item whose chain runs
 * {@code ZigBoost} reads them off the stack in hand over the interaction's own leaves, so one item id stands for a
 * token of any size: a mod mints one with {@link #mint} (an admin's give, a reward), and two tokens with the same
 * numbers carry the same document and stack.
 *
 * <p>A stack with no document (the item taken from the creative menu) starts what its interaction names. Only the
 * leaves a token writes are written, so a token of the boost's own numbers is just its {@code Boost}.
 */
public final class BoostToken {

    /** The metadata document's key on the stack. */
    public static final String KEY = "ZigBoost";

    @Nullable private String boost;
    @Nullable private Double percent;
    @Nullable private Long seconds;
    @Nullable private String track;

    public static final BuilderCodec<BoostToken> CODEC = BuilderCodec.builder(BoostToken.class, BoostToken::new)
            .append(new KeyedCodec<>("Boost", Codec.STRING), (t, v) -> t.boost = v, t -> t.boost)
            .documentation("The boost the token starts, by id (a file under Server/ZiggfreedCommon/Boosts).").add()
            .append(new KeyedCodec<>("Percent", Codec.DOUBLE), (t, v) -> t.percent = v, t -> t.percent)
            .documentation("How much it lifts by, as a percent, in place of the boost's own. Absent for the "
                    + "boost's own.").add()
            .append(new KeyedCodec<>("Seconds", Codec.LONG), (t, v) -> t.seconds = v, t -> t.seconds)
            .documentation("How long it runs, in seconds, in place of the boost's own. Absent for the boost's "
                    + "own.").add()
            .append(new KeyedCodec<>("Track", Codec.STRING), (t, v) -> t.track = v, t -> t.track)
            .documentation("The one track it lifts, out of those the boost lifts. Absent for all of them.").add()
            .build();

    public static final KeyedCodec<BoostToken> KEYED_CODEC = new KeyedCodec<>(KEY, CODEC);

    public BoostToken() {
    }

    /**
     * A token starting {@code boost} at {@code percent} for {@code seconds}, lifting only {@code track}; each of the
     * three null for the boost's own.
     */
    public BoostToken(@Nonnull String boost, @Nullable Double percent, @Nullable Long seconds,
            @Nullable String track) {
        this.boost = BoostAsset.blankToNull(boost);
        this.percent = percent != null && Double.isFinite(percent) ? percent : null;
        this.seconds = seconds != null && seconds > 0L ? seconds : null;
        this.track = BoostAsset.blankToNull(track);
    }

    /** The boost it starts, trimmed, or null when the document names none. */
    @Nullable
    public String boost() {
        return BoostAsset.blankToNull(boost);
    }

    /** Its percent in place of the boost's own, or null for the boost's own. */
    @Nullable
    public Double percent() {
        return percent != null && Double.isFinite(percent) ? percent : null;
    }

    /** Its length in seconds in place of the boost's own, or null for the boost's own. */
    @Nullable
    public Long seconds() {
        return seconds != null && seconds > 0L ? seconds : null;
    }

    /** The one track it lifts, trimmed, or null for all its boost lifts. */
    @Nullable
    public String track() {
        return BoostAsset.blankToNull(track);
    }

    /** The token {@code stack} carries, or null when it carries none (or one that will not read). */
    @Nullable
    public static BoostToken of(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        try {
            BoostToken token = stack.getFromMetadataOrNull(KEYED_CODEC);
            return token == null || token.boost() == null ? null : token;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /**
     * {@code quantity} of the item {@code itemId} carrying {@code token}, its tooltip saying what using it starts
     * ({@link #description}) when the boost is on this server.
     */
    @Nonnull
    public static ItemStack mint(@Nonnull String itemId, @Nonnull BoostToken token, int quantity) {
        ItemStack stack = new ItemStack(itemId, Math.max(1, quantity)).withMetadata(KEYED_CODEC, token);
        Message description = description(token);
        return description == null ? stack
                : stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, new ItemDisplayMetadata(null, description));
    }

    /** What using {@code token} starts, as its tooltip reads; null when its boost is not on this server. */
    @Nullable
    public static Message description(@Nonnull BoostToken token) {
        BoostAsset live = BoostConfig.getInstance().live(token.boost());
        if (live == null) {
            return null;
        }
        Double percent = token.percent();
        Long seconds = token.seconds();
        return BoostText.line("token.description", BoostText.rewardLine(live,
                percent == null ? live.percent() : percent, seconds == null ? live.seconds() : seconds,
                token.track()));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BoostToken that && Objects.equals(boost(), that.boost())
                && Objects.equals(percent(), that.percent()) && Objects.equals(seconds(), that.seconds())
                && Objects.equals(track(), that.track());
    }

    @Override
    public int hashCode() {
        return Objects.hash(boost(), percent(), seconds(), track());
    }

    @Override
    public String toString() {
        return "BoostToken{boost=" + boost() + ", percent=" + percent() + ", seconds=" + seconds() + ", track="
                + track() + "}";
    }
}
