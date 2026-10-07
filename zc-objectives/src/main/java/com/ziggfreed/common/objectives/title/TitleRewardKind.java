package com.ziggfreed.common.objectives.title;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.title.ZigTitleComponent;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.title.command.TitleCommandLine;
import com.ziggfreed.common.subject.Subject;

/**
 * The reward kind that unlocks a title: {@code {"Kind": "Title", "Params": {"Title": "Example_Title"}}}.
 *
 * <p>UNPREFIXED because the library owns the record behind it. A title the player already has is a
 * successful no-op (a repeatable or a retried payout must not fail). With no live player, the one way
 * a grant fails on a live server, {@link #retryCommand} answers the {@code /zigtitle grant} line, so
 * a consumer's retry queue hands it over later; it is null for exactly the specs a grant refuses on
 * its own terms (no id, an id the save format cannot hold).
 */
public final class TitleRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Title";

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /** The parameter naming which title is unlocked. */
    static final String PARAM_TITLE = "title";

    /** The other spelling of that parameter, read the same way. */
    static final String PARAM_TITLE_ID = "titleid";

    private TitleRewardKind() {
    }

    /** Register the title kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new TitleRewardKind());
    }

    /** Is {@code spec} a reward of this kind? The kind id matches without regard to case, as a grant matches it. */
    public static boolean isTitleReward(@Nullable RewardSpec spec) {
        return spec != null && KIND.equalsIgnoreCase(spec.kind());
    }

    /** Which title {@code spec} unlocks, in either spelling, trimmed; empty when it names none. */
    @Nonnull
    public static String titleOf(@Nonnull RewardSpec spec) {
        String current = spec.paramOr(PARAM_TITLE, "").trim();
        return current.isEmpty() ? spec.paramOr(PARAM_TITLE_ID, "").trim() : current;
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        String titleId = titleOf(spec);
        if (titleId.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND
                    + "' named no title; it needs a 'Title' parameter");
        }
        if (ZigTitleComponent.usesReservedDelimiter(titleId)) {
            throw new IllegalStateException("'" + titleId + "' is not a usable title id: it carries"
                    + " '|' or ':', which the per-player save format reserves");
        }
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            throw new IllegalStateException("no live player to unlock the title '" + titleId + "' for");
        }
        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            throw new IllegalStateException("the player unlocking the title '" + titleId
                    + "' has no live reference on their entity");
        }
        TitleUnlocks.Outcome outcome = TitleUnlocks.unlock(store, ref, playerRef, titleId);
        if (outcome == TitleUnlocks.Outcome.NO_RECORD) {
            throw new IllegalStateException("the player carries no " + ZigTitleComponent.REGISTRY_ID
                    + " record to unlock the title '" + titleId + "' on; the component did not register,"
                    + " or was not attached at connect");
        }
        if (outcome == TitleUnlocks.Outcome.REFUSED) {
            throw new IllegalStateException("the title id '" + titleId + "' was refused at the write");
        }
    }

    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) {
        String titleId = titleOf(spec);
        if (titleId.isEmpty() || ZigTitleComponent.usesReservedDelimiter(titleId)) {
            return null;
        }
        return TitleCommandLine.grant(subject.name(), titleId);
    }
}
