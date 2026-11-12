package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Guida XMLTV: lettura del file (anche compresso), canali riconosciuti per id o per nome, finestra di tempo e tetto per canale. */
class XmltvTest {
    private val now = XmltvParser.timeSeconds("20261111150000 +0000")!!
    private val index = XmltvIndex(
        mapOf("sky.epg" to setOf("id:sky.epg")),
        mapOf("skycinemadue" to setOf("n:skycinemadue")),
    )

    private fun fixture(): String = """
        <tv>
          <channel id="sky.epg"><display-name>IT | Sky Cinema Due FHD</display-name></channel>
          <channel id="sky-name"><display-name>IT | Sky Cinema Due HD</display-name></channel>
          <channel id="unknown"><display-name>Unknown Channel</display-name></channel>
          <programme start="20261111133000 +0000" stop="20261111143000 +0000" channel="sky.epg"><title>Earlier</title></programme>
          <programme start="20261111160000 +0000" stop="20261111170000 +0000" channel="sky.epg"><title>Upcoming</title><desc>Full description</desc></programme>
          <programme start="20261111170000 +0000" stop="20261111180000 +0000" channel="sky-name"><title>Name fallback</title></programme>
          <programme start="20261111110000 +0000" stop="20261111120000 +0000" channel="sky.epg"><title>Too old</title></programme>
          <programme start="20261112170000 +0000" stop="20261112180000 +0000" channel="sky.epg"><title>Too late</title></programme>
          <programme start="20261111160000 +0000" stop="20261111170000 +0000" channel="unknown"><title>Unknown</title></programme>
        </tv>
    """.trimIndent()

    private fun readXml(xml: String, gzip: Boolean = false, cap: Int = 60): List<XmltvProgramme> {
        val bytes = if (gzip) ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        }.toByteArray() else xml.toByteArray(Charsets.UTF_8)
        val limiter = XmltvLimiter(perChannel = cap, total = 100)
        val out = ArrayList<XmltvProgramme>()
        XmltvParser.parse(ByteArrayInputStream(bytes), KXmlParser(), index, now - 2 * 3600L, now + 24 * 3600L, stopAtLimit = { limiter.full }) {
            if (limiter.accept(it.channel, it.start)) out.add(it)
        }
        return out
    }

    @Test
    fun leggeIlFileETieneSoloCanaliNotiNellaFinestra() {
        val programmes = readXml(fixture())
        assertEquals(3, programmes.size)
        assertEquals(setOf("Earlier", "Upcoming", "Name fallback"), programmes.map { it.title }.toSet())
        assertTrue(programmes.any { it.title == "Name fallback" && it.channel == "n:skycinemadue" })
        assertEquals("Full description", programmes.first { it.title == "Upcoming" }.description)
    }

    @Test
    fun leggeIlFileCompressoERispettaIlTettoPerCanale() {
        val xml = """
            <tv><channel id="sky.epg"><display-name>Sky Cinema Due</display-name></channel>
            <programme start="20261111150000 +0000" stop="20261111160000 +0000" channel="sky.epg"><title>One</title></programme>
            <programme start="20261111160000 +0000" stop="20261111170000 +0000" channel="sky.epg"><title>Two</title></programme>
            <programme start="20261111170000 +0000" stop="20261111180000 +0000" channel="sky.epg"><title>Three</title></programme></tv>
        """.trimIndent()
        val programmes = readXml(xml, gzip = true, cap = 2)
        assertEquals(listOf("One", "Two"), programmes.map { it.title })
    }

    @Test
    fun ilNomeDelCanaleSiConfrontaSenzaPaeseEQualita() {
        assertEquals("skycinemadue", EpgName.key("IT | Sky Cinema Due FHD"))
        assertEquals(EpgName.key("Sky Cinema Due"), EpgName.key("IT: Sky Cinema Due HD"))
        assertEquals("", EpgName.key("   "))
    }
}
