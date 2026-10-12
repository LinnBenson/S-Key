package io.secure.keyboard.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsDraftBudgetTest {
    @Test fun exactAggregateBoundaryIncludesBothFieldsErrorsAndDescriptors() {
        val metadata = listOf("dictionary", "english_dictionary", "ENGLISH", "item-123", "number-key")
        val first = "w".repeat(8_000)
        val errors = listOf("word error", "reading error")
        val available = SettingsDraftBudget.MAX_SAVED_TEXT_CODE_UNITS -
            metadata.sumOf { it.length } - errors.sumOf { it.length } - first.length
        val parts = metadata + first + errors + "r".repeat(available)
        assertTrue(SettingsDraftBudget.accepts(parts.asSequence()))
        assertFalse(SettingsDraftBudget.accepts((parts + "x").asSequence()))
        assertFalse(SettingsDraftBudget.accepts(sequenceOf("a".repeat(8_200), "b".repeat(8_200))))
    }

    @Test fun nullAndEmptyPartsDoNotConsumeBudget() {
        assertTrue(SettingsDraftBudget.accepts(sequenceOf(null, "", null)))
        assertTrue(SettingsDraftBudget.accepts(sequenceOf(null, "x".repeat(16_384), "")))
        assertFalse(SettingsDraftBudget.accepts(sequenceOf(null, "x".repeat(16_385))))
    }

    @Test fun eligibilityReadsLengthWithoutMaterializingOrNormalizingText() {
        val lengthOnly = object : CharSequence {
            override val length = 600_000
            override fun get(index: Int): Char = error("Must not read characters")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Must not copy")
            override fun toString(): String = error("Must not materialize oversized text")
        }
        assertFalse(SettingsDraftBudget.accepts(sequenceOf(lengthOnly)))
    }

    @Test fun lengthAccountingCannotOverflowOrReadBeyondAnOversizedPart() {
        val extreme = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char = error("Must not inspect")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Must not inspect")
        }
        assertFalse(SettingsDraftBudget.accepts(sequence {
            yield("prefix")
            yield(extreme)
            error("Must reject before requesting another part")
        }))
    }

    @Test fun budgetUsesUtf16IncludingSupplementaryAndCombiningCharacters() {
        val text = "𠀀e\u0301\n"
        assertEquals(5, text.length)
        val fits = text.repeat(3_276) + "xxxx"
        assertEquals(16_384, fits.length)
        assertTrue(SettingsDraftBudget.accepts(sequenceOf(fits)))
        assertFalse(SettingsDraftBudget.accepts(sequenceOf(fits, "\u0301")))
    }

    @Test fun completeLargeSnapshotOwnsOnlyLatestValuesAndCopiesTheFieldList() {
        val text = "𠀀e\u0301\n".repeat(120_000)
        val fields = mutableListOf(SettingsEditableDraft.Field(text, text.length - 1, 2, "invalid"))
        val snapshot = SettingsEditableDraft("phrases", "phrase", null, null, "item-123", fields, 0)
        fields.clear()
        assertEquals(600_000, snapshot.fields.single().text.length)
        assertSame(text, snapshot.fields.single().text)
        assertEquals(text.length - 1, snapshot.fields.single().selectionStart)
        assertEquals(2, snapshot.fields.single().selectionEnd)
        assertEquals("invalid", snapshot.fields.single().error)
        assertEquals("item-123", snapshot.itemId)
    }
}
