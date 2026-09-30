package com.nuvio.app.umbra
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.streams.StreamSubtitle
import org.json.JSONObject

@Composable actual fun UmbraEngineEntry(onPlayback: (PlayerLaunch) -> Unit) {
    val context = LocalContext.current
    val latestPlayback by rememberUpdatedState(onPlayback)
    var failure by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching {
                val raw = requireNotNull(result.data?.getStringExtra("umbraPlayback"))
                val launch = decodeUmbraPlayback(raw)
                latestPlayback(launch)
            }.onFailure { failure = "Umbra playback handoff could not be opened." }
        }
    }
    Button(onClick = { failure = null; launcher.launch(Intent().setClassName(context, "com.nuvio.app.umbra.UmbraHostActivity")) }) { Text("Umbra engine") }
    failure?.let { Text(it) }
}

/** Shared by the Activity result callback and the device handoff test. */
fun decodeUmbraPlayback(raw: String): PlayerLaunch {
    require(raw.toByteArray().size < 128 * 1024)
    val value = JSONObject(raw)
    fun headers(o: JSONObject): Map<String, String> = o.keys().asSequence().associateWith { o.getString(it) }
    val subtitles = value.getJSONArray("subtitles")
    val title = value.getString("title")
    return PlayerLaunch(profileId = 0, title = title, sourceUrl = value.getString("uri"),
        sourceHeaders = headers(value.getJSONObject("headers")),
        externalSubtitles = (0 until subtitles.length()).map { i -> subtitles.getJSONObject(i).let {
            StreamSubtitle(it.getString("url"), it.getString("language"), it.getString("name"), headers(it.getJSONObject("headers")))
        } }, streamTitle = title, providerName = "Umbra Ghost", parentMetaId = value.getString("mediaId"),
        parentMetaType = "video", initialPositionMs = value.getLong("position"))
}
