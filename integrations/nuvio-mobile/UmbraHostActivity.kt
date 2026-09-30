package com.nuvio.app.umbra

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import com.umbra.runtime.api.*
import com.umbra.runtime.client.AndroidUmbraRuntimeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.transformWhile

/** Consumer presentation is separate from the offline Runtime qualification APK. */
class UmbraHostActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: AndroidUmbraRuntimeClient
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private var operation: RuntimeOperation? = null
    private var loadJob: Job? = null
    private var connected: RuntimeCapabilities? = null
    private val history = ArrayDeque<BrowseRequest>()
    private var currentRoute: BrowseRequest? = null
    private var playbackRequest: ResolvePlaybackRequest? = null
    private var stopped = false
    private val dialogs = mutableSetOf<AlertDialog>()
    private val teal = Color.rgb(104, 224, 198)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = AndroidUmbraRuntimeClient.create(applicationContext, "Umbra Ghost Player", "0.1.0", hostScope)
        showShell()
        savedInstanceState?.getString("addon")?.let { addon ->
            savedInstanceState.getString("route")?.let { currentRoute = BrowseRequest(addon, it) }
        }
        connect()
    }

    private fun showShell() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11, 16, 24)); setPadding(24, 28, 24, 16) }
        page.fitsSystemWindows = true
        val brand = text("UMBRA  GHOST", 24f).apply { setTextColor(teal); setTypeface(null, Typeface.BOLD) }
        page.addView(brand)
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navigation.addView(button("Home") { history.clear(); currentRoute = null; playbackRequest = null; connect() })
        navigation.addView(button("Back") { navigateBack() })
        navigation.addView(button("Cancel") { cancelCurrent(); status.text = "Cancelled" })
        navigation.addView(button("Add source") {
            if (RuntimeOperationType.INSTALL_ADDON !in connected?.supportedOperations.orEmpty()) {
                status.text = "Installing sources is not qualified for this engine yet."
            } else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 21)
        })
        navigation.addView(button("Licenses") {
            val names = assets.list("licenses").orEmpty()
            AlertDialog.Builder(this).setTitle("Open-source licenses").setItems(names) { _, index ->
                val terms = assets.open("licenses/${names[index]}").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle(names[index]).setMessage(terms).setPositiveButton("Close", null).show()
            }.show()
        })
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(navigation) })
        heading = text("Your library", 30f).apply { setTypeface(null, Typeface.BOLD); setPadding(0, 24, 0, 8) }
        page.addView(heading)
        status = text("Connecting to your add-ons…", 15f).apply { setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 20) }
        page.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun connect(): Unit = launchLoad {
        connected = withContext(Dispatchers.IO) { runtime.getCapabilities() }
        if (RuntimeOperationType.BROWSE !in requireNotNull(connected).supportedOperations) {
            status.text = "This engine is connected, but browsing has not been qualified."
            return@launchLoad
        }
        currentRoute?.let { browse(it, false); return@launchLoad }
        val addons = withContext(Dispatchers.IO) { runtime.listInstalledAddons() }
        heading.text = "Your add-ons"
        content.removeAllViews()
        status.text = if (addons.isEmpty()) {
            if (RuntimeOperationType.INSTALL_ADDON in connected?.supportedOperations.orEmpty()) "No add-ons installed. Add a trusted source to get started."
            else "No add-ons installed. This engine does not currently support installation."
        } else "Choose a video add-on or browse a repository."
        addons.filter { it.enabled }.forEach { addon ->
            content.addView(card(addon.name, "${addon.addonType.name.lowercase()} • ${addon.version}", true, if (addon.addonType == AddonType.REPOSITORY) "BROWSE PACKAGES  ›" else "OPEN  ›") {
                when {
                    addon.addonType == AddonType.REPOSITORY -> browseRepository(addon.addonId)
                    addon.rootRoute != null -> browse(BrowseRequest(addon.addonId, requireNotNull(addon.rootRoute)), true)
                    else -> status.text = "This add-on has no browsable entry point (${addon.addonType.name.lowercase()})."
                }
            })
        }
    }

    private fun browse(request: BrowseRequest, remember: Boolean) {
        if (remember) currentRoute?.let(history::addLast)
        currentRoute = request
        launchLoad {
            content.removeAllViews(); heading.text = "Explore"; status.text = "Loading…"
            val current = withContext(Dispatchers.IO) { runtime.browse(request) }
            collect(current) { event ->
                if (event is RuntimeOperationEvent.RouteItemsBatch) event.items.forEach { item ->
                    content.addView(card(item.metadata.title ?: item.label, item.metadata.plot ?: item.label2.orEmpty(), item.folder) {
                        val route = item.route
                        if (route == null) status.text = "This item has no supported route."
                        else if (item.folder) browse(BrowseRequest(item.addonId, route), true)
                        else if (item.playable) resolve(ResolvePlaybackRequest(item.addonId, route, playerCapabilities = NuvioPlaybackPolicy.capabilities))
                        else status.text = "This item cannot be played."
                    })
                }
            }
            status.text = if (content.childCount == 0) "No items returned." else "Select a folder or video."
        }
    }

    private fun browseRepository(repositoryId: String): Unit = launchLoad {
        heading.text = "Repository packages"; content.removeAllViews()
        if (RuntimeOperationType.REFRESH_REPOSITORY !in connected?.supportedOperations.orEmpty()) {
            status.text = "This engine has not enabled repository browsing. The repository is installed; it is not a playable add-on."
            return@launchLoad
        }
        status.text = "Refreshing repository…"
        collect(withContext(Dispatchers.IO) { runtime.refreshRepository(RepositoryRefreshRequest(repositoryId)) }) { event ->
            if (event is RuntimeOperationEvent.RepositoryRefreshed && event.result.errors.isNotEmpty()) throw RuntimeClientException(event.result.errors.first())
        }
        val result = withContext(Dispatchers.IO) { runtime.listRepositoryAddons(RepositoryAddonQuery(repositoryId, limit = 200)) }
        result.errors.firstOrNull()?.let { throw RuntimeClientException(it) }
        result.addons.forEach { addon ->
            content.addView(card(addon.name ?: addon.addonId, "${addon.addonType.name.lowercase()} • ${addon.version.orEmpty()}", true, "INSPECT PACKAGE  ›") {
                inspectPackage(repositoryId, addon)
            })
        }
        status.text = "${result.returnedCount} of ${result.totalCount} packages. Select a package to inspect before approval."
    }

    private fun inspectPackage(repositoryId: String, addon: RepositoryAddonSummary): Unit = launchLoad {
        if (RuntimeOperationType.INSTALL_REPOSITORY_ADDON !in connected?.supportedOperations.orEmpty()) {
            status.text = "Repository package installation is not enabled by this engine."
            return@launchLoad
        }
        val inspection = withContext(Dispatchers.IO) { runtime.inspectRepositoryAddon(InspectRepositoryAddonRequest(repositoryId, addon.addonId, addon.version)) }
        inspection.errors.firstOrNull()?.let { throw RuntimeClientException(it) }
        AlertDialog.Builder(this@UmbraHostActivity).setTitle("Install ${addon.name ?: addon.addonId}?")
            .setMessage("This package and its dependencies contain executable code. Approve only sources you trust. Version: ${inspection.selectedVersion.orEmpty()}")
            .setNegativeButton("Cancel", null).setPositiveButton("Approve and install") { _, _ ->
                launchLoad {
                    withContext(Dispatchers.IO) { runtime.authorizeSource(AuthorizeSourceRequest(addon.addonId, AuthorizationDecision.APPROVE, AuthorizationProfile.ANDROID_COMPATIBILITY, inspection.sourceEvidence)) }
                    collect(withContext(Dispatchers.IO) { runtime.installRepositoryAddon(InstallRepositoryAddonRequest(repositoryId, addon.addonId, inspection.sourceEvidence, inspection.selectedVersion)) }) {}
                    currentRoute = null; connect()
                }
            }.show().also { shown -> dialogs += shown; shown.setOnDismissListener { dialogs -= shown } }
    }

    private fun failureText(error: RuntimeError): String {
        val category = error.category.name.lowercase().replace('_', ' ')
        val action = when (error.suggestedAction) {
            RuntimeSuggestedAction.REAUTHORIZE -> "Approve the source again."
            RuntimeSuggestedAction.CHECK_NETWORK -> "Check your connection."
            RuntimeSuggestedAction.FREE_STORAGE -> "Free some storage."
            RuntimeSuggestedAction.RESTART_RUNTIME -> "Restart the Runtime."
            RuntimeSuggestedAction.UPDATE_RUNTIME -> "Update the Runtime."
            RuntimeSuggestedAction.RETRY -> "Try again."
            else -> "Collect Runtime diagnostics for more detail."
        }
        return "Request failed ($category). $action"
    }

    private fun resolve(request: ResolvePlaybackRequest) = launchLoad {
        status.text = "Resolving playback…"
        playbackRequest = request
        collect(withContext(Dispatchers.IO) { runtime.resolvePlayback(request) }) { event ->
            if (event is RuntimeOperationEvent.PlaybackResolved) {
                val payload = event.result.payload
                if (!event.result.ready || payload == null) error("This source did not return playable media.")
                play(payload)
            }
        }
    }

    @Deprecated("Activity result compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        launchLoad {
            status.text = "Inspecting source…"
            collect(withContext(Dispatchers.IO) { runtime.inspectSource(InspectSourceRequest(AddonSource.LocalContentUri(uri.toString()))) }) { event ->
                if (event is RuntimeOperationEvent.InspectionCompleted) {
                    val inspection = event.inspection
                    AlertDialog.Builder(this@UmbraHostActivity).setTitle("Authorize ${inspection.name}?")
                        .setMessage("This add-on contains executable code. Approve only a source you trust. It may access the network and your add-on settings.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Approve and install") { _, _ ->
                            launchLoad {
                                withContext(Dispatchers.IO) { runtime.authorizeSource(AuthorizeSourceRequest(inspection.addonId, AuthorizationDecision.APPROVE, AuthorizationProfile.ANDROID_COMPATIBILITY, inspection.sourceEvidence)) }
                                collect(withContext(Dispatchers.IO) { runtime.installAddon(InstallAddonRequest(inspection.addonId, inspection.sourceEvidence)) }) {}
                                currentRoute = null; connect()
                            }
                        }.show()
                }
            }
        }
    }

    private fun play(payload: PlaybackPayload) {
        NuvioPlaybackPolicy.validate(payload)
        val headers = payload.defaultRequestHeaders.toMutableMap()
        payload.userAgent?.let { headers["User-Agent"] = it }
        val document = org.json.JSONObject().put("mediaId", payload.mediaId)
            .put("uri", payload.mediaUri).put("title", payload.metadata.title ?: "Umbra media")
            .put("headers", org.json.JSONObject(headers)).put("position", payload.startPositionMs)
        val subtitles = org.json.JSONArray()
        payload.subtitles.forEach { subtitles.put(org.json.JSONObject().put("url", it.uri)
            .put("language", it.language ?: "und").put("name", it.label ?: "Subtitle")
            .put("headers", org.json.JSONObject(it.headers))) }
        document.put("subtitles", subtitles)
        val value = document.toString()
        require(value.toByteArray().size < 128 * 1024) { "Playback handoff is too large." }
        setResult(RESULT_OK, Intent().putExtra("umbraPlayback", value)); finish()
    }

    private suspend fun collect(current: RuntimeOperation, receive: suspend (RuntimeOperationEvent) -> Unit) {
        operation = current
        try {
            withTimeout(180_000) {
            current.events.transformWhile { event -> emit(event); !event.isTerminal() }.collect { event ->
                when (event) {
                    is RuntimeOperationEvent.Failed -> throw RuntimeClientException(event.error)
                    is RuntimeOperationCancelledEvent -> throw CancellationException("Cancelled")
                    is RuntimeOperationTimedOutEvent -> error("The source request timed out.")
                    is RuntimeOperationEvent.HostOperationRequested -> dialog(event.request)
                    else -> receive(event)
                }
            }
        }
        } finally {
            if (operation === current) {
                operation = null
                hostScope.launch { runtime.cancelOperation(current.operationId) }
            }
        }
    }

    private fun dialog(request: HostOperationRequest) {
        var answered = false
        fun respond(state: HostOperationResponseState, payload: Map<String, String> = emptyMap()) {
            if (answered) return
            answered = true
            hostScope.launch { runtime.submitHostOperationResponse(HostOperationResponse(request.requestId, request.operationId, state, payload)) }
        }
        val builder = AlertDialog.Builder(this).setTitle(request.payload["heading"] ?: "Add-on request")
            .setOnCancelListener { respond(HostOperationResponseState.CANCELLED) }
        when (request.type) {
            HostOperationType.DIALOG_OK -> builder.setMessage(request.payload["message"]).setPositiveButton("OK") { _, _ -> respond(HostOperationResponseState.COMPLETED) }
            HostOperationType.DIALOG_YES_NO -> builder.setMessage(request.payload["message"]).setPositiveButton("Yes") { _, _ -> respond(HostOperationResponseState.COMPLETED, mapOf("value" to "true")) }.setNegativeButton("No") { _, _ -> respond(HostOperationResponseState.COMPLETED, mapOf("value" to "false")) }
            HostOperationType.DIALOG_SELECT -> builder.setItems(request.options.toTypedArray()) { _, index -> respond(HostOperationResponseState.COMPLETED, mapOf("selected_index" to index.toString())) }
            HostOperationType.DIALOG_INPUT, HostOperationType.DIALOG_NUMERIC -> {
                val input = EditText(this).apply { inputType = if (request.sensitive) 129 else if (request.type == HostOperationType.DIALOG_NUMERIC) 2 else 1 }
                builder.setView(input).setPositiveButton("Continue") { _, _ -> respond(HostOperationResponseState.COMPLETED, mapOf("value" to input.text.toString())) }
                    .setNegativeButton("Cancel") { _, _ -> respond(HostOperationResponseState.CANCELLED) }
            }
            else -> { respond(HostOperationResponseState.CANCELLED); status.text = "This add-on requested an unsupported interaction."; return }
        }
        builder.show().also { shown ->
            dialogs += shown
            shown.setOnDismissListener { dialogs -= shown; respond(HostOperationResponseState.CANCELLED) }
        }
    }

    private fun launchLoad(block: suspend () -> Unit) {
        cancelCurrent()
        loadJob = scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: RuntimeClientException) { status.text = failureText(error.runtimeError) }
            catch (_: UnsupportedOperationException) { status.text = "This operation is not supported by the connected engine." }
            catch (_: Exception) { status.text = "The request failed. Try again or collect Runtime diagnostics." }
        }
    }

    private fun cancelCurrent() {
        loadJob?.cancel()
        dialogs.toList().forEach { it.dismiss() }
        operation?.let { active -> hostScope.launch { runtime.cancelOperation(active.operationId) } }
        operation = null
    }

    private fun navigateBack() {
        playbackRequest = null; releasePlayer(); showShell()
        if (history.isNotEmpty()) browse(history.removeLast(), false) else { currentRoute = null; connect() }
    }

    @Deprecated("Android compatibility back callback")
    override fun onBackPressed() { navigateBack() }

    override fun onSaveInstanceState(outState: Bundle) {
        currentRoute?.let { outState.putString("addon", it.addonId); outState.putString("route", it.route) }
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            showShell()
            if (playbackRequest != null) {
                status.text = "Playback paused while the app was away."
                content.addView(button("Resume playback") { playbackRequest?.let(::resolve) })
            } else connect()
        }
    }
    override fun onStop() {
        stopped = true; releasePlayer(); cancelCurrent(); super.onStop()
    }
    override fun onDestroy() { runtime.close(); scope.cancel(); hostScope.cancel(); super.onDestroy() }
    private fun releasePlayer() {}
    private fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isSingleLine = true; setOnClickListener { action() } }
    private fun card(title: String, detail: String, folder: Boolean, actionLabel: String? = null, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(29, 48, 61), Color.rgb(18, 27, 40))).apply { cornerRadius = 20f }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 16) }
        addView(text(title, 21f).apply { setTypeface(null, Typeface.BOLD) })
        addView(text(detail.take(220), 14f).apply { setTextColor(Color.LTGRAY); maxLines = 3 })
        addView(text(actionLabel ?: if (folder) "EXPLORE  ›" else "PLAY  ›", 12f).apply { setTextColor(teal); setPadding(0, 16, 0, 0) })
        isFocusable = true; isClickable = true; setOnClickListener { action() }
        setOnFocusChangeListener { view, focused -> view.alpha = if (focused) 1f else 0.85f }
        contentDescription = title
    }
}
