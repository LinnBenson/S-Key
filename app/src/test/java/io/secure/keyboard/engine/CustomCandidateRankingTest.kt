package io.secure.keyboard.engine

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CustomCandidateRankingTest {
    private data class Entry(val text: String, val code: String)

    private fun merge(query: String, baseline: List<String>, vararg custom: Entry): List<String> =
        CustomCandidateRanking.merge(query, baseline, custom.toList(), { it.code }, { it.text })

    @Test
    fun exactMatchesStartSecondAndStayInSavedOrderAheadOfRemainingWubi() {
        assertEquals(
            listOf("五笔首选", "自定一", "自定二", "五笔二", "五笔三", "拼音"),
            merge("ni", listOf("五笔首选", "五笔二", "五笔三", "拼音"),
                Entry("自定一", "NI"), Entry("不匹配", "na"), Entry("自定二", "n ' i")),
        )
    }

    @Test
    fun normalizedPrefixMatchesFollowEveryBuiltinAndExactMatch() {
        assertEquals(
            listOf("首选", "精确", "内置二", "前缀一", "前缀二"),
            merge("N'I", listOf("首选", "内置二"),
                Entry("前缀一", "Ni Hao"), Entry("精确", " n i "),
                Entry("前缀二", "ni'hao'ma"), Entry("过短", "n"), Entry("不匹配", "hao")),
        )
    }

    @Test
    fun noBaselineStartsWithExactCustomBlockThenPrefixes() {
        assertEquals(listOf("精确一", "精确二", "前缀"),
            merge("qqqqq", emptyList(), Entry("前缀", "qqqqqa"),
                Entry("精确一", "qqqqq"), Entry("精确二", "QQQQQ")))
        assertEquals(listOf("只有前缀"),
            merge("qqqqq", emptyList(), Entry("只有前缀", "qqqqqa")))
    }

    @Test
    fun exactCustomPromotesDuplicateTextButCannotDisplaceFirstBaseline() {
        assertEquals(listOf("首选", "后置内置", "自定义", "普通二", "前缀"),
            merge("ni", listOf("首选", "普通二", "后置内置", "首选"),
                Entry("前缀", "nihao"), Entry("后置内置", "ni"), Entry("首选", "ni"),
                Entry("自定义", "ni"), Entry("自定义", "NI"), Entry("普通二", "nihao"),
                Entry("前缀", "nin")))
    }

    @Test
    fun duplicateExactAndPrefixTextUsesExactPositionRegardlessOfSavedOrder() {
        assertEquals(listOf("首选", "同词", "第二", "别的前缀"),
            merge("ni", listOf("首选", "第二"), Entry("同词", "nihao"),
                Entry("别的前缀", "nihao"), Entry("同词", "ni")))
    }

    @Test
    fun blankQueryDoesNotExpandTheEntireCustomDictionary() {
        assertEquals(listOf("首选"), merge(" ' ", listOf("首选", "首选"), Entry("词", "a")))
        assertTrue(merge("", emptyList(), Entry("词", "a")).isEmpty())
    }

    @Test
    fun builtinLimitDoesNotTruncateTenThousandCustomPrefixes() {
        val baseline = (1..40).map { "内置$it" }
        val custom = (1..10_000).map { Entry("自定$it", "nihao") }
        val result = CustomCandidateRanking.merge("n", baseline, custom, { it.code }, { it.text })
        assertEquals(10_040, result.size)
        assertEquals(baseline, result.take(40))
        assertEquals("自定1", result[40])
        assertEquals("自定10000", result.last())
    }

    @Test
    fun editsAndDeletesTakeEffectWithoutRetainingOrLearningPreviousMatches() {
        val rows = mutableListOf(Entry("自定义", "ni"), Entry("前缀", "nihao"))
        fun current() = CustomCandidateRanking.merge("ni", listOf("首选", "第二"), rows,
            { it.code }, { it.text })
        val before = current()
        assertEquals(listOf("首选", "自定义", "第二", "前缀"), before)
        repeat(5) { assertEquals(before, current()) }
        rows[0] = Entry("已改码", "hao")
        assertEquals(listOf("首选", "第二", "前缀"), current())
        rows.clear()
        assertEquals(listOf("首选", "第二"), current())
        assertEquals(listOf("首选", "自定义", "第二", "前缀"), before)
    }

    @Test
    fun normalizationDoesNotDependOnDeviceLocaleAndAcceptsUmlaut() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("首选", "词"), merge("NI", listOf("首选"), Entry("词", "ni")))
            assertEquals(listOf("女"), merge("nv", emptyList(), Entry("女", "NÜ")))
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun character(codePoint: Int) = String(Character.toChars(codePoint))

    @Test
    fun extensionBBoundariesMoveToTailWithoutMovingOtherSupplementaryRanges() {
        val firstB = character(0x20000)
        val lastB = character(0x2A6DF)
        val before = character(0x1FFFF)
        val after = character(0x2A6E0)
        val extensionA = character(0x3400)
        val extensionC = character(0x2A700)
        val extensionG = character(0x30000)
        val emoji = character(0x1F600)
        assertEquals(listOf("普通", before, after, extensionA, extensionC, extensionG, emoji, firstB, lastB),
            merge("a", listOf(firstB, "普通", before, lastB, after, extensionA, extensionC, extensionG, emoji)))
    }

    @Test
    fun mixedStringWithAnyExtensionBMovesAsOneCompleteCandidate() {
        val rare = character(0x20BB7)
        val mixed = "前缀${character(0x1F600)}${rare}后缀"
        val repeated = rare.repeat(3)
        assertEquals(listOf("首选", "普通词", "末尾", mixed, repeated),
            merge("a", listOf("首选", mixed, "普通词", repeated, "末尾")))
    }

    @Test
    fun isolatedSurrogatesAreNotMistakenForExtensionBCharacters() {
        val highOnly = "高\uD840尾"
        val lowOnly = "低\uDC00尾"
        val nonAdjacent = "\uD840分隔\uDC00"
        val rare = "\uD840\uDC00"
        assertEquals(listOf(highOnly, lowOnly, nonAdjacent, "普通", rare),
            merge("a", listOf(rare, highOnly, lowOnly, nonAdjacent, "普通")))
    }

    @Test
    fun extensionBCustomExactCannotOverrideFinalTailRuleButOrdinaryExactRemainsSecond() {
        val builtinB = character(0x20000)
        val customB = "自定义${character(0x2A6DF)}完整词"
        assertEquals(listOf("首选", "普通自定义", "内置二", customB, builtinB),
            merge("ni", listOf("首选", builtinB, "内置二"),
                Entry(customB, "ni"), Entry("普通自定义", "n i")))
    }

    @Test
    fun builtinFirstExtensionBAlsoMovesBehindOrdinaryCustomExactAndPrefixMatches() {
        val firstB = character(0x20000)
        val exactB = "精确${character(0x20001)}"
        val prefixB = "前缀${character(0x20002)}"
        assertEquals(listOf("普通精确", "内置普通", "普通前缀", firstB, exactB, prefixB),
            merge("ni", listOf(firstB, "内置普通"),
                Entry(prefixB, "nihao"), Entry(exactB, "ni"),
                Entry("普通前缀", "nin"), Entry("普通精确", "NI")))
    }

    @Test
    fun extensionBFinalPartitionKeepsDuplicatesDeduplicatedAndBothGroupsStable() {
        val rareOne = character(0x20001)
        val rareTwo = character(0x20002)
        val rareThree = character(0x20003)
        assertEquals(listOf("首选", "精确一", "精确二", "内置二", "普通前缀", rareTwo, rareOne, rareThree),
            merge("ni", listOf("首选", rareOne, "内置二", rareTwo, rareOne),
                Entry(rareThree, "nihao"), Entry("精确一", "ni"), Entry(rareTwo, "ni"),
                Entry("精确二", "ni"), Entry(rareOne, "nihao"), Entry("普通前缀", "nihao")))
    }

    @Test
    fun allExtensionBAndEmptyQueryStillRetainEveryCandidateInStableOrder() {
        val one = character(0x20001)
        val two = character(0x20002)
        assertEquals(listOf(one, two), merge("ni", listOf(one, two, one)))
        assertEquals(listOf("普通", one, two),
            merge(" ' ", listOf(one, "普通", two, one), Entry("不应添加", "a")))
        assertEquals(listOf("普通", one, two),
            merge("ni", emptyList(), Entry(one, "ni"), Entry("普通", "ni"), Entry(two, "nihao")))
    }

    @Test
    fun tenThousandCustomMatchesRemainReachableAfterStableExtensionBPartition() {
        val entries = List(10_000) { index ->
            Entry(if (index % 3 == 0) "候选${character(0x20000)}$index" else "候选$index", "nihao")
        }
        val result = CustomCandidateRanking.merge("n", listOf("首选"), entries, { it.code }, { it.text })
        assertEquals(10_001, result.size)
        assertEquals(listOf("首选") + entries.filterIndexed { index, _ -> index % 3 != 0 }.map { it.text } +
            entries.filterIndexed { index, _ -> index % 3 == 0 }.map { it.text }, result)
        assertEquals(10_001, result.toSet().size)
    }
}
