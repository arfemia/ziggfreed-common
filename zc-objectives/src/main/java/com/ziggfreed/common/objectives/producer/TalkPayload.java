package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.ziggfreed.common.npc.TalkCredit;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with a {@code TALK_TO_NPC} moment beyond the character-id target: the whole credited
 * conversation, so a reaction can read the character's entity when there was one, every id it
 * answers to, and the qualifier the beat carried. Its presence is what proves the moment is the
 * library's own rather than one fired by hand.
 *
 * @param credit the credited conversation, exactly as the talk-credit engine handed it over
 */
public record TalkPayload(@Nonnull TalkCredit credit) implements MomentPayload {
}
