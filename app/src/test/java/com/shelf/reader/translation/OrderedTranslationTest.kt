package com.shelf.reader.translation

import org.junit.Assert.*
import org.junit.Test

class OrderedTranslationTest {
    private val base = PhraseDictionary(mapOf("你好" to "chào ngươi", "世界" to "thế giới", "李四" to "lý tứ", "说" to "nói"))
    private fun rules(vararg lines: String) = TranslationRule.parseAll(lines.joinToString("\n"))

    @Test fun canonicalSyntaxAndLegacyLiteralRules() {
        assertEquals(TranslationRule("张三", "A=B/$1"), TranslationRule.parse("\$[张三]=A=B/$1"))
        assertEquals("\$[张三]=Trương Tam\n# note", TranslationRule.canonical("张三=Trương Tam\n# note"))
        assertNull(TranslationRule.parse("\$[]=empty"))
    }
    @Test fun sourceRulesRunBeforeSmoothAndVietnameseRulesEvenWhenListedLast() {
        val engine = OrderedTranslation(base, rules("\$[lý tứ]=anh tư", "\$[张三]=李四"))
        assertEquals("anh tư nói", engine.translate("张三说"))
    }
    @Test fun laterRulesSeeEarlierResultsWithoutRecursion() {
        assertEquals("bạn tốt", OrderedTranslation(base, rules("\$[chào ngươi]=xin chào", "\$[xin chào]=bạn tốt")).translate("你好"))
        assertEquals("xin chào", OrderedTranslation(base, rules("\$[xin chào]=bạn tốt", "\$[chào ngươi]=xin chào")).translate("你好"))
        assertEquals("aa", OrderedTranslation(base, rules("\$[a]=aa")).translate("a"))
        assertEquals("a", OrderedTranslation(base, rules("\$[a]=b", "\$[b]=a")).translate("a"))
    }
    @Test fun storyOverridesGlobalAndDoesNotAffectOtherStories() {
        val global = rules("\$[你好]=global", "\$[global]=chung")
        assertEquals("riêng", OrderedTranslation(base, global, rules("\$[你好]=riêng")).translate("你好"))
        assertEquals("chung", OrderedTranslation(base, global).translate("你好"))
    }
    @Test fun capitalizationRunsAfterSmoothAndAllReplacementRules() {
        val engine = OrderedTranslation(base, listOf(TranslationRule("张三", "李四", capitalize = true)) + rules("\$[lý tứ]=anh tư"))
        assertEquals("Anh tư nói", engine.translate("张三说"))
        assertEquals("Chào ngươi", OrderedTranslation(base, listOf(TranslationRule("你好", "chào ngươi", true))).translate("你好"))
    }
    @Test fun vietnameseTextAndSourceOffsetsSurviveChainedReplacements() {
        val engine = OrderedTranslation(base, rules("\$[chào ngươi]=xin chào", "\$[thế giới]=mọi người"))
        val tokens = engine.tokens("你好，世界!")
        assertEquals("xin chào, mọi người!", tokens.joinToString("") { it.text })
        val first = tokens.first { it.selectable }
        assertEquals(0, first.start); assertEquals(2, first.end)
        val latin = OrderedTranslation(base, rules("\$[đồ vật]=vật phẩm"))
        assertEquals("một vật phẩm đẹp", latin.translate("một đồ vật đẹp"))
        assertEquals(listOf("một", "đồ", "vật", "đẹp"), latin.tokens("một đồ vật đẹp", original = true).filter { it.selectable }.map { it.text })
    }
    @Test fun adjacentCustomChineseNamesRemainSeparateWords() {
        assertEquals("Trương Tam Lý Tứ", OrderedTranslation(base, rules("\$[张三]=Trương Tam", "\$[李四]=Lý Tứ")).translate("张三李四"))
    }
    @Test fun chineseIntroducedByVietnameseRulesIsTranslatedBeforeNextRule() {
        assertEquals("anh tư", OrderedTranslation(base, rules("\$[chào ngươi]=李四", "\$[lý tứ]=anh tư")).translate("你好"))
    }
}
