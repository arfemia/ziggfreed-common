package com.ziggfreed.common.npc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/**
 * Credit a conversation from an NPC's own behaviour instead of from a dialogue: the escape hatch for
 * a character that has no conversation to put a {@code MarkTalked} beat in.
 *
 * <p>Registered as {@code "ZigTalkCredit"} ({@link NpcActions#registerTalkCredit()}) and referenced
 * from a role's {@code InteractionInstruction}:
 * {@code { "Type": "ZigTalkCredit", "Npc": "blacksmith" }}. It is the direct analogue of the
 * engine's own {@code {"Type":"CompleteTask"}} NPC action, which is how first-party content credits
 * an objective from inside a role's behaviour tree.
 *
 * <p>{@code Npc} is REQUIRED and a blank one credits nothing. That asymmetry with a dialogue beat is
 * deliberate: a conversation always knows who it is with, so an author never has to say; a role does
 * not, and guessing here would turn every press-F on every NPC using the role into a credit. Whatever
 * id is named is expanded through {@link NpcIdentities} exactly as a conversation's beat is, so the
 * alias set, the re-trigger window and every registered sink behave identically either way.
 */
public class ActionTalkCredit extends ActionBase {

    /** The character id to credit. Blank is a refusal, never a guess. */
    @Nonnull
    protected final String npc;

    /** An optional secondary label passed through to whoever counts the conversation. */
    @Nullable
    protected final String qualifier;

    public ActionTalkCredit(@Nonnull BuilderActionTalkCredit builder, @Nonnull BuilderSupport support) {
        super(builder);
        String n = builder.getNpc(support);
        this.npc = n == null ? "" : n.trim();
        String q = builder.getQualifier(support);
        this.qualifier = (q == null || q.isBlank()) ? null : q.trim();
    }

    @Override
    public boolean canExecute(
            @Nonnull Ref<EntityStore> ref, @Nonnull Role role, @Nullable InfoProvider sensorInfo, double dt,
            @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, role, sensorInfo, dt, store)
                && role.getStateSupport().getInteractionIterationTarget() != null;
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull Role role, @Nullable InfoProvider sensorInfo,
            double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, role, sensorInfo, dt, store);

        if (npc.isEmpty()) {
            return false;
        }
        // The entity that triggered this interaction (the player who pressed F).
        Ref<EntityStore> playerReference = role.getStateSupport().getInteractionIterationTarget();
        if (playerReference == null) {
            return false;
        }
        PlayerRef playerRef = store.getComponent(playerReference, PlayerRef.getComponentType());
        if (playerRef == null) {
            return false;
        }
        // This action IS standing in front of the NPC, so the credit carries its ref - a sink that
        // wants to read something off the character it just credited can.
        return TalkCredits.credit(store, playerReference, playerRef, ref, npc, qualifier);
    }
}
