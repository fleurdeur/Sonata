package com.sonata.core.audio

import com.sonata.core.audio.engine.DspParameterMapper
import com.sonata.core.domain.model.BandFilterType
import com.sonata.core.domain.model.ParametricBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspParameterMapperTest {

    @Test
    fun `bell band peaks at its center frequency`() {
        val band = ParametricBand(0, BandFilterType.PEAK, 1000f, 6f, 1.4f)
        val atPeak = DspParameterMapper.frequencyResponseDb(band, 1000f)
        val offPeak = DspParameterMapper.frequencyResponseDb(band, 4000f)
        assertEquals(6f, atPeak, 0.01f)
        assertTrue(offPeak < atPeak)
    }

    @Test
    fun `flat preset produces near-zero response`() {
        val flat = listOf(ParametricBand(0, BandFilterType.PEAK, 1000f, 0f, 1f))
        assertEquals(0f, DspParameterMapper.totalResponseDb(flat, 100f), 0.05f)
        assertEquals(0f, DspParameterMapper.totalResponseDb(flat, 15000f), 0.05f)
    }

    @Test
    fun `compressor leaves quiet input untouched and reduces loud input`() {
        val settings = com.sonata.core.domain.model.CompressorSettings(
            thresholds = listOf(-20f, -20f, -20f, -20f), ratios = listOf(4f, 4f, 4f, 4f),
        )
        assertEquals(0f, DspParameterMapper.compressGainDb(settings, -30f, 0), 0.001f)
        assertTrue(DspParameterMapper.compressGainDb(settings, 0f, 0) < 0f)
    }

    @Test
    fun `limiter only engages above ceiling`() {
        val settings = com.sonata.core.domain.model.LimiterSettings(ceilingDb = -1f)
        assertEquals(0f, DspParameterMapper.limiterGainDb(settings, -6f), 0.001f)
        assertTrue(DspParameterMapper.limiterGainDb(settings, 2f) < 0f)
    }
}
