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

    private companion object {
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
        val MYDADDY = """vastAdvanced:{noVastVideoCallback:(function(){}),vastLoadedCallback:(function(){}),vastVideoSkippedCallback:(function(){}),vastVideoEndedCallback:(function(){console.log("vast end");t_chk=true;})} },layoutControls:{primaryColor:"#ff8397", preload:"metadata",allowDownload:true,timelinePreview:{file:"//s57.bigcdn.cc/pubs/6afe4af2477065.39950053/tile.vtt",spriteRelativePath:true,type:"VTT"} ,htmlOnPauseBlock: { html: '<div id="adc" style="width:300px;height:250px;position:relative;border-radius:8px;background:#000;"><iframe src="/hf.html" width="300" height="250" scrolling="no" marginwidth="0" marginheight="0" frameborder="0" style="border-radius:8px;"></iframe><div style="background:#DF7C8C; padding: 5px 12px 5px 10px; position:absolute; bottom:20px; left:0px; border-top-right-radius:8px; border-bottom-right-radius:8px; color:#fff; font-weight:bold; cursor:pointer;" onclick="myFluidPlayer.pause();" >Resume</div></div>', height: 250, width: 300 }  }}); flvv=${"$"}("#flvv")[0];myFluidPlayer.ft_"""
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
