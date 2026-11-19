package dev.pfeed

import dev.pfeed.source.tube.TubeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sites added in 5.5, against snippets of their pages as served in November 2026. */
class NewTubeSitesTest {
    @Test
    fun hqporner() {
        val list = TubeSite.HqPorner.parseList(HQ_LIST)
        assertEquals("128661", list[0].id)
        assertEquals("We have room for everything", list[0].title)
        assertEquals(52 * 60 + 32, list[0].durationSec)
        assertTrue(list[0].thumb!!.startsWith("https://"))
        val s = TubeSite.HqPorner.stream(MYDADDY)!!
        assertTrue(s.url, s.url.endsWith("/1080.mp4"))
    }

    @Test
    fun porndig() {
        val list = TubeSite.Porndig.parseList(PORNDIG_LIST)
        assertEquals("258176", list[0].id)
        assertEquals(600, list[0].durationSec)
        val s = TubeSite.Porndig.stream(PORNDIG_PLAYER)!!
        assertTrue(s.url, s.url.contains("_h264_") && s.url.contains("_1080.mp4") && !s.url.contains("\\"))
    }

    @Test
    fun perfectgirls() {
        val list = TubeSite.PerfectGirls.parseList(PG_LIST)
        assertEquals("808821", list[0].id)
        assertEquals(16 * 60 + 42, list[0].durationSec)
        assertEquals("Stefanie Moon", list[0].creator)
    }

    @Test
    fun okxxx() {
        val list = TubeSite.OkXxx.parseList(OK_LIST)
        assertEquals("805943", list[0].id)
        assertEquals(10 * 60 + 17, list[0].durationSec)
        assertEquals("Pressure", list[0].creator)
    }

    @Test
    fun porngo() {
        val list = TubeSite.PornGo.parseList(PORNGO_LIST)
        assertEquals("2024237", list[0].id)
        assertEquals(13 * 60 + 6, list[0].durationSec)
        assertTrue(TubeSite.PornGo.stream(PORNGO_VIDEO)!!.url.contains("/get_file/"))
    }

    @Test
    fun xfreehd() {
        val list = TubeSite.XFreeHd.parseList(XFREEHD_LIST)
        assertEquals("1101755", list[0].id)
        assertEquals(11 * 60, list[0].durationSec)
        assertTrue(TubeSite.XFreeHd.stream(XFREEHD_VIDEO)!!.url.startsWith("https://lb.xfreehd.com/7461"))
    }

