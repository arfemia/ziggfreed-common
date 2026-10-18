package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The {@code Reward_Chest} reward kind and the collector it pays into: "a reward chest stands here for whoever this
 * pass is about". {@code {"Kind": "Reward_Chest", "Params": {"Chest": "Zc_Reward_Chest"}}}.
 *
 * <p>A pass that can stand a chest where its moment happened (zc-objectives' library bonus rows on a kill and a
 * break) builds one {@code ChestSite}, layers it on the subject it grants through
 * ({@code subject.withFacets(site)}), rolls, and then stands the chest {@link #first} names where the kill or the
 * break was, for the player the moment credited. The chest is a reward chest file (zc-instance's
 * {@code Server/ZiggfreedCommon/RewardChests/}); what it holds is that file's own {@code Contents}, rolled for the
 * player as it goes up, and nothing of this pass. It is a framework kind ({@link CollectingRewardKind}): registered
 * once into {@link RewardKinds#shared()}, it finds the pass's collector on the subject at any depth, a nested table
 * included, and anywhere no pass carries one (a quest, a boss's loot, a consumer's own bonus table) it pays nothing,
 * loudly, counted lost.
 *
 * <table>
 *   <caption>Parameters</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code Chest}</td><td>The reward chest file to stand, by id. Default
 *       {@value #DEFAULT_CHEST}.</td></tr>
 * </table>
 *
 * <p>A pass stands one chest: several {@code Reward_Chest} rewards naming one file collect it once, and the pass
 * stands the first file collected ({@link #first}); a second file it collected is the pass's to report.
 *
 * <p>World-thread only, like the pass it collects for.
 */
public final class ChestSite {

    /** The kind id content writes. Unprefixed: a framework kind. */
    public static final String KIND = "Reward_Chest";

    /** The one parameter the kind reads, as the lower-cased key {@link RewardSpec} stores. */
    public static final String P_CHEST = "chest";

    /** The chest a reward that names none stands: the library's neutral reward chest. */
    public static final String DEFAULT_CHEST = "Zc_Reward_Chest";

    /** The one handler, so registering twice keeps the existing entry and its failure history. */
    private static final CollectingRewardKind<ChestSite> HANDLER =
            CollectingRewardKind.of(KIND, ChestSite.class, (site, spec) -> site.add(chestOf(spec)));

    /** The chest files this pass collected, in order, each once (matched without regard to case). */
    private final List<String> chests = new ArrayList<>();

    /** A collector for one pass. */
    public ChestSite() {
    }

    /** Register the kind into {@code kinds}, attributed to the framework. Idempotent. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, LootRewardKinds.OWNER, HANDLER);
    }

    /** Every parameter key this kind reads, for a validator that wants to warn about a typo. */
    @Nonnull
    public static Map<String, List<String>> parameterKeys() {
        return Map.of(KIND, List.of(P_CHEST));
    }

    /** Every chest file this pass collected, in the order collected, each once; empty when none. */
    @Nonnull
    public List<String> chests() {
        return List.copyOf(chests);
    }

    /** The first chest file this pass collected, the one it stands, or null when it collected none. */
    @Nullable
    public String first() {
        return chests.isEmpty() ? null : chests.get(0);
    }

    private void add(@Nonnull String chest) {
        for (String held : chests) {
            if (held.equalsIgnoreCase(chest)) {
                return;
            }
        }
        chests.add(chest);
    }

    /** A reward's {@code Chest}: as written, trimmed, else {@link #DEFAULT_CHEST}. */
    @Nonnull
    static String chestOf(@Nonnull RewardSpec spec) {
        String written = spec.param(P_CHEST);
        return written == null || written.isBlank() ? DEFAULT_CHEST : written.trim();
    }
}
