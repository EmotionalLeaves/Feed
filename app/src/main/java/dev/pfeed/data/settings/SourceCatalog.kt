package dev.pfeed.data.settings

/**
 * The large built-in source library, generated from real board hosts × real tag vocabularies (and
 * real RedGIFs search terms), so every entry is a query that actually returns posts. Merged into
 * [DefaultSources] and, through a settings migration, into existing installs.
 *
 * Nothing here is age-adjacent: no school, petite/flat or age-play tags on drawn boards, and no
 * "teen" searches. The always-on minors filter still applies on top of all of it.
 */
object SourceCatalog {

    // ── Image boards ────────────────────────────────────────────────────────────

    /** Gelbooru-engine anime/art boards: they share the Danbooru-style tag vocabulary. */
    private val ANIME_GELBOORU_HOSTS = listOf("rule34.xxx", "gelbooru.com", "tbib.org", "xbooru.com")

    /** Danbooru-engine boards: anonymous searches allow two tags, so it's always "rating:explicit TAG". */
    private val DANBOORU_HOSTS = listOf("danbooru.donmai.us", "aibooru.online")

    private val E621_HOSTS = listOf("e621.net", "e6ai.net")

    private val MOEBOORU_HOSTS = listOf("yande.re", "konachan.com")

    /** Danbooru-style tags (drawn/3D). */
    val animeTags: List<String> = listOf(
        // acts
        "sex", "vaginal", "anal", "oral", "fellatio", "deepthroat", "irrumatio", "cunnilingus", "anilingus",
        "paizuri", "handjob", "footjob", "thigh_sex", "masturbation", "fingering", "female_masturbation",
        "group_sex", "threesome", "ffm_threesome", "mmf_threesome", "gangbang", "double_penetration",
        "spitroast", "orgy", "bukkake", "creampie", "cum_in_pussy", "cum_in_mouth", "cum_in_ass",
        "cum_on_body", "cum_on_breasts", "facial", "cum_drip", "cum_overflow", "excessive_cum", "gokkun",
        "after_sex", "ejaculation", "squirting", "female_ejaculation", "orgasm", "trembling",
        // positions
        "doggystyle", "sex_from_behind", "cowgirl_position", "reverse_cowgirl_position", "missionary",
        "mating_press", "standing_sex", "full_nelson", "suspended_congress", "prone_bone", "lotus_position",
        "spooning", "piledriver_(sex)", "69",
        // kinks
        "bondage", "bdsm", "shibari", "rope", "restrained", "bound_wrists", "gag", "ball_gag", "blindfold",
        "collar", "leash", "chain", "handcuffs", "femdom", "pegging", "strap-on", "sex_toy", "vibrator",
        "dildo", "anal_beads", "butt_plug", "mind_control", "hypnosis", "ahegao", "fucked_silly", "drooling",
        "x-ray", "cross-section", "impregnation", "pregnant", "lactation", "spanking", "choking",
        "humiliation", "exhibitionism", "public_indecency", "voyeurism", "stomach_bulge", "size_difference",
        "tentacles", "tentacle_sex", "futanari", "futa_with_female", "futa_with_futa", "yuri", "yaoi",
        "monster_girl", "succubus", "demon_girl", "elf", "dark_elf", "cat_girl", "fox_girl", "bunny_girl",
        "slime_girl", "lamia", "harpy", "orc", "goblin",
        // bodies (adult-coded)
        "huge_breasts", "large_breasts", "gigantic_breasts", "sagging_breasts", "milf", "mature_female",
        "curvy", "thick_thighs", "wide_hips", "plump", "huge_ass", "ass_focus", "muscular_female", "abs",
        "dark-skinned_female", "tan", "tanlines", "gyaru", "pubic_hair", "hairy_pussy", "nipples",
        "inverted_nipples", "puffy_nipples", "pussy", "spread_pussy", "spread_legs", "spread_ass",
        "presenting", "completely_nude", "nude", "uncensored", "huge_penis", "large_penis",
        // outfits
        "thighhighs", "pantyhose", "stockings", "garter_belt", "lingerie", "bikini", "micro_bikini",
        "bodysuit", "latex", "leotard", "playboy_bunny", "maid", "nurse", "nun", "office_lady",
        "fishnets", "high_heels", "see-through", "wet_clothes", "torn_clothes", "naked_apron",
        // media
        "animated", "video", "sound", "3d", "comic", "pov",
    )

