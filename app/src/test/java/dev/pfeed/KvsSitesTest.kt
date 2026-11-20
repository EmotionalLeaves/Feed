package dev.pfeed

import dev.pfeed.source.tube.TubeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Analdin, KatesTube and EMPFlix, against their pages as served in November 2026. */
class KvsSitesTest {
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

    @Test
    fun `empflix shares tnaflix's template`() {
        val list = TubeSite.Empflix.parseList(EMP_LIST)
        assertEquals(2, list.size)
        assertEquals("3825592", list[0].id)
        assertEquals("Wife Lies to Cuck on Phone Keeps Sucking Lover", list[0].title)
        assertEquals("hentaifushigi", list[0].creator)
        assertEquals(207, list[0].durationSec)
        assertTrue(list[0].pageUrl.startsWith("https://www.empflix.com/"))
        assertTrue(list[0].preview!!.contains("trailer.mp4"))
        assertTrue(TubeSite.Tnaflix.listUrl("new", 0).startsWith("https://www.tnaflix.com/new/"))
    }

    private val EMP_LIST = """<div data-vid="3825592" data-num="1" class="col-xs-6 col-md-4 col-lg-4 col-xl-3 mb-3" data-first-thumb="8">
 
 <a class="thumb thumb-chrome video-thumb bg-dark" href="https://www.empflix.com/amateur-porn/wife-lies-to-cuck-on-phone-keeps-sucking-lover/video3825592" data-trailer="https://cdnl.empflix.com/227/38/25/3825592/trailer.mp4?secure=1dnbUtFAZobbVSIapile0A==,1795269638">

 <img fetchpriority="high" src="https://cdnl.empflix.com/thumb/a16:8q80w356r/227/38/25/3825592/8.jpg" alt="Wife Lies to Cuck on Phone Keeps Sucking Lover" width="300" height="150" onerror="this.src='/assets/img/video_cover_placeholder.jpg'" />
 
 <div class="thumb-icon video-duration">03:27</div>
 <div class="thumb-icon max-quality">1080p</div>
 </a>

 <a href="https://www.empflix.com/amateur-porn/wife-lies-to-cuck-on-phone-keeps-sucking-lover/video3825592" class="video-title text-break">
 Wife Lies to Cuck on Phone Keeps Sucking Lover </a>

 <div class="d-flex">
 <a href="https://www.empflix.com/profile/hentaifushigi" class="me-2 text-start text-truncate badge badge-video-info badge-unverified">
 hentaifushigi </a>
 
 <div class="my-auto text-truncate text-small d-flex">
 <div class="me-2">
 <i class="icon-eye"></i>344 </div>
 </div>

 </div>
 </div>
 
 <div data-vid="3825589" data-num="2" class="col-xs-6 col-md-4 col-lg-4 col-xl-3 mb-3" data-first-thumb="23">
 
 <a class="thumb thumb-chrome video-thumb bg-dark" href="https://www.empflix.com/amateur-porn/plumber-gets-along-with-a-naughty-and-hot-housewife/video3825589" data-trailer="https://cdnl.empflix.com/227/38/25/3825589/trailer.mp4?secure=SpOZbkNisVBAssyqIDw2Dg==,1795269638">

 <img class="lazyload" src="/assets/img/video_cover_placeholder.jpg" data-src="https://cdnl.empflix.com/thumb/a16:8q80w356r/227/38/25/3825589/23.jpg" alt="Plumber gets along with a naughty and hot housewife" width="300" height="150" onerror="this.src='/assets/img/video_cover_placeholder.jpg'" />
 
 <div class="thumb-icon video-duration">02:59</div>
 <div class="thumb-icon max-quality">1080p</div>
 </a>

 <a href="https://www.empflix.com/amateur-porn/plumber-gets-along-with-a-naughty-and-hot-housewife/video3825589" class="video-title text-break">
 Plumber gets along with a naughty and hot housewife </a>

 <div class="d-flex">
 <a href="https://www.empflix.com/profile/hentaifushigi" class="me-2 text-start text-truncate badge badge-video-info badge-unverified">
 hentaifushigi </a>
 
 <div class="my-auto text-truncate text-small d-flex">
 <div class="me-2">
 <i class="icon-eye"></i>346 </div>
 </div>

 </div>
 </div>
 
 """

