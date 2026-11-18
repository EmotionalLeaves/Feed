package dev.pfeed

import dev.pfeed.domain.reco.TasteEngine
import dev.pfeed.sim.Baseline
import dev.pfeed.sim.EngineRec
import dev.pfeed.sim.User
import dev.pfeed.sim.World
import dev.pfeed.sim.simulate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.random.Random

/**
 * The taste engine against the old ranking (tag weights alone), in the simulated world (see
 * dev.pfeed.sim.SimWorld): it should find what you like sooner, follow a change of taste sooner,
 * and keep several tastes alive.
 */
class TasteEngineTest {
    private val seeds = 1..10

    private fun early(make: (Int) -> dev.pfeed.sim.Recommender, user: () -> User) =
        seeds.map { simulate(make, user(), it, rounds = 6).hits.take(5).average() }.average()

    @Test
    fun `finds what you like sooner`() {
        val user = { User(mapOf(3 to 1.0, 11 to 0.8), dislike = 7) }
        val old = early({ Baseline(it) }, user)
        val new = early({ EngineRec(it) }, user)
        assertTrue("engine's first batches should land more often: $new vs $old", new > old + 0.08)
    }

    @Test
    fun `follows a change of taste sooner`() {
        fun recovery(make: (Int) -> dev.pfeed.sim.Recommender) = seeds.map { s ->
            simulate(make, User(mapOf(3 to 1.0), 7), s + 100, rounds = 22, shiftAt = 12, shiftTo = User(mapOf(15 to 1.0), 7)).hits.drop(13).take(9).average()
        }.average()
        val old = recovery { Baseline(it) }
        val new = recovery { EngineRec(it) }
        assertTrue("after a change of taste the engine should catch up faster: $new vs $old", new > old + 0.04)
    }

    @Test
    fun `keeps several tastes alive and well served`() {
        val runs = seeds.map { simulate({ EngineRec(it) }, User(mapOf(3 to 1.0, 11 to 0.85, 19 to 0.7), 7), it) }
        val hits = runs.map { it.hits.takeLast(10).average() }.average()
        assertTrue("most of the feed should be things they like (got $hits)", hits >= 0.82)
        // Each of the three tastes gets a real share, not one taking everything.
        listOf(3, 11, 19).forEach { t ->
            val share = runs.map { it.shares[t] ?: 0.0 }.average()
            assertTrue("taste $t got only $share", share >= 0.15)
        }
    }

    @Test
    fun `the map puts tags that keep company together`() {
        val world = World(Random(1))
        val engine = TasteEngine()
        repeat(3000) { engine.observe(listOf(world.post().item)) }
        fun pair(a: Int, b: Int) = List(100) { TasteEngine.cos(engine.vectorOf(world.post(a).item)!!, engine.vectorOf(world.post(b).item)!!) }.average()
        val same = pair(3, 3)
        val different = pair(3, 11)
        assertTrue("same theme $same should be much closer than different themes $different", same > different + 0.25)
    }

    @Test
    fun `what it learned survives a restart`() {
        val world = World(Random(2))
        val engine = TasteEngine()
        repeat(500) { engine.observe(listOf(world.post().item)) }
        repeat(20) { engine.learn(world.post(3).item, 2.0) }
        val bytes = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { engine.writeTo(it) } }.toByteArray()
        val back = TasteEngine().apply { readFrom(DataInputStream(ByteArrayInputStream(bytes))) }
        assertEquals(engine.interests.size, back.interests.size)
        val probe = world.post(3).item
        assertEquals(engine.interestScore(engine.vectorOf(probe)!!), back.interestScore(back.vectorOf(probe)!!), 1e-3)
    }

    @Test
    fun `not interested steers away from that kind of thing`() {
        val world = World(Random(3))
        val engine = TasteEngine()
        repeat(2000) { engine.observe(listOf(world.post().item)) }
        val bad = world.post(7).item
        engine.learn(bad, -8.0)
        val near = engine.aversion(engine.vectorOf(world.post(7).item)!!)
        val far = engine.aversion(engine.vectorOf(world.post(12).item)!!)
        assertTrue("posts like the rejected one should be avoided ($near vs $far)", near > far + 0.2)
    }

    @Test
    fun `the taste map draws two tastes apart and explains a pick`() {
        val world = World(Random(4))
        val engine = TasteEngine()
        repeat(3000) { engine.observe(listOf(world.post().item)) }
        val aff = HashMap<String, Double>()
        repeat(30) { listOf(3, 11).forEach { t -> val p = world.post(t).item; engine.learn(p, 2.0); p.tags.forEach { aff[it] = 0.8 } } }
        val map = engine.tasteMap(aff)
        fun spot(t: Int) = map.stars.filter { it.tag.startsWith("t${t}_") }
        val a = spot(3); val b = spot(11)
        assertTrue("both tastes should be on the map", a.isNotEmpty() && b.isNotEmpty())
        fun spread(x: List<TasteEngine.Star>, y: List<TasteEngine.Star>) = x.flatMap { p -> y.map { q -> kotlin.math.hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) } }.average()
        assertTrue("a taste's own tags should sit closer together than to the other taste's", spread(a, a) < spread(a, b))
        val why = engine.explain(world.post(3).item, aff)
        assertTrue("a post from a taste should be explained by it: $why", why.any { it.strong })
    }

    @Test
    fun `a late-night taste gets the late-night slots`() {
        val world = World(Random(5))
        val engine = TasteEngine()
        repeat(3000) { engine.observe(listOf(world.post().item)) }
        var hour = 14
        engine.clock = { hour }
        // Afternoons: loves theme 3, skips theme 11. Late at night: the other way round.
        repeat(40) {
            hour = 14; engine.learn(world.post(3).item, 2.0); engine.learn(world.post(11).item, -1.0)
            hour = 23; engine.learn(world.post(11).item, 2.0); engine.learn(world.post(3).item, -1.0)
        }
        fun share(h: Int, theme: Int): Double {
            hour = h
            val probe = FloatArray(engine.vectorOf(world.post(theme).item)!!.size)
            repeat(30) { engine.vectorOf(world.post(theme).item)?.let { v -> for (k in probe.indices) probe[k] += v[k] } }
            TasteEngine.normalize(probe)
            val rng = Random(9)
            val picks = List(200) { engine.pickInterest(rng) }
            return picks.count { it != null && TasteEngine.cos(it.centroid, probe) > 0.6 } / 200.0
        }
        assertTrue("late at night, theme 11 should get the slots (${share(23, 11)} vs ${share(23, 3)})", share(23, 11) > share(23, 3) + 0.15)
        assertTrue("in the afternoon, theme 3 should (${share(14, 3)} vs ${share(14, 11)})", share(14, 3) > share(14, 11) + 0.15)
    }

    @Test
    fun `finds creators who make the same kind of thing`() {
        val world = World(Random(7))
        val engine = TasteEngine()
        repeat(4000) { engine.observe(listOf(world.post().item)) }
        fun byCreator(name: String, theme: Int) = List(8) { world.post(theme).item.copy(creator = name) }
        listOf("ann" to 3, "bea" to 3, "cat" to 11, "dee" to 11).forEach { (n, t) -> engine.observe(byCreator(n, t)) }
        assertEquals("bea", engine.similarCreators("ann").firstOrNull())
        assertEquals("dee", engine.similarCreators("cat").firstOrNull())
    }
}
