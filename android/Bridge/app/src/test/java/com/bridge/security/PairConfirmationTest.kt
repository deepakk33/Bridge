package com.bridge.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The confirmation is shared mutable state between the service and the screen. */
class PairConfirmationTest {

    @After
    fun tearDown() = PairConfirmation.clear()

    @Test
    fun `raise then answer delivers once`() {
        val answers = mutableListOf<Boolean>()
        PairConfirmation.raise("MacBook Air", "418207") { answers.add(it) }

        assertEquals("MacBook Air", PairConfirmation.pending?.deviceName)
        assertEquals("418207", PairConfirmation.pending?.code)

        PairConfirmation.answer(true)
        assertEquals(listOf(true), answers)
        assertNull("answering clears the prompt", PairConfirmation.pending)
    }

    /** Two taps, or a tap racing the service's own teardown, must not pin twice. */
    @Test
    fun `answering twice delivers once`() {
        val answers = mutableListOf<Boolean>()
        PairConfirmation.raise("MacBook Air", "418207") { answers.add(it) }

        PairConfirmation.answer(true)
        PairConfirmation.answer(false)

        assertEquals(listOf(true), answers)
    }

    @Test
    fun `clear means no answer is owed`() {
        val answers = mutableListOf<Boolean>()
        PairConfirmation.raise("MacBook Air", "418207") { answers.add(it) }

        PairConfirmation.clear()
        PairConfirmation.answer(true)

        assertEquals(emptyList<Boolean>(), answers)
        assertNull(PairConfirmation.pending)
    }

    @Test
    fun `answering with nothing pending is a no-op`() {
        PairConfirmation.answer(true)
        assertNull(PairConfirmation.pending)
    }
}
