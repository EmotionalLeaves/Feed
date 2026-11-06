package dev.pfeed

import dev.pfeed.data.settings.JsonFeed
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.parse.BooruParser
import dev.pfeed.source.parse.GenericJsonMapper
import dev.pfeed.source.parse.MastodonParser
import dev.pfeed.source.parse.RedditParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceParsersTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(s: String) = json.parseToJsonElement(s)

    @Test fun `reddit maps image and hls video, skips link posts, unescapes and flags nsfw`() {
        val root = parse(
            """
            {"data":{"after":"t3_x","children":[
              {"kind":"t3","data":{"name":"t3_1","title":"A cat","author":"alice","subreddit":"cats",
                "over_18":false,"permalink":"/r/cats/comments/1/a/","url":"https://i.redd.it/abc.jpg",
                "created_utc":1700000000.0,
                "preview":{"images":[{"source":{"url":"https://preview.redd.it/abc.jpg?w=640&amp;crop=smart"}}]}}},
              {"kind":"t3","data":{"name":"t3_2","title":"A clip","author":"bob","subreddit":"videos",
                "over_18":true,"permalink":"/r/videos/comments/2/b/","is_video":true,
                "media":{"reddit_video":{"hls_url":"https://v.redd.it/xyz/HLS.m3u8?a=1&amp;b=2","duration":30}}}},
              {"kind":"t3","data":{"name":"t3_3","title":"text","author":"carol","subreddit":"news",
                "permalink":"/r/news/3/","url":"https://example.com/article"}}
            ]}}
            """.trimIndent(),
        )
        val items = RedditParser.parse(root)
        assertEquals(2, items.size)

        val image = items[0]
        assertEquals(MediaType.IMAGE, image.type)
        assertEquals("https://i.redd.it/abc.jpg", image.extras["media"])
        assertEquals("https://preview.redd.it/abc.jpg?w=640&crop=smart", image.thumbnailUrl)
        assertEquals("https://www.reddit.com/r/cats/comments/1/a/", image.pageUrl)

        val video = items[1]
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals("https://v.redd.it/xyz/HLS.m3u8?a=1&b=2", video.extras["media"])
        assertEquals("application/x-mpegURL", video.extras["mime"])
        assertTrue("nsfw" in video.tags)
        assertEquals(30, video.durationSec)
    }

    @Test fun `booru parses danbooru array and skips posts with no file`() {
        val root = parse(
            """
            [{"id":101,"file_url":"https://cdn/aaa.jpg","preview_file_url":"https://cdn/p_aaa.jpg",
              "tag_string":"sky sunset scenery","tag_string_artist":"someartist","created_at":"2026-01-02T03:04:05Z"},
             {"id":102,"file_url":"https://cdn/bbb.webm","tag_string":"loop animated"},
             {"id":103,"tag_string":"no file"}]
            """.trimIndent(),
        )
        val items = BooruParser.parse(root, "https://booru.example")
        assertEquals(2, items.size)
        assertEquals(MediaType.IMAGE, items[0].type)
        assertEquals("someartist", items[0].creator)
        assertEquals("https://booru.example/posts/101", items[0].pageUrl)
        assertEquals("booru.example/101", items[0].id)
        assertEquals(MediaType.VIDEO, items[1].type)
        assertTrue("sunset" in items[0].tags)
    }

    @Test fun `booru parses e621 posts-wrapper and file-url shape`() {
        val root = parse(
            """{"posts":[{"id":5,"file":{"url":"https://e6/ccc.png"},"preview":{"url":"https://e6/prev.png"},
               "tags":{"general":["a","b"]},"created_at":"2026-02-02T02:02:02Z"}]}""",
        )
        val items = BooruParser.parse(root, "https://e6.example")
        assertEquals(1, items.size)
        assertEquals("https://e6/ccc.png", items[0].extras["media"])
        assertTrue("a" in items[0].tags && "b" in items[0].tags)
    }

    @Test fun `mastodon yields one item per media attachment with stripped title`() {
        val root = parse(
            """
            [{"id":"111","url":"https://m.example/@alice/111","created_at":"2026-03-03T03:03:03Z",
              "account":{"acct":"alice"},"content":"<p>Hello <b>world</b></p>","tags":[{"name":"Art"}],
              "media_attachments":[
                {"id":"a1","type":"image","url":"https://m/a1.jpg","preview_url":"https://m/a1s.jpg"},
                {"id":"a2","type":"video","url":"https://m/a2.mp4","preview_url":"https://m/a2s.jpg"}]},
             {"id":"222","url":"https://m.example/@bob/222","account":{"acct":"bob"},
              "content":"<p>no media</p>","media_attachments":[]}]
            """.trimIndent(),
        )
        val items = MastodonParser.parse(root)
        assertEquals(2, items.size)
        assertEquals("111_a1", items[0].id)
        assertEquals(MediaType.IMAGE, items[0].type)
        assertEquals("Hello world", items[0].title)
        assertTrue("art" in items[0].tags)
        assertEquals(MediaType.VIDEO, items[1].type)
    }

    @Test fun `generic json mapper resolves items via configured paths`() {
        val root = parse(
            """{"data":{"children":[{"data":{"id":"z1","title":"T","url":"https://x/y.mp4","au":"me"}}]},"next":"abc"}""",
        )
        val feed = JsonFeed(
            name = "X",
            urlTemplate = "https://x",
            itemsPath = "data.children",
            wrapPath = "data",
            idPath = "id",
            titlePath = "title",
            mediaPath = "url",
            creatorPath = "au",
            typeHint = "auto",
        )
        val items = GenericJsonMapper.map(root, feed)
        assertEquals(1, items.size)
        assertEquals(MediaType.VIDEO, items[0].type)
        assertEquals("T", items[0].title)
        assertEquals("me", items[0].creator)
        assertEquals("https://x/y.mp4", items[0].extras["media"])
        assertTrue(items[0].id.endsWith(":z1"))
        assertTrue("x" in items[0].tags)
    }

    @Test fun `booru links go to the right page for each board engine`() {
        val root = parse("""[{"id":7,"file_url":"https://cdn/x.jpg","tags":"a b"}]""")
        assertEquals("https://rule34.xxx/index.php?page=post&s=view&id=7", BooruParser.parse(root, "https://api.rule34.xxx")[0].pageUrl)
        assertEquals("https://gelbooru.com/index.php?page=post&s=view&id=7", BooruParser.parse(root, "gelbooru.com")[0].pageUrl)
        assertEquals("https://yande.re/post/show/7", BooruParser.parse(root, "https://yande.re")[0].pageUrl)
        assertEquals("https://konachan.com/post/show/7", BooruParser.parse(root, "konachan.com")[0].pageUrl)
        assertEquals("https://danbooru.donmai.us/posts/7", BooruParser.parse(root, "https://danbooru.donmai.us/")[0].pageUrl)
    }

    @Test fun `same post number on two boards is two different items`() {
        val root = parse("""[{"id":7,"file_url":"https://cdn/x.jpg"}]""")
        val a = BooruParser.parse(root, "rule34.xxx")[0]
        val b = BooruParser.parse(root, "gelbooru.com")[0]
        assertTrue(a.key != b.key)
    }
}
