package com.phequals7.muesli.meetings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm32kTo16kDownsamplerTest {

    private fun shorts(vararg values: Int) = ShortArray(values.size) { values[it].toShort() }

    @Test
    fun `averages each pair with arithmetic shift rounding`() {
        val d = Pcm32kTo16kDownsampler()
        val out = ShortArray(4)
        val n = d.process(shorts(100, 200, -100, -201, -32768, -32768, 32767, 32767), 8, out)
        assertEquals(4, n)
        assertArrayEquals(shorts(150, -151, -32768, 32767), out.copyOf(n))
    }

    @Test
    fun `extreme pairs clamp correctly without overflow`() {
        val d = Pcm32kTo16kDownsampler()
        val out = ShortArray(2)
        val n = d.process(shorts(-32768, 32767, 32767, -32768), 4, out)
        assertEquals(2, n)
        // (-1) shr 1 rounds to -1, not 0: both extreme pairs average to -1.
        assertArrayEquals(shorts(-1, -1), out.copyOf(n))
    }

    @Test
    fun `empty read preserves the carry unchanged`() {
        val d = Pcm32kTo16kDownsampler()
        assertEquals(0, d.process(shorts(7), 1, ShortArray(1))) // odd input: 7 becomes the carry
        assertEquals(0, d.process(shorts(), 0, ShortArray(1)))
        // Carry then pairs with the next input sample.
        val out = ShortArray(1)
        assertEquals(1, d.process(shorts(9), 1, out))
        assertArrayEquals(shorts(8), out.copyOf(1))
    }

    @Test
    fun `partitioning at every boundary equals one continuous read`() {
        val input = ShortArray(51) { ((it * 997).rem(65536) - 32768).toShort() }
        val continuous = ShortArray(26)
        val nContinuous = Pcm32kTo16kDownsampler().process(input, input.size, continuous)

        for (split in 0..input.size) {
            val d = Pcm32kTo16kDownsampler()
            val out = ShortArray(nContinuous)
            var produced = 0
            val inBuf = ShortArray(input.size)
            val outBuf = ShortArray(input.size / 2 + 1)
            for ((from, to) in listOf(0 to split, split to input.size)) {
                val len = to - from
                if (len == 0) continue
                System.arraycopy(input, from, inBuf, 0, len)
                val n = d.process(inBuf, len, outBuf)
                System.arraycopy(outBuf, 0, out, produced, n)
                produced += n
            }
            assertEquals("split=$split", nContinuous, produced)
            assertArrayEquals("split=$split", continuous.copyOf(nContinuous), out.copyOf(produced))
        }
    }

    @Test
    fun `32000 complete samples produce 16000 outputs regardless of read size`() {
        val input = ShortArray(32_000) { (it % 251).toShort() }
        val expected = ShortArray(16_000)
        assertEquals(16_000, Pcm32kTo16kDownsampler().process(input, input.size, expected))

        for (chunkSize in intArrayOf(1, 3, 1600, 3200)) {
            val d = Pcm32kTo16kDownsampler()
            val out = ShortArray(16_000)
            var produced = 0
            var offset = 0
            val staging = ShortArray(chunkSize)
            val stagingOut = ShortArray(chunkSize / 2 + 1)
            while (offset < input.size) {
                val len = minOf(chunkSize, input.size - offset)
                System.arraycopy(input, offset, staging, 0, len)
                val written = d.process(staging, len, stagingOut)
                System.arraycopy(stagingOut, 0, out, produced, written)
                produced += written
                offset += len
            }
            assertEquals("chunkSize=$chunkSize", 16_000, produced)
            assertArrayEquals("chunkSize=$chunkSize", expected, out)
        }
    }

    @Test
    fun `odd input keeps exactly one unpaired sample for the next call`() {
        val d = Pcm32kTo16kDownsampler()
        val out = ShortArray(2)
        assertEquals(1, d.process(shorts(10, 12, 20), 3, out))
        assertArrayEquals(shorts(11), out.copyOf(1))
        assertEquals(1, d.process(shorts(22), 1, out))
        assertArrayEquals(shorts(21), out.copyOf(1))
    }

    @Test
    fun `invalid length or capacity rejects without changing state`() {
        val d = Pcm32kTo16kDownsampler()
        assertEquals(0, d.process(shorts(5), 1, ShortArray(1))) // odd input: carry now holds 5

        assertThrows<IllegalArgumentException> { d.process(shorts(1), 2, ShortArray(1)) }
        assertThrows<IllegalArgumentException> { d.process(shorts(1, 2), -1, ShortArray(2)) }
        assertThrows<IllegalArgumentException> { d.process(shorts(1, 2, 3), 3, ShortArray(1)) }

        // The carry survived the rejected calls: 5 pairs with the next sample.
        val out = ShortArray(1)
        assertEquals(1, d.process(shorts(7), 1, out))
        assertArrayEquals(shorts(6), out.copyOf(1))
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return
            throw AssertionError("Expected ${T::class.simpleName} but got ${t::class.simpleName}: $t")
        }
        throw AssertionError("Expected ${T::class.simpleName} but nothing was thrown")
    }
}
