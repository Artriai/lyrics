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
        // Ateji / slang kuromoji cannot read must not produce a blank
        // annotation, otherwise full-lyrics mode renders no romaji line.
        val reading = converter.readingFor("夜露死苦")
        assertNotNull(reading)
        assertTrue(reading!!.isNotBlank())
    }

    @Test
    fun readingForMixedLineKeepsUnknownKanjiWithoutGaps() {
        val reading = converter.readingFor("夜露死苦な夜に")
        assertNotNull(reading)
        assertTrue(reading!!.isNotBlank())
        // The unknown kanji survive as-is alongside real romaji.
        assertTrue(reading.contains("夜露死苦"))
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
}
