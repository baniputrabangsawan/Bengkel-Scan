/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

/**
 * Stable SpeechRecognizer error values, kept Android-free for JVM tests.
 * Source: https://developer.android.com/reference/android/speech/SpeechRecognizer
 * No error authorizes a cloud recognizer, model download, or automatic retry.
 */
object SpeechErrorPolicy {
    enum class Failure {
        NETWORK_TIMEOUT, NETWORK, AUDIO, SERVER, CLIENT, NO_SPEECH, NO_MATCH, BUSY,
        PERMISSION, TOO_MANY_REQUESTS, DISCONNECTED, LANGUAGE_NOT_SUPPORTED,
        LANGUAGE_NOT_INSTALLED, SUPPORT_CHECK_UNAVAILABLE, DOWNLOAD_EVENTS_UNAVAILABLE, UNKNOWN,
    }

    fun fromPlatformError(code: Int): Failure = when (code) {
        1 -> Failure.NETWORK_TIMEOUT
        2 -> Failure.NETWORK
        3 -> Failure.AUDIO
        4 -> Failure.SERVER
        5 -> Failure.CLIENT
        6 -> Failure.NO_SPEECH
        7 -> Failure.NO_MATCH
        8 -> Failure.BUSY
        9 -> Failure.PERMISSION
        10 -> Failure.TOO_MANY_REQUESTS
        11 -> Failure.DISCONNECTED
        12 -> Failure.LANGUAGE_NOT_SUPPORTED
        13 -> Failure.LANGUAGE_NOT_INSTALLED
        14 -> Failure.SUPPORT_CHECK_UNAVAILABLE
        15 -> Failure.DOWNLOAD_EVENTS_UNAVAILABLE
        else -> Failure.UNKNOWN
    }

    /** Only ERROR_CANNOT_CHECK_SUPPORT permits trying the already on-device-only recognizer. */
    fun canAttemptWithoutSupport(code: Int): Boolean =
        fromPlatformError(code) == Failure.SUPPORT_CHECK_UNAVAILABLE
}
