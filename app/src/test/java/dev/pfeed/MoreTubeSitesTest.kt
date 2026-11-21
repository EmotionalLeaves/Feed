package dev.pfeed

import dev.pfeed.source.tube.TubeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3Movs and AnySex, against their pages as served in November 2026. */
class MoreTubeSitesTest {
    @Test
    fun `3movs listing and stream`() {
        val list = TubeSite.ThreeMovs.parseList(THREE_LIST)
        assertEquals(2, list.size)
        assertEquals("468595", list[0].id)
        assertEquals("I've never done this before with a cougar!", list[0].title)
        assertEquals(8 * 60 + 14, list[0].durationSec)
        assertTrue(list[0].pageUrl.startsWith("https://www.3movs.com/videos/468595/"))
        assertTrue(list[0].preview!!.endsWith("_preview.mp4/"))
        val s = TubeSite.ThreeMovs.stream(THREE_VIDEO)!!
        assertTrue(s.url, s.url.contains("/468009.mp4/"))
        assertTrue(TubeSite.ThreeMovs.pageTags(THREE_VIDEO).contains("oral"))
    }

    @Test
    fun `anysex listing and stream`() {
        val list = TubeSite.AnySex.parseList(ANY_LIST)
        assertEquals(2, list.size)
        assertEquals("402359", list[0].id)
        assertEquals("Kevlyn Santos P", list[0].creator)
        assertEquals(720, list[0].height)
        assertEquals(8 * 60 + 54, list[0].durationSec)
        assertTrue(list[0].title!!.startsWith("I fuck red haired escort girl"))
        assertTrue(list[0].preview!!.startsWith("https://preview.anysex.com/"))
        // The best of 240/480/720p.
        assertTrue(TubeSite.AnySex.stream(ANY_VIDEO)!!.url.contains("_720.mp4"))
    }

