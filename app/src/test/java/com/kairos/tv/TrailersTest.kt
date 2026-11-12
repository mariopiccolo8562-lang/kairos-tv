package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Trailer: codice del video su YouTube dalla linea (codice o link) e risposta del pannello (/api/player/trailer). */
class TrailersTest {
    @Test
    fun codiceYoutubeDaCodiceOLink() {
        assertEquals("dQw4w9WgXcQ", Yt.id("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Yt.id("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", Yt.id("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Yt.id("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(Yt.id(""))
        assertNull(Yt.id("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Yt.id("javascript:alert(1)"))
    }

    @Test
    fun rispostaDelPannello() {
        val yt = Trailers.parse("""{"url":"","yt":"gnOCZhluoow","title":"Interstellar","src":"tmdb"}""", "x")!!
        assertEquals("gnOCZhluoow", yt.yt); assertEquals("", yt.url); assertEquals("Interstellar", yt.title)
        val file = Trailers.parse("""{"url":"https://video.example/a.m4v","yt":"","title":"","src":"itunes"}""", "Barbie")!!
        assertEquals("https://video.example/a.m4v", file.url); assertEquals("", file.yt); assertEquals("Barbie", file.title)
        assertNull(Trailers.parse("""{"url":"","yt":""}""", "x"))
        assertNull(Trailers.parse("""{"url":"http://video.example/a.m4v","yt":"non valido"}""", "x"))
        assertNull(Trailers.parse("non è json", "x"))
    }
}
