/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechErrorPolicyTest {
    @Test fun mapsEveryDocumentedPlatformErrorWithoutAndroidRuntime() {
        val expected = listOf(
            SpeechErrorPolicy.Failure.NETWORK_TIMEOUT,
            SpeechErrorPolicy.Failure.NETWORK,
            SpeechErrorPolicy.Failure.AUDIO,
            SpeechErrorPolicy.Failure.SERVER,
            SpeechErrorPolicy.Failure.CLIENT,
            SpeechErrorPolicy.Failure.NO_SPEECH,
            SpeechErrorPolicy.Failure.NO_MATCH,
            SpeechErrorPolicy.Failure.BUSY,
            SpeechErrorPolicy.Failure.PERMISSION,
            SpeechErrorPolicy.Failure.TOO_MANY_REQUESTS,
            SpeechErrorPolicy.Failure.DISCONNECTED,
            SpeechErrorPolicy.Failure.LANGUAGE_NOT_SUPPORTED,
            SpeechErrorPolicy.Failure.LANGUAGE_NOT_INSTALLED,
            SpeechErrorPolicy.Failure.SUPPORT_CHECK_UNAVAILABLE,
            SpeechErrorPolicy.Failure.DOWNLOAD_EVENTS_UNAVAILABLE,
        )
        expected.forEachIndexed { index, failure ->
            assertEquals("Platform error ${index + 1}", failure,
                SpeechErrorPolicy.fromPlatformError(index + 1))
        }
    }

    @Test fun unrecognizedPlatformErrorsAlwaysHaveTypedFallback() {
        for (code in listOf(Int.MIN_VALUE, -1, 0, 16, 1000, Int.MAX_VALUE)) {
            assertEquals(SpeechErrorPolicy.Failure.UNKNOWN, SpeechErrorPolicy.fromPlatformError(code))
        }
    }

    @Test fun onlyUnsupportedCapabilityCheckAllowsUnverifiedOnDeviceAttempt() {
        assertTrue(SpeechErrorPolicy.canAttemptWithoutSupport(14))
        for (code in -1..16) {
            if (code != 14) assertFalse("Platform error $code",
                SpeechErrorPolicy.canAttemptWithoutSupport(code))
        }
        assertFalse(SpeechErrorPolicy.canAttemptWithoutSupport(Int.MAX_VALUE))
    }
}
