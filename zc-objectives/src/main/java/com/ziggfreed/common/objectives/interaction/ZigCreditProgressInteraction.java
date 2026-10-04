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
            ProgressCredit credit = ProgressCredit.resolve(kind, target, qualifier, amount, UsedItems.id(ctx));
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
}
