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
        assertEquals(engine.interestScore(engine.vectorOf(probe)!!), back.interestScore(back.vectorOf(probe)!!), 1e-6)
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
}
