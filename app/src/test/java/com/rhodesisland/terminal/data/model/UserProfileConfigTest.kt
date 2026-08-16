package com.rhodesisland.terminal.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 博士档案指令块拼接测试：空段跳过、全空返回空串（调用方跳过注入）、空白裁剪。
 */
class UserProfileConfigTest {

    @Test
    fun emptyProfile_returnsEmptyDirective() {
        assertEquals("", UserProfileConfig().toDirectiveText())
        assertEquals("", UserProfileConfig(persona = "  ", relationship = "\n").toDirectiveText())
    }

    @Test
    fun personaOnly_containsPersonaWithoutRelationship() {
        val text = UserProfileConfig(persona = " 温和的博士 ").toDirectiveText()
        assertTrue(text.contains("人设：温和的博士"))
        assertFalse(text.contains("关系"))
    }

    @Test
    fun relationshipOnly_containsRelationshipWithoutPersona() {
        val text = UserProfileConfig(relationship = "战友").toDirectiveText()
        assertTrue(text.contains("博士与你的关系：战友"))
        assertFalse(text.contains("人设"))
    }

    @Test
    fun bothFields_composedInOrder() {
        val text = UserProfileConfig(persona = "P", relationship = "R").toDirectiveText()
        assertTrue(text.indexOf("人设：P") < text.indexOf("博士与你的关系：R"))
    }
}