    /** Real-photo tags (realbooru). */
    val photoTags: List<String> = listOf(
        "sex", "blowjob", "deepthroat", "anal", "creampie", "cumshot", "facial", "cum_in_mouth", "swallowing",
        "threesome", "gangbang", "double_penetration", "orgy", "lesbian", "solo_female", "masturbation",
        "fingering", "dildo", "vibrator", "squirting", "orgasm", "pov", "doggy_style", "cowgirl",
        "reverse_cowgirl", "missionary", "riding", "titfuck", "handjob", "footjob", "big_breasts",
        "huge_breasts", "natural_breasts", "big_ass", "pawg", "thick", "curvy", "chubby", "bbw", "fit",
        "milf", "mature", "asian", "japanese", "latina", "ebony", "indian", "redhead", "blonde", "brunette",
        "tattoo", "piercing", "pierced_nipples", "hairy_pussy", "shaved_pussy", "pussy", "spread_legs",
        "lingerie", "stockings", "pantyhose", "high_heels", "bikini", "yoga_pants", "cosplay", "outdoor",
        "public", "shower", "bondage", "bdsm", "rope", "spanking", "choking", "rough_sex", "interracial",
        "bbc", "cuckold", "femdom", "strapon", "pegging", "feet", "ass_to_mouth", "gape", "selfie",
        "mirror_selfie", "amateur", "pornstar", "video", "animated", "gif",
    )

    /** e621-style tags (furry/anthro; feral excluded). */
    val furryTags: List<String> = listOf(
        "anthro", "sex", "anal", "oral", "vaginal_penetration", "knot", "knotting", "cum_inside", "cum_on_face",
        "cumshot", "excessive_cum", "creampie", "bondage", "bdsm", "femdom", "gangbang", "threesome",
        "group_sex", "masturbation", "solo", "presenting", "spread_legs", "nude", "big_breasts",
        "huge_breasts", "big_butt", "thick_thighs", "muscular", "muscular_female", "big_penis", "huge_penis",
        "male/female", "female/female", "male/male", "intersex", "gynomorph", "herm", "tentacles",
        "inflation", "pregnant", "latex", "rubber", "collar", "leash", "hypnosis", "transformation",
        "canine", "feline", "equine", "dragon", "scalie", "avian", "bovine", "lagomorph", "vulpine",
        "lupine", "animated", "3d_(artwork)", "comic", "pov", "ahegao", "orgasm", "cum_drip",
    )

    /** moebooru tags (yande.re / Konachan). */
    val moebooruTags: List<String> = listOf(
        "sex", "nipples", "pussy", "cum", "naked", "breasts", "bondage", "anal", "fellatio", "paizuri",
        "masturbation", "yuri", "tentacles", "thighhighs", "lingerie", "bikini", "nopan", "uncensored",
        "erect_nipples", "breast_hold", "ass", "pussy_juice", "spread_legs", "topless", "bottomless",
        "censored", "futanari", "dildo", "garter", "pantyhose",
    )

    /** Acts paired with "animated" on boards: short looping clips, the most TikTok-like board content. */
    val animatedActs: List<String> = listOf(
        "sex", "vaginal", "anal", "oral", "fellatio", "deepthroat", "paizuri", "handjob", "footjob",
        "masturbation", "fingering", "cunnilingus", "group_sex", "threesome", "gangbang",
        "double_penetration", "creampie", "cum_in_pussy", "cum_in_mouth", "facial", "ejaculation",
        "squirting", "orgasm", "doggystyle", "cowgirl_position", "reverse_cowgirl_position",
        "missionary", "mating_press", "standing_sex", "full_nelson", "bondage", "femdom", "futanari",
        "yuri", "tentacles", "ahegao", "huge_breasts", "bouncing_breasts", "jiggle", "huge_ass",
        "milf", "monster_girl", "x-ray", "pov", "sound", "3d", "blender_(medium)", "source_filmmaker_(medium)",
    )

