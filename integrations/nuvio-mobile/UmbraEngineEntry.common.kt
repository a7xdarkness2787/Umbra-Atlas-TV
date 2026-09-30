package com.nuvio.app.umbra
import androidx.compose.runtime.Composable
import com.nuvio.app.features.player.PlayerLaunch
@Composable expect fun UmbraEngineEntry(onPlayback: (PlayerLaunch) -> Unit)
