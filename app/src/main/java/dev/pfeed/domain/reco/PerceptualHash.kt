package dev.pfeed.domain.reco

/** 64-bit difference hash (dHash) over a 9×8 grayscale image. Pure, so it is unit-testable. */
object PerceptualHash {
    const val WIDTH = 9
    const val HEIGHT = 8

    fun toGray(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    fun dHash(gray: IntArray): Long {
        require(gray.size == WIDTH * HEIGHT) { "expected ${WIDTH * HEIGHT} pixels, got ${gray.size}" }
        var hash = 0L
        var bit = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH - 1) {
                if (gray[y * WIDTH + x] > gray[y * WIDTH + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
