package com.relay.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitialsTest {

    @Test
    fun twoWordNamesUseOneLetterFromEach() {
        assertEquals("AL", initialsOf("Ada Lovelace"))
    }

    @Test
    fun aSingleWordUsesItsFirstTwoLetters() {
        assertEquals("AD", initialsOf("ada"))
    }

    @Test
    fun aBlankLabelFallsBackToAQuestionMark() {
        assertEquals("?", initialsOf("   "))
    }

    @Test
    fun anAstralFirstCharacterIsKeptWhole() {
        val initials = initialsOf("🙂 Lovelace")
        assertEquals("🙂L", initials)
        assertTrue(initials.none { it.isHighSurrogate() && initials.indexOf(it) == initials.length - 1 })
    }

    @Test
    fun aSingleAstralWordKeepsBothCodePointsWhole() {
        assertEquals("🙂🚀", initialsOf("🙂🚀xyz"))
    }

    @Test
    fun aSingleShortAstralWordDoesNotOverrun() {
        assertEquals("🙂", initialsOf("🙂"))
    }
}
