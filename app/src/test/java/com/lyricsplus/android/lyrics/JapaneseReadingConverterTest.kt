package com.lyricsplus.android.lyrics

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseReadingConverterTest {
    private val converter = JapaneseReadingConverter()

    @Test
    fun readingForNormalLineProducesRomaji() {
        val reading = converter.readingFor("悲しみの向こう側")
        assertNotNull(reading)
        assertTrue(reading!!.any { it in 'a'..'z' })
    }

    @Test
    fun readingForUnknownKanjiFallsBackToSurfaceInsteadOfBlank() {
        // kuromoji has no reading for 做 here: the old code dropped the token
        // entirely ("kokoro shi"), leaving a gap in full-lyrics romaji mode.
        val reading = converter.readingFor("心做し")
        assertNotNull(reading)
        assertTrue(reading!!.isNotBlank())
        assertTrue(reading.contains("做"))
        assertTrue(reading.any { it in 'a'..'z' })
    }

    @Test
    fun readingForMixedLineKeepsUnknownKanjiWithoutGaps() {
        val reading = converter.readingFor("心做しな夜に")
        assertNotNull(reading)
        assertTrue(reading!!.isNotBlank())
        // The unknown kanji survive as-is alongside real romaji.
        assertTrue(reading.contains("做"))
        assertTrue(reading.any { it in 'a'..'z' })
    }

    @Test
    fun readingForPureKanaLineProducesRomaji() {
        val reading = converter.readingFor("あいしてる")
        assertNotNull(reading)
        assertTrue(reading!!.any { it in 'a'..'z' })
    }

    @Test
    fun readingForNonJapaneseReturnsNull() {
        assertNull(converter.readingFor("Hello world"))
    }

    @Test
    fun readingForPunctuationOnlyReturnsNull() {
        assertNull(converter.readingFor("・・・"))
    }

    @Test
    fun directKanaFallbackConvertsKanaWithoutDictionary() {
        val reading = converter.directKanaReadingFor("物語は終わり")
        assertNotNull(reading)
        assertTrue(reading!!.contains("ha"))
    }

    @Test
    fun directKanaFallbackKeepsKanjiWhenNoRomajiProduced() {
        // Pure kanji with no kana -> nothing pronounceable -> null.
        assertNull(converter.directKanaReadingFor("夜露死苦"))
    }

    @Test
    fun directKanaFallbackRejectsPunctuationAndNonJapanese() {
        assertNull(converter.directKanaReadingFor("・・・"))
        assertNull(converter.directKanaReadingFor("Hello world"))
    }
}
