package dev.pfeed

import dev.pfeed.source.photos.BabeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabeSource photo sets, against its pages as served in November 2026. */
class PhotoSetsTest {
    @Test
    fun `listing and a set's pictures`() {
        val list = BabeSource.parseList(LIST)
        assertEquals(2, list.size)
        assertEquals("220875", list[0].id)
        assertEquals("Liz Jordan - Deeper", list[0].title)
        assertEquals(12, list[0].count)
        assertTrue(list[0].thumb!!.startsWith("https://media.babesource.com/galleries/"))
        val set = BabeSource.gallery(list[0], GALLERY)!!
        assertEquals(12, set.pages.size)
        assertTrue(set.pages[0], set.pages[0].contains("/m1080/"))
        assertEquals("Deeper", set.creator)
        assertTrue(set.tags.toString(), "parker ambrose" in set.tags && "cowgirl" in set.tags)
        assertEquals(listOf("Liz Jordan"), dev.pfeed.domain.reco.Mentions.performers(set.title))
        assertEquals("https://babesource.com/newest/page2.html", BabeSource.listUrl("newest", 2))
        assertEquals("https://babesource.com/search/big-tits/1/", BabeSource.searchUrl("Big Tits", 1))
    }

    private val LIST = """<a class="main-content__card-link" href="https://babesource.com/galleries/liz-jordan-deeper-220875.html">
<picture>
<source type="image/webp" srcset="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/300x406/44.jpg.webp?v=1795182902">
<img alt="Liz Jordan - Deeper" loading="eager" fetchpriority="high" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/300x406/44.jpg">
</picture>
</a>
<span class="bs-photo-count"><i class="fa-solid fa-images" aria-hidden="true"></i>12</span>
<div class="main-content__counts">
<span class="main-content__count-views">
<svg width="12" height="10" viewBox="0 0 12 10" fill="white" aria-hidden="true" focusable="false"><use href="#bs-ic-views"/></svg>
6409</span>
<span class="main-content__count-like">
<svg width="13" height="13" viewBox="0 0 10 10" fill="white" aria-hidden="true" focusable="false"><use href="#bs-ic-like"/></svg>
93%
</span>
<svg width="0" height="0" style="position:absolute;width:0;height:0;overflow:hidden" aria-hidden="true" focusable="false"><symbol id="bs-ic-views" viewBox="0 0 12 10"><path d="M6 0C3.31819 0 0.915086 1.95516 0.0200536 4.86532C-0.00668455 4.9528 -0.00668455 5.0472 0.0200536 5.13468C0.915086 8.04484 3.31819 10 6 10C8.68181 10 11.0849 8.04484 11.9799 5.13468C12.0067 5.0472 12.0067 4.9528 11.9799 4.86532C11.0849 1.95516 8.68179 0 6 0ZM6 9.16666C3.6804 9.16666 1.59552 7.49797 0.77262 5C1.59552 2.50203 3.68038 0.833341 6 0.833341C8.3196 0.833341 10.4045 2.50203 11.2274 5C10.4045 7.49797 8.3196 9.16666 6 9.16666Z"/><path d="M6.00004 2.5C4.7593 2.5 3.75 3.62143 3.75 5C3.75 6.37858 4.7593 7.50001 6.00004 7.50001C7.24077 7.50001 8.25008 6.3786 8.25008 5C8.25008 3.62141 7.24077 2.5 6.00004 2.5ZM6.00004 6.66666C5.17274 6.66666 4.50002 5.91918 4.50002 5C4.50002 4.0808 5.17277 3.33334 6.00004 3.33334C6.82733 3.33334 7.50006 4.08083 7.50006 5C7.50006 5.91918 6.82731 6.66666 6.00004 6.66666Z"/></symbol><symbol id="bs-ic-like" viewBox="0 0 10 10"><path d="M9.47902 3.62621C9.21377 3.39081 8.87856 3.26104 8.53753 3.26104H8.21981H7.0772H6.53504V1.79736C6.53504 1.09721 6.33392 0.587186 5.93459 0.282379C5.30499 -0.194449 4.45094 0.0741441 4.41305 0.0862156C4.26731 0.134502 4.1682 0.273325 4.1682 0.430256V2.19572C4.1682 2.73291 3.92044 3.18861 3.42784 3.55378C3.05474 3.83142 2.6729 3.95818 2.62334 3.97326L2.58837 3.98232C2.4572 3.82841 2.26482 3.73183 2.04912 3.73183H0.719962C0.323546 3.73183 0 4.06682 0 4.47725V8.99202C0 9.40246 0.323546 9.73744 0.719962 9.73744H2.05495C2.22984 9.73744 2.39307 9.67105 2.5155 9.5624C2.78366 9.83402 3.14801 10 3.54152 10H4.86776H5.00476H7.71846C8.1382 10 8.50838 9.89437 8.79112 9.69821C9.15256 9.44471 9.377 9.04333 9.44113 8.53029L9.98037 5.05971C10.0649 4.52252 9.87252 3.97326 9.47902 3.62621ZM2.07536 8.99202C2.07536 9.0041 2.06661 9.01315 2.05495 9.01315H0.719962C0.708303 9.01315 0.699558 9.0041 0.699558 8.99202V4.47725C0.699558 4.46518 0.708303 4.45613 0.719962 4.45613H2.05495C2.06661 4.45613 2.07536 4.46518 2.07536 4.47725V8.99202ZM9.28955 4.94804L8.75031 8.42466C8.75031 8.42768 8.75031 8.43371 8.7474 8.43975C8.72408 8.64798 8.64829 9.27872 7.71846 9.27872H5.00476H4.86776H3.54152C3.16842 9.27872 2.84196 8.98901 2.78366 8.60573C2.78075 8.59064 2.77783 8.57555 2.77492 8.56348V4.68247L2.79532 4.67643C2.80115 4.67643 2.80406 4.67342 2.80989 4.67342C2.8303 4.66738 3.32582 4.52252 3.81842 4.15736C4.50341 3.65337 4.86776 2.97434 4.86776 2.19572V0.729027C5.06014 0.707902 5.32539 0.716956 5.52068 0.864833C5.73055 1.02478 5.83548 1.33864 5.83548 1.79434V3.62017C5.83548 3.81935 5.99289 3.98232 6.18526 3.98232H7.0772H8.21981H8.53753C8.71242 3.98232 8.88439 4.05173 9.0243 4.17244C9.23417 4.35955 9.33328 4.65531 9.28955 4.94804Z"/></symbol></svg>
<span class="main-content__new-tag">New</span>
</div>
<h5 class="main-content__title">Liz Jordan</h5>
</div>
</div>
<div class="main-content__tumba tumba">
<div class="main-content__card tumba-card">
<a class="main-content__card-link" href="https://babesource.com/galleries/alina-lopez-vr-bangers-220865.html">
<picture>
<source type="image/webp" srcset="https://media.babesource.com/galleries/6b00270e07708/thumbs/300x406/6afc6601c73fd401860719_XL.jpg.webp?v=1795182902">
<img alt="Alina Lopez - VR Bangers" loading="eager" fetchpriority="high" src="https://media.babesource.com/galleries/6b00270e07708/thumbs/300x406/6afc6601c73fd401860719_XL.jpg">
</picture>
</a>
<span class="bs-photo-count"><i class="fa-solid fa-images" aria-hidden="true"></i>12</span>
<div class="main-content__counts">
<span class="main-content__count-views">
<svg width="12" height="10" viewBox="0 0 12 10" fill="white" aria-hidden="true" focusable="false"><use href="#bs-ic-views"/></svg>
6115</span>
<span class="main-content__count-like">
<svg width="13" height="13" viewBox="0 0 10 10" fill="white" aria-hidden="true" focusable="false"><use href="#bs-ic-like"/></svg>
95%
</span>
<span class="main-content__new-tag">New</span>
</div>
<h5 class="main-content__title">Alina Lopez</h5>
</div>
</div>
<div class="main-content__tumba tumba">
<div class="main-content__card tumba-card">
"""
    private val GALLERY = """<meta name="keywords" content="Parker Ambrose, Tattoos, Small Boobs, Hairy, Cowgirl,">
<div class="box-massage__card tumba-card">

 <a data-index="0" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/03.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/03.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 1" loading="eager" fetchpriority="high" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/03.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="1" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/24.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/24.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 2" loading="eager" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/24.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="2" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/37.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/37.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 3" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/37.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="3" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/40.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/40.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 4" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/40.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="4" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/44.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/44.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 5" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/300x406/44.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="5" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/50.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/50.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 6" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/50.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="6" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/54.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/54.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 7" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/54.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="7" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/56.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/56.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 8" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/56.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="8" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/59.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/59.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 9" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/59.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="9" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/63.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/63.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 10" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/63.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="10" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/70.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/70.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 11" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/70.jpg">
 </picture>
 </a>
 <div class="box-massage__icon-search">
 <i class="fa-solid fa-magnifying-glass"></i>
 </div>


 </div>
 </div><div class="box-massage__tumba tumba">
 <div class="box-massage__card tumba-card">

 <a data-index="11" class="box-massage__card-link slideshowGalleryImage" href="https://babesource.com/media/galleries/6b002a193e7c6/74.jpg" data-msrc="https://babesource.com/media/galleries/6b002a193e7c6/m1080/74.jpg.webp">
 <picture>
 <img alt="Liz Jordan - Deeper - photo 12" loading="lazy" src="https://media.babesource.com/galleries/6b002a193e7c6/thumbs/74.jpg">
 """
}
