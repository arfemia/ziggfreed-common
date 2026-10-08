package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.ziggfreed.common.npc.TalkCredit;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with a {@code TALK_TO_NPC} moment beyond the character's primary id: the whole credited
 * conversation, so a reaction can read every id the character answers to, the NPC entity when the
 * credit came from standing in front of one, and the beat's qualifier. Only the primary fire carries
 * it; the alias fires reach the engines alone and are never reacted to.
 *
 * @param credit the conversation exactly as the talk-credit engine handed it to the library's sink
 */
public record TalkPayload(@Nonnull TalkCredit credit) implements MomentPayload {
}