    /**
     * Franchises whose main cast is adult. Series centred on school-age characters are deliberately
     * left out; the minors filter and negative tags still apply to everything else.
     */
    val franchiseTags: List<String> = listOf(
        "overwatch", "league_of_legends", "final_fantasy", "final_fantasy_vii", "resident_evil",
        "street_fighter", "mortal_kombat", "tekken", "soulcalibur", "dead_or_alive", "metroid",
        "apex_legends", "valorant", "fortnite", "mass_effect", "tomb_raider", "the_witcher",
        "cyberpunk_2077", "elden_ring", "dark_souls", "bayonetta", "devil_may_cry", "darkstalkers",
        "the_elder_scrolls", "skyrim", "fallout", "warcraft", "world_of_warcraft", "star_wars", "marvel",
        "dc_comics", "batman_(series)", "spider-man_(series)", "x-men", "genshin_impact",
        "honkai:_star_rail", "zenless_zone_zero", "goddess_of_victory:_nikke", "one_piece",
        "dragon_ball", "helltaker", "metal_gear", "baldur's_gate_3", "dungeons_and_dragons", "warhammer_40k",
        "monster_hunter", "nier:_automata", "stellar_blade", "bioshock",
    )

    /** Real-photo video feeds (realbooru tags its clips "video"). */
    private val photoVideoActs = listOf(
        "sex", "blowjob", "deepthroat", "anal", "creampie", "cumshot", "facial", "threesome", "lesbian",
        "masturbation", "squirting", "orgasm", "pov", "riding", "doggy_style", "titfuck", "handjob",
        "bondage", "rough_sex", "interracial", "milf", "amateur", "big_breasts", "big_ass", "asian",
        "latina", "ebony", "redhead", "blonde", "outdoor",
    )

    /** hypnohub.net (gelbooru engine): one niche board, a few feeds. */
    private val hypnoTags = listOf(
        "sex", "nude", "femsub", "maledom", "femdom", "malesub", "spiral_eyes", "empty_eyes",
        "animated", "comic", "corruption", "drool",
    )

    /** Body type × act pairings on the biggest boards (empty pairings get pruned by feed health). */
    private val pairBodies = listOf(
        "huge_breasts", "large_breasts", "milf", "mature_female", "dark-skinned_female", "muscular_female",
        "thick_thighs", "huge_ass", "futanari", "elf", "cat_girl", "succubus", "tan", "gyaru", "plump", "curvy",
    )
    private val pairActs = listOf(
        "sex", "anal", "paizuri", "fellatio", "cowgirl_position", "doggystyle", "missionary", "creampie",
        "ahegao", "bondage", "handjob", "footjob", "masturbation", "group_sex", "mating_press", "animated",
    )

    val booruFeeds: List<BooruFeed> = buildList {
        // Pools: ordered multi-page sets (comics), one gallery item each. e621's are nearly all
        // explicit; Danbooru's are filtered down to their adult pages.
        for (host in listOf("e621.net", "danbooru.donmai.us")) for (order in listOf("recent", "new")) add(BooruFeed(host, "pools:$order"))
        for (b in pairBodies) for (a in pairActs) {
            add(BooruFeed("rule34.xxx", "$b $a"))
            add(BooruFeed("gelbooru.com", "$b $a"))
            add(BooruFeed("tbib.org", "$b $a"))
            add(BooruFeed("xbooru.com", "$b $a"))
            // Danbooru: two tags is the anonymous limit; the post-level rating filter drops safe ones.
            add(BooruFeed("danbooru.donmai.us", "$b $a"))
        }
        for (f in franchiseTags) for (host in ANIME_GELBOORU_HOSTS) add(BooruFeed(host, f))
        for (f in franchiseTags) for (host in DANBOORU_HOSTS) add(BooruFeed(host, "rating:explicit $f"))
        for (act in photoVideoActs) add(BooruFeed("realbooru.com", "video $act"))
        for (t in hypnoTags) add(BooruFeed("hypnohub.net", t))
        for (act in animatedActs) for (host in ANIME_GELBOORU_HOSTS) add(BooruFeed(host, "animated $act"))
        // Danbooru allows two tags anonymously; the post-level rating filter drops any safe clips.
        for (act in animatedActs) for (host in DANBOORU_HOSTS) add(BooruFeed(host, "animated $act"))
        for (tag in animeTags) for (host in ANIME_GELBOORU_HOSTS) add(BooruFeed(host, tag))
        for (tag in animeTags) for (host in DANBOORU_HOSTS) add(BooruFeed(host, "rating:explicit $tag"))
        for (tag in photoTags) add(BooruFeed("realbooru.com", tag))
        for (tag in furryTags) for (host in E621_HOSTS) add(BooruFeed(host, "$tag rating:e"))
        for (tag in moebooruTags) for (host in MOEBOORU_HOSTS) add(BooruFeed(host, "$tag rating:e"))
    }

