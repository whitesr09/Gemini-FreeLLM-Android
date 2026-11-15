package com.nshd.geminifreellm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bare-"continue" send-path intercept (fix round 2, Fix 2). */
class ContinuationRoutingTest {

    @Test fun barePhrasesMatch() {
        listOf("continue", "Continue", "CONTINUE", "  continue  ", "continue.",
            "go on", "Go On", "resume", "keep going", "carry on").forEach {
            assertTrue("expected bare continuation: '$it'", isBareContinuation(it))
        }
    }

    @Test fun realMessagesDoNotMatch() {
        listOf("", "   ", "continued", "please continue", "continue!",
            "continue writing the essay", "continue from step 3", "go", "on",
            "resume my application", "keep going please").forEach {
            assertFalse("expected a real message: '$it'", isBareContinuation(it))
        }
    }

    @Test fun routesWhenBareWithContinuableTailAndIdle() {
        assertTrue(shouldRouteToContinuation("continue", hasAttachments = false, busy = false, tailContinuable = true))
        assertTrue(shouldRouteToContinuation("Keep Going", hasAttachments = false, busy = false, tailContinuable = true))
    }

    @Test fun doesNotRouteWhenBusy() {
        assertFalse(shouldRouteToContinuation("continue", hasAttachments = false, busy = true, tailContinuable = true))
    }

    @Test fun doesNotRouteWithAttachments() {
        assertFalse(shouldRouteToContinuation("continue", hasAttachments = true, busy = false, tailContinuable = true))
    }

    @Test fun doesNotRouteWithoutContinuableTail() {
        assertFalse(shouldRouteToContinuation("continue", hasAttachments = false, busy = false, tailContinuable = false))
    }

    @Test fun doesNotRouteRealMessages() {
        assertFalse(shouldRouteToContinuation("continue the story about the fox",
            hasAttachments = false, busy = false, tailContinuable = true))
    }
}
