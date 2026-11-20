package dev.pfeed

import dev.pfeed.source.tube.TubeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Xozilla, Analdin and KatesTube, against their pages as served in November 2026. */
class KvsSitesTest {
    @Test
    fun `xozilla listing and stream`() {
        val list = TubeSite.Xozilla.parseList(XOZ_LIST)
        assertEquals(2, list.size)
        assertEquals("850503", list[0].id)
        assertEquals("Curvy Blonde Explores Vintage Orgy Ecstasy", list[0].title)
        assertEquals(27 * 60 + 25, list[0].durationSec)
        assertTrue(list[0].thumb!!.endsWith("/300x169/6.jpg"))
        assertTrue(list[0].preview!!.endsWith("850503_vthumb.mp4"))
        // 720p over 540p.
        assertTrue(TubeSite.Xozilla.stream(XOZ_VIDEO)!!.url.contains("850503hd.mp4"))
        val tags = TubeSite.Xozilla.pageTags(XOZ_VIDEO)
        assertTrue(tags.toString(), "anal" in tags && "big dick" in tags)
        assertFalse(tags.toString(), tags.any { it.length > 25 })
    }

    @Test
    fun `analdin listing, stream, own tags and timeline`() {
        val list = TubeSite.Analdin.parseList(ANA_LIST)
        assertEquals(2, list.size)
        assertEquals("831228", list[0].id)
        assertTrue(list[0].title!!.startsWith("Inked Blond hair girl's Bum"))
        assertFalse(list[0].title!!.endsWith("..."))
        assertEquals(15 * 60, list[0].durationSec)
        assertTrue(list[0].thumb!!.startsWith("https://i.analdin.com/"))
        assertTrue(TubeSite.Analdin.stream(ANA_VIDEO)!!.url.contains("831228hd.mp4"))
        val tags = TubeSite.Analdin.pageTags(ANA_VIDEO)
        assertTrue(tags.toString(), "ass fucking" in tags && "tattoos" in tags)
        assertNotNull(TubeSite.Analdin.timeline(ANA_VIDEO))
    }

    @Test
    fun `katestube listing and stream`() {
        val list = TubeSite.KatesTube.parseList(KATE_LIST)
        assertEquals(2, list.size)
        assertEquals("3793113", list[0].id)
        assertEquals("German curvy MILF cures hangover with blowjob", list[0].title)
        assertEquals(8 * 60 + 8, list[0].durationSec)
        assertEquals("EroCom.TV", list[0].creator)
        assertNotNull(list[0].published)
        assertTrue(list[0].preview!!.endsWith("_preview.mp4"))
        // The 1080p file over 720p.
        val s = TubeSite.KatesTube.stream(KATE_VIDEO)!!
        assertTrue(s.url, s.url.contains("3793113_s.mp4"))
        assertEquals(listOf("amateur", "blowjob"), TubeSite.KatesTube.pageTags(KATE_VIDEO))
    }