    // ── RedGIFs ─────────────────────────────────────────────────────────────────

    private val redgifsCategories = listOf(
        "amateur", "homemade", "couple", "real couple", "pov", "close up", "60fps", "4k", "onlyfans",
        "blowjob", "deepthroat", "sloppy blowjob", "throatpie", "face fuck", "handjob", "titfuck", "footjob",
        "anal", "anal creampie", "ass to mouth", "gape", "creampie", "cumshot", "facial", "cum in mouth",
        "swallow", "cum on tits", "cum on ass", "bukkake", "squirting", "orgasm", "female orgasm",
        "shaking orgasm", "edging", "ruined orgasm", "masturbation", "fingering", "dildo", "vibrator",
        "toys", "riding", "cowgirl", "reverse cowgirl", "doggystyle", "missionary", "mating press",
        "prone bone", "standing sex", "rough", "rough sex", "choking", "hair pulling", "spanking", "slapping",
        "bdsm", "bondage", "tied up", "shibari", "gagged", "collar", "submissive", "dominant", "femdom",
        "pegging", "strapon", "degradation", "cnc", "breeding", "threesome", "ffm", "mmf", "foursome",
        "gangbang", "orgy", "double penetration", "spitroast", "group sex", "lesbian", "lesbian strapon",
        "scissoring", "kissing", "big tits", "huge tits", "natural tits", "small tits", "bouncing tits",
        "big ass", "pawg", "thick", "curvy", "chubby", "bbw", "fit", "muscle girl", "skinny", "tall",
        "short", "milf", "mature", "cougar", "college", "asian", "japanese", "korean", "chinese", "thai",
        "filipina", "indian", "latina", "brazilian", "colombian", "ebony", "black", "white", "redhead",
        "blonde", "brunette", "goth", "alt", "emo", "tattoo", "piercing", "pierced nipples", "pale",
        "tan lines", "freckles", "glasses", "hairy", "shaved", "pussy", "pussy close up", "ass close up",
        "feet", "soles", "legs", "thighs", "stockings", "fishnets", "pantyhose", "lingerie", "heels",
        "yoga pants", "leggings", "bikini", "thong", "panties", "latex", "leather", "cosplay", "anime cosplay",
        "nurse", "maid", "public", "outdoor", "beach", "car sex", "shower", "bathtub", "oiled", "massage",
        "caught", "risky", "flashing", "voyeur", "striptease", "dancing", "twerking", "jiggle", "booty shake",
        "interracial", "bbc", "cuckold", "hotwife", "wife sharing", "cheating", "glory hole", "casting",
        "big dick", "monster cock", "huge cock", "cock worship", "ball sucking", "rimming", "ass eating",
        "pussy eating", "facesitting", "69", "trans", "tgirl", "futanari", "femboy", "gay", "bisexual",
        "hentai", "3d hentai", "animated hentai", "rule34", "sfm", "blender", "overwatch", "genshin",
        "jav", "uncensored jav", "pornstar", "camgirl", "selfie", "mirror", "teasing", "joi", "asmr",
        "moaning", "talking dirty", "sex sounds", "slow motion", "compilation", "cumshot compilation",
        "rough anal", "deep anal", "anal gape", "anal toys", "plug", "fisting", "squirt compilation",
        // more categories
        "bratty", "good girl", "praise", "eye contact", "looking at camera", "begging", "pleading",
        "facial expressions", "ahegao", "drooling", "messy", "spit", "sloppy", "wet", "dripping",
        "cum drip", "cum play", "cum swap", "snowballing", "creampie eating", "multiple creampies",
        "breeding kink", "pregnant", "lactating", "hairy pussy", "hairy armpits", "armpits", "belly",
        "abs", "muscular", "bodybuilder", "athletic", "yoga", "gym", "sweaty", "stretching", "flexible",
        "splits", "legs up", "legs behind head", "spread", "spreading", "gaping pussy", "pussy lips",
        "big clit", "clit rubbing", "grinding", "humping", "pillow humping", "dry humping", "frotting",
        "thigh job", "boob job", "nipple play", "nipple clamps", "big nipples", "puffy nipples",
        "hanging tits", "saggy tits", "perky tits", "fake tits", "oiled tits", "tit slapping", "jiggling",
        "clapping", "ass clapping", "ass jiggle", "spanking ass", "red ass", "belt", "paddle", "whip",
        "rope bondage", "hogtied", "spreader bar", "blindfolded", "ball gag", "mouth gag", "leash",
        "pet play", "puppy play", "kneeling", "worship", "foot worship", "pussy worship", "ass worship",
        "face fucking", "throat bulge", "gagging", "tears", "mascara", "makeup", "lipstick", "nails",
        "long nails", "heels on", "stockings sex", "lingerie sex", "dress", "skirt", "upskirt",
        "no panties", "panties aside", "panty", "bra", "sports bra", "crop top", "tank top", "jeans",
        "shorts", "towel", "bathrobe", "mirror sex", "bed", "couch", "kitchen", "office", "hotel",
        "pool", "hot tub", "sauna", "forest", "camping", "balcony", "window", "stairs", "gym sex",
        "first time", "real orgasm", "multiple orgasms", "shaking legs", "contractions", "pulsating",
        "slow sex", "passionate", "romantic", "sensual", "hard fuck", "pounding", "deep penetration",
        "big dick ride", "cock riding", "dildo riding", "suction dildo", "glass dildo", "double dildo",
        "magic wand", "rabbit vibrator", "sex machine", "fucking machine", "remote vibrator",
    )

