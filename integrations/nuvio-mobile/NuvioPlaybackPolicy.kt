package com.nuvio.app.umbra

import com.umbra.runtime.api.PlaybackPayload
import com.umbra.runtime.api.PlayerCapabilities
import java.net.URI

/** Capabilities actually implemented by the consumer's Media3 adapter. */
object NuvioPlaybackPolicy {
    val capabilities = PlayerCapabilities(
        supportedUriSchemes = setOf("https"),
        supportedDrmSchemes = emptySet(),
        supportsCustomDrmCallback = false,
        supportsDistinctManifestAndSegmentHeaders = false,
    )

    fun validate(payload: PlaybackPayload) {
        require(URI(payload.mediaUri).scheme?.lowercase() in capabilities.supportedUriSchemes) {
            "This source uses an unsupported media address."
        }
        require(payload.audioUri == null) { "Separate audio streams require a different player adapter." }
        require(!payload.requiresCustomDataSource) { "This source requires a custom data source." }
        require(payload.startPositionMs >= 0) { "The source returned an invalid playback position." }
        require(payload.manifestRequestHeaders.isEmpty() && payload.segmentRequestHeaders.isEmpty()) {
            "This source requires distinct manifest and segment headers."
        }
        require(payload.subtitles.all { it.headers.isEmpty() || it.headers == payload.defaultRequestHeaders }) {
            "This source requires separate subtitle headers."
        }
        payload.subtitles.forEach {
            require(URI(it.uri).scheme?.lowercase() in capabilities.supportedUriSchemes) {
                "This source uses an unsupported subtitle address."
            }
        }
        require(payload.drm == null) { "Nuvio launch contract does not carry DRM configuration." }
        require(payload.customCacheKey == null && !payload.allowCrossProtocolRedirects && payload.playWhenReady) { "Unsupported Nuvio launch requirement." }
        require(payload.defaultRequestHeaders.keys.none { it.equals("Range", true) }) { "Nuvio does not preserve Range headers." }
        require(payload.subtitles.all { it.selectionFlags.isEmpty() && it.roleFlags.isEmpty() }) { "Nuvio does not preserve subtitle selection flags." }
        payload.drm?.let {
            require(it.scheme.lowercase() in capabilities.supportedDrmSchemes) { "Unsupported DRM scheme." }
            require(!it.requiresCustomCallback && it.requestBodyTemplate == null && it.responseTemplate == null) {
                "This source requires a custom DRM callback."
            }
            it.licenseUri?.let { uri -> require(URI(uri).scheme?.lowercase() == "https") { "DRM licenses must use HTTPS." } }
        }
        val headers = payload.defaultRequestHeaders + payload.drm?.licenseRequestHeaders.orEmpty()
        require(headers.all { (name, value) -> name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) && '\r' !in value && '\n' !in value }) {
            "The source returned invalid request headers."
        }
    }
}
