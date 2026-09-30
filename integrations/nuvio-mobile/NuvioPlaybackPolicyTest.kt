package com.nuvio.app.umbra
import com.umbra.runtime.api.*
import kotlin.test.*
class NuvioPlaybackPolicyTest {
    @Test fun `plain HTTPS stream can reach the existing player`() {
        NuvioPlaybackPolicy.validate(PlaybackPayload("media", "https://example.invalid/video.mp4"))
        assertTrue(NuvioPlaybackPolicy.capabilities.supportedDrmSchemes.isEmpty())
    }
    @Test fun `DRM and transport requirements cannot be silently lost`() {
        val media = PlaybackPayload("media", "https://example.invalid/video.mp4")
        assertFailsWith<IllegalArgumentException> { NuvioPlaybackPolicy.validate(media.copy(drm = PlaybackDrmConfiguration("widevine"))) }
        assertFailsWith<IllegalArgumentException> { NuvioPlaybackPolicy.validate(media.copy(manifestRequestHeaders = mapOf("Authorization" to "token"))) }
        assertFailsWith<IllegalArgumentException> { NuvioPlaybackPolicy.validate(media.copy(defaultRequestHeaders = mapOf("Range" to "bytes=0-"))) }
        assertFailsWith<IllegalArgumentException> { NuvioPlaybackPolicy.validate(media.copy(playWhenReady = false)) }
    }
}