    private val redgifsWho = listOf(
        "milf", "asian", "latina", "ebony", "redhead", "blonde", "brunette", "pawg", "thick", "goth",
        "japanese", "indian", "big tits", "amateur", "college", "mature", "fit", "tattooed", "busty",
        "curvy", "bbw", "alt", "korean", "brazilian", "wife", "girlfriend", "pornstar", "natural tits",
        "big ass", "pale",
    )
    private val redgifsWhat = listOf(
        "blowjob", "anal", "creampie", "riding", "doggystyle", "squirting", "deepthroat", "facial",
        "threesome", "pov", "rough", "bondage", "masturbation", "orgasm", "titfuck", "cumshot",
        "reverse cowgirl", "missionary", "handjob", "lesbian", "toys", "gangbang", "outdoor", "shower",
        "teasing",
    )

    /** Categories, plus "who × what" pairings (e.g. "milf anal"), each its own trending feed. */
    val redgifsTerms: List<String> = (redgifsCategories + redgifsWho.flatMap { w -> redgifsWhat.map { "$w $it" } })
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()

    // ── Mastodon ────────────────────────────────────────────────────────────────

    private val mastodonTags = listOf(
        "nsfw", "porn", "nude", "nudes", "lewd", "hentai", "rule34", "bdsm", "bondage", "kink", "shibari",
        "femdom", "amateur", "boobs", "ass", "pussy", "cock", "blowjob", "anal", "cum", "milf", "feet",
        "latex", "cosplay", "furry", "yiff", "futanari", "yuri", "gay", "trans", "selfie", "nsfwart",
    )
    /** Servers checked to answer tag timelines without an account. */
    private val MASTODON_HOSTS = listOf("mastodon.social", "mas.to", "mstdn.jp", "kinktroet.social", "rubber.social")

