package com.bridge.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BR-10: `shouldShowRequestPermissionRationale` returns false both before the first prompt
 * and after a permanent denial, so it cannot tell them apart on its own. Pairing it with
 * "have we ever asked" is what makes the difference, and only one of the four combinations
 * may strand the user in Settings.
 */
class RuntimeRecoveryTest {

    @Test
    fun `never asked yet - prompt`() {
        assertEquals(RuntimeRecovery.REQUEST, RuntimeRecovery.resolve(hasAsked = false, showRationale = false))
    }

    @Test
    fun `never asked but the system wants a rationale - still prompt`() {
        assertEquals(RuntimeRecovery.REQUEST, RuntimeRecovery.resolve(hasAsked = false, showRationale = true))
    }

    @Test
    fun `asked once and denied - prompt again`() {
        assertEquals(RuntimeRecovery.REQUEST, RuntimeRecovery.resolve(hasAsked = true, showRationale = true))
    }

    @Test
    fun `asked and the system will no longer prompt - app settings`() {
        assertEquals(
            RuntimeRecovery.OPEN_APP_SETTINGS,
            RuntimeRecovery.resolve(hasAsked = true, showRationale = false)
        )
    }
}
