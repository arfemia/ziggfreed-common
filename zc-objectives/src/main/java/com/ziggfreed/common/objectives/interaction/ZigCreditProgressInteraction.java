package com.ziggfreed.common.objectives.interaction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.objectives.producer.ProgressDispatch;
import com.ziggfreed.common.progress.asset.ProgressEditorDataSets;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * Credits one use of an item to the shared quest and achievement engines, from inside any native
 * interaction chain. Authored beside whatever the use does (a throw, a crack, a costume):
 *
 * <pre>{@code
 * { "Type": "ZigCreditProgress", "Qualifier": "Throw" }
 * }</pre>
 *
 * <p>With nothing else authored it credits {@code USE_ITEM}, names the item the chain is about and
 * counts one; {@code Kind}, {@code Target} and {@code Amount} override each ({@link ProgressCredit}).
 * The moment goes through {@link ProgressDispatch#fire} like any producer's, with no payload, so a
 * reaction that needs a producer's own record never mistakes this for one.
 *
 * <p><b>Always resolves Finished.</b> A chain no player owns, a use that names no item and a kind no
 * step can count are skips, never failures: the throw still throws.
 *
 * <p><b>{@code QualifierFromTarget} (since 2.5.3)</b> makes the credit's qualifier the character the
 * chain hit: its NpcId through {@link NpcIdentities#declaredNpcIdOfEntity} (a placed NPC answers its
 * placement's id, an NPC an identity overlay declares answers that id), so a step reads "give a gift to the
 * miller" as that item's {@code USE_ITEM} qualified by the miller's id. A hit on a player, or on an NPC
 * nothing declares a character (a wild creature), credits nothing. The credit still goes to the chain's
 * owner, the user.
 */
public final class ZigCreditProgressInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigCreditProgress";

    @Nullable
    protected String kind;
    @Nullable
    protected String target;
    @Nullable
    protected String qualifier;
    /** Boxed, so an absent leaf keeps the default instead of failing the decode. */
    @Nullable
    protected Integer amount;
    /** Boxed, so an absent leaf keeps the default (the authored Qualifier) instead of failing the decode. */
    @Nullable
    protected Boolean qualifierFromTarget;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigCreditProgressInteraction> CODEC = BuilderCodec.builder(
                        ZigCreditProgressInteraction.class, ZigCreditProgressInteraction::new,
                        SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Kind", Codec.STRING),
                        (i, v) -> i.kind = v, i -> i.kind, (i, p) -> i.kind = p.kind)
                .metadata(new UIEditor(new UIEditor.Dropdown(ProgressEditorDataSets.OBJECTIVE_KINDS)))
                .metadata(EditorSchema.defaultValue(ProgressCredit.DEFAULT_KIND))
                .documentation("The objective kind one use counts toward. Defaults to USE_ITEM.")
                .add()
                .appendInherited(new KeyedCodec<>("Target", Codec.STRING),
                        (i, v) -> i.target = v, i -> i.target, (i, p) -> i.target = p.target)
                .documentation("What the use names. Defaults to the item this chain is about, even when "
                        + "an earlier step spent the last of the stack.")
                .add()
                .appendInherited(new KeyedCodec<>("Qualifier", Codec.STRING),
                        (i, v) -> i.qualifier = v, i -> i.qualifier, (i, p) -> i.qualifier = p.qualifier)
                .documentation("How the item was used (Throw, Crack, Costume), matched against a step's "
                        + "own Qualifier in any casing. A step with no Qualifier counts every use.")
                .add()
                .appendInherited(new KeyedCodec<>("Amount", Codec.INTEGER),
                        (i, v) -> i.amount = v, i -> i.amount, (i, p) -> i.amount = p.amount)
                .metadata(EditorSchema.defaultValue(1))
                .documentation("How much one use counts for. Defaults to 1; below 1 counts nothing.")
                .add()
                .appendInherited(new KeyedCodec<>("QualifierFromTarget", Codec.BOOLEAN),
                        (i, v) -> i.qualifierFromTarget = v, i -> i.qualifierFromTarget,
                        (i, p) -> i.qualifierFromTarget = p.qualifierFromTarget)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Use the id of the character this chain hit (a Selector's hit) as the "
                        + "Qualifier, in place of the authored one, so a step can ask for this item given to "
                        + "one character. A hit on a player, or on something that is no character, counts "
                        + "nothing. The use still counts for the player using the item.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigCreditProgressInteraction> getCODEC() {
        return Holder.CODEC;
    }

    /** Crediting happens on the server tick alone, or a simulated pass would count the use twice. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            String authoredQualifier = qualifier;
            if (Boolean.TRUE.equals(qualifierFromTarget)) {
                authoredQualifier = targetCharacter(ctx);
                if (authoredQualifier == null) {
                    return true; // A player, or nothing a character answers for: no credit.
                }
            }
            ProgressCredit credit = ProgressCredit.resolve(kind, target, authoredQualifier, amount, UsedItems.id(ctx));
            CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
            Ref<EntityStore> owner = InteractionCtx.owner(ctx);
            if (credit == null || buffer == null || owner == null
                    || InteractionCtx.player(ctx, owner) == null
                    || !ProgressCredit.creditable(ProgressionRuntime.objectiveKinds(), credit.kind())) {
                return true;
            }
            ProgressDispatch.fire(owner.getStore(), owner, buffer, credit.kind(), credit.target(),
                    credit.qualifier(), credit.amount(), null);
            return true;
        });
    }

    /**
     * The NpcId of the character {@code ctx}'s chain hit, or null when it hit nothing, a player, or an
     * entity nothing declares a character (its role name alone is never one). World thread.
     */
    @Nullable
    private static String targetCharacter(@Nonnull InteractionContext ctx) {
        Ref<EntityStore> hit = InteractionCtx.target(ctx);
        if (hit == null || InteractionCtx.player(ctx, hit) != null) {
            return null;
        }
        String npcId = NpcIdentities.declaredNpcIdOfEntity(hit.getStore(), hit);
        return npcId == null || npcId.isBlank() ? null : npcId.trim();
    }
}