    private val XOZ_LIST = """<a href="https://www.xozilla.xxx/videos/850503/curvy-blonde-explores-vintage-orgy-ecstasy/" class="item "
 vthumb="https://www.xozilla.xxx/get_file/13/8b92fe3a88bd27ecf908c8f7f51eac81/850000/850503/850503_vthumb.mp4/">
 <div class="mobile-preview"></div>
 <div class="img ithumb">
 <img class="thumb lazy-load"
 src="data:," data-original="https://www.xozilla.xxx/contents/videos_screenshots/850000/850503/300x169/6.jpg"
 alt="Curvy Blonde Explores Vintage Orgy Ecstasy"
 thumb="https://www.xozilla.xxx/contents/videos_screenshots/850000/850503/300x169/6.jpg"
 width="300"
 height="169"/>
 <!-- <span class="hd-label">HD</span> -->
 <!-- <div class="duration">27m:25s</div> -->
 </div>
 <strong class="title">Curvy Blonde Explores Vintage Orgy Ecstasy</strong>
 </a>
 <a href="https://www.xozilla.xxx/videos/850477/playful-milf-explores-new-pants-ecstasy/" class="item "
 vthumb="https://www.xozilla.xxx/get_file/13/32e400971bf653ef5f3f947314992008/850000/850477/850477_vthumb.mp4/">
 <div class="mobile-preview"></div>
 <div class="img ithumb">
 <img class="thumb lazy-load"
 src="data:," data-original="https://www.xozilla.xxx/contents/videos_screenshots/850000/850477/300x169/1.jpg"
 alt="Playful MILF Explores New Pants Ecstasy"
 thumb="https://www.xozilla.xxx/contents/videos_screenshots/850000/850477/300x169/1.jpg"
 width="300"
 height="169"/>
 <!-- <span class="hd-label">HD</span> -->
 <!-- <div class="duration">14m:40s</div> -->
 </div>
 <strong class="title">Playful MILF Explores New Pants Ecstasy</strong>
 </a>
 """
    private val XOZ_VIDEO = """var flashvars = {
 video_url: 'https://www.xozilla.xxx/get_file/13/acc64d79cda0ae56ba72829eb8cce413/850000/850503/850503.mp4/?v-acctoken=MTY4OHwxNDIzNzl8MHxhMTJkNGU5MjM0YmZhODhlNzA1YTExMzYwMTc1NjA3Yg',
 video_url_text: '540p',
 video_alt_url: 'https://www.xozilla.xxx/get_file/13/bdd41421639b1c3c3f7c1190d658c120/850000/850503/850503hd.mp4/?v-acctoken=MjYxOHwxNDIzNzl8MHwzNDI4MjA3NWFlYjg3NzQwMjY3MzA2ZGZlYmE4MTI1MQ',
 video_alt_url_text: '720p',
 preview_url: 'https://www.xozilla.xxx/contents/videos_screenshots/850000/850503/preview.jpg'
};
" class="tab-content">
 <div class="block-details">
<!-- <div class="block-user">
 <div class="username">
 <a href="https://www.xozilla.xxx/members/142379/">
 kiera.daugherty
 </a>
 </div>
 <a class="avatar" href="https://www.xozilla.xxx/members/142379/" title="kiera.daugherty">
 <span class="no-thumb ">no photo</span>
 </a>
 </div>
 -->
 <div class="info">
 <div class="item">
 <span><i class="fa fa-clock-o" aria-hidden="true"></i> Duration: <em>27min 25sec</em></span>
 <!-- <span><i class="fa fa-eye" aria-hidden="true"></i> Views: <em>185</em></span> -->
 <span><i class="fa fa-calendar-check-o" aria-hidden="true"></i> Submitted: <em>12 hours ago</em></span>
<!-- <span class="added-by">Submitted by:</span>
 -->
 </div>
 <div class="item">
 Sponsored by:
 <a href="https://www.xozilla.xxx/channels/noname/">noname</a>
 </div>
 <div class="item">
 <i class="fa fa-sliders" aria-hidden="true"></i>
 Categories:
 <a href="https://www.xozilla.xxx/categories/anal/">Anal</a>
 <a href="https://www.xozilla.xxx/categories/hardcore/">Hardcore</a>
 <a href="https://www.xozilla.xxx/categories/big-dick/">Big Dick</a>
 <a href="https://www.xozilla.xxx/categories/blowjob/">Blowjob</a>
 <a href="https://www.xozilla.xxx/categories/big-tits/">Big Tits</a>
 <a href="https://www.xozilla.xxx/categories/blonde/">Blonde</a>
 <a href="https://www.xozilla.xxx/categories/hd-porn-1080p/">hd porn 1080p</a>
 <a href="https://www.xozilla.xxx/categories/small-tits/">Small Tits</a>
 <a href="https://www.xozilla.xxx/categories/vintage/">Vintage</a>
 <a href="https://www.xozilla.xxx/categories/orgy/">Orgy</a>
 <a href="https://www.xozilla.xxx/categories/hard-fuck/">Hard Fuck</a>
 <a href="https://www.xozilla.xxx/categories/hard/">Hard</a>
 <a href="https://www.xozilla.xxx/categories/brunettes/">Brunettes</a>
 </div>
 <div class="item">
 <i class="fa fa-tags" aria-hidden="true"></i>
 Tags:
 <a href="https://www.xozilla.xxx/tags/big-tits/">big tits</a>
 <a href="https://www.xozilla.xxx/tags/hardcore/">hardcore</a>
 <a href="https://www.xozilla.xxx/tags/big-dick/">big dick</a>
 <a href="https://www.xozilla.xxx/tags/blowjob/">blowjob</a>
 <a href="https://www.xozilla.xxx/tags/blonde/">blonde</a>
 <a href="https://www.xozilla.xxx/tags/brunette/">brunette</a>
 <a href="https://www.xozilla.xxx/tags/small-tits/">Small Tits</a>
 <a href="https://www.xozilla.xxx/tags/vintage/">vintage</a>
 <a href="https://www.xozilla.xxx/tags/bridgette/">bridgette</a>
 <a href="https://www.xozilla.xxx/tags/hard-fuck/">Hard Fuck</a>
 <a href="https://www.xozilla.xxx/tags/4991c4e00aeb5bf6096de5e6197c3299/">-</a>
 </div>
 <div class="item">
 Models:
 <a href="https://www.xozilla.xxx/models/bridgette-kerkove/">Bridgette Kerkove</a>
 </div>
 </div>
 </div>
 </div>
 <div id="tab_report_rrror" class="tab-content hidden">
 <div class="block-flagging">
 <form method="post">
 <div class="generic-error hidden"></div>
 <div class="success hidden">Thank you! We appreciate your help.</div>
 <div class="block-radios">
 <label class="field-label">Report this video as</label>
 <div class="row">
 <input type="radio" id="flag_inappropriate_video" name="flag_id" value="flag_inappropriate_video" class="radio" >
 <label
<div class="menu"></div>"""
    private val ANA_LIST = """<div class="item ">

 
 <a class="popup-video-link"

 href="https://www.analdin.com/videos/831228/inked-blond-hair-girl-s-bum-fleshy-of-ejaculant-2-lets-try-ass-fucking/" 
 data-rt="" 
 thumb="https://i.analdin.com/contents/videos_screenshots/831000/831228/293x165/24.jpg"
 vthumb="https://www.analdin.com/get_file/3/458a03fab908c4b95faa61f196b1244c/831000/831228/831228_vthumb.mp4/">
 <div class="mobile-preview"></div>
 <div class="img ithumb">
 <img class="thumb lazy-load" 
 src="data:," data-original="https://i.analdin.com/contents/videos_screenshots/831000/831228/293x165/24.jpg"
 
 width="293"
 height="165"/>
 
 <!--<div class="hd"> </div> --> 
 <!-- <div class="duration">15:00</div>--> 
 </div>
 <strong class="title">
 Inked Blond hair girl's Bum Fleshy Of Ejaculant 2 - ...
 </strong>
 </a>
 </div>
 <div class="item ">

 
 <a class="popup-video-link"

 href="https://www.analdin.com/videos/830981/roller-dirty-2-pervs-on-patrol/" 
 data-rt="" 
 thumb="https://i.analdin.com/contents/videos_screenshots/830000/830981/293x165/3.jpg"
 vthumb="https://www.analdin.com/get_file/20/b1b0e27c6e60c076284b02d02c2c9706/830000/830981/830981_vthumb.mp4/">
 <div class="mobile-preview"></div>
 <div class="img ithumb">
 <img class="thumb lazy-load" 
 src="data:," data-original="https://i.analdin.com/contents/videos_screenshots/830000/830981/293x165/3.jpg"
 
 width="293"
 height="165"/>
 
 <!--<div class="hd"> </div> --> 
 <!-- <div class="duration">15:00</div>--> 
 </div>
 <strong class="title">
 Roller Dirty 2 - Pervs On Patrol
 </strong>
 </a>
 </div>
 """
    private val ANA_VIDEO = """var flashvars = {
 video_url: 'https://www.analdin.com/get_file/3/dd8cc2ccfff0075853de300f07de476b/831000/831228/831228.mp4/?v-acctoken=MjE0M3w1OTIzMTd8MHxmNGI4MWNhYzQyNWIzNDFiY2FjZWY1MzUyZDgzNjQxNw',
 video_url_text: '540p',
 video_alt_url: 'https://www.analdin.com/get_file/3/e4b69d64167a5b58a258206294a0d9a6/831000/831228/831228hd.mp4/?v-acctoken=MzIzNHw1OTIzMTd8MHw5MmE0OGI0YTU5NmE1YWZmYjNjYTc4MjI3YTUzNDY5OA',
 video_alt_url_text: '720p',
 timeline_screens_url: 'https://i.analdin.com/contents/videos_screenshots/831000/831228/timelines/timelines/150x84/{time}.jpg',
 timeline_screens_interval: '5',
 timeline_screens_count: '180',
 preview_url: 'https://i.analdin.com/contents/videos_screenshots/831000/831228/preview.jpg'
};
ecodeURIComponent(a[0])] = decodeURIComponent(a[1]);
 return p;
 },
 {}
 );
 if (params['comment'] == 'true') {
 ${'$'}('.comments-box').slideDown(300);
 ${'$'}('html, body').animate({ scrollTop: ${'$'}('.comments-box').offset().top - ${'$'}(window).height() + 200 }, 1000);
 }
 });
 </script>
 </div>
 <div id="tab_video_info" class="tab-content">
 <div class="block-details">
<!-- <div class="block-user">
 <div class="username">
 <a href="https://www.analdin.com/members/592317/">
 ContentAdmin
 </a>
 </div>
 <a class="avatar" href="https://www.analdin.com/members/592317/" title="ContentAdmin">
 <span class="no-thumb ">no avatar</span>
 </a>
 </div>
 -->
 <div class="info">
 <div class="item">
 <span class="hp-info-item-label">Channel:</span>
 <div style="overflow: hidden;">
 <a href="https://www.analdin.com/channels/mofos/">Mofos</a>
 </div>
 </div>
 <div class="item">
 <span class="hp-info-item-label">Categories:</span>
 <div style="overflow: hidden;">
 <a href="https://www.analdin.com/categories/ass-fucking/">Ass Fucking</a>
 <a href="https://www.analdin.com/categories/tattoos/">Tattoos</a>
 <a href="https://www.analdin.com/categories/blondes/">Blondes</a>
 <a href="https://www.analdin.com/categories/ass/">Ass</a>
 <a href="https://www.analdin.com/categories/chubby/">Chubby</a>
 <a href="https://www.analdin.com/categories/cum/">Cum</a>
 <a href="https://www.analdin.com/categories/sex/">Sex</a>
 <a href="https://www.analdin.com/categories/pov/">POV</a>
 <a href="https://www.analdin.com/categories/anal/">Anal</a>
 <a href="https://www.analdin.com/categories/doggy-style/">Doggy Style</a>
 <a href="https://www.analdin.com/categories/missionary/">Missionary</a>
 <a href="https://www.analdin.com/categories/spanking/">Spanking</a>
 <a href="https://www.analdin.com/categories/reverse-cowgirl/">Reverse Cowgirl</a>
 <a href="https://www.analdin.com/categories/creampie/">Creampie</a>
 <a href="https://www.analdin.com/categories/caucasian/">Caucasian</a>
 </div>
 </div>
 <div class="item">
 <span class="hp-info-item-label">Tags:</span>
 <div style="overflow: hidden;">
 <a href="https://www.analdin.com/tags/caucasian/">caucasian</a>
 <a href="https://www.analdin.com/tags/anal-creampie/">anal creampie</a>
 <a href="https://www.analdin.com/tags/creampie/">creampie</a>
 <a href="https://www.analdin.com/tags/anal-fingering/">anal fingering</a>
 <a href="https://www.analdin.com/tags/pussy-fingering/">pussy fingering</a>
 <a href="https://www.analdin.com/tags/spanking/">spanking</a>
 <a href="https://www.analdin.com/tags/doggystyle/">doggystyle</a>
 <a href="https://www.analdin.com/tags/missionary/">missionary</a>
 <a href="https://www.analdin.com/tags/reverse-cowgirl/">reverse cowgirl</a>
 <a href="https://www.analdin.com/tags/ass-fuck/">ass fuck</a>
 <a href="https://www.analdin.com/tags/ass-fucking/">ass fucking</a>
 <a href="https://www.analdin.com/tags/ass-fuckin/">ass fuckin</a>
 <a href="https://www.analdin.com/tags/a7b1cedebd838949b4e2a201c9600265/">-</a>
 <a href="https://www.analdin.com/tags/blond-hair/">Blond Hair</a>
 <a href="https://www.analdin.com/tags/s/">s</a>
 <a href="https://www.analdin.com/tags/of/">OF</a>
 <a href="https://www.analdin.com/tags
<div class="menu"><a href="https://www.analdin.com/categories/18-years-old/">18 Years Old</a><a href="https://www.analdin.com/categories/3d/">3d</a><a href="https://www.analdin.com/categories/4k-ultra-hd/">4k ultra hd</a><a href="https://www.analdin.com/categories/60-fps/">60 fps</a><a href="https://www.analdin.com/categories/69/">69</a></div>"""
    private val KATE_LIST = """<div class="thumb" itemscope itemtype="https://schema.org/VideoObject">
 <a class="ponn" href="https://www.katestube.com/videos/3793113/german-curvy-milf-cures-hangover-with-blowjob/" itemprop="url">
 <span class="img" data-id="3793113" data-poster="https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3793000/3793113/340x255/1.jpg?ver=3" data-src="https://cdn.katestube.com/3793000/3793113/3793113_preview.mp4">
 <img src="https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3793000/3793113/340x255/1.jpg?ver=3" fetchpriority="high" width="340" height="255" alt="German curvy MILF cures hangover with blowjob" >
 <span class="info">
 <meta itemprop="name" content="German curvy MILF cures hangover with blowjob">
 <meta itemprop="thumbnailUrl" content="https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3793000/3793113/340x255/1.jpg?ver=3">
 <meta itemprop="uploadDate" content="2026-11-03T23:50:11+02:00">
 <meta itemprop="duration" content="PT8M08S">
 <meta itemprop="description" content="Curvy Rosella Extrem is in the mood for a private blowjob. She suffers from a hangover after partying the previous night and believes semen cures headaches. She takes her African partner and sucks his large penis until he climaxes. She accepts the ejaculate directly in her mouth and swallows it. Afterwards, both she and the man feel much better, as he rarely experiences such an intense performance.">
 <span class="length">8:08</span>
 </span>
 <span class="icon-new">New</span>
 </span>
 </a>
 <span class="thumb-info">
 <span class="avatar">
 <a class="ponn" href="https://www.katestube.com/members/420102/"><span class="color_e">E</span></a>
 </span>
 <span class="info-inner">
 <a class="ponn" href="https://www.katestube.com/videos/3793113/german-curvy-milf-cures-hangover-with-blowjob/" itemprop="url"><span class="title">German curvy MILF cures hangover with blowjob</span></a>
 
 <span class="thumb-members">
 <span class="thumb-submitter"><a class="ponn" href="https://www.katestube.com/members/420102/">EroCom.TV</a></span>
 <span class="views">0 </span> </span>
 </span>
 </span>

 <div class="dots">
 <b></b>
 <div class="drop">
 <span class="block-fav">
 <button title="Favorite" data-id="3793113">Add To Favorite</button>
 <span class="success">Video Added</span>
 <span class="fail">You have to <a href="https://www.katestube.com/login.php">Login</a></span>
 </span>
 <span class="subs">
 <button title="Subscribe to EroCom.TV" data-id="420102">Subscribe to EroCom.TV</button>
 <span class="success">Subscribed</span>
 <span class="fail">You have to <a href="https://www.katestube.com/login.php">Login</a></span>
 </span>
 <a href="/" data-page="https://www.katestube.com/videos/3793113/german-curvy-milf-cures-hangover-with-blowjob/" class="btn-share">Share</a>
 <span class="btn-date">12 minutes ago</span>
 <span class="btn-quality">720p</span>
 </div>
 </div>
 </div>
 <div class="thumb" itemscope itemtype="https://schema.org/VideoObject">
 <a class="ponn" href="https://www.katestube.com/videos/3799228/anything-but-a-girl-next-door/" itemprop="url">
 <span class="img" data-id="3799228" data-poster="https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3799000/3799228/340x255/6.jpg?ver=3" data-src="https://cdn.katestube.com/3799000/3799228/3799228_preview.mp4">
 <img src="https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3799000/3799228/340x255/6.jpg?ver=3" fetchpriority="high" width="340" height="255" alt="anything but a girl next door" >
 <span class="info">
 <meta itemprop="name" content="anything but a girl next door">
 <meta itemprop="thumbnailUrl" content="https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3799000/3799228/340x255/6.jpg?ver=3">
 <meta itemprop="uploadDate" content="2026-11-16T20:34:53+02:00">
 <meta itemprop="duration" content="PT31M19S">
 <meta itemprop="description" content="anything but a girl next door">
 <span class="length">31:19</span>
 </span>
 <span class="icon-new">New</span>
 </span>
 </a>
 <span class="thumb-info">
 <span class="avatar">
 <a class="ponn" href="https://www.katestube.com/members/498837/"><span class="color_s">s</span></a>
 </span>
 <span class="info-inner">
 <a class="ponn" href="https://www.katestube.com/videos/3799228/anything-but-a-girl-next-door/" itemprop="url"><span class="title">anything but a girl next door</span></a>
 
 <span class="thumb-members">
 <span class="thumb-submitter"><a class="ponn" href="https://www.katestube.com/members/498837/">sarahbd7</a></span>
 <span class="views">18 </span> </span>
 </span>
 </span>

 <div class="dots">
 <b></b>
 <div class="drop">
 <span class="block-fav">
 <button title="Favorite" data-id="3799228">Add To Favorite</button>
 <span class="success">Video Added</span>
 <span class="fail">You have to <a href="https://www.katestube.com/login.php">Login</a></span>
 </span>
 <span class="subs">
 <button title="Subscribe to sarahbd7" data-id="498837">Subscribe to sarahbd7</button>
 <span class="success">Subscribed</span>
 <span class="fail">You have to <a href="https://www.katestube.com/login.php">Login</a></span>
 </span>
 <a href="/" data-page="https://www.katestube.com/videos/3799228/anything-but-a-girl-next-door/" class="btn-share">Share</a>
 <span class="btn-date">16 minutes ago</span>
 <span class="btn-quality">720p</span>
 </div>
 </div>
 </div>
 """
    private val KATE_VIDEO = """var flashvars = {
 video_url: 'https://www.katestube.com/get_file/1/644e583d9bb0c16374c940164f8fabc1/3793000/3793113/3793113.mp4/?v-acctoken=MTMwOHw0MjAxMDJ8MHwwYWE1NzBkODhiNWZmYTY3NjE4YTA5NWFmOGU1YjllNQ7fa3302bcbac772f',
 video_url_text: '720p',
 video_alt_url: 'https://www.katestube.com/get_file/1/7a839322a4e602ad4d4709f115146f39/3793000/3793113/3793113_s.mp4/?v-acctoken=ODYyfDQyMDEwMnwwfGI0YTc2MTMyYTExMzc3YTNiMzYyMzhjODhlYzdjN2Rm93e963cc9297a1fa',
 video_alt_url_text: '1080p',
 timeline_screens_url: 'https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3793000/3793113/timelines/mp4/200x116/{time}.jpg',
 timeline_screens_interval: '10',
 timeline_screens_count: '49',
 preview_url: 'https://c8b8a8134e.mjedge.net/contents/videos_screenshots/3793000/3793113/preview.jpg'
};
 params['container_id'] = 'flagging_container';
 params['button_id'] = 'flag_inappropriate_video';
 params['flag_external_id'] = 'flag_inappropriate_video';
 params['hint_message_id'] = 'flagging_hint';
 params['success_message_id'] = 'flagging_success';
 params['failure_message_id'] = 'flagging_failure';
 params['video_id'] = 3793113;
 videoViewEnableFlagging(params);
 </script> 
 
 <div class="block-more more-mobile">
 <div class="row"><span class="desc">Curvy Rosella Extrem is in the mood for a private blowjob. She suffers from a hangover after partying the previous night and believes semen cures headaches. She takes her African partner and sucks his large penis until he climaxes. She accepts the ejaculate directly in her mouth and swallows it. Afterwards, both she and the man feel much better, as he rarely experiences such an intense performance.</span></div> 
 <div class="row">
 <span>Categories:</span>
 <b><a class="btn-red" href="https://www.katestube.com/categories/amateur/" title="Free amateur teen tubes">Amateur</a><a class="btn-red" href="https://www.katestube.com/categories/blowjob/" title="Free blowjob teen tube">Blowjob</a></b>
 </div>
 <div class="row">
 <span>Video Quality:</span>
 <b class="btn-red">720p</b>
 </div>
 </div><!-- block-more -->
 </div>
 <div class="block-report pop-report">
 <button class="report-close">x</button>
 <form id="report_form" action="#" method="post">
 <input name="action" value="send" type="hidden">
 <div class="row">
 <div id="report_sel">
 <b class="title">Flag Video</b>
 <div class="row">
 <input id="Inappropriate" name="rad" type="radio">
 <label for="Inappropriate">Inappropriate</label>
 </div>
 <div class="row">
 <input id="copyright" name="rad" type="radio">
 <label for="copyright">Copyright <a rel="nofollow" href="https://www.katestube.com/dmca/">DMCA</a></label>
 </div>
 <div class="row">
 <input id="non_consensual" name="rad" type="radio">
 <label for="non_consensual">Non-Consensual <a rel="nofollow" href="https://www.katestube.com/ncii/">NCII</a></label>
 </div>
 <div class="row">
 <input id="video_not_playing" name="rad" type="radio">
 <label for="video_not_playing">Video Not Playing, Please Specify Your Country</label>
 </div>
 <div class="row">
 <input id="other" name="rad" type="radio">
 <label for="other">Report malware/virus - Please provide us with as much detailed information as possible in the Reason box: Country, malware URL, Browser</label>
 </div>
 </div>
 <div id="report-text">
 <div class="row">
 <label class="title" for="report_message">Reason</label>
 <textarea id="report_message" cols="30" rows="10"></textarea>
 <span class="report_empty g_hidden">The field is required</span>
 </div>
 </div>
 </div>
 <div class="row">
 <div class="g-recaptcha" data-sitekey="6LfHsJ4UAAAAABuv-mdgMLAG3oKbSQYHRsnfope5"></div>
 <div class="recaptcha-error field_error g_hidden">Please tick the check box</div>
 </div>
 <div class="row">
 <input class="submit send" value="Send" type="submit">
 </div>
 </form>
 <div class="row row-report">
 <span class="report_err g_hidden">You have already reported this video!</span>
 <span class="report_empty g_hidden">The field is required</span>
 </div>
 </div><!-- block-report --> 
 <div class="video-options long">
 <div class="top-spot" id="mobile-under-video" data-banner="1718048">
 <a style="text-align:center; width:300px; color:#FFFFFF; font-size:11px; margin:0 auto 2px; font-weight:normal;display:block;" class="remove-spots" href="https://www.katestube.com/signup.php">Re
<div class="menu"></div>"""
}
