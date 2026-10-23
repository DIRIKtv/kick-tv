package com.diriktv.sidekick.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessLevelerTest {
    private val rate = 48_000
    private val format = AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    /** Stereo sine at [amplitude] (0..1 of full scale), [seconds] long. */
    private fun sine(amplitude: Double, seconds: Double, hz: Double = 440.0): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i ->
            (amplitude * 32767 * sin(2 * PI * hz * (i / 2) / rate)).toInt().toShort()
        }
    }

    /** Runs [input] through the leveler in decoder-sized chunks and returns the output. */
    private fun run(leveler: LoudnessLeveler, input: ShortArray, chunkFrames: Int = 1024): ShortArray {
        leveler.configure(format)
        leveler.flush()
        val out = ArrayList<Short>(input.size)
        var i = 0
        while (i < input.size) {
            val n = minOf(chunkFrames * 2, input.size - i)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until n) buf.putShort(input[i + k])
            buf.flip()
            leveler.queueInput(buf)
            val o = leveler.output
            while (o.hasRemaining()) out.add(o.short)
            i += n
        }
        return out.toShortArray()
    }

    private fun rmsDb(s: ShortArray, fromFraction: Double = 0.8): Double {
        val start = (s.size * fromFraction).toInt()
        var sum = 0.0
        for (i in start until s.size) sum += (s[i] / 32768.0).let { it * it }
        return 10 * log10(sum / (s.size - start))
    }

    private fun peak(s: ShortArray) = s.maxOf { abs(it.toInt()) } / 32768.0

    @Test
    fun quietStreamIsTurnedUpToTheTarget() {
        // 440 Hz sine at -26 dBFS RMS (the high-pass barely touches 440 Hz).
        val input = sine(0.0708, 20.0)
        val out = run(LoudnessLeveler(true), input)
        assertEquals("input level", -26.0, rmsDb(input), 0.5)
        assertEquals("boosted to target", LoudnessLeveler.TARGET_DB.toDouble(), rmsDb(out), 1.5)
    }

    @Test
    fun loudStreamIsTurnedDown() {
        // -9 dBFS RMS.
        val out = run(LoudnessLeveler(true), sine(0.5, 20.0))
        assertEquals(LoudnessLeveler.TARGET_DB.toDouble(), rmsDb(out), 1.5)
    }

    @Test
    fun boostIsCappedForVeryQuietAudio() {
        // -40 dBFS RMS can only come up by the maximum boost.
        val input = sine(0.0141, 30.0)
        val out = run(LoudnessLeveler(true), input)
        assertEquals(rmsDb(input) + LoudnessLeveler.MAX_BOOST_DB, rmsDb(out), 1.0)
    }

    @Test
    fun boostedPeaksNeverClip() {
        // Quiet speech-like bed with sudden near-full-scale spikes after the boost has built up.
        val input = sine(0.05, 20.0)
        for (spikeAt in listOf(8.0, 12.0, 16.0)) {
            val start = (spikeAt * rate).toInt() * 2
            for (i in 0 until rate / 50 * 2) {
                input[start + i] = (0.95 * 32767 * sin(2 * PI * 1000 * (i / 2) / rate)).toInt().toShort()
            }
        }
        val out = run(LoudnessLeveler(true), input)
        assertTrue("peak ${peak(out)} stayed under the ceiling", peak(out) <= 0.95)
    }

    @Test
    fun offPassesAudioThroughUnchanged() {
        val input = sine(0.6, 3.0)
        val out = run(LoudnessLeveler(false), input)
        assertTrue(input.contentEquals(out))
    }

    @Test
    fun silenceIsNotPumpedUp() {
        // A normal-level stream, then near-silence: the gain shouldn't climb towards max boost.
        val leveler = LoudnessLeveler(true)
        val normal = sine(0.18, 10.0) // about -18 dBFS: target, so ~0 dB gain
        val hush = sine(0.0005, 10.0) // -69 dBFS, below the gate
        run(leveler, normal + hush)
        assertTrue("gain ${leveler.currentGainDb} dB stayed near unity", abs(leveler.currentGainDb) < 2f)
    }

    @Test
    fun turningItOffFadesBackToUnity() {
        val leveler = LoudnessLeveler(true)
        run(leveler, sine(0.0708, 15.0))
        assertTrue("boosting first", leveler.currentGainDb > 5f)
        leveler.enabled = false
        val out = run(leveler, sine(0.0708, 5.0))
        assertEquals(0f, leveler.currentGainDb, 0.2f)
        assertEquals(-26.0, rmsDb(out), 0.5)
    }

    @Test
    fun monoAndOddChunkSizesWork() {
        val leveler = LoudnessLeveler(true)
        leveler.configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        leveler.flush()
        val input = ShortArray(rate * 10) { i -> (0.0708 * 32767 * sin(2 * PI * 440 * i / rate)).toInt().toShort() }
        val out = ArrayList<Short>()
        var i = 0
        while (i < input.size) {
            val n = minOf(777, input.size - i)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until n) buf.putShort(input[i + k])
            buf.flip()
            leveler.queueInput(buf)
            val o = leveler.output
            while (o.hasRemaining()) out.add(o.short)
            i += n
        }
        assertEquals(input.size, out.size)
        val tail = out.takeLast(rate * 2).map { it / 32768.0 }
        val rms = 20 * log10(sqrt(tail.sumOf { it * it } / tail.size))
        assertTrue("mono boosted (rms $rms)", rms > -22)
    }

    @Test
    fun otherEncodingsAreLeftAlone() {
        val leveler = LoudnessLeveler(true)
        leveler.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        assertTrue(!leveler.isActive)
    }
}