    private companion object {
        val OK_LIST = """							<div class="  item thumb-bl thumb-bl-video video_1">
				<div class="thumb thumb-video">
					<a href="/video/805943/" title="Brandi Bae Takes Pressure"  data-preview-custom="https://ok.xxx/get_file/13/afa35e0acada585e1ddd5ff21b0efab1/805000/805943/805943_preview360p.mp4/">
													<img class="thumb lazy-load" fetchpriority="low" loading="lazy" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-original="https://static.ok.xxx/contents/videos_screenshots/805000/805943/640x360/1.jpg" alt="Brandi Bae Takes Pressure"  width="640" height="360"/>
																	</a>
					<div class="preview">
						<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="34" height="34" viewBox="0 0 32 32" id="startButton_805943" style="margin: 0 0 -3px;">
							<path stroke-width=".2"  class="path" stroke="#F0F0F0" fill="#ccc" d="M16 6c-6.979 0-13.028 4.064-16 10 2.972 5.936 9.021 10 16 10s13.027-4.064 16-10c-2.972-5.936-9.021-10-16-10zM23.889 11.303c1.88 1.199 3.473 2.805 4.67 4.697-1.197 1.891-2.79 3.498-4.67 4.697-2.362 1.507-5.090 2.303-7.889 2.303s-5.527-0.796-7.889-2.303c-1.88-1.199-3.473-2.805-4.67-4.697 1.197-1.891 2.79-3.498 4.67-4.697 0.122-0.078 0.246-0.154 0.371-0.228-0.311 0.854-0.482 1.776-0.482 2.737 0 4.418 3.582 8 8 8s8-3.582 8-8c0-0.962-0.17-1.883-0.482-2.737 0.124 0.074 0.248 0.15 0.371 0.228v0zM16 13c0 1.657-1.343 3-3 3s-3-1.343-3-3 1.343-3 3-3 3 1.343 3 3z"></path>
						</svg>
					</div> 
				</div> 
				<div class="thumb-bl-info">
					<div class="content_items">
													<a href="/sites/bangbros/" title="Bang Bros">
								<svg class="icon icon-channels"><use xlink:href="#icon-channels"></use></svg>
								<span>Bang Bros</span>
							</a>
										
																														<a href="/models/pressure/" title="Pressure">
																					<svg class="icon icon-models"><use xlink:href="#icon-models"></use></svg>
																				<span>Pressure</span>
									</a>
																																<a href="/models/brandi-bae/" title="Brandi Bae">
																					<svg class="icon icon-models"><use xlink:href="#icon-models"></use></svg>
																				<span>Brandi Bae</span>
									</a>
																										</div>
					<ul class="video-meta">
						<li><i class="fa fa-clock-o"></i> <span>10:17</span></li>
						<li><i class="fa fa-calendar-o"></i> <span>28 minutes ago</span></li>
						<li><i class="fa fa-eye"></i> <span>23K</span></li>
					</ul>
				</div>
			</div>
												 

					 

					  

					 
																								"""
        val PORNGO_LIST = """<a href="https://www.porngo.com/videos/2024237/my-stepsister-washes-dishes-while-i-suck-her-boyfriends-dick-big-tits-blowjob-pov/" class="thumb__top ">
					<div class="thumb__img" data-preview="https://cast21.porngo.com/preview/2024237.mp4">
						<img src="https://img21.porngo.com/2024000/2024237/medium@2x/1.jpg" alt="My stepsister washes dishes while I suck her boyfriend's dick - big tits blowjob POV" srcset="https://img21.porngo.com/2024000/2024237/medium/1.jpg 2x" />
						<span class="thumb__duration">13:06</span>
					</div>
					<div class="thumb__title">
						<span>My stepsister washes dishes while I suck her boyfriend's dick - big tits blowjob POV</span>
					</div>
																																													<span class="thumb__bage">480p</span>
				</a>
 
				<div class="thumb__info">
					<div class="thumb-models">
																					<a href="https://www.porngo.com/models/kaorianya/" class="thumb-models__link">Kaorianya</a>
															<a href="https://www.porngo.com/models/anissamiller/" class="thumb-models__link">AnissaMiller</a>
																		</div>
					<div class="thumb__bottom">
						<div class="thumb__box">
																					<span class="thumb__ico thumb__ico_like">
								<svg class="svg-icon">
									<use xlink:href="#like"></use>
								</svg>
							</span>
							<span class="thumb__text">100%</span>
						</div>
						<div class="thumb__box">
							<span class="thumb__ico">
								<svg class="svg-icon">
									<use xlink:href="#eye"></use>
								</svg>
							</span>
															<span class="thumb__text">1426 views</span>
													</div>
					</div>
				</div>
			</div> 

								<div class="thumb item ">
				"""
        val PORNGO_VIDEO = """<source src='https://www.porngo.com/get_file/1021/cb95c205d387349799b05e05d088a613385d24882d/2024000/2024237/2024237_480m.mp4/?cparam=1795051022' type='video/mp4' label="" selected="true">                    
                            <p class='vjs-no-js'>
                                To view this video please enable JavaScript, and consider upgrading to a web browser that
                   """
        val XFREEHD_LIST = """<a class="video-link" href="https://beta.xfreehd.com/video/1101755/petite-bootylicious-latina-craves-bwc">
							<div class="thumb-overlay">
																	<img src="/img/ximgx.png" data-src="https://image.xfreehd.com/media/videos/tmb34/1101755/20.jpg" alt="Petite Bootylicious Latina Craves BWC -" id="rotate_1101755_20_20_viewed" class="img-responsive2  lazy" width="360" height="202" />
																								<div class="hd-text-icon">HD</div>								<div class="later" id="later_1101755"></div>
								<div class="duration-new">
									
									11m
								</div>
								<div class="video-views-new">
									<i class="far fa-eye"></i> 190 
								</div>
																<div class="video-rating-new">
									<i class="far fa-thumbs-up"></i> <b>0%</b>
								</div>
															</div>
							<span class="video-title-new m-t-5">Petite Bootylicious Latina Craves BWC -</span>
						</a>
												
						<div class="clearfix"></div>
						
					</div>						
				</div>			
            				<div class="col-xs-6 col-sm-4 col-md-3 col-lg-3">
					<div class="well well-sm vidthumbmoveobjects o-hidden">
						"""
        val XFREEHD_VIDEO = """<source src="https://lb.xfreehd.com/84962ead6ea1855d5f66bf388e785ccf0f566208.mp4" title="SD" type="video/mp4" /><source src="https://lb.xfreehd.com/74617948b0a9fec23cb48b31056ec0cb8ea23998.mp4" title="HD" type="video/mp4" /></video>
                <script src="https://beta.xfreehd.com/media/fluid/master/fluidplayer.min.js"></script>
                <script src="https://beta.xfreehd.com/media/flui"""
        val HQ_LIST = """<a href="/hdporn/128661-we_have_room_for_everything.html" class="atfib n8hu6s"><img src="//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_main.jpg" id="slide128661" alt="We have room for everything"/></a>
		</div>

		        <div style="padding: 12px 12px 0 12px;">
			<h2 style="margin-bottom: 0px;">We have room for everything</h2>
		</div>
		
		<div style="padding: 6px 12px 42px 12px;font-size:14px;">
					<span class="meta_data">
			<i class="fa fa-clock-o" aria-hidden="true"></i>
				52m 32s			</span>
						<span class="meta_data" id="slideShowPlayBtn128661">
				<span class="idle">
					<i class="fa fa-forward" aria-hidden="true"></i>
					<a href="#" class="click-trigger">play images</a>
				</span>
				<span class="loading" style="display:none">
					<i class="fa fa-circle-o-notch fa-spin fa-fw"></i>
					loading...
				</span>
				<span class="playing" style="display:none">
					<i class="fa fa-cog fa-spin fa-fw"></i>
					playing...
				</span>
			</span>
			<script type="text/javascript">slideShowPlayer(document.querySelector("#slide128661"), ["//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_1.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_2.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_3.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_4.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_5.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_6.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_7.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_8.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_9.jpg","//fastporndelivery.hqporner.com/imgs/77/24/76ff00c2e2dc924_10.jpg"], document.querySelector("#slideShowPlayBtn128661"));</script>
							</div>

			
								
		<div class="img-container">
			<a href="/hdporn/128660-shes_from_Slide_it_in_house.html" class="atfib n8hu6s"><img src="//fastporndelivery.hqporner.com/imgs/4/26/f195e8ca5c2306d_main.jpg" id="slide128660" alt="She's from Slide it in house"/></a>
		</div>

		        <div style="padding: 12px 12px 0 12px;">
			<h2 style="margin-bottom: 0px;">She's from Slide it in house</h2>
		</div>
		
		<div style="padding: 6px 12px 42px 12px;f"""
        val MYDADDY = """lvv\" preload=\"none\" poster=\"//s57.bigcdn.cc/pubs/6afe4af2477065.39950053/main.jpg\" controls style=\"width:100%; height:100%;\" src=\"\"><source src=\"//s57.bigcdn.cc/pubs/6afe4af2477065.39950053/360.mp4\" title=\"360p\" type=\"video/mp4\" /></video>"); }else{ ${"$"}("#jw").html("<video id=\"flvv\" preload=\"none\" poster=\"//s57.bigcdn.cc/pubs/6afe4af2477065.39950053/main.jpg\" controls style=\"width:100%; height:100%;\" src=\"\"><source src=\"//s57.bigcdn.cc/pubs/6afe4af2477065.39950053/360.mp4\" title=\"360p\" type=\"video/mp4\" /><source src=\"//s57.bigcdn.cc/pubs/6afe4af2477065.39950053/720.mp4\" title=\"720p60\" type=\"video/mp4\" /><source src=\"//s57.bigcdn.cc/pubs/6afe4af2477065.39950053/1080.mp4\" title=\"1080p60\" type=\"video/mp4\" /></video>");}if(hasAdblock){${"$"}("#ovrl_ab").show();${"$"}("#ab_wait").hide();${"$"}("#ab_close").show();}else{hide_ov();}${"$"}("#flvv").on("error",function(){ if(${"$"}("#flvv").attr("src")!=""){${"$"}("#ovrl_err").show();} });}else{${"$"}("#jw").css("background","#000").html("<span style='color:#fff'>Your browser has not supported. Video can not be played.<br />You can down"""
        val PORNDIG_LIST = """<section id="multifilter_videos_258176" class="video_item_wrapper odd_item video_item_medium " data-post_id="258176"  data-post_duration="600" ><div class="video_item_section video_item_title"><header><h2 class="video_item_section_container"><a href="/videos/258176/izzy-lush-sucks-daddy-s-hard-cock-before-being-used.html">Izzy Lush sucks daddy's hard cock before being used</a></h2><i class="icon icon_19 icon-ic_19_qlt_4k pull-right"></i></header></div><a class="video_item_thumbnail js_show_loader  " href="/videos/258176/izzy-lush-sucks-daddy-s-hard-cock-before-being-used.html" data-tag_pos="" alt="Izzy Lush sucks daddy's hard cock before being used"><div class="blurmyass img_18plus_wrapper video_item_thumbnail"><img class="img_18plus align-center" src="/assets/default/img/18plus.svg" alt="Verify your age"><img class="img_sfw_class sfw_class_20" src="https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/2.jpg" alt="Verify your age" srcset="https://image-cdn.porndig.com/thumbs/2026/08/571446/400x225/clips/2.jpg 400w, https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/2.jpg 320w, https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/2.jpg 320w"><img class="sfw_class_thumb_preview hidden" data-src="" src="" loading="lazy" data_base_url="https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/2.jpg" data-thumbs_20='[1..2]' srcset="" sizes="(max-width: 479px) 120px, (max-width: 767px) 150px, 400px"></div><img data-src="https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg?prev=1" class="thumb_preview hidden" data-thumbs='[1..20]' data-srcset="https://image-cdn.porndig.com/thumbs/2026/08/571446/400x225/clips/16.jpg 400w, https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg 320w, https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg 320w" sizes="(max-width: 479px) 120px, (max-width: 767px) 150px, 400px" title="Izzy Lush sucks daddy's hard cock before being used" alt="Izzy Lush sucks daddy's hard cock before being used"><img data-src="https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg" src="https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg" loading="lazy" class="js_video_preview " data-base_url="https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg" data-thumbs='[1..20]' data-vid='https://image-cdn.porndig.com/previewclips/2026/08/571446/clips/571446_1.mp4' data-post_url='/videos/258176/izzy-lush-sucks-daddy-s-hard-cock-before-being-used.html' srcset="https://image-cdn.porndig.com/thumbs/2026/08/571446/400x225/clips/16.jpg 400w, https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg 320w, https://image-cdn.porndig.com/thumbs/2026/08/571446/320x180/clips/16.jpg 320w" sizes="(max-width: 479px) 120px, (max-width: 767px) 150px, 400px"></a><div class="video_item_section video_item_stats clearfix"><div class="bubble bubble_duration"><span>10:00</span></div><div class="bubble"><i class="icon icon_12 icon-ic_12_vid_th_comment"></i><span>1</span></div><div class="bubble"><i class="icon icon_12 icon-ic_12_vid_th_thumb"></i><span>60%</span></div></div></section>"""
        val PORNDIG_PLAYER = """tamps":{"20":"0","30":"58"},"classifications":{"0":"sfw","10":"nudity","20":"erotic","30":"porn","40":"restricted"},"player_mode":"default","blur_poster":false}}},"tracks":[],"src":[{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/SCYyz7uSPRXREJZDuFx-Cg\/1795057426\/master.mpd","type":"application\/dash+xml","codec":"AUTO"},{"type":"multi-progressive","codec":"av1","srcSet":[{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3867v1_av1_8000_2160.mp4?md5=wTcdb1AwzSAH28acbZ7DuA&expires=1795057426","type":"video\/mp4; codecs=\"av01.0.05M.08\"","label":"2160p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3864v1_av1_2000_1080.mp4?md5=rDiWssRstpb5wwqVoY6tpA&expires=1795057426","type":"video\/mp4; codecs=\"av01.0.05M.08\"","label":"1080p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3861v1_av1_900_720.mp4?md5=_7w6NbHYcSrL-OSMqCQ1PA&expires=1795057426","type":"video\/mp4; codecs=\"av01.0.05M.08\"","label":"720p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3858v1_av1_500_540.mp4?md5=2TGHwkWjRoTU98pezWUEZg&expires=1795057426","type":"video\/mp4; codecs=\"av01.0.05M.08\"","label":"540p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3855v1_av1_225_360.mp4?md5=9TTHWgorcUTBRyu_LH72xQ&expires=1795057426","type":"video\/mp4; codecs=\"av01.0.05M.08\"","label":"360p","active":false}]},{"type":"multi-progressive","codec":"h264","srcSet":[{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3897v1_h264_13113_2160.mp4?md5=CEwxuhtO3SgNsjhj_vBUQg&expires=1795057426","type":"video\/mp4; codecs=\"avc1.4d4015\"","label":"2160p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3894v1_h264_4000_1080.mp4?md5=dQsVZc4420pAP7v6R-xy8Q&expires=1795057426","type":"video\/mp4; codecs=\"avc1.4d4015\"","label":"1080p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3891v1_h264_1800_720.mp4?md5=A76pq2tUYW65cainMp1Kdw&expires=1795057426","type":"video\/mp4; codecs=\"avc1.4d4015\"","label":"720p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3888v1_h264_1000_540.mp4?md5=8-QVj0yRGYWN893U73ZxWQ&expires=1795057426","type":"video\/mp4; codecs=\"avc1.4d4015\"","label":"540p","active":false},{"src":"https:\/\/video-cdn.porndig.com\/videos\/423590_BsuoTkQ41jgmz6K\/qid3885v1_h264_450_360.mp4?md5=BfqkRPTer0JTlBKiwY9inQ&expires=1795057426","type":"video\/mp4; codecs=\"avc1.4d4015\"","label":"360p","active":false}]}],"""
        val PG_LIST = """							<div class="  item thumb-bl thumb-bl-video video_1">
				<div class="thumb thumb-video">
					<a href="/video/808821/" title="Two Babes Get Fucked by the Same Guy"  data-preview-custom="https://www.perfectgirls.xxx/get_file/13/4187f607252c622515e3185ea1301861/808000/808821/808821_preview360p.mp4/">
													<img  fetchpriority="low" loading="lazy" class="thumb lazy-load" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-original="https://static.perfectgirls.xxx/contents/videos_screenshots/808000/808821/640x360/1.jpg" alt="Two Babes Get Fucked by the Same Guy"  width="640" height="360"/>
																</a>
					<div class="preview">
						<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="34" height="34" viewBox="0 0 32 32" id="startButton_808821" style="margin: 0 0 -3px;">
							<path stroke-width=".2"  class="path" stroke="#F0F0F0" fill="#ccc" d="M16 6c-6.979 0-13.028 4.064-16 10 2.972 5.936 9.021 10 16 10s13.027-4.064 16-10c-2.972-5.936-9.021-10-16-10zM23.889 11.303c1.88 1.199 3.473 2.805 4.67 4.697-1.197 1.891-2.79 3.498-4.67 4.697-2.362 1.507-5.090 2.303-7.889 2.303s-5.527-0.796-7.889-2.303c-1.88-1.199-3.473-2.805-4.67-4.697 1.197-1.891 2.79-3.498 4.67-4.697 0.122-0.078 0.246-0.154 0.371-0.228-0.311 0.854-0.482 1.776-0.482 2.737 0 4.418 3.582 8 8 8s8-3.582 8-8c0-0.962-0.17-1.883-0.482-2.737 0.124 0.074 0.248 0.15 0.371 0.228v0zM16 13c0 1.657-1.343 3-3 3s-3-1.343-3-3 1.343-3 3-3 3 1.343 3 3z"></path>
						</svg>
					</div> 
				</div> 
				<div class="thumb-bl-info">
					<div class="content_items">
													<a href="/channels/ultra-films/" title="Ultra Films">
								<svg class="icon icon-channels"><use xlink:href="#icon-channels"></use></svg>
								<span>Ultra Films</span>
							</a>
										
																														<a href="/pornstars/stefanie-moon/" title="Stefanie Moon">
																					<svg class="icon icon-models"><use xlink:href="#icon-models"></use></svg>
																				<span>Stefanie Moon</span>
									</a>
																																<a href="/pornstars/lika-star/" title="Lika Star">
																					<svg class="icon icon-models"><use xlink:href="#icon-models"></use></svg>
																				<span>Lika Star</span>
									</a>
																										</div>
					<ul class="video-meta">
						<li><i class="fa fa-clock-o"></i> <span>16:42</span></li>
						<li><i class="fa fa-calendar-o"></i> <span>1 hour ago</span></li>
						<li><i class="fa fa-eye"></i> <span>6.5K</span></li>
					</ul>
				</div>
			</div>
												 

					 

					  

					 
																								"""
    }
}
