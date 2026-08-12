package com.ziggfreed.common.npc;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * A player's conversation with a character was credited.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired ONCE per credited
 * conversation, after every registered {@link TalkCreditSink} has run. It is the WATCH half of the
 * talk surface: a mod that wants to react to conversations without owning any of the crediting -
 * an analytics tally, a reputation nudge, an achievement in another mod - listens for this and
 * registers nothing.
 *
 * <p>It does not fire for a conversation the re-trigger window swallowed, so a listener counts the
 * same conversations the quest steps do.
 */
public final class NpcTalkedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final String npcId;
    private final List<String> answersTo;
    private final String qualifier;

    public NpcTalkedEvent(@Nonnull UUID playerId, @Nonnull String npcId,
                          @Nonnull List<String> answersTo, @Nullable String qualifier) {
        this.playerId = playerId;
        this.npcId = npcId;
        this.answersTo = List.copyOf(answersTo);
        this.qualifier = qualifier;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The character's PRIMARY id: what it is, not merely what it responds to. */
    @Nonnull
    public String npcId() {
        return npcId;
    }

    /** Every id the character answers to, primary first. */
    @Nonnull
    public List<String> answersTo() {
        return answersTo;
    }

    /** The optional secondary label the credit carried, or null. */
    @Nullable
    public String qualifier() {
        return qualifier;
    }
}
