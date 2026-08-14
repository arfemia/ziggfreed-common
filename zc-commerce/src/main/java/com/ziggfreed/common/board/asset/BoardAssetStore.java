package com.ziggfreed.common.board.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * Holds the loaded contracts - every {@link BountyAsset} - and folds them into runnable definitions
 * on demand.
 *
 * <p>The layer is rebuilt WHOLESALE from each load event, so a hot re-import is idempotent. Folding
 * is a separate call because it is the moment the contract POLICY is stamped on, and a consumer wants
 * the folded set exactly once, when it hands its progression the whole catalogue.
 *
 * <pre>{@code
 * Map<String, QuestDefinition> contracts = BoardAssetStore.getInstance().resolveAll();
 * }</pre>
 *
 * <p>The store is process-wide because the ASSETS are: one folder, one set of files, however many
 * mods post them. Two FILES landing on one id is reported, naming the id, because in that case one of
 * them simply never exists - and an id is what a player's progress is filed under, so the loser takes
 * its progress with it.
 *
 * <p>Contracts are already whole when they arrive: the engine's own asset loading resolved their
 * {@code Parent} chains as the files were read, so a child that retunes one step is settled before
 * anything here sees it.
 */
public final class BoardAssetStore {

    private static final BoardAssetStore INSTANCE = new BoardAssetStore();

    @Nonnull
    public static BoardAssetStore getInstance() {
        return INSTANCE;
    }

    /** What a fold produced, and everything worth reporting about it. */
    public record Resolution(@Nonnull Map<String, QuestDefinition> contracts,
                             @Nonnull List<Finding> issues) {

        public Resolution {
            contracts = Map.copyOf(contracts);
            issues = List.copyOf(issues);
        }
    }

    private final Map<String, BountyAsset> bounties = new ConcurrentHashMap<>();
    /** What the last {@link #merge} noticed about the layer itself, replayed by every fold. */
    private final List<Finding> layerFindings = new ArrayList<>();

    private BoardAssetStore() {
    }

    /** Rebuild the contract layer from a load event's decoded assets. Idempotent on re-import. */
    public synchronized void merge(@Nonnull Map<String, BountyAsset> layer) {
        bounties.clear();
        layerFindings.clear();
        for (Map.Entry<String, BountyAsset> e : layer.entrySet()) {
            BountyAsset asset = e.getValue();
            if (e.getKey() == null || asset == null) {
                continue;
            }
            String id = asset.getId() == null || asset.getId().isBlank()
                    ? e.getKey().toLowerCase(Locale.ROOT)
                    : asset.getId().toLowerCase(Locale.ROOT);
            BountyAsset clash = bounties.put(id, asset);
            if (clash != null) {
                layerFindings.add(Finding.error(BoardValidator.DOMAIN, "DUPLICATE_BOUNTY_ID",
                        "two files both resolve to the contract id '" + id + "', so only one of them exists. "
                                + "An id is what a player's progress is filed under, so rename one of them", id));
            }
        }
    }

    /** Unmodifiable view of the loaded contracts, keyed by id (skeletons included). */
    @Nonnull
    public Map<String, BountyAsset> assets() {
        return Collections.unmodifiableMap(bounties);
    }

    /** The folded contracts, findings logged. */
    @Nonnull
    public Map<String, QuestDefinition> resolveAll() {
        Resolution resolution = resolve();
        logIssues(resolution.issues());
        return resolution.contracts();
    }

    /**
     * Fold every postable contract, with the contract policy stamped on. Skeletons are left out:
     * they exist to be inherited from, never to be posted.
     */
    @Nonnull
    public Resolution resolve() {
        List<Finding> issues = new ArrayList<>(layerFindings);
        Map<String, QuestDefinition> out = new LinkedHashMap<>();

        List<String> authoredIds = new ArrayList<>(bounties.keySet());
        Collections.sort(authoredIds);
        for (String id : authoredIds) {
            BountyAsset asset = bounties.get(id);
            if (asset == null || asset.isAbstract()) {
                continue;
            }
            out.put(id, asset.toDefinition(null));
        }
        return new Resolution(out, issues);
    }

    /** Log a fold's findings: an error as a warning line, anything else at info. */
    public static void logIssues(@Nonnull List<Finding> issues) {
        ValidationReport.logAll("[commerce] board content", issues, SafeLog::warn, SafeLog::info);
    }
}