    private val THREE_LIST = """class="item thumb  ">
				<div class="th ">
					<a class="wrap_image" href="https://www.3movs.com/videos/468595/i-ve-never-done-this-before-with-a-cougar/" title="I've never done this before with a cougar!" >
						              <img class="img lazyload" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-src="https://img.3movs.com/contents/videos_screenshots/468000/468595/320x180/1.jpg" data-webp="https://img.3movs.com/contents/videos_screenshots/468000/468595/321x181/1.jpg" alt="I've never done this before with a cougar!"  data-preview="https://www.3movs.com/get_file/8/48c54e6bdca9d889733138fa9bf650e6/468000/468595/468595_preview.mp4/"  width="320" height="180"/>
																																										<span class="ico-fav-0 " title="Add to Favorites" data-fav-video-id="468595" data-fav-type="0"></span>
																	<span class="ico-fav-1 " title="Watch Later" data-fav-video-id="468595" data-fav-type="1"></span>
																					
						<div class="time">8:14</div>

						
					</a>
					<a class="title" href="https://www.3movs.com/videos/468595/i-ve-never-done-this-before-with-a-cougar/" title="I've never done this before with a cougar!">
													I've never done this before with a cougar!
											</a>
					<div class="wrap">
												<div class="list">
							<div class="list_item">
																								<i class="icon-calendar"></i>
								<span>15 hours ago</span>
							</div>
							<div class="list_item">
								<i class="icon-eye"></i>
								<span>309</span>
							</div>
						</div>
					</div>
									</div>
			</div>
					<div class="item thumb  ">
				<div class="th ">
					<a class="wrap_image" href="https://www.3movs.com/videos/468594/nice-latina-teen-swore-it-was-soft-modeling-but-blew-my-bwc/" title="NICE LATINA TEEN swore it was soft modeling but blew my BWC" >
						              <img class="img lazyload" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-src="https://img.3movs.com/contents/videos_screenshots/468000/468594/320x180/1.jpg" data-webp="https://img.3movs.com/contents/videos_screenshots/468000/468594/321x181/1.jpg" alt="NICE LATINA TEEN swore it was soft modeling but blew my BWC"  data-preview="https://www.3movs.com/get_file/8/e7b4dda9b81edf30d907093df80d9528/468000/468594/468594_preview.mp4/"  width="320" height="180"/>
																																										<span class="ico-fav-0 " title="Add to Favorites" data-fav-video-id="468594" data-fav-type="0"></span>
																	<span class="ico-fav-1 " title="Watch Later" data-fav-video-id="468594" data-fav-type="1"></span>
																					
						<div class="time">3:08</div>

						
					</a>
					<a class="title" href="https://www.3movs.com/videos/468594/nice-latina-teen-swore-it-was-soft-modeling-but-blew-my-bwc/" title="NICE LATINA TEEN swore it was soft modeling but blew my BWC">
													NICE LATINA TEEN swore it was soft modeling but blew my BWC
											</a>
					<div class="wrap">
												<div class="list">
							<div class="list_item">
																								<i class="icon-calendar"></i>
								<span>15 hours ago</span>
							</div>
							<div class="list_item">
								<i class="icon-eye"></i>
								<span>1</span>
							</div>
						</div>
					</div>
									</div>
			</div>
					<div """
    private val THREE_VIDEO = """', 																	video_url: 'https://www.3movs.com/get_file/8/60b6b295bd742a3a811c34eeeb708d0a/468000/468009/468009.mp4/?v-acctoken=MTMyNnwyNTQyNTh8ODh8ZmUzN2RiZWI4ZmU2OTAzMmUzMmNjOTFmNTIxZjk2Mzga6589b93614ee019', 																	video_url_hd: '1', 																	postfix: '.mp4', 																	video_url_text: 'High Quality', 																	video_alt_url: 'https://www.3movs.com/get_file/8/8b938f681c3164d2f8b3b129c5191002/468000/468009/468009_lq.mp4/?v-acctoken=NjQxfDI1NDI1OHw4OHwzNDliYzEwM2MxZjBiNjJmMWZjZGVmNTAyYmVjYjRjYwe8ea351f28731d5c', 																	video_alt_url_text: 'Low Quality', 																	timeline_screens_url: 'https://img.3movs.com/contents/videos_screenshots/468000/468009/timelines/time_screens/160x120/{time}.jpg', 																	timeline_screens_interval: '10', 																	timeline_screens_count: '73', 																	preview_url: 'https://img.3movs.com/contents/video
m">
									<strong>Tags:</strong>
								</div>
																	<div class="item">
										<a href="https://www.3movs.com/tags/oral/">Oral</a>
									</div>
																	<div class="item">
										<a href="https://www.3movs.com/tags/tattoo/">Tattoo</a>
									</div>
																	<div class="item">
										<a href="https://www.3movs.com/tags/inked/">inked</a>
									</div>
																	<div class="item">
										<a href="https://www.3movs.com/tags/booty/">booty</a>
									</div>
																	<div class="item">
										<a href="https://www.3movs.com/tags/busty/">Busty</a>
									</div>
																	<div class="item">
										<a href="https://www.3m"""
    private val ANY_LIST = """<div class="item  "  data-video-id="402359" data-views="7099294" data-rating="83.532">
				<a href="https://anysex.com/video/402359/i-fuck-red-haired-escort-girl-and-leave-her-with-stretched-and-oozing-anal-hole/"  class="popfire">
					<div class="img">
													<img  src="https://i4.anysex.com/videos_screenshots/402000/402359/608x342/1.jpg" alt="I fuck red haired escort girl and leave her with stretched and oozing anal hole" data-cnt="1" data-preview="https://preview.anysex.com/402000/402359/402359_pr640.mp4"  width="640" height="360" data-jpg="https://i4.anysex.com/videos_screenshots/402000/402359/640x360/1.jpg"/>
																								
                                                                                                                                                                                                                                                                                                                                                                                                                <span class="item-quality">
                            <span class="is-hd hd720 ">720p</span>
                        </span>
							                    <div class="duration">8:54</div>
					</div>
					<strong class="title">
						I fuck red haired escort girl and leave her with stretched and oozing anal hole
					</strong>
				</a>
                <div class="wrap">
                                            <div class="author-link"><a href="https://anysex.com/models/kevlyn-santos-p/"><i class="mi star"></i>Kevlyn Santos P</a></div>
                                                                <div class="views">7m views</div>
                                        
                    
                                        <div class="rating positive"> <i class="mi thumb_up"></i>
                        83%
                    </div>
                </div>
                                    <span class="item-menu tippy_menu" data-rel=".item_menu_list_videos_custom_videos_search_result_402359">
						<i class="mi more_vert"></i>
					</span>
					<div class="tippy_in item_menu_list_videos_custom_videos_search_result_402359">
						<ul class="default-nav">
												        <li><a class="" data-fav-video-id="402359" data-fav-type="0"><i class="mi favorite"></i> Add to Favourites</a></li>  
					        					        <li><a class="" data-fav-video-id="402359" data-fav-type="1"><i class="mi watch_later"></i> Watch Later</a></li>   
					        					        					    </ul>
					</div>
                							</div>
		                        			<div class="item  "  data-video-id="460229" data-views="1582664" data-rating="82.946">
				<a href="https://anysex.com/video/460229/horny-milf-can-t-get-enough-of-her-stepson-s-cock-so-she-gets-stuck-again/"  class="popfire">
					<div class="img">
													<img  src="https://i2.anysex.com/videos_screenshots/460000/460229/608x342/1.jpg" alt="Horny MILF can't get enough of her stepson's cock so she gets stuck again" data-cnt="1" data-preview="https://preview.anysex.com/460000/460229/460229_pr640.mp4"  width="640" height="360" data-jpg="https://i2.anysex.com/videos_screenshots/460000/460229/640x360/1.jpg"/>
																								
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                <span class="item-quality">
                            <span class="is-hd hd1080 ">1080p</span>
                        </span>
							                    <div class="duration">21:32</div>
					</div>
					<strong class="title">
						Horny MILF can't get enough of her stepson's cock so she gets stuck again
					</strong>
				</a>
                <div class="wrap">
                                                                <div class="views">1.5m views</div>
                                        
                    
                                        <div class="rating positive"> <i class="mi thumb_up"></i>
                        82%
                    </div>
                </div>
                                    <span class="item-menu tippy_menu" data-rel=".item_menu_list_videos_custom_videos_search_result_460229">
						<i class="mi more_vert"></i>
					</span>
					<div class="tippy_in item_menu_list_videos_custom_videos_search_result_460229">
						<ul class="default-nav">
												        <li><a class="" data-fav-video-id="460229" data-fav-type="0"><i class="mi favorite"></i> Add to Favourites</a></li>  
					        					        <li><a class="" data-fav-video-id="460229" data-fav-type="1"><i class="mi watch_later"></i> Watch Later</a></li>   
					        					        					    </ul>
					</div>
                							</div>
		                        			"""
    private val ANY_VIDEO = """<source id="video_source_3" src="https://anysex.com/get_file/1/efb8762395bed5296ff8a24f227501949af794e786/409000/409947/409947_720.mp4/?br=531" type="video/mp4" title="720p" data-fluid-hd="true"><source id="video_source_2" src="https://anysex.com/get_file/1/baad51aa2a132d7004408ac4bc991661732b5db2a3/409000/409947/409947_240.mp4/?br=225" type="video/mp4" title="240p" ><source id="video_source_1" src="https://anysex.com/get_file/1/f4ec4fa040a755a9d5eb2ba878978cf039a9696891/409000/409947/409947.mp4/?br=353" type="video/mp4" title="480p"></video></div><script>window.fluid_cdn_path = 'https://static.anysex.com/fluidplayer/3.57.0/';</script><script src="https://static.anysex.com/fluidplayer/3.57.0/fluidplayer.min.js?v=85"></script><script>let video_id = 409947;var poster_url = "https://i4.anysex"""