    @Test
    fun `sleazyneasy shares katestube's template`() {
        val list = TubeSite.SleazyNeasy.parseList(SLEAZY_LIST)
        assertEquals(2, list.size)
        assertEquals("3293871", list[0].id)
        assertEquals("She really did let me see her panties and lick her pussy", list[0].title)
        assertEquals(8 * 60, list[0].durationSec)
        assertEquals("gody80", list[0].creator)
        assertTrue(list[0].preview!!.endsWith("3293871_preview.mp4"))
        assertTrue(TubeSite.VikiPorn.listUrl("milf", 0).startsWith("https://www.vikiporn.com/search/?q=milf"))
    }

    private val SLEAZY_LIST = """<a href="https://www.sleazyneasy.com/videos/3293871/she-really-did-let-me-see-her-panties-and-lick-her-pussy/" itemprop="url">
 <span class="img" data-id="3293871" data-poster="https://c8c116ce62.mjedge.net/contents/videos_screenshots/3293000/3293871/343x257/2.jpg?ver=3" data-src="https://cdn.sleazyneasy.com/3293000/3293871/3293871_preview.mp4">
 
 <img fetchpriority="high" class="lazy" src="https://c8c116ce62.mjedge.net/images_new/placeholder.gif?ver=3" data-original="https://c8c116ce62.mjedge.net/contents/videos_screenshots/3293000/3293871/343x257/2.jpg?ver=3" width="343" height="257" alt="She really did let me see her panties and lick her pussy" >
 <meta itemprop="name" content="She really did let me see her panties and lick her pussy">
 <meta itemprop="thumbnailUrl" content="https://c8c116ce62.mjedge.net/contents/videos_screenshots/3293000/3293871/343x257/2.jpg?ver=3">
 <meta itemprop="uploadDate" content="2026-11-10T03:21:29+02:00">
 <meta itemprop="duration" content="PT8M00S">
 <meta itemprop="description" content="Kirsten Lee is flirting with her roommate Alison Rey asking her if she wanna let her sniff her panties.Alison takes her to bed,shes rubbing her clit while sucking her small tits.Kirsten is licking her shaved pussy then gets on top facesitting her.">
 <span class="length">
 <i>8:00</i>
 </span>
 <span class="icon-new">New</span>
 </span>
 </a>
 <div class="thumb-info">
 <div class="avatar">
 <a class="color_g" href="https://www.sleazyneasy.com/members/732/"><img width="36" height="36" loading="lazy" src="https://www.sleazyneasy.com/contents/avatars/0/732.jpg?rand=20261119122757" alt="gody80"></a>
 </div>
 <div class="info-inner">
 <span class="title"><a href="https://www.sleazyneasy.com/videos/3293871/she-really-did-let-me-see-her-panties-and-lick-her-pussy/">She really did let me see her panties and lick her pussy</a></span>
 <span class="thumb-members">
 <span class="thumb-submitter">
 <a href="https://www.sleazyneasy.com/members/732/">gody80</a>
 </span>
 <span class="views">36 </span>
 </span>
 </div>
 </div>
 <div class="dots">
 <b></b>
 <div class="drop">
 <span class="block-fav">
 <button title="Favorite" data-id="3293871">Add To Favorite</button>
 <span class="success">Video Added</span>
 <span class="fail">You have to <a href="https://www.sleazyneasy.com/login.php">Login</a></span>
 </span>
 <span class="subs">
 <button title="Subscribe to gody80" data-id="732">Subscribe to gody80</button>
 <span class="success">Subscribed</span>
 <span class="fail">You have to <a href="https://www.sleazyneasy.com/login.php">Login</a></span>
 </span>
 <a href="/" data-page="https://www.sleazyneasy.com/videos/3293871/she-really-did-let-me-see-her-panties-and-lick-her-pussy/" class="btn-share">Share</a>
 <span class="btn-date">1 hour ago</span>
 <span class="btn-quality">720p</span>
 </div>
 </div>
 </div>
 </div>
 <div class="thumb" itemscope itemtype="https://schema.org/VideoObject">
 <div class="thumb-inner">
 <a href="https://www.sleazyneasy.com/videos/3293051/we-pick-up-pretty-young-things-like-you-and-fuck-em-at-home/" itemprop="url">
 <span class="img" data-id="3293051" data-poster="https://c8c116ce62.mjedge.net/contents/videos_screenshots/3293000/3293051/343x257/2.jpg?ver=3" data-src="https://cdn.sleazyneasy.com/3293000/3293051/3293051_preview.mp4">
 
 <img fetchpriority="high" class="lazy" src="https://c8c116ce62.mjedge.net/images_new/placeholder.gif?ver=3" data-original="https://c8c116ce62.mjedge.net/contents/videos_screenshots/3293000/3293051/343x257/2.jpg?ver=3" width="343" height="257" alt="We pick up pretty young things like you and fuck em at home" >
 <meta itemprop="name" content="We pick up pretty young things like you and fuck em at home">
 <meta itemprop="thumbnailUrl" content="https://c8c116ce62.mjedge.net/contents/videos_screenshots/3293000/3293051/343x257/2.jpg?ver=3">
 <meta itemprop="uploadDate" content="2026-11-08T23:34:17+02:00">
 <meta itemprop="duration" content="PT8M00S">
 <meta itemprop="description" content="Petite Gracie May Green is picked up by couple and taken home.Milf Kissa Sins offers her their car if she fucks them.The small tits babe is deepthroat facefucked and banged rough while licking pussy.She tastes the brunette cougars squirt">
 <span class="length">
 <i>8:00</i>
 </span>
 <span class="icon-new">New</span>
 </span>
 </a>
 <div class="thumb-info">
 <div class="avatar">
 <a class="color_d" href="https://www.sleazyneasy.com/members/605/"><img width="36" height="36" loading="lazy" src="https://www.sleazyneasy.com/contents/avatars/0/605.jpg?rand=20261119122757" alt="desomore"></a>
 </div>
 <div class="info-inner">
 <span class="title"><a href="https://www.sleazyneasy.com/videos/3293051/we-pick-up-pretty-young-things-like-you-and-fuck-em-at-home/">We pick up pretty young things like you and fuck em at home</a></span>
 <span class="thumb-members">
 <span class="thumb-submitter">
 <a href="https://www.sleazyneasy.com/members/605/">desomore</a>
 </span>
 <span class="views">29 </span>
 </span>
 </div>
 </div>
 <div class="dots">
 <b></b>
 <div class="drop">
 <span class="block-fav">
 <button title="Favorite" data-id="3293051">Add To Favorite</button>
 <span class="success">Video Added</span>
 <span class="fail">You have to <a href="https://www.sleazyneasy.com/login.php">Login</a></span>
 </span>
 <span class="subs">
 <button title="Subscribe to desomore" data-id="605">Subscribe to desomore</button>
 <span class="success">Subscribed</span>
 <span class="fail">You have to <a href="https://www.sleazyneasy.com/login.php">Login</a></span>
 </span>
 <a href="/" data-page="https://www.sleazyneasy.com/videos/3293051/we-pick-up-pretty-young-things-like-you-and-fuck-em-at-home/" class="btn-share">Share</a>
 <span class="btn-date">1 hour ago</span>
 <span class="btn-quality">720p</span>
 </div>
 </div>
 </div>
 </div>
 <div class="thumb" itemscope itemtype="https://schema.org/VideoObject">
 <div class="thumb-inner">
 """

