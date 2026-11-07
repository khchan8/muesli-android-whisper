package com.phequals7.muesli.meetings

/**
 * Streaming 2:1 pairwise downsample 32 kHz → 16 kHz:
 * `(first + second) shr 1` per output sample (spec-mandated simple average).
 * Keeps at most one unpaired input short between calls; a final unmatched
 * sample (< 1/32,000 s) is discarded at stop rather than padded.
 *
 * Validation happens before any carry state changes, so a rejected call
 * leaves the stream exactly where it was.
 */
internal class Pcm32kTo16kDownsampler {
    private var carry = false
    private var carryValue: Short = 0

    /**
     * Consumes [length] shorts of [input], writing at most
     * `(length + carry) / 2` shorts into [output]. Returns the number of
     * output samples produced.
     */
    fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        require(length in 0..input.size) { "length $length outside 0..${input.size}" }
        val maxOutputs = (length + if (carry) 1 else 0) / 2
        require(output.size >= maxOutputs) { "output capacity ${output.size} < $maxOutputs" }

        var produced = 0
        var i = 0
        var pending: Int? = if (carry) carryValue.toInt() else null
        while (i < length) {
            val first = pending ?: input[i].also { i++ }.toInt()
            pending = null
            if (i < length) {
                val second = input[i].also { i++ }.toInt()
                output[produced++] = ((first + second) shr 1).toShort()
            } else {
                pending = first
            }
        }
        val leftover = pending
        if (leftover != null) {
            carry = true
            carryValue = leftover.toShort()
        } else {
            carry = false
        }
        return produced
    }
}
