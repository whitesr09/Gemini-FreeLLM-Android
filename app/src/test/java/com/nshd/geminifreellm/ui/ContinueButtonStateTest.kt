package com.nshd.geminifreellm.ui

import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.FINISH_REASON_USER_CANCELLED
import org.junit.Assert.assertEquals
import org.junit.Test

/** Visibility matrix for the per-message Continue affordance (Stages 3 + fix round 2). */
class ContinueButtonStateTest {

    private fun assistant(text: String = "partial reply", interrupted: Boolean = false, finishReason: String? = null) =
        ChatMessage(text = text, role = ChatMessage.Role.ASSISTANT, interrupted = interrupted, finishReason = finishReason)

    private fun state(message: ChatMessage, isTail: Boolean = true, generating: Boolean = false) =
        continueButtonState(message, isTail = isTail, isGeneratingThis = generating)

    @Test fun tokenLimitFinishShowsReady() {
        assertEquals(ContinueButtonState.READY, state(assistant(finishReason = "length")))
        assertEquals(ContinueButtonState.READY, state(assistant(finishReason = "MAX_TOKENS")))
        assertEquals(ContinueButtonState.READY, state(assistant(finishReason = "max_tokens")))
    }

    @Test fun interruptedStreamWithoutFinishReasonShowsReady() {
        assertEquals(ContinueButtonState.READY, state(assistant(interrupted = true, finishReason = null)))
    }

    @Test fun stoppedReplyWithPreservedContentShowsReady() {
        // Regression (fix round 2, Fix 1): an Auto reply cut off when stop()
        // fired — explicitly, or implicitly by switching chats mid-generation —
        // keeps interrupted = true with finishReason = "cancelled". The reply
        // is visibly incomplete, so it must offer continuation.
        assertEquals(ContinueButtonState.READY,
            state(assistant(interrupted = true, finishReason = FINISH_REASON_USER_CANCELLED)))
    }

    @Test fun interruptedWithNonLimitFinishShowsReady() {
        // Anthropic shape: the finish value arrived on message_delta but the
        // stream dropped before message_stop — interrupted, yet the recorded
        // finish value is a normal one.
        assertEquals(ContinueButtonState.READY, state(assistant(interrupted = true, finishReason = "end_turn")))
        assertEquals(ContinueButtonState.READY, state(assistant(interrupted = true, finishReason = "stop")))
    }

    @Test fun normalCompletionHides() {
        assertEquals(ContinueButtonState.HIDDEN, state(assistant(finishReason = "stop")))
        assertEquals(ContinueButtonState.HIDDEN, state(assistant(finishReason = "STOP")))
        assertEquals(ContinueButtonState.HIDDEN, state(assistant(finishReason = "end_turn")))
        assertEquals(ContinueButtonState.HIDDEN, state(assistant()))
    }

    @Test fun stoppedPlaceholderHides() {
        // The empty-stop placeholder is not a partial reply worth resuming.
        assertEquals(ContinueButtonState.HIDDEN, state(assistant(text = "Reply stopped.", interrupted = true)))
        assertEquals(ContinueButtonState.HIDDEN,
            state(assistant(text = "Reply stopped.", interrupted = true, finishReason = FINISH_REASON_USER_CANCELLED)))
    }

    @Test fun userAndErrorMessagesHide() {
        assertEquals(ContinueButtonState.HIDDEN, state(
            ChatMessage(text = "hi", role = ChatMessage.Role.USER, finishReason = "length")))
        assertEquals(ContinueButtonState.HIDDEN, state(
            ChatMessage(text = "boom", role = ChatMessage.Role.ERROR, interrupted = true)))
    }

    @Test fun blankPartialHides() {
        assertEquals(ContinueButtonState.HIDDEN, state(assistant(text = "", interrupted = true)))
    }

    @Test fun continuableButNotTailHides() {
        // Later conversation content follows: continuing would orphan it, and
        // the ViewModel plan would refuse — so no button.
        assertEquals(ContinueButtonState.HIDDEN, state(assistant(finishReason = "length"), isTail = false))
    }

    @Test fun inFlightContinuationHides() {
        // Fix round 2, Fix 3: while the continuation itself is streaming, the
        // button renders nothing — the composer's stop control is the signal.
        assertEquals(ContinueButtonState.HIDDEN,
            state(assistant(finishReason = "length"), generating = true))
        assertEquals(ContinueButtonState.HIDDEN,
            state(assistant(interrupted = true, finishReason = FINISH_REASON_USER_CANCELLED), generating = true))
    }

    @Test fun freshGenerationHides() {
        // A brand-new reply streaming in is not a continuation: no affordance.
        assertEquals(ContinueButtonState.HIDDEN,
            state(assistant(interrupted = true, finishReason = null), generating = true))
    }
}
