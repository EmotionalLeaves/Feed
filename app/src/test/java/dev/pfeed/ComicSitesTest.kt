package dev.pfeed

import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.reco.Spread
import dev.pfeed.source.comics.ComicSite
import dev.pfeed.ui.feed.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicSitesTest {
    private val listing = """
        <div class="inner_thumb"><a href="/gallery/1778959/"><img class="lazy" data-src="https://m11.imhentai.xxx/033/amug5i4xo1/thumb.jpg" /></a></div>
        <div class="caption"><a href="/gallery/1778959/">Seieki &amp; Kitsune</a></div>
        <div class="inner_thumb"><a href="/gallery/2/"><img data-src="https://m11.imhentai.xxx/033/zz/thumb.jpg" alt="" /></a></div>
    """

    private val gallery = """
        <h1>Seieki</h1>
        <aside><a href='/tag/lolicon/'>sidebar tag</a></aside>
        <ul class="galleries_info"><li><span class='tags_text'>Tags:</span> <a class='tag btn' href='/tag/big-breasts/'>big breasts</a><a class='tag btn' href='/tag/milf/'>milf</a></li></ul>
        <input type="hidden" name="load_server" id="load_server" value="11" /><input type="hidden" name="load_dir" id="load_dir" value="033" />
        <input type="hidden" name="load_id" id="load_id" value="amug5i4xo1" /><input type="hidden" name="load_pages" id="load_pages" value="3" />
        <script>var g_th = $.parseJSON('{"1":"w,1280,952","2":"j,1280,952","3":"p,1280,952"}');</script>
    """

    @Test
    fun `imhentai gallery becomes a comic with its own tags only`() {
        val l = ComicSite.parseList(ComicSite.IMHENTAI, listing)
        assertEquals(listOf("1778959", "2"), l.map { it.id })
        assertEquals("Seieki & Kitsune", l[0].title)
        val g = ComicSite.gallery(ComicSite.IMHENTAI, l[0], gallery)!!
        assertEquals(
            listOf("https://m11.imhentai.xxx/033/amug5i4xo1/1.webp", "https://m11.imhentai.xxx/033/amug5i4xo1/2.jpg", "https://m11.imhentai.xxx/033/amug5i4xo1/3.png"),
            g.pages,
        )
        assertEquals(listOf("comic", "big_breasts", "milf"), g.tags) // not the sidebar's
        assertTrue(MinorsFilter.allows(g))
        assertEquals("comics:imhentai.xxx", Spread.origin(g))
        assertEquals("IMHentai", Display.source(g, "x"))
    }

    @Test
    fun `a gallery tagged for minors is rejected, and one without tags is never built`() {
        val l = ComicSite.parseList(ComicSite.IMHENTAI, listing)[0]
        val bad = ComicSite.gallery(ComicSite.IMHENTAI, l, gallery.replace("/tag/milf/", "/tag/shotacon/"))!!
        assertFalse(MinorsFilter.allows(bad))
        assertEquals(null, ComicSite.gallery(ComicSite.IMHENTAI, l, gallery.replace("Tags:", "Other:")))
    }

    @Test
    fun `hentaifox pages come from its page thumbnails`() {
        val fox = """<input type="hidden" name="load_id" id="load_id" value="4329276" /><input type="hidden" name="load_dir" id="load_dir" value="005" />
            <ul class="tags"><span class="i_text">Tags:</span><li><a class='tag_btn ' href='/tag/anal/'>anal</a></li></ul>
            <a href="/g/177483/1/"><img data-src="https://i3.hentaifox.com/005/4329276/1t.jpg" /></a><a href="/g/177483/2/"><img data-src="https://i3.hentaifox.com/005/4329276/2t.webp" /></a>"""
        val g = ComicSite.gallery(ComicSite.HENTAIFOX, ComicSite.Listed("177483", "Huntress", "https://i3.hentaifox.com/005/4329276/thumb.jpg"), fox)!!
        assertEquals(listOf("https://i3.hentaifox.com/005/4329276/1.jpg", "https://i3.hentaifox.com/005/4329276/2.webp"), g.pages)
    }
}