    @Test
    fun `alotporn listing, stream and genres`() {
        val list = TubeSite.AlotPorn.parseList(ALOT_LIST)
        assertEquals(2, list.size)
        assertEquals("640149", list[0].id)
        assertEquals("The teacher gave the student a reward for his work", list[0].title)
        assertEquals(30 * 60 + 58, list[0].durationSec)
        assertTrue(list[0].preview!!.contains("640149_preview.mp4"))
        assertTrue(TubeSite.AlotPorn.stream(ALOT_VIDEO)!!.url.endsWith("640149_720p.mp4"))
        val tags = TubeSite.AlotPorn.pageTags(ALOT_VIDEO)
        assertTrue(tags.toString(), "teens" in tags && "big boobs" in tags && "teacher" in tags)
    }

    private val ALOT_LIST = """<a href="https://www.alotporn.com/video/640149/the-teacher-gave-the-student-a-reward-for-his-work/" title="The teacher gave the student a reward for his work" >
 <div class="img-holder">
 <img loading="lazy" src="https://bcdn.alotporn.com/contents/videos_screenshots/640000/640149/480x270/1.jpg" alt="The teacher gave the student a reward for his work" data-preview="https://www.alotporn.com/get_file/6/d2543c04040d503c14dbad7182f587e2/640000/640149/640149_preview.mp4/" width="480" height="270"/>
 
 <div class="item-bottom">
 <div class="time">30:58</div>
 </div>
 <div class="item-top">
 
 <div class="item-heart " title="Add to Favorites" data-fav-video-id="640149" data-fav-type="0">
 <svg class="svg-icon icon-heart"><use xlink:href="#icon-heart"></use></svg>
 </div>
 
 <div class="item-time " title="Watch Later" data-fav-video-id="640149" data-fav-type="1">
 <svg class="svg-icon icon-oclock"><use xlink:href="#icon-oclock"></use></svg>
 </div>
 </div>
 </div>
 <div class="title">
 The teacher gave the student a reward for his work
 </div>
 </a>
 </div>
 <div class="thumb thumb_rel item ">
 <a href="https://www.alotporn.com/video/637231/a-latina-with-a-big-ass-fucks-her-stepfather-one-on-one/" title="A Latina with a big ass fucks her stepfather one on one" >
 <div class="img-holder">
 <img loading="lazy" src="https://bcdn.alotporn.com/contents/videos_screenshots/637000/637231/480x270/1.jpg" alt="A Latina with a big ass fucks her stepfather one on one" data-preview="https://www.alotporn.com/get_file/6/c3c791e68d9396753764cbb2c20d1dda/637000/637231/637231_preview.mp4/" width="480" height="270"/>
 
 <div class="item-bottom">
 <div class="time">8:01</div>
 </div>
 <div class="item-top">
 
 <div class="item-heart " title="Add to Favorites" data-fav-video-id="637231" data-fav-type="0">
 <svg class="svg-icon icon-heart"><use xlink:href="#icon-heart"></use></svg>
 </div>
 
 <div class="item-time " title="Watch Later" data-fav-video-id="637231" data-fav-type="1">
 <svg class="svg-icon icon-oclock"><use xlink:href="#icon-oclock"></use></svg>
 </div>
 </div>
 </div>
 <div class="title">
 A Latina with a big ass fucks her stepfather one on one
 </div>
 </a>
 </div>
 <div class="thumb thumb_rel item ">
 """
    private val ALOT_VIDEO = """ve-the-student-a-reward-for-his-work/", "name": "The teacher gave the student a reward for his work"}}
 ]
}
</script>

<script type="application/ld+json">
{
 "@context": "https://schema.org",
 "@type": "VideoObject",
 "@id": "#640149",
 "mainEntityOfPage": "https://www.alotporn.com/video/640149/the-teacher-gave-the-student-a-reward-for-his-work/",
 "name": "The teacher gave the student a reward for his work",
 "description": "The Teacher Rewards The Student",
 "thumbnailUrl": ["https://bcdn.alotporn.com/contents/videos_screenshots/640000/640149/preview_360p.mp4.jpg"],
 "uploadDate": "2026-11-20T08:59:13Z",
 "duration": "PT0H30M58S",
 "contentUrl": "https://www.alotporn.com/get_file/6/e9bee96d296c92c4c1a3410c4f900724/640000/640149/640149_720p.mp4/"
 ,
 "genre": ["Teens +18","Amateur","Big Boobs","Teacher","Facial","Castings"]
 ,
 "publisher": {
 "@type": "Organization", 
 "name": "AlotPorn", 
 "logo": {
 "@type": "ImageObject",
 "url": "https://bcdn.alotporn.com/contents/alp/theme/logo.png?v=1" 
 }
 },
 "interactionStatistic": [
 {"@type": "InteractionCounter", "interactionType": {"@type": "WatchAction"}, "userInteractionCount": 122},
 {"@type": "InteractionCounter", "interactionType": {"@type": "LikeAction"}, "userInteractionCount": 1}
 ],
 "isFamilyFriendly": false,
 "contentRating": "X18"
}
</script>

 <link href="https://www.alotporn.com/video/640149/the-teacher-gave-the-student-a-reward-for-his-work/" rel="canonical"/>
 <meta property="og:url" content="https://www.alotporn.com/video/640149/the-teacher-gave-the-student-a-reward-for-his-work/"/>
 
 <meta property="og:title" content="The teacher gave the st
<video id="my-video" poster="https://bcdn.alotporn.com/contents/videos_screenshots/640000/640149/preview_360p.mp4.jpg" fetchpriority="high" playsinline webkit-playsinline >
<source src='https://www.alotporn.com/get_file/6/8e5c4c7222788f6202d0c36ac0eb9014/640000/640149/640149_360p.mp4' title="SD" type='video/mp4' />
<source src='https://www.alotporn.com/get_file/6/e9bee96d296c92c4c1a3410c4f900724/640000/640149/640149_720p.mp4' title="HD" type='video/mp4' />
</video>"""

