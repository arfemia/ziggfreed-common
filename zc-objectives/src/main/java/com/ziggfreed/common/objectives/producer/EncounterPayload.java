package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.event.EncounterPhaseChangedEvent;
import com.ziggfreed.common.encounter.event.EncounterWipedEvent;
import com.ziggfreed.common.progress.runtime.SharedCredit;

/**
 * What rides with an {@code ENCOUNTER_DEFEATED}, {@code ENCOUNTER_ATTEMPT} or
 * {@code ENCOUNTER_PHASE} moment beyond the encounter-id target and its qualifier: the whole native
 * event the beat came off, so a reaction can read who else fought, how long it took, how many died
 * or which phase the fight was in, and THIS participant's own share of the credit, which the event
 * lists for everybody but the moment is about one of. The same event is handed to every player the
 * beat names; the share is theirs.
 *
 * <p>Every fire of one beat carries the run id as its {@link SharedCredit}, because a party's boss
 * kill is one event dispatched once per member: a server-first one of them wins is won by all of
 * them, and never by a later party under a later run.
 *
 * @param runId       the run the beat belongs to
 * @param encounterId the native encounter script id, the moment's own target
 * @param event       the beat as the producer received it: a {@link EncounterDefeatedEvent}, an
 *                    {@link EncounterWipedEvent} or an {@link EncounterPhaseChangedEvent}
 * @param share       this participant's credited share, 0 to 1 of the top contributor's, or null
 *                    for a phase beat, which is not a settlement
 */
public record EncounterPayload(@Nonnull UUID runId, @Nonnull String encounterId,
                               @Nonnull IEvent<Void> event, @Nullable Double share) implements SharedCredit {

    @Override
    @Nonnull
    public String creditKey() {
        return runId.toString();
    }

    /** The defeat this moment came off, or null when it was a wipe or a phase. */
    @Nullable
    public EncounterDefeatedEvent defeated() {
        return event instanceof EncounterDefeatedEvent defeated ? defeated : null;
    }

    /** The wipe this moment came off, or null. */
    @Nullable
    public EncounterWipedEvent wiped() {
        return event instanceof EncounterWipedEvent wiped ? wiped : null;
    }

    /** The phase beat this moment came off, or null. */
    @Nullable
    public EncounterPhaseChangedEvent phaseChanged() {
        return event instanceof EncounterPhaseChangedEvent phase ? phase : null;
    }
}
