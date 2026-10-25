package com.ziggfreed.common.grant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;

/**
 * The families a grant belongs to, read off the asset it raises (R858: which grants a world switch takes away is a
 * tag on the asset, never a list in code). A stat names its families in its own native {@code Tags}, so any mod's
 * stat joins one with a line in its own file and nothing of anyone else's:
 *
 * <pre>{@code
 * "Tags": { "ZigGrantFamily": [ "Combat" ] }
 * }</pre>
 *
 * <p>A world rule may switch a grant off by its kind ({@code Stat}) or by any family it belongs to: zc-track's
 * {@code Grants.Kinds} reads both words, a family word written there deciding over the kind's (R895). The library
 * tags its own damage, resistance, weak-spot and fall-landing stats {@value #COMBAT}; a consumer tags its ability
 * stats {@value #ABILITY}. A stat a mod registers from Java
 * carries the tag the same way, decoded through the stat store's own codec from a document that writes it
 * ({@code AssetStore.decode}), since a stat built with the engine's constructor holds no asset data and so no tags.
 *
 * <p>Read live off the loaded stat (its {@code Parent}'s tags merged in, as the engine merges them), so a pack that
 * retags a stat is read at the next grant rebuild. A stat the server does not have belongs to no family.
 */
public final class GrantFamilies {

    /** The stat tag naming the families a grant on the stat belongs to. */
    public static final String TAG = "ZigGrantFamily";

    /** The family the library's own combat stats carry: what a hit deals or takes. */
    public static final String COMBAT = "Combat";

    /** The family an ability's own stats carry (its reach, its cost, its wait): a world's ability switch. */
    public static final String ABILITY = "Ability";

    private GrantFamilies() {
    }

    /**
     * The families the stat {@code statId} names in its {@value #TAG} tag, as written; empty when it names none, the
     * stat is not loaded, or the stat store cannot be read (a unit-test JVM).
     */
    @Nonnull
    public static List<String> ofStat(@Nullable String statId) {
        if (statId == null || statId.isBlank()) {
            return List.of();
        }
        int index = StatIndexCache.resolve(statId.trim());
        if (index == StatIndexCache.UNRESOLVED) {
            return List.of();
        }
        try {
            EntityStatType type = EntityStatType.getAssetMap().getAsset(index);
            AssetExtraInfo.Data data = type == null ? null : EntityStatType.CODEC.getData(type);
            return read(data == null ? null : data.getRawTags());
        } catch (Throwable t) {
            SafeLog.fine("[grant] a stat's grant families could not be read: " + t.getMessage());
            return List.of();
        }
    }

    /**
     * The families {@code rawTags} names under {@value #TAG}, trimmed, blanks left out, in written order (pure: the
     * seam a unit test drives). Empty when the tags are null or write no {@value #TAG}.
     */
    @Nonnull
    public static List<String> read(@Nullable Map<String, String[]> rawTags) {
        String[] values = rawTags == null ? null : rawTags.get(TAG);
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return List.copyOf(out);
    }
}