    @Test
    fun `zbporn listing and stream`() {
        val list = TubeSite.ZbPorn.parseList(ZB_LIST)
        assertEquals(2, list.size)
        assertEquals("678184", list[0].id)
        assertEquals(11 * 60 + 21, list[0].durationSec)
        assertTrue(list[0].preview!!.endsWith(".mp4"))
        assertTrue(list[0].thumb!!.endsWith(".jpg"))
        // The full file (highest bitrate), not the 360p one.
        assertTrue(TubeSite.ZbPorn.stream(ZB_VIDEO)!!.url.endsWith("000/678184/678184.mp4/?br=1774"))
        assertTrue(TubeSite.ZbPorn.pageTags(ZB_VIDEO).isNotEmpty())
    }

    private val ZB_LIST = """<a class="th-image-link" href="https://zbporn.com/videos/678184/stepmom-s-big-tits-featured-in-a-big-boobs-compilation/" title="Stepmom's big tits featured in a big boobs compilation" data-preview="https://pr1.zbporn.com/contents/videos/678000/678184/678184_short_preview.mp4" data-rt="1:2c8d63ec93028cf593fa06c9ab7db742:4:678184:1:">
                                                                                                    
                                                    <picture>
                                <source type="image/webp" srcset="https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/182x136/4.jpg 182w, https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/362x272/4.jpg 362w" sizes="(max-width: 480px) calc(50vw - 7px), (max-width: 760px) calc(33vw - 10px), 264px">
                                <source type="image/jpeg" srcset="https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/180x135/4.jpg 180w, https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/360x270/4.jpg 360w" sizes="(max-width: 480px) calc(50vw - 7px), (max-width: 760px) calc(33vw - 10px), 264px">
                                <img src="https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/180x135/4.jpg" alt="Stepmom's big tits featured in a big boobs compilation">
                            </picture>
                                            </a>
                    <span class="th-duration">11:21</span>
                </div>
                <a class="th-row-title" href="https://zbporn.com/videos/678184/stepmom-s-big-tits-featured-in-a-big-boobs-compilation/" title="Stepmom's big tits featured in a big boobs compilation" data-rt="1:2c8d63ec93028cf593fa06c9ab7db742:4:678184:1:">Stepmom's big tits featured in a big boobs compilation</a>
                <div class="th-bottom-info">
                    <div class="th-date">1 hour ago</div>
                    
                                                                            </div>
            </div>
                    <div class="th th-i">
                                                                
                <div class="th-image">
                    <a class="th-image-link" href="https://zbporn.com/videos/680009/secretary-with-natural-tits-gets-drilled-at-work/" title="Secretary with natural tits gets drilled at work" data-preview="https://pr1.zbporn.com/contents/videos/680000/680009/680009_short_preview.mp4" data-rt="2:2c8d63ec93028cf593fa06c9ab7db742:7:680009:1:">
                                                                                                    
                                                    <picture>
                                <source type="image/webp" srcset="https://cdnth.zbporn.com/contents/videos_screenshots/680000/680009/182x136/7.jpg 182w, https://cdnth.zbporn.com/contents/videos_screenshots/680000/680009/362x272/7.jpg 362w" sizes="(max-width: 480px) calc(50vw - 7px), (max-width: 760px) calc(33vw - 10px), 264px">
                                <source type="image/jpeg" srcset="https://cdnth.zbporn.com/contents/videos_screenshots/680000/680009/180x135/7.jpg 180w, https://cdnth.zbporn.com/contents/videos_screenshots/680000/680009/360x270/7.jpg 360w" sizes="(max-width: 480px) calc(50vw - 7px), (max-width: 760px) calc(33vw - 10px), 264px">
                                <img src="https://cdnth.zbporn.com/contents/videos_screenshots/680000/680009/180x135/7.jpg" alt="Secretary with natural tits gets drilled at work">
                            </picture>
                                            </a>
                    <span class="th-duration">11:21</span>
                </div>
                <a class="th-row-title" href="https://zbporn.com/videos/680009/secretary-with-natural-tits-gets-drilled-at-work/" title="Secretary with natural tits gets drilled at work" data-rt="2:2c8d63ec93028cf593fa06c9ab7db742:7:680009:1:">Secretary with natural tits gets drilled at work</a>
                <div class="th-bottom-info">
                    <div class="th-date">2 hours ago</div>
                    
                                                                                    <div class="th-rating ">
                            <span class="ico-holder">
                                <i class="icon-thumbs-up"></i>
                            </span>
                            <span class="text">100%</span>
                        </div>
                                    </div>
            </div>
                    <div class="th th-i">
                                                                
                <div class="th-image">
                    """
    private val ZB_VIDEO = """     
                                            video_url: 'https://zbporn.com/get_file/28/dd83deab80324a638cf13a1d238d0437c80aaf4863/678000/678184/678184_360p.mp4/?br=624',                                                                                                                     
                                            video_url_hd: '1',                                                                                                                     
                                            postfix: '.mp4',                                                                                                                     
                                            video_url_text: 'High Quality',                                                                                                                                                                                                                                                                                     
                                            video_alt_url_text: '360p',                                                                                                                     
                                            default_slot: '2',                                                                                                                     
                                            timeline_screens_url: 'https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/timelines/mp4_timeline_screen/170x96/{time}.jpg',                                                                                                                     
                                            timeline_screens_webp_url: 'https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/timelines/mp4_timeline_screen/172x97/{time}.jpg',                                                                                                                     
                                            timeline_screens_interval: '10',                                                                                                                     
                                            timeline_screens_count: '69',                                                                                                                     
                                            preview_url: 'https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/preview.jpg',                                                                                                                     
                                            preview_url1: 'https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/preview.mp4.jpg',                                                                                                                     
                                            preview_height1: '720',                                                                                                                     
                                            preview_url2: 'https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/preview_short_preview.mp4.jpg',                                                                                                                     
                                            preview_height2: '160',                                                                                                                     
                                            preview_url3: 'https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/preview_360p.mp4.jpg',                                                                                                                     
                                            preview_height3: '360',                                                                                                                     
                                            preview_url4: 'https://cdnth.zbporn.com/contents/videos_screenshots/678000/678184/preview_short_preview_320x180.mp4.jpg',                                                                                                                     
                                            preview_height4: '180',                                                                                                                     
                                            skin: 'custom_skin.css',                                                                                                                     
                                            logo_position: '0,0',                                                                                                                     
                                            logo_anchor: 'topleft',                                                                                                                     
                                            hide_controlbar: '1',                                                                                                                     
                                            volume: '1',                                                                                                                     
                                            related_src: 'https://zbporn.com/related_videos_html/678184/',                                                                                                                     
                                            embed: '0',                                                                                                                     
                                            adv_pre_vast: '/frk/?r=1&keywords=' + current_keyword,                                                                                                                     
                                            adv_pre_vast_alt: '/frk/vast.php?sub=333333',                                                                                                                     
                                            adv_pre_duration: '20',                                                                                                                     
                                            adv_pre_duration_text: 'Ends in %time seconds',                                                                                                                     
                                            adv_pre_skip_duration: '5',                                                                                                                     
                                            adv_pre_skip_text_time: 'Skip ad in %time',                                                                                                                     
                                            adv_pre_skip_text: 'Skip ad',                                                                                                                     
                                            lrcv: '1799846162967108919480357',                                                                                                                     
                                            vast_timeout1: '12',                                                                                                                     
                                            player_width: '660',                                                                                                                     
                                            player_height: '371.25'                                                                                                            };
                            } else {
                                var flashvars = {
                                                                            
                                            video_id: '678184',                                                                                                                     
                                            video_categories: 'Blondes, Redheads, MILFs, Big Tits, Big Asses, Babes, Blowjobs, Handjobs',                                                                                                                     
                                            video_tags: 'cougar milf, cheating wife, taboo, stepmom, stepson, blowjob, huge natural tits, titty fuck, hairy pussy, blonde, pawg, mypervyfamily, big ass, big dick, big tits, compilation, hd porn, hardcore, milf, pornstar, red head, step fantasy',                                                                                                                     
                                            license_code: '${"$"}437603614437307',                                                                                                                     
                                            rnd: '1795117523',                                                                                                                     
                                            video_url: 'https://zbporn.com/get_file/28/42c1ed896019bbbe7515f77148fe4971d0cc20baa3/678000/678184/678184.mp4/?br=1774',                                                                                                                     
                                            video_url_hd: '1',                                                                                                                     
                                            postfix: '.mp4',                                                                                                                     
                                            video_url_text: 'High Quality',                                                                                                                     
                                            video_alt_url: 'https://zbporn.com/get_file/28/dd83deab80324a638cf13a1d238d0437c80aaf4863/678000/678184/678184_360p.mp4/?br=624',                                                                                                                     
                                            video_alt
</li>
                <li>
                    <a href="https://zbporn.com/categories/"  onclick="gtag('event', 'menu_click', {'menu_item_name': 'Categories', 'menu_item_url': 'https://zbporn.com/categories/', 'method': 'Mobile Top Menu'})">
                        <i class="icon-categories"></i>
                        Categories
                    </a>
                </li>
                <li>
                    <a href="https://zbporn.com/performers/"  onclick="gtag('event', 'menu_click', {'menu_item_name': 'Models', 'menu_item_url': 'htt
        <a href="https://zbporn.com/categories/big-tits/" class="item-link" title="Big Tits">
                                <i class="icon-categories"></i>
                                <span>Big Tits</span>
                            </a>
                                                    <a href="https://zbporn.com/categories/blondes/" class="item-link" title="Blondes">
                                <i class="icon-categories"></i>
                                <span>Blondes</span>
                            </a>
                                                    <a href="https://zbporn.com/categories/blowjobs/" class="item-link" title="Blowjobs">
                               """
}
