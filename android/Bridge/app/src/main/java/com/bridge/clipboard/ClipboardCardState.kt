package com.bridge.clipboard

/**
 * What the clipboard card says, as a pure function of state. One sentence, the most
 * actionable one — the card is not a status dump.
 */
object ClipboardCardState {

    enum class Kind { LINK_DOWN, MANUAL_NO_GRANT, TRUNCATED, SYNCING }

    data class CardState(val kind: Kind, val formatArg: String?, val isWarning: Boolean)

    fun of(hasGrant: Boolean, isLinkUp: Boolean, lastSend: SendOutcome?): CardState = when {
        !isLinkUp -> CardState(Kind.LINK_DOWN, null, true)
        !hasGrant -> CardState(Kind.MANUAL_NO_GRANT, null, true)
        lastSend?.truncated == true ->
            CardState(Kind.TRUNCATED, formatBytes(lastSend.originalBytes), true)
        else -> CardState(Kind.SYNCING, null, false)
    }

    fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
