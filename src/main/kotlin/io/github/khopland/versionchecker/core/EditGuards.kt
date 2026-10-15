package io.github.khopland.versionchecker.core

/** Live result/settings authorization; checked before input capture and again inside the write command. */
internal fun interface EditAuthorization {
    fun isCurrent(): Boolean
}

/** Provider inputs captured before write access; final validation reads the current IDE state. */
internal fun interface CapturedEditInputs {
    fun isCurrent(): Boolean
}

/** Potentially expensive provider I/O belongs to background capture, never the atomic write callback. */
internal fun interface EditInputCapture {
    suspend fun capture(): CapturedEditInputs
}
