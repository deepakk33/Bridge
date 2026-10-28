package com.bridge.clipboard

/**
 * What the clipboard card says, as a pure function of state. One sentence, the most
 * actionable one — the card is not a status dump.
 */
object ClipboardCardState {

    enum class Kind { LINK_DOWN, MANUAL_NO_GRANT, TRUNCATED, SYNCING }

    data class CardState(val kind: Kind, val formatArg: String?, val isWarning: Boolean)

    fun of(isWatching: Boolean, isLinkUp: Boolean, lastSend: SendOutcome?): CardState = when {
        !isLinkUp -> CardState(Kind.LINK_DOWN, null, true)
        // Truncation outranks the manual-sync notice: it is transient, it is about a clip the
        // user just sent, and the manual path is where most truncated sends come from.
        lastSend?.truncated == true ->
            CardState(Kind.TRUNCATED, formatBytes(lastSend.originalBytes), true)
        !isWatching -> CardState(Kind.MANUAL_NO_GRANT, null, false)
        else -> CardState(Kind.SYNCING, null, false)
    }

    fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