    @Test
    fun `kvs default theme`() {
        val list = TubeSite.FreePorn8.parseList(FP8_LIST)
        assertEquals(2, list.size)
        assertEquals("1456783", list[0].id)
        assertEquals("My neighbour asked me for a fine bang for his birthday.", list[0].title)
        assertEquals(10 * 60 + 25, list[0].durationSec)
        assertTrue(list[0].thumb!!.endsWith("/320x180/2.jpg"))
        assertTrue(list[0].preview!!.contains("1456783_preview.mp4"))
    }

    private val FP8_LIST = """<a href="https://www.freeporn8.com/videos/1456783/my-neighbour-asked-me-for-a-fine-bang-for-his-birthday/" title="My neighbour asked me for a fine bang for his birthday." >
 <div class="img">
 <img class="thumb " src="https://www.freeporn8.com/contents/videos_screenshots/1456000/1456783/320x180/2.jpg" alt="My neighbour asked me for a fine bang for his birthday." data-cnt="10" data-preview="https://www.freeporn8.com/get_file/1/b976235acab66521a8d5f3d3784183b2/1456000/1456783/1456783_preview.mp4/" width="320" height="180"/>
 <span class="ico-fav-0 " title="Add to Favourites" data-fav-video-id="1456783" data-fav-type="0"></span>
 <span class="ico-fav-1 " title="Watch Later" data-fav-video-id="1456783" data-fav-type="1"></span>
 </div>
 <strong class="title">
 My neighbour asked me for a fine bang for his birthday.
 </strong>
 <div class="wrap">
 <div class="duration">10:25</div>

 <div class="rating positive">
 0%
 </div>
 </div>
 <div class="wrap">
 <div class="added"><em>5 seconds ago</em></div>
 </div>
 </a>
 </div>
 <div class="item ">
 <a href="https://www.freeporn8.com/videos/1456785/family-sinners-jessica-ryan-john-legendary-mothers-stepson-8-movie-scene-1/" title="FAMILY SINNERS - Jessica Ryan, John Legendary - Mothers & Stepson 8 Movie scene 1" >
 <div class="img">
 <img class="thumb " src="https://www.freeporn8.com/contents/videos_screenshots/1456000/1456785/320x180/2.jpg" alt="FAMILY SINNERS - Jessica Ryan, John Legendary - Mothers & Stepson 8 Movie scene 1" data-cnt="10" data-preview="https://www.freeporn8.com/get_file/1/374432138074975ffaf21921c9c3b57d/1456000/1456785/1456785_preview.mp4/" width="320" height="180"/>
 <span class="ico-fav-0 " title="Add to Favourites" data-fav-video-id="1456785" data-fav-type="0"></span>
 <span class="ico-fav-1 " title="Watch Later" data-fav-video-id="1456785" data-fav-type="1"></span>
 </div>
 <strong class="title">
 FAMILY SINNERS - Jessica Ryan, John Legendary - Mothers & Stepson 8 Movie scene 1
 </strong>
 <div class="wrap">
 <div class="duration">10:01</div>

 <div class="rating positive">
 0%
 </div>
 </div>
 <div class="wrap">
 <div class="added"><em>1 minute ago</em></div>
 </div>
 </a>
 </div>
 <span><div class="item pignu">
 <div style="position: relative;">
 <span id="F8m-Native-Ad-1"></span>
 <span class="is-av">Ad</span>
 </div>
 </div></span>
 <div class="item ">
 """