    val mastodonFeeds: List<MastodonFeed> = buildList {
        for (tag in mastodonTags) for (host in MASTODON_HOSTS) add(MastodonFeed(host, tag))
    }

    // ── Reddit ──────────────────────────────────────────────────────────────────

    /** Additional well-known NSFW subreddits (dead or renamed ones are dropped after a few failures). */
    val redditSubs: List<String> = listOf(
        // amateur / general
        "Amateur", "AmateurPorn", "amateurgirlsbigcocks", "AmateurPornVideos", "RealGirls", "NSFW_GIF",
        "nsfw_gifs", "porn_gifs", "PornGifs", "NSFW_HTML5", "60FPSPorn", "HighResNSFW", "UHDnsfw",
        "LabiaGW", "GirlsFinishingTheJob", "HappyEmbarrassedGirls", "UnderwearGW", "WouldYouFuckMyWife",
        "GWCouples", "GoneWildCouples", "gwcumsluts", "Exxxtras", "PornStarHQ", "PornStarLinks", "Pornstars",
        "nsfwhardcore", "hardcore", "Sex", "SexyFrex", "palegirls", "NSFW_Wallpapers", "iWantToFuckHer",
        "DirtySnapchat", "NSFW_Snapchat", "snapleaks", "Fitness_NSFW", "gonewildstories", "GWNerdy",
        "GoneWildPlus", "gonewildcolor", "AsiansGoneWild", "gonewildcurvy", "BustyPetite", "PetiteGoneWild",
        // bodies
        "boobs", "Boobies", "Titties", "tits", "hugeboobs", "BigBoobsGonewild", "BigBoobsGW", "naturaltitties",
        "Nipples", "PuffyNipples", "Areolas", "TittyDrop", "BouncingTits", "boobbounce", "Stacked",
        "ass", "asstastic", "pawg", "bigasses", "Booty", "BootyGIFs", "BubbleButts", "girlsinyogapants",
        "yogapants", "LegsUp", "thighdeology", "thick", "thickthighs", "curvy", "Curvy_Women", "BBW",
        "chubby", "pussy", "GodPussy", "LipsThatGrip", "rearpussy", "spreadeagle", "Asshole", "HairyPussy",
        "hairy", "Bushy", "Innie", "FitGirls", "fitnakedgirls", "muscle_girls", "Tall_Gone_Wild", "TallGoneWild",
        "redheads", "ginger", "Blondes", "Brunette", "Tgirls", "Freckled", "tattoo_girls", "PiercedNSFW",
        "altgonewild", "GothGirls", "goth_babes", "SuicideGirls", "AlternativeNSFW", "emogirlsgonewild",
        // acts
        "Blowjobs", "blowjobsandwich", "deepthroat", "SloppyBlowjobs", "throatpies", "facefuck", "Facials",
        "cumsluts", "cumshots", "CumHaters", "cumfetish", "Creampie", "creampies", "creampiegifs",
        "anal", "analgw", "AnalGonewild", "buttsex", "Gape", "AnalOrgasms", "assholegonewild",
        "Squirting", "squirtingcunts", "O_Faces", "FemaleOrgasm", "RealOrgasms", "Orgasms", "orgasmiccontrol",
        "Handjobs", "titfuck", "Paizuri", "footjobs", "Riding", "GirlsRiding", "Doggystyle", "doggy",
        "Missionary", "matingpress", "pronebone", "SexInFrontOfOthers", "threesome", "Threesomes", "FFM",
        "MMF", "groupsex", "gangbang", "GangbangGIFs", "DoublePenetration", "Spitroast", "orgies",
        "lesbians", "Lesbian_gifs", "scissoring", "girlskissing", "StraponLesbians", "Strapon", "Pegging",
        // kink
        "BDSM", "BDSMGW", "bdsmgifs", "bondage", "Bondage_GIFs", "shibari", "ropebunnies", "Rope_Bondage",
        "femdom", "FemdomCommunity", "Femdom_GIFs", "maledom", "Dominated", "submissive", "SubmissiveGirls",
        "collared", "Choking", "breathplay", "spanking", "Spanking_GIFs", "redbottoms", "OTKSpanking",
        "Degradation", "humiliation", "Gagged", "Gagging", "throatfuck", "ForcedOrgasms", "Overstimulation",
        "breeding", "BreedingKink", "Impregnation", "Lactation", "PreggoPorn",
        "latex", "latexfetish", "rubberfetish", "Leather", "Stockings", "pantyhose", "nylon", "fishnets",
        "lingerie", "LingerieGW", "HighHeels", "feet", "FootFetish", "feetish", "Soles", "TickleTorture",
        "public", "PublicFlashing", "PublicSexPorn", "flashing", "exhibitionism", "Nudism", "nakedoutdoors",
        "CuckoldCommunity", "Cuckold", "HotWife", "Hotwife", "slutwife", "Swingers", "Wifesharing",
        "interracial", "InterracialGifs", "bbc", "BlackGirls", "ebony", "EbonyGW", "asianhotties",
        "AsianNSFW", "juicyasians", "japanesesex", "JAV", "KoreanHotties", "IndianBabes", "IndianNSFW",
        "latinas", "LatinaCuties", "LatinasGW", "Brazilian", "milf", "milfs", "MILFS", "maturemilf",
        "OlderWomen", "GILF", "cougars", "Wives", "milfsgonewild",
        // drawn / 3d
        "hentai", "HENTAI_GIF", "hentaibondage", "ahegao", "rule34", "rule34gifs", "Rule34LoL",
        "overwatch_porn", "Genshin_Impact_NSFW", "pokeporn", "cartoonporn", "sfmcompileclub", "SFMporn",
        "3DPorncraft", "blender_porn", "futanari", "FutanariGifs", "yuri", "yaoi", "MonsterGirl",
        "tentai", "Tentacle_Hentai", "ecchi", "animebooty", "AnimeMILFS", "thighhighs", "Paizuri",
        // trans / gay
        "Tgirls", "Transgonewild", "tgifs", "traps", "GoneWildTrans", "transporn", "femboys", "femboy",
        "GayGifs", "gaybrosgonewild", "gayporn", "GaybrosGW", "MassiveCock", "ratemycock", "penis",
        "bigdick", "cock", "MonsterCocks", "ThickDick",
        // video-heavy
        "NSFW_GFY", "porninfifteenseconds", "holdthemoan", "Shemales", "60fpsporn", "pornvids", "nsfwvideos",
        "PornVideos", "NSFWVideoBox", "HardcoreSexGifs", "Sexgifs", "Cumshot_GIFs", "BlowjobGifs", "AnalGifs",
        "CreampieGifs", "RidingGifs", "SquirtingGifs", "LesbianGifs", "BondageGifs", "FacialGifs",
        // more amateur / verified
        "GoneWildSmiles", "gonewildaudio", "GoneMild", "GWBigBoobs", "BigBoobsWithFriends", "realsexyselfies",
        "Nude_Selfie", "nudeselfies", "selfshots", "AmateurSelfShots", "ShowerBeer", "Shower_Sex",
        "SexyTummies", "Hipcleavage", "LegsUp", "Feet_NSFW", "SpreadEm", "pussyrating", "ratemyboobs",
        "RateMyNudeBody", "WomenOfColor", "darkangels", "OnlyFansHottest",
        "OnlyFansBooty", "OnlyFansBusty", "OnlyFansLatinas",
        "OnlyFans101", "onlyfansgirls101", "FanslyFriends", "CamGirls", "camwhores", "Chaturbate",
        // more video
        "NSFW_Videos", "nsfw_vids", "PornClips", "TikTokNSFW", "TikTokPorn", "tiktokthots",
        "NSFWFunny", "RealPublicNudity", "OutdoorSex", "CarSex", "Sexsounds", "MoaningGirls",
        "orgasmcontrol", "ThroatGoats", "sloppy", "ass_and_titties", "Boobsandbottoms", "BonerMaterial",
        "nsfw2", "NSFW_nospam", "IWantToSuckCock", "BlowJob", "blowjobgifs", "DeepThroatTears",
        "GaggingWhores", "MouthWideOpen", "cumcoveredfucking", "CumInsideMe", "creampiepov", "povporn",
        "POV_Porn", "RealPOV", "FacesitPorn", "Facesitting", "Femdomporn", "strapon_sex",
        // bodies & looks
        "tightdresses", "SeeThroughClothes", "Braless", "pokies", "bodystockings", "YogaPantsGW",
        "AssInThong", "Thongs", "Underboob", "Sideboob", "BustyNaturals", "Bigtitsinbikinis", "Bikinis",
        "tanlines", "TanLines", "PaleGirls", "Redheads", "RedheadGifs", "GingerGW", "LatinasGW",
        "AsianPorn", "AsianCumsluts", "AsianAnal", "JapaneseAmateur", "ThickAsians", "ThickLatinas",
        "Ebony_Porn", "BlackPornMatters", "IndianPorn", "DesiNSFW", "ArabPorn", "MiddleEasternHotties",
        "Euro_Girls", "RussianGirls", "UkrainianGirls", "BritishBabes", "AussieGirls", "CanadianGW",
        // kink
        "BDSM_NSFW", "BDSMerotica", "bondageporn", "tiedtits", "Breast_Bondage", "Hogtied", "suspensionbondage",
        "Fucking_Machines", "FuckingMachines", "sexmachines", "Vibrators", "dildo", "DildoGW", "toys_nsfw",
        "Hitachi", "Squirt", "squirtinggirls", "SquirtingOrgasms", "AnalPlay", "ButtPlug", "AnalHook",
        "EdgingTalk", "Edging", "ForcedToCum", "cumcontrol", "Chastity", "Pegging_Unkinked", "Sissy",
        "SissyHypno", "HypnoHentai", "Hypno", "Latex_Fetish", "LatexBondage", "Rubberdoll", "NylonFeet",
        "Pantyhosed", "StockingsGW", "Corsets", "Lingerie_GW", "SexyLingerie", "Garter_Belts",
        // couples / amateur video
        "CouplesGoneWild", "CoupleSex", "HomemadeNsfw", "homemadeporn", "RealHomemade", "AmateurCouples",
        "SexyCouples", "GWCouples4Ladies", "MarriedSex", "WifeSex", "OnOffGW",
        "POVgifs", "GirlsOnTop", "CumInside", "CumshotSelfies", "PornGifsHub", "HQGifs", "NSFWgifsHQ",
        // more bodies
        "BigTitsGW", "MassiveTitsnAss", "Bustyfit", "fitgirlsnsfw", "GymNudes", "FitAndNatural",
        "AthleticGirls", "CurvyGirls", "Curvy_Gone_Wild", "ChubbyGirls", "BBWGW", "SSBBW_Love",
        "Thick_Booty", "AssGW", "bootygifs", "PerfectAss", "assholes", "WhiteGirlsAss",
        "LatinaAss", "EbonyAss", "AsianAss", "Pawg_Gonewild", "ThickPawg", "TattooedGirls", "InkedGirls",
        "PiercedTits", "NipplePiercings", "Pierced_NSFW", "AltGW", "GothGW", "EmoGirls", "Punk_GW",
        // more kink
        "Rough_Sex", "RoughSexGifs", "ChokingGifs", "SlappingGifs", "SpitGifs", "Spit", "DroolingGirls",
        "MessyBlowjobs", "AhegaoIRL", "Ahegao_IRL", "OFaces", "CumFaces", "Bukkake_GW", "GloryHoleSex",
        "FreeUse", "FreeUseGifs", "Exhibitionists", "PublicNudity",
        "NudeBeach", "Naturists", "Skinnydipping", "HotTubSex", "ShowerGirls", "BathtubGirls",
    )
}
