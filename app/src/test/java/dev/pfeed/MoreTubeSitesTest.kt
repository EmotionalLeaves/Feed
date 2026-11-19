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
}