    @Test
    fun `drtuber network listing and player files`() {
        val list = TubeSite.DrTuber.parseList(DR_LIST)
        assertEquals(2, list.size)
        assertEquals("4324013", list[0].id)
        assertEquals("CUTE WIFE IN ECSTASY WITH HER FIRST BBC", list[0].title)
        assertEquals(10 * 60 + 20, list[0].durationSec)
        assertEquals("https://www.drtuber.com/video/4324013/cute-wife-in-ecstasy-with-her-first-bbc", list[0].pageUrl)
        assertTrue(list[0].preview!!.endsWith("4324013.mp4"))
        // High quality over low.
        assertTrue(TubeSite.Nuvid.stream(NUVID_FILES)!!.url.contains("/mp4_hq/"))
        assertTrue(TubeSite.Nuvid.streamPageUrl("x", "6138764").startsWith("https://www.nuvid.com/player_config_json/?vid=6138764"))
    }

    private val DR_LIST = """<a href="/video/4324013/cute-wife-in-ecstasy-with-her-first-bbc" title="CUTE WIFE IN ECSTASY WITH HER FIRST BBC" data-mobile_adv_pop class="th wrap-better-content" data-video_id="4324013"> <img class="b-lazy" src="https://g1.drtst.com/media/videos/tmb/4324013/320_240/18.jpg" width="320" height="240" alt="CUTE WIFE IN ECSTASY WITH HER FIRST BBC" id="rotate_4324013_" rotator="off" _server="g1.drtst.com" _url_scheme="https" data-webm="https://g3.drtst.com/media/videos/tmb/4324013/4324013.mp4" /> <strong class="toolbar"> <em class="rate_thumb"><i class="ico_rate_like"></i><em>92%</em></em> <em class="time_thumb"> <em>10:20</em> </em> </strong> <span class="description_thumb"> <em> CUTE WIFE IN ECSTASY WITH HER FIRST BBC </em> </span> </a> </li> <li> <a href="/video/547636/horny-black-granny-loves-young-man" title="Horny Black Granny loves young man" data-mobile_adv_pop class="th wrap-better-content" data-video_id="547636"> <img class="b-lazy" src="https://g2.drtst.com/media/videos/tmb/547636/320_240/3.jpg" width="320" height="240" alt="Horny Black Granny loves young man" id="rotate_547636_" rotator="off" _server="g2.drtst.com" _url_scheme="https" data-webm="https://g3.drtst.com/media/videos/tmb/547636/547636.mp4" /> <strong class="toolbar"> <em class="rate_thumb"><i class="ico_rate_like"></i><em>81%</em></em> <em class="time_thumb"> <em>38:41</em> </em> </strong> <span class="description_thumb"> <em> Horny Black Granny loves young man </em> </span> </a> </li> </ul> <ul> <li> """
    private val NUVID_FILES = """{"title": "Hot damn homemade brunette babe fucked thoroughly", "duration": "936", "files": {"lq": "https://gcdn.nuvid.com/mp4_lq/4972e6847eb48b93098b19de7d20b995.mp4?md5=wZoV49zldeehlCp0-sjLlQ&expire=1795222290&speed=77k&buffer=1017k&tip=1", "hq": "https://gcdn.nuvid.com/mp4_hq/4972e6847eb48b93098b19de7d20b995.mp4?md5=bwBRtCivaP1fKo76WgQyMw&expire=1795222290&speed=263k&buffer=1557k&tip=1", "4k": ""}}"""
}
