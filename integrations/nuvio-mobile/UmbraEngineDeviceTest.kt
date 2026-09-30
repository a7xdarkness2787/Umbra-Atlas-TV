package com.nuvio.android

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.umbra.UmbraHostActivity
import com.nuvio.app.umbra.NuvioPlaybackPolicy
import com.nuvio.app.umbra.decodeUmbraPlayback
import com.umbra.runtime.api.*
import com.umbra.runtime.client.AndroidUmbraRuntimeClient
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline contract smoke test; never fetches or plays the fixture media URL. */
@RunWith(AndroidJUnit4::class)
class UmbraEngineDeviceTest {
    @Test fun startupInstallBrowseResolveAndPlayerHandoff() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val receipt = JSONObject().put("schemaVersion", 1).put("kind", "umbra_nuvio_device_smoke")
            .put("passed", false).put("playbackVerified", false)
        val output = File(context.filesDir, "umbra-device-smoke.json")
        output.writeText(receipt.toString())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtime = AndroidUmbraRuntimeClient.create(context, "Nuvio device smoke", "1", scope)
        val id = "plugin.video.umbra.device.${UUID.randomUUID().toString().replace("-", "")}" 
        val label = "Umbra device fixture ${id.takeLast(8)}"
        val archive = File(context.cacheDir, "$id.zip")
        var installed = false
        suspend fun events(operation: RuntimeOperation): List<RuntimeOperationEvent> = withTimeout(60_000) {
            operation.events.transformWhile { event -> emit(event); !event.isTerminal() }.toList().also { list ->
                list.filterIsInstance<RuntimeOperationEvent.Failed>().firstOrNull()?.let {
                    throw AssertionError("Runtime failure: ${it.error.category}")
                }
                assertTrue("Operation did not complete", list.lastOrNull() is RuntimeOperationEvent.Completed)
            }
        }
        try {
            withTimeout(180_000) {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                    scenario.onActivity { assertFalse(it.isFinishing) }
                }
                receipt.put("startupVerified", true)
                val capabilities = runtime.getCapabilities()
                assertEquals("0.3.6", capabilities.engineVersion)
                val required = setOf(RuntimeOperationType.INSPECT_SOURCE, RuntimeOperationType.INSTALL_ADDON,
                    RuntimeOperationType.BROWSE, RuntimeOperationType.RESOLVE_PLAYBACK, RuntimeOperationType.UNINSTALL_ADDON)
                assertTrue(capabilities.supportedOperations.containsAll(required))
                receipt.put("engineVersion", capabilities.engineVersion)
                    .put("operations", org.json.JSONArray(capabilities.supportedOperations.map { it.name }.sorted()))
                    .put("connectionVerified", true)
                val files = mapOf("addon.xml" to """<addon id="$id" name="$label" version="1.0.0" provider-name="Umbra device test"><requires><import addon="xbmc.python" version="3.0.0"/></requires><extension point="xbmc.python.pluginsource" library="default.py"><provides>video</provides></extension></addon>""",
                    "default.py" to """
import sys, xbmcgui, xbmcplugin
handle = int(sys.argv[1])
if 'play=1' in sys.argv[2]:
    xbmcplugin.setResolvedUrl(handle, True, xbmcgui.ListItem(path='https://example.invalid/umbra-device.mp4'))
else:
    item = xbmcgui.ListItem(label='Device fixture video')
    item.setProperty('IsPlayable', 'true')
    xbmcplugin.addDirectoryItem(handle, 'plugin://$id/?play=1', item, False)
    xbmcplugin.endOfDirectory(handle)
""")
                ZipOutputStream(archive.outputStream()).use { zip -> files.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry("$id/$name")); zip.write(value.toByteArray()); zip.closeEntry()
                } }
                val inspection = events(runtime.inspectSource(InspectSourceRequest(AddonSource.LocalContentUri(archive.toURI().toString()))))
                    .filterIsInstance<RuntimeOperationEvent.InspectionCompleted>().single().inspection
                assertEquals(id, inspection.addonId)
                val authorization = runtime.authorizeSource(AuthorizeSourceRequest(id, AuthorizationDecision.APPROVE,
                    AuthorizationProfile.ANDROID_COMPATIBILITY, inspection.sourceEvidence))
                assertEquals(AuthorizationState.APPROVED, authorization.record.state)
                installed = true
                events(runtime.installAddon(InstallAddonRequest(id, inspection.sourceEvidence)))
                val addon = runtime.listInstalledAddons().single { it.addonId == id }
                assertEquals(AddonType.VIDEO, addon.addonType)
                val routes = events(runtime.browse(BrowseRequest(id, "plugin://$id/")))
                    .filterIsInstance<RuntimeOperationEvent.RouteItemsBatch>().flatMap { it.items }
                val route = routes.single { it.label == "Device fixture video" }
                assertTrue(route.playable)
                val payload = events(runtime.resolvePlayback(ResolvePlaybackRequest(id, requireNotNull(route.route), playerCapabilities = NuvioPlaybackPolicy.capabilities)))
                    .filterIsInstance<RuntimeOperationEvent.PlaybackResolved>().single().result.payload
                assertNotNull(payload)
                NuvioPlaybackPolicy.validate(requireNotNull(payload))
                receipt.put("installVerified", true).put("browseVerified", true).put("resolveVerified", true)
                runtime.close()
                ActivityScenario.launchActivityForResult<UmbraHostActivity>(Intent(context, UmbraHostActivity::class.java)).use { scenario ->
                    suspend fun clickCard(text: String) {
                        withTimeout(30_000) {
                            var clicked = false
                            while (!clicked) {
                                scenario.onActivity { activity ->
                                    fun find(view: View): TextView? {
                                        if (view is TextView && view.text.toString() == text) return view
                                        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                                        return null
                                    }
                                    find(activity.window.decorView)?.let { child ->
                                        var clickable: View? = child
                                        while (clickable != null && !clickable.isClickable) clickable = clickable.parent as? View
                                        clicked = clickable?.performClick() == true
                                    }
                                }
                                if (!clicked) delay(100)
                            }
                        }
                    }
                    clickCard(label)
                    clickCard("Device fixture video")
                    val result = withContext(Dispatchers.IO) { scenario.result }
                    assertEquals(Activity.RESULT_OK, result.resultCode)
                    val launch = decodeUmbraPlayback(requireNotNull(result.resultData?.getStringExtra("umbraPlayback")))
                    assertEquals("https://example.invalid/umbra-device.mp4", launch.sourceUrl)
                    val launchId = PlayerLaunchStore.put(launch)
                    assertEquals(launch, PlayerLaunchStore.get(launchId))
                    PlayerLaunchStore.remove(launchId)
                    receipt.put("nativeUiHandoffVerified", true).put("nuvioLaunchContractVerified", true)
                }
            }
            receipt.put("passed", true)
        } catch (error: Throwable) {
            receipt.put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            try {
                if (installed) {
                    withContext(NonCancellable) {
                        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                        val cleanup = AndroidUmbraRuntimeClient.create(context, "Nuvio fixture cleanup", "1", cleanupScope)
                        try {
                            events(cleanup.uninstallAddon(UninstallAddonRequest(id, removeSettings = true)))
                            assertFalse(cleanup.listInstalledAddons().any { it.addonId == id })
                            receipt.put("cleanupVerified", true)
                        } finally { cleanup.close(); cleanupScope.cancel() }
                    }
                }
            } catch (error: Throwable) {
                receipt.put("passed", false).put("cleanupFailureType", error.javaClass.simpleName)
                throw error
            } finally {
                archive.delete(); runtime.close(); scope.cancel(); output.writeText(receipt.toString(2))
            }
        }
    }
}
