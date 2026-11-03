package com.shelf.reader.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseDictionaryTest {
    @Test fun chineseModePreservesLatinWordsInMixedParagraphs() {
        val dictionary = PhraseDictionary(mapOf("你好" to "xin chào", "This" to "BAD", "a" to "BAD"))
        assertEquals("xin chào This is a paragraph", dictionary.translate("你好 This is a paragraph", cjkOnly = true))
    }
    @Test fun bundledSmoothDictionaryAndOverrides() {
        val mapping = HashMap<String, String>()
        for (name in listOf("ChinesePhienAmWords.txt", "VietPhrase.compact.txt", "Names.txt", "Names2.txt", "VietPhraseOverride.txt")) {
            java.io.File("src/main/assets/dictionaries/$name").bufferedReader(Charsets.UTF_8).use {
                mapping.putAll(PhraseDictionary.parse(it.lineSequence(), true))
            }
        }
        org.junit.Assert.assertTrue("Full bundled dictionary must load", mapping.size > 300_000)
        val dictionary = PhraseDictionary(mapping)
        assertEquals("chào ngươi, thế giới!", dictionary.translate("你好，世界！"))
        assertEquals("chương 1:", dictionary.translate("第一章"))
        val custom = PhraseDictionary(mapOf("张三" to "Tên riêng", "修仙" to "Tu Tiên"))
        assertEquals("Tên riêng Tu Tiên", dictionary.translate("张三修仙", custom))
    }

    @Test fun longestPhraseAndPunctuation() {
        val dictionary = PhraseDictionary(mapOf("修" to "tu", "修仙" to "tu tiên", "世界" to "thế giới"))
        assertEquals("tu tiên, thế giới!", dictionary.translate("修仙，世界！"))
    }

    @Test fun customNameIsProtectedFromLongerBundledPhrase() {
        val base = PhraseDictionary(mapOf("张三说" to "wrong", "说" to "nói"))
        val custom = PhraseDictionary(mapOf("张三" to "Trương Tam"))
        assertEquals("Trương Tam nói", base.translate("张三说", custom))
    }

    @Test fun dictionaryParsingPreservesLiteralCustomValues() {
        val custom = PhraseDictionary.parse(sequenceOf("\uFEFF张三=A/B", "# ignored", "invalid", "空=", "名=x=y"))
        assertEquals(mapOf("张三" to "A/B", "名" to "x=y"), custom)
        assertEquals(mapOf("张三" to "A"), PhraseDictionary.parse(sequenceOf("张三=A/B"), true))
    }

    @Test fun originalLatinAndParagraphBreaksSurvive() {
        val dictionary = PhraseDictionary(mapOf("你好" to "xin chào"))
        assertEquals("Hello world\nxin chào.", dictionary.translate("Hello world\n你好。"))
        assertEquals("“xin chào”", dictionary.translate("“你好”"))
    }
}
