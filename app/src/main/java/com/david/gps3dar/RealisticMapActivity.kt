package com.david.gps3dar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.ToneGenerator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.graphics.Typeface
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RealisticMapActivity : AppCompatActivity(), TextToSpeech.OnInitListener, SensorEventListener {

    private lateinit var webMapView: WebView
    private lateinit var signalRepository: SignalRepository
    private var lastSignalUiMs = 0L
    private lateinit var fusedLocation: FusedLocationProviderClient

    private lateinit var searchInput: EditText
    private lateinit var searchSuggestions: LinearLayout
    private lateinit var routeChoices: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var instruction: TextView
    private lateinit var turnIcon: TextView
    private lateinit var turnDistance: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var speedText: TextView
    private lateinit var etaText: TextView
    private lateinit var routeDistance: TextView
    private lateinit var arrivalText: TextView
    private lateinit var stopButton: TextView
    private lateinit var tollCount: TextView
    private lateinit var tollTotal: TextView
    private lateinit var avoidTollsButton: TextView
    private lateinit var viewModeButton: ImageButton
    private lateinit var modeButton: TextView
    private lateinit var zoomInButton: TextView
    private lateinit var zoomOutButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var settings3d: TextView
    private lateinit var settingsTerrain: TextView
    private lateinit var settingsBuildings: TextView
    private lateinit var settingsSatellite: TextView
    private lateinit var settingsVoice: TextView
    private lateinit var settingsAutoZoom: TextView
    private lateinit var settingsFollowDelay: TextView
    private lateinit var placePreview: LinearLayout
    private lateinit var placePreviewImage: ImageView
    private lateinit var placePreviewTitle: TextView
    private lateinit var placePreviewAddress: TextView
    private lateinit var placePreviewGo: TextView
    private lateinit var placePreviewClose: TextView

    private var http = OkHttpClient()
    private val ui = Handler(Looper.getMainLooper())
    private var applyingCarRoute = false
    private var carRevision = -1L
    private val carObserver: (com.david.gps3dar.car.CarSnapshot) -> Unit = { snapshot ->
        ui.post {
            if (!isDestroyed && snapshot.revision == com.david.gps3dar.car.CarNavigation.state.revision && snapshot.revision != carRevision) {
                carRevision = snapshot.revision
                if (snapshot.source == "car") {
                    applyingCarRoute = true
                    routeCall?.cancel();routeGeneration++;rerouting=false;pendingAvoidTolls=null
                    val option=snapshot.route;val destination=snapshot.destination
                    if(option==null) stopNavigation()
                    else if(destination!=null) {
                        routeDestination=destination;routeAlternatives=listOf(option)
                        snapshot.location?.let { rawLocation=it;displayLocation=it }
                        activateRoute(0,false)
                    }
                    applyingCarRoute = false
                }
            }
        }
    }
    private val destinationSearch = DestinationSearch(http)
    private lateinit var recentDestinations: RecentDestinations
    private var searchEditing = false
    private var searchEpoch = 0
    private lateinit var tollRepository: TollRepository
    private var tollQuote: TollRepository.Quote? = null
    private var tollProgressDistances = DoubleArray(0)
    private var tollLookupPending = false
    private val enteredTollPlazas = mutableListOf<Long>()
    private val passedTollPlazas = mutableSetOf<Long>()
    private var avoidTolls = false
    private var viewMode = 0
    private var externalLinkCall: Call? = null
    private var routeGeneration = 0
    private var lastCatalogRevision = 0L

    private var mapReady = false
    private var rawLocation: Location? = null
    private var filteredLocation: Location? = null
    private var displayLocation: Location? = null
    private var lastAcceptedLocation: Location? = null
    private var lastCameraBearing = 0.0
    private val announcements = NavigationAnnouncements()
    private var ttsReady = false
    private var voiceRouteId = 0
    private var spokenUtterance = ""
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocus: AudioFocusRequest
    private lateinit var mapCache: MapResourceCache
    private lateinit var connectivity: ConnectivityManager
    private var networkAvailable = true
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            setMapNetworkAvailable(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        override fun onLost(network: Network) { setMapNetworkAvailable(false) }
    }
    private var lastPreparedAt = 0L
    private var lastPreparedIndex = -1
    private lateinit var sensorManager: SensorManager
    private val darkPolicy = AutoDarkMode()
    private var ambientLux: Double? = null
    private var darkTheme = false
    private var themeInitialized = false
    private var trafficSeverity = "normal"
    private val originalTextColors = WeakHashMap<TextView, Int>()
    private val originalBackgroundColors = WeakHashMap<View, Int>()

    private var routePoints: List<GeoPoint> = emptyList()
    private var pedestrianRoute = false
    private data class RouteBeforeAr(val options: List<RouteOption>, val index: Int,
                                     val destination: GeoPoint, val view: Int)
    private var routeBeforeAr: RouteBeforeAr? = null
    private var arDestination: GeoPoint? = null
    private var navigationGeometry: NavigationRoutePosition? = null
    private var lastMatchAtNanos = 0L
    private val arLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val previous = routeBeforeAr
        val launchedDestination = arDestination
        routeBeforeAr = null; arDestination = null
        voiceEnabled = result.data?.getBooleanExtra("walking_voice", voiceEnabled) ?: voiceEnabled
        if (previous != null && routeDestination == launchedDestination) {
            // AR calculates its walking route separately. It must never overwrite car navigation.
            routeCall?.cancel(); routeGeneration++; rerouting = false; pendingAvoidTolls = null
            routeDestination = previous.destination; routeAlternatives = previous.options
            activateRoute(previous.index, recenterMap = false)
            setViewMode(previous.view)
        } else if (result.resultCode == RESULT_OK && result.data?.getBooleanExtra(ArNavigationActivity.EXTRA_WALKING_ROUTE, false) == true) {
            val walking = runCatching { WalkingRoute.decode(java.io.File(filesDir, ArNavigationActivity.ROUTE_FILE).readText()) }.getOrNull()
            val lat = result.data?.getDoubleExtra(WalkingRoute.EXTRA_LAT, Double.NaN) ?: Double.NaN
            val lon = result.data?.getDoubleExtra(WalkingRoute.EXTRA_LON, Double.NaN) ?: Double.NaN
            if (walking != null && RouteGeometry.distance(walking.destination, RouteGeometry.Point(lat, lon)) < 3.0) {
                voiceEnabled = result.data?.getBooleanExtra("walking_voice", voiceEnabled) ?: voiceEnabled
                routeCall?.cancel(); routeGeneration++; rerouting = false; pendingAvoidTolls = null
                val points = walking.points.map { GeoPoint(it.lat, it.lon) }
                routeDestination = GeoPoint(walking.destination.lat, walking.destination.lon)
                routeAlternatives = listOf(RouteOption(points, walking.steps.map { step ->
                    NavStep(step.point.lat, step.point.lon, step.message, iconForTomTomManeuver(step.maneuver),
                        nearestRoutePointIndex(points, step.point.lat, step.point.lon), step.maneuver)
                }, walking.durationSeconds, ArRouteGeometry(walking.points).length, pedestrian = true))
                activateRoute(0, recenterMap = true)
            }
        }
    }
    private var navSteps: List<NavStep> = emptyList()
    private var currentStepIndex = 0
    private var routeActive = false
    private var routeDurationSeconds = 0.0
    private var routeDistanceMeters = 0.0
    private var routeAlternatives: List<RouteOption> = emptyList()
    private var activeRouteIndex = 0
    private var routeDestination: GeoPoint? = null
    private var routeCall: Call? = null
    private var routeProgressIndex = 0
    private var lastRenderedProgressIndex = -1
    private var lastProgressRenderAtMs = 0L
    private var routeRemainingFromIndex = DoubleArray(0)
    private var lastRouteMatch: RouteMatch? = null
    private var offRouteSinceMs = 0L
    private var lastRerouteAtMs = 0L
    private var rerouting = false
    private var pendingAvoidTolls: Boolean? = null
    private var pendingExternalDestination: GeoPoint? = null
    private var pendingExternalQuery: String? = null

    private var searchResults: List<SearchResult> = emptyList()
    private var searchCall: Call? = null
    private var searchDebounce: Runnable? = null
    private var suppressSearchWatcher = false
    private var placePreviewCall: Call? = null
    private var placeImageCall: Call? = null
    private var selectedMapPoint: SearchResult? = null


    private var is3D = false
    private var terrainEnabled = false
    private var buildingsEnabled = false
    private var satelliteEnabled = false
    private var voiceEnabled = true
    private var autoZoomEnabled = false
    private var zoomPresetIndex = DEFAULT_ZOOM_PRESET_INDEX
    private var followResumeDelayMs = DEFAULT_FOLLOW_RESUME_MS
    private var manualCameraUntilMs = 0L

    private lateinit var tts: TextToSpeech
    private val rerouteTone = ToneGenerator(AudioManager.STREAM_MUSIC, 68)

    private val ticker = object : Runnable {
        override fun run() {
            updateTollPanel()
            updateAutoTheme()
            if (routeActive && ttsReady) updateNavigationStep(displayLocation)
            ui.postDelayed(this, 1000L)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { processLocation(it) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startHighAccuracyLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realistic_map)
        // Retire the old automation state when upgrading from 0.18/0.19.
        deleteSharedPreferences("automatic_route_share")

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_GAIN) ui.post { updateNavigationStep(displayLocation) }
            }.build()
        mapCache = MapResourceCache(this).also { it.configure(tomTomApiKey()) }
        tts = TextToSpeech(this, this)

        bindViews()
        val recentPrefs = getSharedPreferences("destinations", MODE_PRIVATE)
        recentDestinations = RecentDestinations({ recentPrefs.getString("recent", "[]") ?: "[]" },
            { recentPrefs.edit().putString("recent", it).apply() })
        val catalogJson = TollCatalogStore.load(this)
        lastCatalogRevision = JSONObject(catalogJson).getLong("revision")
        tollRepository = TollRepository(http, catalogJson)
        TariffSyncWorker.schedule(this)
        val catalogUpdated: () -> Unit = {
            val json = TollCatalogStore.load(this)
            val revision = JSONObject(json).getLong("revision")
            if (revision > lastCatalogRevision) {
                lastCatalogRevision = revision
                tollRepository.replaceCatalog(json)
                if (routeActive) refreshTolls()
            }
        }
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("mx-tolls-daily").observe(this) { catalogUpdated() }
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("mx-tolls-now").observe(this) { catalogUpdated() }
        avoidTolls = getPreferences(MODE_PRIVATE).getBoolean("avoidTolls", false)
        setupWebMap()
        connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(networkCallback)
        setMapNetworkAvailable(connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        setupSearchUi()
        setupSettingsUi()
        setupButtons()
        com.david.gps3dar.car.CarNavigation.observe(carObserver)
        setupBackNavigation()
        refreshSettingsLabels()
        updateTollPanel()
        updateAutoTheme()
        val viewportChanged = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncViewport() }
        findViewById<View>(R.id.bottomPanel).addOnLayoutChangeListener(viewportChanged)
        findViewById<View>(R.id.turnBanner).addOnLayoutChangeListener(viewportChanged)
        webMapView.addOnLayoutChangeListener(viewportChanged)
        webMapView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeSearchSuggestions() }
        handleNavigationIntent(intent)
        requestLocationPermission()
        ui.post(ticker)
    }

    private fun bindViews() {
        webMapView = findViewById(R.id.webMapView)
        searchInput = findViewById(R.id.searchInput)
        searchSuggestions = findViewById(R.id.searchSuggestions)
        routeChoices = findViewById(R.id.routeChoices)
        settingsPanel = findViewById(R.id.settingsPanel)
        instruction = findViewById(R.id.instruction)
        turnIcon = findViewById(R.id.turnIcon)
        turnDistance = findViewById(R.id.turnDistance)
        gpsStatus = findViewById(R.id.gpsStatus)
        speedText = findViewById(R.id.speedText)
        etaText = findViewById(R.id.etaText)
        routeDistance = findViewById(R.id.routeDistance)
        arrivalText = findViewById(R.id.arrivalText)
        stopButton = findViewById(R.id.stopButton)
        tollCount = findViewById(R.id.tollCount)
        tollTotal = findViewById(R.id.tollTotal)
        avoidTollsButton = findViewById(R.id.avoidTollsButton)
        viewModeButton = findViewById(R.id.viewModeButton)
        modeButton = findViewById(R.id.modeButton)
        zoomInButton = findViewById(R.id.zoomInButton)
        zoomOutButton = findViewById(R.id.zoomOutButton)
        voiceButton = findViewById(R.id.voiceButton)
        settings3d = findViewById(R.id.settings3d)
        settingsTerrain = findViewById(R.id.settingsTerrain)
        settingsBuildings = findViewById(R.id.settingsBuildings)
        settingsSatellite = findViewById(R.id.settingsSatellite)
        settingsVoice = findViewById(R.id.settingsVoice)
        settingsAutoZoom = findViewById(R.id.settingsAutoZoom)
        settingsFollowDelay = findViewById(R.id.settingsFollowDelay)
        placePreview = findViewById(R.id.placePreview)
        placePreviewImage = findViewById(R.id.placePreviewImage)
        placePreviewTitle = findViewById(R.id.placePreviewTitle)
        placePreviewAddress = findViewById(R.id.placePreviewAddress)
        placePreviewGo = findViewById(R.id.placePreviewGo)
        placePreviewClose = findViewById(R.id.placePreviewClose)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebMap() {
        webMapView.setBackgroundColor(Color.TRANSPARENT)
        webMapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webMapView.settings.javaScriptEnabled = true
        webMapView.settings.domStorageEnabled = true
        webMapView.settings.loadsImagesAutomatically = true
        webMapView.settings.builtInZoomControls = false
        webMapView.settings.displayZoomControls = false
        webMapView.webChromeClient = WebChromeClient()
        webMapView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return request?.url?.toString()?.let { mapCache.intercept(it) }
            }
        }
        webMapView.addJavascriptInterface(WebMapBridge(), "AndroidBridge")
        webMapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                pauseCameraFollow()
            }
            false
        }
        webMapView.loadUrl("file:///android_asset/map3d.html")
    }

    private inner class WebMapBridge {
        @JavascriptInterface
        fun onMapReady() {
            ui.post {
                mapReady = true
                syncMapAll()
                syncSignals()
                jsCall("setDarkTheme($darkTheme)")
                jsCall("setNetworkAvailable($networkAvailable)")
            }
        }

        @JavascriptInterface
        fun routeTo(lat: Double, lon: Double) {
            ui.post { previewMapPoint(lat, lon) }
        }

        @JavascriptInterface
        fun previewDestination(lat: Double, lon: Double) {
            ui.post { previewMapPoint(lat, lon) }
        }

        @JavascriptInterface
        fun onTrafficSeverity(severity: String) {
            if (severity !in listOf("normal", "moderate", "slow", "heavy")) return
            ui.post { trafficSeverity = severity; updateEtaColor() }
        }

        @JavascriptInterface
        fun prepareMapViewport(west: Double, south: Double, east: Double, north: Double, zoom: Double) {
            if (!isDestroyed) mapCache.prepareViewport(west, south, east, north, zoom)
        }
    }

    private fun setMapNetworkAvailable(available: Boolean) {
        mapCache.setNetworkAvailable(available)
        ui.post {
            if (isDestroyed) return@post
            val restored = !networkAvailable && available
            networkAvailable = available
            jsCall("setNetworkAvailable($available)")
            if (restored) { lastPreparedAt = 0; prepareMapAhead(true) }
        }
    }

    private fun setupSearchUi() {
        findViewById<View>(android.R.id.content).apply { isFocusableInTouchMode = true; requestFocus() }
        searchInput.setOnFocusChangeListener { _, focused ->
            if (focused) {
                searchEditing = true
                updateSearchChrome()
                showRecentDestinations()
            }
        }
        searchInput.setOnClickListener { if (searchInput.text.isBlank()) showRecentDestinations() }
        findViewById<TextView>(R.id.searchButton).setOnClickListener { clearDestinationSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDestination()
                true
            } else {
                false
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchWatcher) return
                val query = s?.toString()?.trim().orEmpty()
                searchDebounce?.let { ui.removeCallbacks(it) }
                searchCall?.cancel()
                destinationSearch.cancel()
                searchEpoch++

                if (!searchInput.hasFocus()) return
                searchEditing = true
                updateSearchChrome()
                val local = recentDestinations.list(query) + destinationSearch.cached(query, searchBias())
                showSearchSuggestions(local.distinctBy { RecentDestinations.identity(it) }.map { it.toSearchResult() },
                    if (query.isBlank()) "Destinos recientes" else "Sugerencias",
                    if (query.length >= 3) "Buscando direcciones…" else if (local.isEmpty()) "Escribe una dirección para buscar" else null)

                if (query.length < 3) {
                    return
                }

                searchDebounce = Runnable { fetchSearchSuggestions(query, navigateFirst = false) }
                ui.postDelayed(searchDebounce!!, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setupSettingsUi() {
        findViewById<TextView>(R.id.menuButton).setOnClickListener {
            hideSearchSuggestions()
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        settings3d.setOnClickListener { openVrMode() }
        settingsTerrain.setOnClickListener {
            terrainEnabled = !terrainEnabled
            refreshSettingsLabels()
            syncVisualSettings()
        }
        settingsBuildings.setOnClickListener {
            buildingsEnabled = !buildingsEnabled
            refreshSettingsLabels()
            syncVisualSettings()
        }
        settingsSatellite.setOnClickListener {
            satelliteEnabled = !satelliteEnabled
            refreshSettingsLabels()
            syncVisualSettings()
        }
        settingsVoice.setOnClickListener {
            voiceEnabled = !voiceEnabled
            refreshSettingsLabels()
        }
        settingsAutoZoom.setOnClickListener {
            autoZoomEnabled = !autoZoomEnabled
            refreshSettingsLabels()
            recenter(true)
        }
        settingsFollowDelay.setOnClickListener {
            followResumeDelayMs = when (followResumeDelayMs) {
                5000L -> 8000L
                8000L -> 12000L
                12000L -> 20000L
                else -> 5000L
            }
            refreshSettingsLabels()
        }
        findViewById<TextView>(R.id.settingsClose).setOnClickListener {
            settingsPanel.visibility = View.GONE
        }
        findViewById<TextView>(R.id.settingsMapCredits).setOnClickListener { showMapCredits() }
    }

    private fun clearDestinationSearch() {
        searchDebounce?.let { ui.removeCallbacks(it) }
        searchDebounce = null
        searchCall?.cancel()
        destinationSearch.cancel()
        externalLinkCall?.cancel()
        externalLinkCall = null
        searchEpoch++
        searchResults = emptyList()
        pendingExternalDestination = null
        pendingExternalQuery = null
        if (rerouting) {
            routeCall?.cancel(); routeGeneration++; rerouting = false; pendingAvoidTolls = null
            updateTollPanel()
        }
        if (!routeActive) {
            instruction.text = "Busca un destino o mantén pulsado el mapa"
            turnIcon.text = "↑"
        }
        hidePlacePreview()
        suppressSearchWatcher = true
        searchInput.setText("")
        suppressSearchWatcher = false
        searchEditing = true
        searchInput.requestFocus()
        showRecentDestinations()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showMapCredits() {
        webMapView.evaluateJavascript("window.GPS3D ? GPS3D.mapCredits() : ''") { value ->
            if (isDestroyed) return@evaluateJavascript
            val current = runCatching { org.json.JSONTokener(value).nextValue() as? String }.getOrNull().orEmpty()
            val message = TextView(this).apply {
                setPadding(dp(24), dp(12), dp(24), dp(12))
                textSize = 16f
                text = listOf(current, "© TomTom. Todos los derechos reservados.",
                    "© OpenStreetMap contributors. Datos bajo ODbL:\nhttps://www.openstreetmap.org/copyright",
                    "Condiciones de TomTom:\nhttps://developer.tomtom.com/terms-and-conditions",
                    "Edificios: OpenFreeMap / OpenMapTiles / OpenStreetMap.\nhttps://openfreemap.org",
                    "Imágenes satelitales: EOX Sentinel-2 cloudless.\nhttps://s2maps.eu").filter { it.isNotBlank() }.joinToString("\n\n")
                android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
            AlertDialog.Builder(this).setTitle("Acerca del mapa y licencias")
                .setView(android.widget.ScrollView(this).apply { addView(message) })
                .setPositiveButton("Cerrar", null).show()
        }
    }

    private fun setupButtons() {
        signalRepository = SignalRepository(http, java.io.File(filesDir, "osm-signals.json"))
        findViewById<View>(R.id.settingsMapsConnection).setOnClickListener {
            startActivity(Intent(this, MapsConnectionActivity::class.java))
        }
        findViewById<View>(R.id.changeDestinationButton).setOnClickListener {
            hidePlacePreview()
            searchEditing = true
            updateSearchChrome()
            suppressSearchWatcher = true
            searchInput.setText("")
            suppressSearchWatcher = false
            searchInput.requestFocus()
            showRecentDestinations()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
        findViewById<TextView>(R.id.recenterButton).setOnClickListener { setViewMode(0); recenter(true) }
        viewModeButton.setOnClickListener { setViewMode((viewMode + 1) % 3) }
        avoidTollsButton.setOnClickListener {
            if (pedestrianRoute || rerouting) return@setOnClickListener
            val requestedAvoid = !avoidTolls
            val current = rawLocation ?: displayLocation
            val destination = routeDestination
            if (routeActive && destination != null) {
                if (current == null) {
                    Toast.makeText(this, "Esperando ubicación GPS para cambiar la ruta…", Toast.LENGTH_SHORT).show()
                } else {
                    requestRoute(current.latitude, current.longitude, destination.lat, destination.lon,
                        isReroute = true, avoidOverride = requestedAvoid)
                }
            } else {
                avoidTolls = requestedAvoid
                getPreferences(MODE_PRIVATE).edit().putBoolean("avoidTolls", avoidTolls).apply()
                updateTollPanel()
            }
        }
        tollCount.setOnClickListener { showTollDetails() }
        tollTotal.setOnClickListener { showTollDetails() }
        zoomInButton.setOnClickListener { changeZoomPreset(+1) }
        zoomOutButton.setOnClickListener { changeZoomPreset(-1) }
        modeButton.setOnClickListener { openVrMode() }
        voiceButton.setOnClickListener {
            voiceEnabled = !voiceEnabled
            refreshSettingsLabels()
            Toast.makeText(
                this,
                if (voiceEnabled) "Indicaciones por voz activadas" else "Indicaciones por voz silenciadas",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<TextView>(R.id.reportButton).setOnClickListener {
            Toast.makeText(
                this,
                "Reportes de tráfico, accidente, peligro y obra: siguiente módulo",
                Toast.LENGTH_LONG
            ).show()
        }
        stopButton.setOnClickListener { stopNavigation() }
        placePreviewClose.setOnClickListener { hidePlacePreview() }
        placePreviewGo.setOnClickListener {
            val point = selectedMapPoint ?: return@setOnClickListener
            val current = displayLocation ?: rawLocation
            if (current == null) {
                Toast.makeText(this, "Esperando ubicación GPS…", Toast.LENGTH_SHORT).show()
            } else {
                hidePlacePreview()
                requestRoute(current.latitude, current.longitude, point.lat, point.lon)
            }
        }
    }

    private fun hideKeyboard() {
        searchInput.clearFocus()
        searchEditing = false
        updateSearchChrome()
        val input = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        input?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    placePreview.visibility == View.VISIBLE -> hidePlacePreview()
                    searchSuggestions.visibility == View.VISIBLE -> {
                        hideSearchSuggestions()
                        hideKeyboard()
                    }
                    settingsPanel.visibility == View.VISIBLE -> settingsPanel.visibility = View.GONE
                    routeActive -> {
                        AlertDialog.Builder(this@RealisticMapActivity)
                            .setTitle("Salir de la ruta")
                            .setMessage("¿Deseas salir de la ubicación y cancelar la ruta actual?")
                            .setNegativeButton("No", null)
                            .setPositiveButton("Sí") { _, _ ->
                                stopNavigation()
                                hideKeyboard()
                            }
                            .show()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }
    private fun refreshSettingsLabels() {
        modeButton.text = "VR"
        voiceButton.text = if (voiceEnabled) "🔊" else "🔇"
        settings3d.text = "VR peatonal: disponible hasta 20 km/h"
        settingsTerrain.text = "Terreno DEM real: ${if (terrainEnabled) "activado" else "desactivado"}"
        settingsBuildings.text = "Edificios 3D por tiles: ${if (buildingsEnabled) "activados" else "desactivados"}"
        settingsSatellite.text = "Satélite híbrido: ${if (satelliteEnabled) "activado" else "desactivado"}"
        settingsVoice.text = "Voz: ${if (voiceEnabled) "activada" else "desactivada"}"
        settingsAutoZoom.text = if (autoZoomEnabled) {
            "Zoom automático: activado"
        } else {
            "Zoom manual: nivel ${zoomPresetIndex + 1} de ${ZOOM_PRESETS.size}"
        }
        settingsFollowDelay.text = "Retomar seguimiento después de zoom: ${followResumeDelayMs / 1000L} s"
    }

    private fun changeZoomPreset(delta: Int) {
        autoZoomEnabled = false
        zoomPresetIndex = (zoomPresetIndex + delta).coerceIn(0, ZOOM_PRESETS.lastIndex)
        refreshSettingsLabels()
        recenter(true)
    }

    private fun openVrMode() {
        val speedKmh = ((rawLocation?.speed ?: 0f) * 3.6f)
        if (speedKmh > VR_MAX_SPEED_KMH) {
            Toast.makeText(
                this,
                "VR es solo para caminar. A más de 20 km/h se usa el mapa 2D.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val selected = selectedMapPoint?.takeIf { placePreview.visibility == View.VISIBLE }
        val destination = selected?.let { GeoPoint(it.lat, it.lon) } ?: routeDestination
            ?: selectedMapPoint?.let { GeoPoint(it.lat, it.lon) }
        if (destination == null) {
            Toast.makeText(this, "Elige un destino y pulsa VR para calcular la ruta a pie", Toast.LENGTH_LONG).show()
            searchInput.requestFocus(); showRecentDestinations()
            return
        }
        arDestination = destination
        routeBeforeAr = if (routeActive && !pedestrianRoute && routeDestination == destination)
            RouteBeforeAr(routeAlternatives, activeRouteIndex, destination, viewMode) else null
        // Store the exact pedestrian polyline currently displayed. The camera must reuse it.
        if (pedestrianRoute && routePoints.size >= 2 && routeDestination == destination) {
            val points = routePoints.map { RouteGeometry.Point(it.lat, it.lon) }
            val cached = runCatching { WalkingRoute.decode(java.io.File(filesDir, ArNavigationActivity.ROUTE_FILE).readText()) }.getOrNull()
            if (cached?.points != points) {
                val walking = WalkingRoute(RouteGeometry.Point(destination.lat, destination.lon), points,
                    navSteps.map { WalkingRoute.Step(RouteGeometry.Point(it.lat, it.lon), it.instruction, it.maneuver) },
                    routeDurationSeconds)
                runCatching {
                    val pending = java.io.File(filesDir, ArNavigationActivity.ROUTE_FILE + ".pending")
                    pending.writeText(walking.encode())
                    check(pending.renameTo(java.io.File(filesDir, ArNavigationActivity.ROUTE_FILE)))
                }
            }
        }
        arLauncher.launch(Intent(this, ArNavigationActivity::class.java)
            .putExtra(WalkingRoute.EXTRA_LAT, destination.lat)
            .putExtra(WalkingRoute.EXTRA_LON, destination.lon)
            .putExtra(WalkingRoute.EXTRA_LABEL, selected?.label ?: searchInput.text.toString())
            .putExtra("walking_voice", voiceEnabled))
    }

    private fun syncMapAll() {
        syncVisualSettings()
        syncRoutes()
        syncRouteProgress(force = true)
        syncTolls()
        displayLocation?.let { updateLocationMarker(it) }
        syncViewport()
        selectedMapPoint?.let { jsCall("setSelectionPin(${num(it.lon)},${num(it.lat)})") }
        if (viewMode == 2) jsCall("showOverview()") else recenter(false)
        maybeStartPendingExternalNavigation()
        com.david.gps3dar.car.CarNavigation.voiceEnabled=voiceEnabled
        com.david.gps3dar.car.CarNavigation.fix(location)
    }

    private fun syncVisualSettings() {
        jsCall("setVisuals($is3D,$terrainEnabled,$buildingsEnabled,$satelliteEnabled)")
        jsCall("setViewMode($viewMode)")
    }

    private fun jsCall(call: String) {
        if (!mapReady) return
        webMapView.evaluateJavascript("window.GPS3D && window.GPS3D.$call;", null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("es", "MX"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) ttsReady = tts.setLanguage(Locale("es", "ES")) >= 0
            tts.voices?.filter { it.locale.language == "es" && !it.isNetworkConnectionRequired }
                ?.sortedByDescending { if (it.locale.country == "MX") 1 else 0 }?.firstOrNull()?.let { tts.voice = it }
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == spokenUtterance) audioManager.abandonAudioFocusRequest(audioFocus)
                }
                @Deprecated("Legacy engine callback")
                override fun onError(utteranceId: String?) {
                    ui.post {
                        val parts = utteranceId.orEmpty().split(':')
                        if (parts.size == 3 && parts[0] == voiceRouteId.toString()) {
                            announcements.failed(parts.take(2).joinToString(":"), parts[2].toIntOrNull() ?: 0)
                        }
                        audioManager.abandonAudioFocusRequest(audioFocus)
                    }
                }
            })
            ui.post { if (::instruction.isInitialized) updateNavigationStep(displayLocation) }
        }
    }

    private fun updateEtaColor() {
        etaText.setTextColor(Color.parseColor(when (trafficSeverity) {
            "moderate" -> "#F9AB00"
            "slow" -> "#F57C00"
            "heavy", "stopped", "closed" -> "#D93025"
            "normal" -> if (darkTheme) "#53D68E" else "#16834B"
            else -> if (darkTheme) "#E3EBF4" else "#243746"
        }))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) ambientLux = event.values.firstOrNull()?.toDouble()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateAutoTheme() {
        val now = System.currentTimeMillis()
        val p = rawLocation
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = if (p == null) hour < 6 || hour >= 19
            else AutoDarkMode.solarElevation(now, p.latitude, p.longitude) < -3.0
        val next = darkPolicy.update(SystemClock.elapsedRealtime(), night, ambientLux)
        if (!themeInitialized || next != darkTheme) {
            themeInitialized = true; darkTheme = next
            applyPalette()
            jsCall("setDarkTheme($darkTheme)")
        }
    }

    private fun applyPalette() {
        val surface = Color.parseColor(if (darkTheme) "#232B36" else "#FFFFFF")
        val text = Color.parseColor(if (darkTheme) "#EDF2F7" else "#27313A")
        val secondary = Color.parseColor(if (darkTheme) "#B8C3CF" else "#6A6D70")
        fun visit(view: View) {
            if (view is TextView) {
                val original = originalTextColors.getOrPut(view) { view.currentTextColor }
                val gray = abs(Color.red(original) - Color.blue(original)) < 55 && Color.red(original) < 220
                view.setTextColor(if (darkTheme && gray) { if (Color.red(original) > 90) secondary else text } else original)
                if (view is EditText) view.setHintTextColor(secondary)
            }
            val bg = view.background
            if (bg is GradientDrawable) {
                val banner = view.id in listOf(R.id.turnBanner, R.id.gpsStatus, R.id.placePreviewGo)
                val toll = view.id == R.id.avoidTollsButton
                (bg.mutate() as GradientDrawable).setColor(if (banner) Color.parseColor(if (darkTheme) "#111A25" else "#14344A")
                    else if (toll) Color.parseColor(if (darkTheme) "#193C2C" else "#ECF8F0") else surface)
            } else if (bg is ColorDrawable && Color.alpha(bg.color) in 1..99) {
                val original = originalBackgroundColors.getOrPut(view) { bg.color }
                view.setBackgroundColor(if (darkTheme) Color.parseColor("#445E6B79") else original)
            }
            if (view is ImageButton) view.imageTintList = ColorStateList.valueOf(text)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(findViewById(android.R.id.content))
        window.statusBarColor = Color.parseColor(if (darkTheme) "#111A25" else "#111111")
        window.navigationBarColor = window.statusBarColor
        updateEtaColor()
    }

    private fun prepareMapAhead(force: Boolean = false) {
        if (!routeActive || routePoints.size < 2) return
        val now = SystemClock.elapsedRealtime()
        val advanced = lastPreparedIndex < 0 || distanceAlongRouteToIndex(lastPreparedIndex) <= 0.0 && routeProgressIndex - lastPreparedIndex > 10
        if (!force && (!advanced || now - lastPreparedAt < 60000)) return
        lastPreparedAt = now; lastPreparedIndex = routeProgressIndex
        mapCache.prepare(routePoints.map { RouteGeometry.Point(it.lat, it.lon) }, routeProgressIndex) { prepared, total ->
            ui.post {
                if (!isDestroyed) findViewById<TextView>(R.id.mapCacheStatus).apply {
                    visibility = View.GONE
                    text = if (prepared == total) "Mapa próximo preparado" else "Mapa próximo guardado: ${prepared * 100 / total.coerceAtLeast(1)}%"
                }
            }
        }
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startHighAccuracyLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHighAccuracyLocation() {
        // Use the last fresh fix immediately so the map does not sit waiting for a new GNSS cycle.
        fusedLocation.lastLocation.addOnSuccessListener { last ->
            if (last != null && System.currentTimeMillis() - last.time < 20_000L) {
                processLocation(last)
            }
        }

        // Navigation needs low-latency fixes. Avoid batching because a delayed batch makes the
        // vehicle appear to outrun the map even when the GNSS itself is accurate.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 300L)
            .setMinUpdateIntervalMillis(100L)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun processLocation(location: Location) {
        val accepted = lastAcceptedLocation
        if (accepted != null && location.elapsedRealtimeNanos <= accepted.elapsedRealtimeNanos) return
        if (accepted != null && SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 3_000_000_000L) return
        rawLocation = location

        if (location.hasAccuracy() && location.accuracy > 65f) {
            gpsStatus.text = "GPS débil · ±${location.accuracy.toInt()} m"
            return
        }

        val previous = lastAcceptedLocation
        if (previous != null) {
            val dt = max(0.25, (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0)
            val jumpSpeed = previous.distanceTo(location) / dt
            if (jumpSpeed > 85.0 && location.accuracy > 12f) return
        }
        lastAcceptedLocation = Location(location)

        // Use the fresh fix for progress and turn distances. Filtering is for stationary jitter;
        // filtering a moving position twice introduces avoidable delay at the intersection.
        val filtered = if (location.hasSpeed() && location.speed > 1.0f) Location(location) else smoothLocation(location)
        filteredLocation = filtered
        val predicted = predictLocation(filtered, location)

        val match = if (routeActive) findRouteMatch(predicted) else null
        lastRouteMatch = match
        if (routeActive) maybeReroute(predicted, match)

        val shown = if (routeActive) snapToRoute(predicted, match) else predicted
        displayLocation = shown

        if (routeActive && match != null) updateRouteProgress(match)

        updateLocationMarker(shown)
        updateCamera(shown)
        updateDrivingUi(location, shown)
        updateNavigationStep(shown)
        updateTollPanel()
        maybeStartPendingExternalNavigation()
        val signalNow = SystemClock.elapsedRealtime()
        if (signalNow - lastSignalUiMs > 1000 && ::signalRepository.isInitialized) {
            lastSignalUiMs = signalNow
            val center = RouteGeometry.Point(location.latitude, location.longitude)
            signalRepository.load(center) { _, _ -> ui.post { if (!isDestroyed) syncSignals() } }
            syncSignals()
        }
    }

    private fun syncSignals() {
        if (!mapReady || !::signalRepository.isInitialized) return
        val here = displayLocation ?: rawLocation
        val data = signalRepository.snapshot?.takeIf { s -> here == null ||
            RouteGeometry.distance(s.center, RouteGeometry.Point(here.latitude, here.longitude)) < 3000 }
        val points = data?.signals.orEmpty()
        val route = if (routeActive) routePoints.drop((routeProgressIndex - 2).coerceAtLeast(0)).take(350)
            .map { RouteGeometry.Point(it.lat, it.lon) } else emptyList()
        val nearest = here?.let { SignalRepository.next(points, RouteGeometry.Point(it.latitude, it.longitude), route, pedestrianRoute) }
        val outdated = data != null && (data.cached || System.currentTimeMillis() - data.loadedAt > 3_600_000)
        val label = when {
            data == null -> signalRepository.lastError ?: if (here != null) "Consultando semáforos OSM" else ""
            nearest != null -> "Semáforo ${if (routeActive) "cerca de la ruta" else "cercano"}: ${nearest.second.toInt()} m · ${if (outdated) "OSM guardado" else "OSM"}\nColor: sin datos en vivo"
            points.isEmpty() -> "OSM: sin semáforos registrados cerca · color sin datos en vivo"
            else -> "${points.size} semáforos registrados cerca · ${if (outdated) "OSM guardado" else "OSM"}\nColor: sin datos en vivo"
        }
        val coordinates = JSONArray(points.map { JSONArray(listOf(it.point.lon, it.point.lat)) }).toString()
        jsCall("setSignals($coordinates,${JSONObject.quote(label)})")
    }

    private fun smoothLocation(raw: Location): Location {
        val old = filteredLocation ?: return Location(raw)
        // Keep enough filtering to suppress GNSS jitter, but give new fixes much more weight
        // while driving so the marker does not visibly trail the real vehicle.
        val alpha = when {
            raw.speed > 15f -> 0.99
            raw.speed > 7f -> 0.97
            raw.speed > 2f -> 0.92
            raw.accuracy <= 6f -> 0.82
            raw.accuracy <= 15f -> 0.72
            else -> 0.60
        }
        return Location(raw).apply {
            latitude = old.latitude + (raw.latitude - old.latitude) * alpha
            longitude = old.longitude + (raw.longitude - old.longitude) * alpha
        }
    }

    private fun predictLocation(filtered: Location, raw: Location): Location {
        if (!raw.hasSpeed() || !raw.hasBearing() || raw.speed < 1.2f) return Location(filtered)

        // Compensate actual fix age only; continuous rendering handles time between fixes.
        val ageSeconds = ((SystemClock.elapsedRealtimeNanos() - raw.elapsedRealtimeNanos) /
            1_000_000_000.0).coerceIn(0.0, 0.6)
        val meters = (raw.speed.toDouble() * ageSeconds).coerceAtMost(18.0)
        val bearing = Math.toRadians(raw.bearing.toDouble())
        val lat = filtered.latitude + (cos(bearing) * meters / 110540.0)
        val lonScale = 111320.0 * cos(Math.toRadians(filtered.latitude)).coerceAtLeast(0.2)
        val lon = filtered.longitude + (sin(bearing) * meters / lonScale)

        return Location(filtered).apply {
            latitude = lat
            longitude = lon
        }
    }

    private fun updateDrivingUi(raw: Location, shown: Location) {
        val accuracy = if (raw.hasAccuracy()) raw.accuracy.toInt() else 0
        gpsStatus.text = when {
            accuracy in 1..7 -> "GPS excelente · ±$accuracy m"
            accuracy in 8..15 -> "GPS preciso · ±$accuracy m"
            accuracy in 16..30 -> "GPS medio · ±$accuracy m"
            else -> "GPS débil · ±$accuracy m"
        }
        speedText.text = (raw.speed * 3.6f).toInt().coerceAtLeast(0).toString()

        if (routeActive && routeDistanceMeters > 0) {
            val remaining = estimateRemainingDistance(shown)
            val ratio = (remaining / routeDistanceMeters).coerceIn(0.0, 1.0)
            val seconds = routeDurationSeconds * ratio
            etaText.text = (if (pedestrianRoute) "A pie · " else "") + formatDuration(seconds)
            routeDistance.text = formatDistance(remaining)
            arrivalText.text = "Llegada aprox. ${formatArrival(seconds)}"
        }
    }

    private fun updateLocationMarker(location: Location) {
        val raw = rawLocation
        val bearing = if (raw?.hasBearing() == true && raw.speed > 0.8f) {
            raw.bearing.toDouble()
        } else {
            lastCameraBearing
        }
        val match = lastRouteMatch
        val onRoute = routeActive && match != null && distanceMeters(location.latitude,location.longitude,match.lat,match.lon) < .5
        jsCall("setLocation(${num(location.longitude)},${num(location.latitude)},${num(bearing)},${num((raw?.speed ?: 0f).toDouble())},$onRoute)")
    }

    private fun pauseCameraFollow() {
        manualCameraUntilMs = SystemClock.elapsedRealtime() + followResumeDelayMs
    }

    private fun updateCamera(location: Location) {
        val moving = rawLocation?.speed ?: 0f
        val raw = rawLocation
        if (raw?.hasBearing() == true && moving > 1.0f) {
            // WebView animates the fresh heading continuously; filtering it once per fix creates steps.
            lastCameraBearing = raw.bearing.toDouble()
        }

        if (viewMode == 2 || SystemClock.elapsedRealtime() < manualCameraUntilMs) return

        val target = GeoPoint(location.latitude, location.longitude)
        val zoom = desiredZoom(moving)
        val pitch = if (viewMode == 1) 45.0 else 0.0
        jsCall(
            "follow(${num(target.lon)},${num(target.lat)},${num(lastCameraBearing)},${num(zoom)},${num(pitch)},110)"
        )
    }

    private fun desiredZoom(speedMps: Float): Double {
        if (!autoZoomEnabled) return ZOOM_PRESETS[zoomPresetIndex]
        return when {
            speedMps >= 30f -> ZOOM_PRESETS[0]
            speedMps >= 20f -> ZOOM_PRESETS[1]
            speedMps >= 12f -> ZOOM_PRESETS[2]
            speedMps >= 6f -> ZOOM_PRESETS[3]
            else -> ZOOM_PRESETS[4]
        }
    }
    private fun lookAheadTarget(location: Location, bearing: Double, speedMps: Float): GeoPoint {
        if (!routeActive || speedMps < 1.8f) return GeoPoint(location.latitude, location.longitude)
        val meters = (10.0 + speedMps * 0.85).coerceIn(10.0, 38.0)
        val r = Math.toRadians(bearing)
        val lat = location.latitude + (cos(r) * meters / 110540.0)
        val lonScale = 111320.0 * cos(Math.toRadians(location.latitude)).coerceAtLeast(0.2)
        val lon = location.longitude + (sin(r) * meters / lonScale)
        return GeoPoint(lat, lon)
    }

    private fun recenter(animated: Boolean) {
        val here = displayLocation ?: rawLocation ?: return
        manualCameraUntilMs = 0L
        val moving = rawLocation?.speed ?: 0f
        if (viewMode == 2) { jsCall("showOverview()"); return }
        val target = GeoPoint(here.latitude, here.longitude)
        val pitch = if (viewMode == 1) 45 else 0
        jsCall(
            "follow(${num(target.lon)},${num(target.lat)},${num(lastCameraBearing)},${num(desiredZoom(moving))},$pitch,${if (animated) 180 else 1})"
        )
    }

    private fun handleNavigationIntent(sourceIntent: Intent?) {
        val text = runCatching {
            sourceIntent?.data?.toString() ?: sourceIntent?.getStringExtra(Intent.EXTRA_TEXT)
        }.getOrNull() ?: return
        receiveDestinationText(text)
    }

    private fun receiveDestinationText(text: String) {
        val received = SharedDestination.parse(text) ?: run {
            Toast.makeText(this, "Esta ubicación no incluye un destino válido", Toast.LENGTH_LONG).show()
            return
        }
        // A shared driving destination must not inherit a previous VR walking route.
        pedestrianRoute = received.walking
        externalLinkCall?.cancel()
        destinationSearch.cancel()
        searchDebounce?.let { ui.removeCallbacks(it) }
        if (received.shortUrl != null) {
            expandMapLink(received.shortUrl)
            return
        }
        pendingExternalDestination = received.point?.let { GeoPoint(it.lat, it.lon) }
        pendingExternalQuery = received.query
        suppressSearchWatcher = true
        searchInput.setText(received.query ?: "Destino recibido")
        suppressSearchWatcher = false
        hideKeyboard()
        hideSearchSuggestions()
        settingsPanel.visibility = View.GONE
        instruction.text = "Destino recibido · preparando mapa y GPS…"
        received.point?.let {
            selectedMapPoint = SearchResult("Destino recibido", it.lat, it.lon)
            jsCall("setSelectionPin(${num(it.lon)},${num(it.lat)})")
        }
        maybeStartPendingExternalNavigation()
    }

    private fun expandMapLink(url: String, redirects: Int = 0) {
        if (redirects > 5) {
            Toast.makeText(this, "No se pudo resolver el enlace de ubicación", Toast.LENGTH_LONG).show()
            return
        }
        instruction.text = "Abriendo ubicación compartida…"
        val request = Request.Builder().url(url).header("User-Agent", "GPS3D-AR-David/0.13").build()
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val linkCall = client.newCall(request)
        externalLinkCall = linkCall
        linkCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                ui.post { if (externalLinkCall === call && !isDestroyed) instruction.text = "No se pudo abrir la ubicación compartida" }
            }
            override fun onResponse(call: Call, response: Response) {
                val next = response.use { it.header("Location")?.let { path -> it.request.url.resolve(path) } }
                ui.post {
                    if (externalLinkCall !== call || isDestroyed) return@post
                    val parsed = next?.let { SharedDestination.parse(it.toString()) }
                    when {
                        next == null || !next.isHttps || !SharedDestination.isMapHost(next.host) ->
                            instruction.text = "El enlace no contiene una ubicación legible"
                        parsed?.point != null || parsed?.query != null -> receiveDestinationText(next.toString())
                        else -> expandMapLink(next.toString(), redirects + 1)
                    }
                }
            }
        })
    }

    private fun maybeStartPendingExternalNavigation() {
        if (!mapReady) return
        val current = displayLocation ?: return
        pendingExternalDestination?.let { destination ->
            pendingExternalDestination = null
            requestRoute(current.latitude, current.longitude, destination.lat, destination.lon)
            return
        }
        pendingExternalQuery?.let { query ->
            pendingExternalQuery = null
            fetchSearchSuggestions(query, navigateFirst = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun searchDestination() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return
        fetchSearchSuggestions(query, navigateFirst = true)
    }

    private fun tomTomApiKey(): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        info.metaData?.getString("com.david.gps3dar.TOMTOM_API_KEY")?.trim().orEmpty()
    }.getOrDefault("")

    private fun fetchSearchSuggestions(query: String, navigateFirst: Boolean) {
        searchDebounce?.let { ui.removeCallbacks(it) }
        val epoch = ++searchEpoch
        SharedDestination.coordinates(query)?.let { point ->
            showPlacePreview(SearchResult(query, point.lat, point.lon), movePin = true)
            return
        }
        if (navigateFirst && (query.startsWith("http") || query.startsWith("geo:") || query.startsWith("google.navigation:"))) {
            receiveDestinationText(query)
            return
        }
        if (navigateFirst) instruction.text = "Buscando $query…"
        val here = searchBias()
        destinationSearch.search(query, tomTomApiKey(), here, navigateFirst) { found, error ->
            ui.post {
                if (isDestroyed || searchEpoch != epoch || searchInput.text.toString().trim() != query) return@post
                if (found.isEmpty()) {
                    val local = recentDestinations.list(query).map { it.toSearchResult() }
                    showSearchSuggestions(local, "Sugerencias", error ?: "No encontré esa dirección. Incluye municipio o código postal.")
                } else {
                    val merged = (if (navigateFirst) found else recentDestinations.list(query) + found)
                        .distinctBy { RecentDestinations.identity(it) }.take(12)
                    deliverSearchResults(query, navigateFirst, merged.map { it.toSearchResult() })
                }
            }
        }
    }

    private fun deliverSearchResults(
        query: String,
        navigateFirst: Boolean,
        results: List<SearchResult>
    ) {
        val epoch = searchEpoch
        ui.post {
            if (isDestroyed || searchEpoch != epoch || searchInput.text.toString().trim() != query) return@post
            searchResults = results
            if (navigateFirst && results.size == 1) {
                hideSearchSuggestions()
                showPlacePreview(results.first(), movePin = true)
            } else {
                if (navigateFirst) instruction.text = "Selecciona la dirección correcta"
                showSearchSuggestions(results)
            }
        }
    }

    private fun searchBias() = rawLocation?.let { SharedDestination.Point(it.latitude, it.longitude) }
    private fun DestinationSearch.Result.toSearchResult() = SearchResult(label, lat, lon, title, address)

    private fun showRecentDestinations() {
        showSearchSuggestions(recentDestinations.list().map { it.toSearchResult() }, "Destinos recientes",
            if (recentDestinations.list().isEmpty()) "Aquí aparecerán los destinos que selecciones" else null)
    }

    private fun updateSearchChrome() {
        if (!::searchInput.isInitialized) return
        findViewById<View>(R.id.searchPanel).visibility = if (routeActive && !searchEditing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.changeDestinationButton).visibility = View.GONE
        findViewById<View>(R.id.mapControls).visibility = if (searchEditing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.turnBanner).visibility = if (searchEditing) View.GONE else View.VISIBLE
        gpsStatus.visibility = View.GONE
        findViewById<View>(R.id.bottomPanel).visibility = if (searchEditing) View.GONE else View.VISIBLE
        if (searchEditing) {
            routeChoices.visibility = View.GONE
            findViewById<View>(R.id.mapCacheStatus).visibility = View.GONE
        } else if (routeActive && routeAlternatives.size > 1) routeChoices.visibility = View.VISIBLE
        webMapView.post { syncViewport() }
    }

    private fun resizeSearchSuggestions() {
        val container = findViewById<View>(R.id.searchSuggestionsContainer)
        if (container.visibility != View.VISIBLE || webMapView.height <= 0) return
        val width = (webMapView.width - dp(24)).coerceAtLeast(1)
        searchSuggestions.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val available = min(dp(360), (webMapView.height - dp(88)).coerceAtLeast(dp(96)))
        val height = min(available, searchSuggestions.measuredHeight.coerceAtLeast(dp(80)))
        if (container.layoutParams.height != height) { container.layoutParams.height = height; container.requestLayout() }
    }

    private fun showSearchSuggestions(results: List<SearchResult>, heading: String = "Sugerencias", status: String? = null) {
        searchEditing = true
        updateSearchChrome()
        searchSuggestions.removeAllViews()
        settingsPanel.visibility = View.GONE
        searchSuggestions.addView(TextView(this).apply {
            setPadding(dp(14), dp(10), dp(12), dp(8)); text = heading; textSize = 13f
            setTextColor(Color.parseColor("#606B76"))
            setTypeface(null, Typeface.BOLD)
        })
        results.forEachIndexed { index, result ->
            val label = result.label.split(",").take(3).joinToString(",")
            val row = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minimumHeight = dp(60)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(12), dp(8))
                text = "${index + 1}. $label"
                textSize = 16f
                setTextColor(Color.parseColor("#27313A"))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    suppressSearchWatcher = true
                    searchInput.setText(label)
                    searchInput.setSelection(searchInput.text.length)
                    suppressSearchWatcher = false
                    hideSearchSuggestions()
                    searchEpoch++
                    destinationSearch.cancel()
                    showPlacePreview(result, movePin = true)
                }
            }
            searchSuggestions.addView(row)
            if (index < results.lastIndex) {
                searchSuggestions.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(Color.parseColor("#19000000"))
                })
            }
        }
        status?.let { message -> searchSuggestions.addView(TextView(this).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12)); text = message; textSize = 14f
            setTextColor(Color.parseColor("#606B76"))
        }) }
        searchSuggestions.visibility = View.VISIBLE
        findViewById<View>(R.id.searchSuggestionsContainer).apply {
            visibility = View.VISIBLE
        }
        resizeSearchSuggestions()
        applyPalette()
    }

    private fun hideSearchSuggestions() {
        searchSuggestions.visibility = View.GONE
        findViewById<View>(R.id.searchSuggestionsContainer).visibility = View.GONE
    }

    private fun previewMapPoint(lat: Double, lon: Double) {
        hideKeyboard()
        hideSearchSuggestions()
        settingsPanel.visibility = View.GONE
        val result = SearchResult(
            label = "Punto seleccionado",
            lat = lat,
            lon = lon,
            title = "Punto seleccionado",
            address = String.format(Locale("es", "MX"), "%.6f, %.6f", lat, lon)
        )
        showPlacePreview(result, movePin = true)
        resolveMapPoint(lat, lon)
    }

    private fun showPlacePreview(result: SearchResult, movePin: Boolean) {
        searchDebounce?.let { ui.removeCallbacks(it) }
        searchDebounce = null
        recentDestinations.select(DestinationSearch.Result(result.lat, result.lon, result.title.ifBlank { result.label }, result.address))
        destinationSearch.cancel()
        searchEpoch++
        hideSearchSuggestions()
        hideKeyboard()
        selectedMapPoint = result
        placePreviewTitle.text = result.title.ifBlank { result.label.substringBefore(",") }
        placePreviewAddress.text = result.address.ifBlank { result.label }
        placePreviewImage.setImageDrawable(null)
        placePreviewImage.visibility = View.GONE
        placePreview.visibility = View.VISIBLE
        if (movePin) {
            jsCall(
                "setSelectionPin(" + num(result.lon) + "," + num(result.lat) + ")"
            )
        }
        if (movePin) {
            manualCameraUntilMs = SystemClock.elapsedRealtime() + followResumeDelayMs
            jsCall("previewPoint(${num(result.lon)},${num(result.lat)})")
        }
        loadPlaceImage(result)
    }

    private fun hidePlacePreview() {
        selectedMapPoint = null
        placePreviewCall?.cancel()
        placeImageCall?.cancel()
        placePreview.visibility = View.GONE
        jsCall("clearSelectionPin()")
    }

    private fun resolveMapPoint(lat: Double, lon: Double) {
        val key = tomTomApiKey()
        if (key.isBlank()) {
            reverseGeocodePoint(lat, lon)
            return
        }

        placePreviewCall?.cancel()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.tomtom.com")
            .addPathSegment("search")
            .addPathSegment("2")
            .addPathSegment("nearbySearch")
            .addPathSegment(".json")
            .addQueryParameter("key", key)
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("radius", "120")
            .addQueryParameter("limit", "1")
            .addQueryParameter("countrySet", "MX")
            .addQueryParameter("language", "es-ES")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GPS3D-AR-David/0.10")
            .build()

        placePreviewCall = http.newCall(request)
        placePreviewCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) reverseGeocodePoint(lat, lon)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        reverseGeocodePoint(lat, lon)
                        return
                    }

                    val root = JSONObject(it.body?.string().orEmpty())
                    val results = root.optJSONArray("results")
                    val item = results?.optJSONObject(0)
                    val position = item?.optJSONObject("position")

                    if (item == null || position == null) {
                        reverseGeocodePoint(lat, lon)
                        return
                    }

                    val poiName = item.optJSONObject("poi")
                        ?.optString("name")
                        ?.trim()
                        .orEmpty()
                    val addressObject = item.optJSONObject("address")
                    val freeform = addressObject
                        ?.optString("freeformAddress")
                        ?.trim()
                        .orEmpty()
                    val municipality = addressObject
                        ?.optString("municipality")
                        ?.trim()
                        .orEmpty()
                    val pLat = position.optDouble("lat", lat)
                    val pLon = position.optDouble("lon", lon)

                    val distance = distanceMeters(lat, lon, pLat, pLon)
                    if (poiName.isBlank() || distance > 120.0) {
                        reverseGeocodePoint(lat, lon)
                        return
                    }

                    val result = SearchResult(
                        label = listOf(poiName, freeform)
                            .filter { value -> value.isNotBlank() }
                            .joinToString(", "),
                        lat = lat,
                        lon = lon,
                        title = poiName,
                        address = listOf(freeform, municipality)
                            .filter { value -> value.isNotBlank() }
                            .distinct()
                            .joinToString(", ")
                    )
                    ui.post {
                        val selected = selectedMapPoint ?: return@post
                        if (distanceMeters(selected.lat, selected.lon, lat, lon) > 2.0) return@post
                        showPlacePreview(result, movePin = false)
                    }
                }
            }
        })
    }

    private fun reverseGeocodePoint(lat: Double, lon: Double) {
        val key = tomTomApiKey()
        if (key.isBlank()) return

        placePreviewCall?.cancel()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.tomtom.com")
            .addPathSegment("search")
            .addPathSegment("2")
            .addPathSegment("reverseGeocode")
            .addPathSegment(lat.toString() + "," + lon + ".json")
            .addQueryParameter("key", key)
            .addQueryParameter("radius", "100")
            .addQueryParameter("language", "es-ES")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GPS3D-AR-David/0.10")
            .build()

        placePreviewCall = http.newCall(request)
        placePreviewCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val root = JSONObject(it.body?.string().orEmpty())
                    val addresses = root.optJSONArray("addresses") ?: return
                    val first = addresses.optJSONObject(0) ?: return
                    val addressObject = first.optJSONObject("address") ?: return
                    val freeform = addressObject.optString("freeformAddress").trim()
                    val street = addressObject.optString("streetName").trim()
                    val municipality = addressObject.optString("municipality").trim()
                    val title = street.ifBlank {
                        freeform.substringBefore(",").ifBlank { "Punto seleccionado" }
                    }
                    val result = SearchResult(
                        label = freeform.ifBlank { lat.toString() + "," + lon },
                        lat = lat,
                        lon = lon,
                        title = title,
                        address = listOf(freeform, municipality)
                            .filter { value -> value.isNotBlank() }
                            .distinct()
                            .joinToString(", ")
                    )
                    ui.post {
                        val selected = selectedMapPoint ?: return@post
                        if (distanceMeters(selected.lat, selected.lon, lat, lon) > 2.0) return@post
                        showPlacePreview(result, movePin = false)
                    }
                }
            }
        })
    }

    private fun loadPlaceImage(result: SearchResult) {
        placeImageCall?.cancel()
        val title = result.title.trim()
        if (title.length < 4 || title.equals("Punto seleccionado", true)) return

        val query = if (result.address.isBlank()) title
            else title + " " + result.address.substringAfterLast(",").trim()

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("es.wikipedia.org")
            .addPathSegment("w")
            .addPathSegment("api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrlimit", "1")
            .addQueryParameter("prop", "pageimages")
            .addQueryParameter("piprop", "thumbnail")
            .addQueryParameter("pithumbsize", "700")
            .addQueryParameter("format", "json")
            .addQueryParameter("origin", "*")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GPS3D-AR-David/0.10")
            .build()

        placeImageCall = http.newCall(request)
        placeImageCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val root = JSONObject(it.body?.string().orEmpty())
                    val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return
                    val keys = pages.keys()
                    if (!keys.hasNext()) return
                    val page = pages.optJSONObject(keys.next()) ?: return
                    val imageUrl = page.optJSONObject("thumbnail")
                        ?.optString("source")
                        ?.trim()
                        .orEmpty()
                    if (imageUrl.isBlank()) return
                    loadPreviewBitmap(imageUrl, result.lat, result.lon)
                }
            }
        })
    }

    private fun loadPreviewBitmap(url: String, lat: Double, lon: Double) {
        val request = Request.Builder().url(url).build()
        placeImageCall = http.newCall(request)
        placeImageCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val bytes = it.body?.bytes() ?: return
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                    ui.post {
                        val selected = selectedMapPoint ?: return@post
                        if (distanceMeters(selected.lat, selected.lon, lat, lon) > 2.0) return@post
                        placePreviewImage.setImageBitmap(bitmap)
                        placePreviewImage.visibility = View.VISIBLE
                    }
                }
            }
        })
    }

    private fun requestRoute(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, isReroute: Boolean = false,
        avoidOverride: Boolean? = null
    ) {
        if (!isReroute && routeDestination?.let { distanceMeters(it.lat,it.lon,toLat,toLon)>50 } != false) {
            enteredTollPlazas.clear(); passedTollPlazas.clear()
        }
        val generation = ++routeGeneration
        val requestedPedestrian = pedestrianRoute
        val requestedAvoid = (avoidOverride ?: avoidTolls) && !requestedPedestrian
        pendingAvoidTolls = avoidOverride
        rerouting = true
        instruction.text = if (requestedPedestrian) "Calculando ruta para caminar…" else if (requestedAvoid) "Calculando ruta sin casetas…" else if (isReroute) "Recalculando ruta…" else "Calculando ruta con tráfico…"
        turnIcon.text = "…"
        hideSearchSuggestions(); hidePlacePreview(); hideKeyboard()
        routeCall?.cancel()
        updateTollPanel()

        fun fail(message: String) {
            ui.post {
                if (isDestroyed || generation != routeGeneration) return@post
                rerouting = false
                pendingAvoidTolls = null
                updateTollPanel()
                instruction.text = message
                turnIcon.text = "!"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        val key = tomTomApiKey()
        if (key.isBlank()) { fail("El servicio de rutas no está configurado"); return }
        val builder = HttpUrl.Builder().scheme("https").host("api.tomtom.com")
            .addPathSegment("routing").addPathSegment("1").addPathSegment("calculateRoute")
            .addPathSegment("$fromLat,$fromLon:$toLat,$toLon").addPathSegment("json")
            .addQueryParameter("key", key).addQueryParameter("traffic", if (requestedPedestrian) "false" else "true")
            .addQueryParameter("routeType", if (requestedPedestrian) "shortest" else "fastest")
            .addQueryParameter("travelMode", if (requestedPedestrian) "pedestrian" else "car")
            .addQueryParameter("routeRepresentation", "polyline").addQueryParameter("instructionsType", "text")
            .addQueryParameter("language", "es-ES").addQueryParameter("computeTravelTimeFor", "all")
            .addQueryParameter("sectionType", "traffic").addQueryParameter("sectionType", "toll")
            .addQueryParameter("sectionType", "tollVignette")
            .addQueryParameter("maxAlternatives", if (isReroute && avoidOverride == null) "0" else "2")
        if (requestedAvoid) builder.addQueryParameter("avoid", "tollRoads")
        if (requestedPedestrian) builder.addQueryParameter("sectionType", "travelMode")
        rawLocation?.takeIf { !requestedPedestrian && it.hasBearing() && it.speed > 1.0f }?.let {
            builder.addQueryParameter("vehicleHeading", (((it.bearing % 360f) + 360f) % 360f).toInt().toString())
        }
        val url = builder.build()
        fun fetch(excluded: List<TollCatalog.Plaza> = emptyList()) {
            val request = Request.Builder().url(url).header("User-Agent", "GPS3D-AR-David/0.22")
            if (excluded.isNotEmpty()) {
                val rectangles = JSONArray(excluded.distinctBy { it.id }.take(100).map { plaza ->
                    // A small exclusion surrounds the booth rather than the whole avenue.
                    val latRadius = 20.0 / 111320.0
                    val lonRadius = latRadius / cos(Math.toRadians(plaza.point.lat))
                    JSONObject().put("southWestCorner", JSONObject()
                        .put("latitude", plaza.point.lat - latRadius).put("longitude", plaza.point.lon - lonRadius))
                        .put("northEastCorner", JSONObject()
                            .put("latitude", plaza.point.lat + latRadius).put("longitude", plaza.point.lon + lonRadius))
                })
                request.post(JSONObject().put("avoidAreas", JSONObject().put("rectangles", rectangles))
                    .toString().toRequestBody("application/json".toMediaType()))
            }
            routeCall = http.newCall(request.build())
            routeCall?.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) fail("No se pudo consultar la ruta. Revisa la conexión.")
                }
                override fun onResponse(call: Call, response: Response) {
                    val crossed = mutableListOf<TollCatalog.Plaza>()
                    val parsed = response.use { r ->
                        if (!r.isSuccessful) null else runCatching {
                            val routes = JSONObject(r.body?.string().orEmpty()).getJSONArray("routes")
                            (0 until min(3, routes.length())).mapNotNull { index ->
                                val route = routes.optJSONObject(index) ?: return@mapNotNull null
                                val points = parseTomTomRoutePoints(route)
                                if (points.size < 2) return@mapNotNull null
                                val summary = route.optJSONObject("summary") ?: return@mapNotNull null
                                val duration = summary.optDouble("travelTimeInSeconds", 0.0)
                                val live = summary.optDouble("liveTrafficIncidentsTravelTimeInSeconds", duration)
                                val sections = route.optJSONArray("sections") ?: JSONArray()
                                if (requestedPedestrian && (0 until sections.length()).any {
                                    val section = sections.optJSONObject(it)
                                    section?.optString("sectionType") == "TRAVEL_MODE" &&
                                        section.optString("travelMode", "pedestrian") != "pedestrian"
                                }) return@mapNotNull null
                                val known = if (requestedPedestrian) emptyList() else tollRepository.crossedPlazas(
                                    points.map { RouteGeometry.Point(it.lat, it.lon) })
                                crossed.addAll(known)
                                val hasTolls = known.isNotEmpty() || (0 until sections.length()).any {
                                    sections.optJSONObject(it)?.optString("sectionType") in listOf("TOLL", "TOLL_ROAD", "TOLL_VIGNETTE")
                                }
                                RouteOption(points, parseTomTomSteps(route, points),
                                    if (live > 0.0) live else duration, summary.optDouble("lengthInMeters", 0.0),
                                    summary.optDouble("trafficDelayInSeconds", 0.0),
                                    summary.optDouble("noTrafficTravelTimeInSeconds", duration), hasTolls, requestedAvoid,
                                    (0 until sections.length()).mapNotNull { j ->
                                        val section=sections.optJSONObject(j) ?: return@mapNotNull null
                                        if (section.optString("sectionType") !in listOf("TOLL", "TOLL_ROAD", "TOLL_VIGNETTE")) return@mapNotNull null
                                        section.optInt("startPointIndex",0)..section.optInt("endPointIndex",points.lastIndex)
                                    }, pedestrian = requestedPedestrian)
                            }
                        }.getOrNull()
                    }
                    if (call.isCanceled() || generation != routeGeneration) return
                    val acceptable = parsed.orEmpty().filter { !requestedAvoid || !it.hasTolls }
                    if (acceptable.isEmpty()) {
                        if (requestedAvoid && excluded.isEmpty() && crossed.isNotEmpty()) {
                            // Some provider routes omit toll metadata. Ask for a new path around
                            // the actual plazas instead of hiding their markers on the old path.
                            ui.post {
                                if (!isDestroyed && generation == routeGeneration) fetch(crossed)
                            }
                            return
                        }
                        fail(if (requestedAvoid) "No se encontró una ruta disponible sin casetas" else "No se encontró una ruta disponible")
                        return
                    }
                    ui.post {
                        if (isDestroyed || generation != routeGeneration) return@post
                        routeDestination = GeoPoint(toLat, toLon)
                        routeAlternatives = acceptable
                        activeRouteIndex = 0
                        rerouting = false; pendingAvoidTolls = null; offRouteSinceMs = 0L
                        activateRoute(0, recenterMap = !isReroute)
                        if (isReroute) runCatching { rerouteTone.startTone(ToneGenerator.TONE_PROP_BEEP, 180) }
                    }
                }
            })
        }
        fetch()
    }

    private fun parseTomTomRoutePoints(route: JSONObject): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        val legs = route.optJSONArray("legs") ?: return out
        for (l in 0 until legs.length()) {
            val leg = legs.optJSONObject(l) ?: continue
            val points = leg.optJSONArray("points") ?: continue
            for (i in 0 until points.length()) {
                val p = points.optJSONObject(i) ?: continue
                val lat = p.optDouble("latitude", Double.NaN)
                val lon = p.optDouble("longitude", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                // Preserve provider indices, including duplicated leg endpoints.
                out.add(GeoPoint(lat, lon))
            }
        }
        return out
    }

    private fun parseTomTomSteps(route: JSONObject, points: List<GeoPoint>): List<NavStep> {
        val out = ArrayList<NavStep>()
        val instructions = route.optJSONObject("guidance")
            ?.optJSONArray("instructions")
            ?: return out

        val geometry = NavigationRoutePosition(points.map { RouteGeometry.Point(it.lat,it.lon) })
        for (i in 0 until instructions.length()) {
            val item = instructions.optJSONObject(i) ?: continue
            val point = item.optJSONObject("point") ?: continue
            val lat = point.optDouble("latitude", Double.NaN)
            val lon = point.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue

            val maneuver = item.optString("maneuver", "STRAIGHT")
            val message = item.optString("message")
                .ifBlank { instructionForTomTomManeuver(maneuver, item.optString("street")) }
            val reportedIndex = item.optInt("pointIndex", -1)
            val routeIndex = if (reportedIndex in points.indices) {
                reportedIndex
            } else {
                nearestRoutePointIndex(points, lat, lon)
            }

            out.add(
                NavStep(
                    lat = lat,
                    lon = lon,
                    instruction = message,
                    icon = iconForTomTomManeuver(maneuver),
                    routeIndex = routeIndex,
                    maneuver = maneuver,
                    alongMeters = geometry.instructionAlong(RouteGeometry.Point(lat,lon), reportedIndex,
                        item.optDouble("routeOffsetInMeters",Double.NaN))
                )
            )
        }
        return out
    }

    private fun instructionForTomTomManeuver(maneuver: String, street: String): String {
        val road = street.ifBlank { "la vía" }
        return when (maneuver) {
            "TURN_LEFT", "BEAR_LEFT", "SHARP_LEFT" -> "Gira a la izquierda en " + road
            "TURN_RIGHT", "BEAR_RIGHT", "SHARP_RIGHT" -> "Gira a la derecha en " + road
            "KEEP_LEFT", "MOTORWAY_EXIT_LEFT" -> "Mantente a la izquierda hacia " + road
            "KEEP_RIGHT", "MOTORWAY_EXIT_RIGHT" -> "Mantente a la derecha hacia " + road
            "MAKE_UTURN", "TRY_MAKE_UTURN" -> "Da vuelta en U"
            "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT", "ROUNDABOUT_CROSS", "ROUNDABOUT_BACK" ->
                "En la glorieta continúa hacia " + road
            "TAKE_EXIT", "ENTRANCE_RAMP" -> "Toma la salida hacia " + road
            "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT" -> "Llegaste a tu destino"
            else -> "Continúa por " + road
        }
    }

    private fun iconForTomTomManeuver(maneuver: String): String = when {
        maneuver.startsWith("ARRIVE") -> "🏁"
        maneuver.contains("LEFT") -> "↰"
        maneuver.contains("RIGHT") -> "↱"
        maneuver.contains("UTURN") -> "↶"
        maneuver.startsWith("ROUNDABOUT") -> "↻"
        else -> "↑"
    }
    private fun activateRoute(index: Int, recenterMap: Boolean) {
        val option = routeAlternatives.getOrNull(index) ?: return
        activeRouteIndex = index
        pedestrianRoute = option.pedestrian
        if (!pedestrianRoute) {
            avoidTolls = option.avoidsTolls
            getPreferences(MODE_PRIVATE).edit().putBoolean("avoidTolls", avoidTolls).apply()
        }
        routePoints = option.points
        navigationGeometry = NavigationRoutePosition(routePoints.map { RouteGeometry.Point(it.lat,it.lon) })
        navSteps = option.steps.map { step -> if (step.alongMeters.isFinite()) step else step.copy(
            alongMeters = navigationGeometry!!.instructionAlong(RouteGeometry.Point(step.lat,step.lon),step.routeIndex)) }
        currentStepIndex = navSteps.indexOfFirst { !it.maneuver.startsWith("DEPART") }.coerceAtLeast(0)
        routeDurationSeconds = option.durationSeconds
        routeDistanceMeters = option.distanceMeters
        routeActive = routePoints.isNotEmpty()
        hideSearchSuggestions()
        hideKeyboard()
        updateSearchChrome()
        announcements.reset(); voiceRouteId++
        routeProgressIndex = 0
        lastRenderedProgressIndex = -1
        lastProgressRenderAtMs = 0L
        lastRouteMatch = null
        offRouteSinceMs = 0L
        rebuildRouteDistanceCache()
        lastMatchAtNanos = 0L
        (rawLocation ?: displayLocation)?.let { location ->
            val match = findRouteMatch(predictLocation(location,location))
            if (match != null && match.distanceMeters < 25.0) {
                lastRouteMatch = match; routeProgressIndex = match.segmentIndex
                displayLocation = snapToRoute(location,match)
            }
        }

        syncRoutes()
        prepareMapAhead(true)
        syncRouteProgress(force = true)
        stopButton.visibility = if (routeActive) View.VISIBLE else View.GONE
        etaText.text = (if (pedestrianRoute) "A pie · " else "") + formatDuration(routeDurationSeconds)
        routeDistance.text = formatDistance(routeDistanceMeters)
        arrivalText.text = "Llegada aprox. ${formatArrival(routeDurationSeconds)}"
        updateNavigationStep(displayLocation)
        showRouteChoices()
        refreshTolls()
        applyPalette()
        if (viewMode == 2) jsCall("showOverview()") else if (recenterMap) recenter(true)
        if (!pedestrianRoute && !applyingCarRoute) routeDestination?.let { destination ->
            com.david.gps3dar.car.CarNavigation.setRoute(option,destination,searchInput.text.toString().ifBlank { "Destino" },"phone")
        }
    }

    private fun syncRoutes() {
        val others = routeAlternatives.indices.filter { it != activeRouteIndex }.take(2)
        val alt1 = others.getOrNull(0)?.let { routeAlternatives[it].points } ?: emptyList()
        val alt2 = others.getOrNull(1)?.let { routeAlternatives[it].points } ?: emptyList()
        jsCall("setRoutes(${pointsJson(routePoints)},${pointsJson(alt1)},${pointsJson(alt2)},$pedestrianRoute)")
        jsCall("setManeuvers(" + JSONArray(navSteps.map { JSONObject().put("index", it.routeIndex).put("maneuver", it.maneuver) }) + ")")
    }

    private fun syncRouteProgress(force: Boolean = false) {
        if (!routeActive || routePoints.size < 2) return
        val match = lastRouteMatch
        val index = routeProgressIndex.coerceIn(0, routePoints.lastIndex - 1)
        val p = if (match != null && match.segmentIndex >= index - 1) {
            GeoPoint(match.lat, match.lon)
        } else {
            routePoints[index]
        }
        val now = SystemClock.elapsedRealtime()
        if (!force &&
            index == lastRenderedProgressIndex &&
            now - lastProgressRenderAtMs < 250L
        ) return

        lastRenderedProgressIndex = index
        lastProgressRenderAtMs = now
        jsCall("setRouteProgress(${num(p.lon)},${num(p.lat)},$index)")
    }

    private fun updateRouteProgress(match: RouteMatch) {
        if (match.distanceMeters > 25.0) return
        routeProgressIndex = match.segmentIndex
        syncRouteProgress()
        syncTolls()
        prepareMapAhead()
    }

    private fun rebuildRouteDistanceCache() {
        routeRemainingFromIndex = DoubleArray(routePoints.size)
        if (routePoints.size < 2) return
        for (i in routePoints.lastIndex - 1 downTo 0) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            routeRemainingFromIndex[i] =
                routeRemainingFromIndex[i + 1] + distanceMeters(a.lat, a.lon, b.lat, b.lon)
        }
    }

    private fun showRouteChoices() {
        routeChoices.removeAllViews()
        if (routeAlternatives.size < 2) {
            routeChoices.visibility = View.GONE
            return
        }

        routeAlternatives.take(3).forEachIndexed { index, option ->
            val card = TextView(this).apply {
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                params.setMargins(if (index == 0) 0 else dp(3), 0, if (index == routeAlternatives.lastIndex) 0 else dp(3), 0)
                layoutParams = params
                background = ContextCompat.getDrawable(this@RealisticMapActivity, R.drawable.search_panel)
                elevation = dp(4).toFloat()
                gravity = android.view.Gravity.CENTER
                setPadding(dp(5), dp(8), dp(5), dp(8))
                text = if (index == activeRouteIndex) {
                    "✓ ${formatDuration(option.durationSeconds)}\n${formatDistance(option.distanceMeters)}"
                } else {
                    "Ruta ${index + 1} · ${formatDuration(option.durationSeconds)}\n${formatDistance(option.distanceMeters)}"
                }
                textSize = 12f
                setTextColor(if (index == activeRouteIndex) Color.rgb(22, 105, 216) else Color.rgb(56, 64, 72))
                setOnClickListener { activateRoute(index, recenterMap = false) }
            }
            routeChoices.addView(card)
        }
        routeChoices.visibility = View.VISIBLE
    }

    private fun parseSteps(route: JSONObject): List<NavStep> {
        val out = ArrayList<NavStep>()
        val legs = route.optJSONArray("legs") ?: return out
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val maneuver = step.optJSONObject("maneuver") ?: continue
                val loc = maneuver.optJSONArray("location") ?: continue
                val type = maneuver.optString("type", "turn")
                val modifier = maneuver.optString("modifier", "straight")
                val name = step.optString("name", "").ifBlank { "la vía" }
                out.add(
                    NavStep(
                        lat = loc.getDouble(1),
                        lon = loc.getDouble(0),
                        instruction = buildInstruction(type, modifier, name),
                        icon = iconFor(type, modifier)
                    )
                )
            }
        }
        return out
    }

    private fun buildInstruction(type: String, modifier: String, name: String): String = when (type) {
        "arrive" -> "Llegaste a tu destino"
        "depart" -> "Continúa por $name"
        "roundabout", "rotary" -> "En la glorieta, continúa hacia $name"
        else -> when {
            modifier.contains("left") -> "Gira a la izquierda en $name"
            modifier.contains("right") -> "Gira a la derecha en $name"
            modifier == "uturn" -> "Da vuelta en U hacia $name"
            else -> "Continúa por $name"
        }
    }

    private fun iconFor(type: String, modifier: String): String = when {
        type == "arrive" -> "●"
        type == "roundabout" || type == "rotary" -> "↻"
        modifier.contains("left") -> "↰"
        modifier.contains("right") -> "↱"
        modifier == "uturn" -> "↶"
        else -> "↑"
    }

    private fun updateNavigationStep(location: Location?) {
        if (rerouting) return
        if (!routeActive || navSteps.isEmpty() || location == null) {
            if (!routeActive) {
                instruction.text = "Busca un destino o mantén pulsado el mapa"
                turnIcon.text = "↑"
                turnDistance.text = "GPS3D · mapa de conducción"
                turnDistance.textSize = 13f
                turnDistance.setTypeface(null, Typeface.NORMAL)
            }
            return
        }

        // A maneuver that is already behind the matched route progress must never remain
        // on screen. This is especially important after rerouting or joining a route midway.
        while (
            currentStepIndex < navSteps.lastIndex &&
            lastRouteMatch?.let { it.distanceMeters <= 25.0 && navSteps[currentStepIndex].alongMeters < it.alongMeters - 3.0 } == true
        ) {
            currentStepIndex++
        }

        var step = navSteps[currentStepIndex.coerceIn(0, navSteps.lastIndex)]
        var distance = distanceAlongRouteToStep(step, location)

        val arriving = step.icon == "🏁" || step.icon == "●"
        val message = if (arriving && distance > 25.0) {
            "Continúa hasta tu destino"
        } else step.instruction
        instruction.text = message
        turnIcon.text = step.icon
        turnDistance.text = "En ${formatDistance(distance)}"
        turnDistance.textSize = 32f
        turnDistance.setTypeface(null, Typeface.BOLD)
        turnDistance.setTextColor(Color.WHITE)

        if (voiceEnabled && ttsReady && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            val id = "$voiceRouteId:$currentStepIndex"
            val phase = announcements.pending(id, distance, (rawLocation?.speed ?: location.speed).toDouble())
            if (phase > 0 && audioManager.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                val phrase = if (phase == 3 && !arriving) "Ahora, $message" else "En ${formatDistance(distance)}, $message"
                spokenUtterance = "$id:$phase"
                if (tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, spokenUtterance) == TextToSpeech.SUCCESS) announcements.accepted(id, phase)
            }
        }
    }

    private fun nearestRoutePointIndex(points: List<GeoPoint>, lat: Double, lon: Double): Int {
        if (points.isEmpty()) return 0
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for (i in points.indices) {
            val p = points[i]
            val d = distanceMeters(lat, lon, p.lat, p.lon)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun distanceAlongRouteToStep(step: NavStep, location: Location): Double {
        if (routePoints.size < 2 || routeRemainingFromIndex.isEmpty()) {
            return distanceMeters(location.latitude, location.longitude, step.lat, step.lon)
        }

        val match = lastRouteMatch
        return if (match != null && match.distanceMeters <= 25.0 && step.alongMeters.isFinite()) {
            navigationGeometry!!.distanceToTurn(step.alongMeters,match.alongMeters)
        } else distanceMeters(location.latitude,location.longitude,step.lat,step.lon)
    }

    private fun stopNavigation() {
        if (!applyingCarRoute) com.david.gps3dar.car.CarNavigation.stop("phone")
        routeActive = false
        pedestrianRoute = false
        routeBeforeAr = null; arDestination = null; navigationGeometry = null; lastMatchAtNanos = 0L
        updateSearchChrome()
        enteredTollPlazas.clear(); passedTollPlazas.clear()
        routeGeneration++
        tollRepository.cancel()
        tollQuote = null
        tollProgressDistances = DoubleArray(0)
        tollLookupPending = false
        pendingExternalDestination = null
        pendingExternalQuery = null
        externalLinkCall?.cancel()
        destinationSearch.cancel()
        viewMode = 0
        is3D = false
        routeDestination = null
        routeCall?.cancel()
        rerouting = false
        pendingAvoidTolls = null
        routePoints = emptyList()
        navSteps = emptyList()
        routeAlternatives = emptyList()
        activeRouteIndex = 0
        currentStepIndex = 0
        routeDistanceMeters = 0.0
        routeDurationSeconds = 0.0
        routeProgressIndex = 0
        lastRenderedProgressIndex = -1
        routeRemainingFromIndex = DoubleArray(0)
        lastRouteMatch = null
        offRouteSinceMs = 0L
        announcements.reset(); voiceRouteId++
        mapCache.cancel()
        findViewById<View>(R.id.mapCacheStatus).visibility = View.GONE
        tts.stop()
        audioManager.abandonAudioFocusRequest(audioFocus)

        syncRoutes()
        routeChoices.visibility = View.GONE
        stopButton.visibility = View.GONE
        syncTolls()
        updateTollPanel()
        syncVisualSettings()
        etaText.text = "Sin ruta activa"
        routeDistance.text = "Selecciona un destino para comenzar"
        arrivalText.text = ""
        updateNavigationStep(displayLocation)
        hidePlacePreview()
        hideKeyboard()
        recenter(true)
    }

    private fun snapToRoute(location: Location, match: RouteMatch?): Location {
        if (match == null) return location
        val maxSnap = max(
            8.0,
            min(24.0, (if (location.hasAccuracy()) location.accuracy else 10f) * 1.2)
        )
        return if (match.distanceMeters <= maxSnap) {
            Location(location).apply {
                latitude = match.lat
                longitude = match.lon
            }
        } else {
            location
        }
    }

    private fun findRouteMatch(location: Location): RouteMatch? {
        val geometry = navigationGeometry ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsed = if (lastMatchAtNanos == 0L) 0.0 else (now-lastMatchAtNanos)/1_000_000_000.0
        val previous = lastRouteMatch?.takeIf { it.distanceMeters <= 25.0 && elapsed < 8.0 }
        val heading = location.bearing.toDouble().takeIf { location.hasBearing() && location.speed > 1.0f &&
            (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees < 45f) }
        val match = geometry.match(RouteGeometry.Point(location.latitude,location.longitude), heading,
            previous?.alongMeters, location.speed.toDouble(), elapsed, location.accuracy.toDouble()) ?: return null
        lastMatchAtNanos = now
        return RouteMatch(match.segment,match.point.lat,match.point.lon,match.distance,match.bearing,match.along)
    }

    private fun maybeReroute(location: Location, match: RouteMatch?) {
        val destination = routeDestination ?: return
        if (rerouting || !routeActive) return

        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 18.0
        if (accuracy > 40.0) {
            offRouteSinceMs = 0L
            return
        }

        val speed = rawLocation?.speed ?: 0f
        val distanceThreshold = max(22.0, min(45.0, accuracy * 1.8))
        val bearingMismatch = if (
            match != null &&
            rawLocation?.hasBearing() == true &&
            speed > 4.5f
        ) {
            angularDifference(rawLocation!!.bearing.toDouble(), match.segmentBearing)
        } else {
            0.0
        }

        val clearlyOffRoute = match == null ||
            match.distanceMeters > distanceThreshold ||
            (speed > 4.5f && match.distanceMeters > 13.0 && bearingMismatch > 72.0)

        val now = SystemClock.elapsedRealtime()
        if (!clearlyOffRoute) {
            offRouteSinceMs = 0L
            return
        }

        if (offRouteSinceMs == 0L) {
            offRouteSinceMs = now
            return
        }

        val confirmationMs = if (speed > 8f) 1_600L else 2_400L
        if (now - offRouteSinceMs < confirmationMs || now - lastRerouteAtMs < 6_500L) return

        lastRerouteAtMs = now
        offRouteSinceMs = 0L
        requestRoute(
            location.latitude,
            location.longitude,
            destination.lat,
            destination.lon,
            isReroute = true
        )
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angularDifference(a: Double, b: Double): Double =
        abs((a - b + 540.0) % 360.0 - 180.0)

    private fun projectToSegment(
        lat: Double,
        lon: Double,
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Pair<Double, Double> {
        val refLat = Math.toRadians((lat1 + lat2 + lat) / 3.0)
        val metersLon = 111320.0 * cos(refLat)
        val metersLat = 110540.0
        val px = (lon - lon1) * metersLon
        val py = (lat - lat1) * metersLat
        val bx = (lon2 - lon1) * metersLon
        val by = (lat2 - lat1) * metersLat
        val denom = bx * bx + by * by
        val t = if (denom < 0.001) 0.0 else ((px * bx + py * by) / denom).coerceIn(0.0, 1.0)
        return Pair(lat1 + (lat2 - lat1) * t, lon1 + (lon2 - lon1) * t)
    }

    private fun estimateRemainingDistance(location: Location): Double {
        if (routePoints.size < 2) return routeDistanceMeters

        val match = lastRouteMatch
        return if (match != null && match.distanceMeters <= 25.0) {
            ((navigationGeometry?.length ?: routeDistanceMeters)-match.alongMeters).coerceAtLeast(0.0)
        } else routeRemainingFromIndex.getOrElse(routeProgressIndex) { routeDistanceMeters }
    }

    private fun refreshTolls() {
        if (pedestrianRoute) {
            tollQuote = null; tollLookupPending = false; tollProgressDistances = DoubleArray(0)
            enteredTollPlazas.clear(); passedTollPlazas.clear(); syncTolls(); updateTollPanel(); return
        }
        remainingTolls() // Preserve a passed closed-system entrance before replacing the quote.
        tollQuote = null
        tollLookupPending = true
        syncTolls()
        updateTollPanel()
        val snapshot = routePoints
        tollProgressDistances = DoubleArray(snapshot.size)
        for (i in 1 until snapshot.size) {
            val a = snapshot[i - 1]; val b = snapshot[i]
            tollProgressDistances[i] = tollProgressDistances[i - 1] + RouteGeometry.distance(
                RouteGeometry.Point(a.lat, a.lon), RouteGeometry.Point(b.lat, b.lon))
        }
        val option = routeAlternatives.getOrNull(activeRouteIndex) ?: return
        tollRepository.load(snapshot.map { RouteGeometry.Point(it.lat, it.lon) }, option.hasTolls, option.tollSections, enteredTollPlazas.toList()) { quote ->
            ui.post {
                if (isDestroyed || !routeActive || routePoints !== snapshot) return@post
                tollLookupPending = false
                tollQuote = quote
                syncTolls()
                updateTollPanel()
            }
        }
    }

    private fun remainingTolls(): List<TollRepository.Booth> {
        // Use the same distance model that projects each booth, so a long route does
        // not mark a plaza as passed early because navigation uses another metric.
        val match = lastRouteMatch
        val segment = max(routeProgressIndex, match?.segmentIndex ?: 0)
            .coerceIn(0, (routePoints.lastIndex - 1).coerceAtLeast(0))
        var traveled = tollProgressDistances.getOrElse(segment) { 0.0 }
        if (match != null && routePoints.size > segment + 1) {
            val a = routePoints[segment]; val b = routePoints[segment + 1]
            traveled += RouteGeometry.project(listOf(RouteGeometry.Point(a.lat, a.lon),
                RouteGeometry.Point(b.lat, b.lon)), RouteGeometry.Point(match.lat, match.lon))?.along ?: 0.0
        }
        val booths=tollQuote?.booths.orEmpty()
        for (booth in booths.filter { it.alongMeters+35.0<traveled }) {
            if (!passedTollPlazas.add(booth.id)) continue
            if (booth.role=="Entrada") enteredTollPlazas.add(booth.id)
            else if (booth.role=="Salida") enteredTollPlazas.clear()
        }
        return booths.filter { it.alongMeters + 35.0 >= traveled }
    }

    private fun syncTolls() {
        val json = remainingTolls().joinToString(prefix = "[", postfix = "]") { "[${num(it.lon)},${num(it.lat)}]" }
        jsCall("setTolls($json)")
    }

    private fun updateTollPanel() {
        avoidTollsButton.isEnabled = !pedestrianRoute && !rerouting
        if (pedestrianRoute) {
            tollCount.text = "Ruta\npeatonal"; tollTotal.text = "A pie"; avoidTollsButton.text = "Caminar"
            return
        }
        avoidTollsButton.text = when (pendingAvoidTolls) {
            true -> "Buscando sin casetas…"
            false -> "Recalculando…"
            null -> if (avoidTolls) "Evitar caseta ✓" else "Evitar caseta"
        }
        avoidTollsButton.isSelected = avoidTolls
        avoidTollsButton.contentDescription = if (avoidTolls) "Evitar casetas activado. Tocar para permitir casetas" else "Evitar casetas y recalcular ruta"
        val quote = tollQuote
        val booths = remainingTolls()
        when {
            !routeActive -> { tollCount.text = "Casetas: --"; tollTotal.text = "-- MXN" }
            tollLookupPending -> { tollCount.text = "Casetas: …"; tollTotal.text = "Consultando…" }
            quote == null || !quote.coverageKnown -> { tollCount.text = "Casetas: ?"; tollTotal.text = "Tarifa\npendiente" }
            booths.isEmpty() -> { tollCount.text = "Casetas: 0"; tollTotal.text = "$0 MXN" }
            else -> {
                tollCount.text = "Casetas: ${booths.size}\ndetectadas"
                val known = booths.filter { it.carMxn != null }.sumOf { it.carMxn!! }
                val maximum = booths.filter { it.carMxn != null }.sumOf { it.maxCarMxn ?: it.carMxn!! }
                tollTotal.text = when {
                    booths.all { it.carMxn != null } && maximum > known + .001 -> String.format(Locale("es", "MX"), "$%.2f–$%.2f\nMXN estimado", known, maximum)
                    booths.all { it.carMxn != null } -> String.format(Locale("es", "MX"), "$%.2f MXN\nestimado", known)
                    known > 0 -> String.format(Locale("es", "MX"), "≥ $%.2f MXN\nfaltan tarifas", known)
                    else -> "Tarifa\npendiente"
                }
            }
        }
    }

    private fun showTollDetails() {
        val booths = remainingTolls()
        val message = if (booths.isEmpty()) {
            if (tollQuote?.coverageKnown == true) "No hay casetas pendientes en esta ruta." else "Aún no hay datos suficientes para contar o cotizar las casetas."
        } else booths.mapIndexed { index, b ->
            "${index + 1}. ${b.name}: " + (b.carMxn?.let {
                if ((b.maxCarMxn ?: it)>it+.001) String.format(Locale("es", "MX"), "$%.2f–$%.2f MXN",it,b.maxCarMxn)
                else String.format(Locale("es", "MX"), "$%.2f MXN",it)
            }?.plus(" (${b.effective})") ?: "tarifa pendiente") + "\nFuente: ${b.source ?: "sin tarifa publicada"}"
        }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("Casetas hasta tu destino")
            .setMessage(message + "\n\nAuto de 2 ejes, sin remolque. Catálogo nacional INEGI/IMT, CAPUFE y concesiones. Se conserva la fecha de cada tarifa; los cobros cerrados dependen de la entrada y salida. Actualización automática al recuperar conexión.")
            .setPositiveButton("Cerrar", null).show()
    }

    private fun setViewMode(mode: Int) {
        viewMode = mode.coerceIn(0, 2)
        is3D = viewMode == 1
        manualCameraUntilMs = 0L
        viewModeButton.contentDescription = when (viewMode) {
            0 -> "Cambiar a vista isométrica"
            1 -> "Mostrar inicio y final de la ruta"
            else -> "Volver a vista de navegación"
        }
        syncVisualSettings()
        syncViewport()
        if (viewMode == 2) jsCall("showOverview()") else recenter(true)
    }

    private fun syncViewport() {
        if (!mapReady || webMapView.height <= 0) return
        val mapPosition = IntArray(2); webMapView.getLocationInWindow(mapPosition)
        val topPosition = IntArray(2); val top = findViewById<View>(R.id.turnBanner); top.getLocationInWindow(topPosition)
        val bottomPosition = IntArray(2); val bottom = findViewById<View>(R.id.bottomPanel); bottom.getLocationInWindow(bottomPosition)
        val topFraction = ((topPosition[1] + top.height - mapPosition[1]).toDouble() / webMapView.height).coerceIn(0.0, 0.45)
        val bottomFraction = ((mapPosition[1] + webMapView.height - bottomPosition[1]).toDouble() / webMapView.height).coerceIn(0.0, 0.45)
        jsCall("setViewport(${num(topFraction)},${num(bottomFraction)})")
    }

    private fun distanceAlongRouteToIndex(targetIndex: Int): Double {
        if (routePoints.size < 2 || routeRemainingFromIndex.isEmpty()) return 0.0
        val match = lastRouteMatch
        val clampedTarget = targetIndex.coerceIn(0, routePoints.lastIndex)
        if (match == null) {
            val current = routeProgressIndex.coerceIn(0, routePoints.lastIndex)
            val currentRemaining = routeRemainingFromIndex.getOrElse(current) { 0.0 }
            val targetRemaining = routeRemainingFromIndex.getOrElse(clampedTarget) { 0.0 }
            return (currentRemaining - targetRemaining).coerceAtLeast(0.0)
        }
        val segment = max(routeProgressIndex, match.segmentIndex).coerceIn(0, routePoints.lastIndex - 1)
        val nextIndex = segment + 1
        val nextPoint = routePoints[nextIndex]
        val currentRemaining = distanceMeters(match.lat, match.lon, nextPoint.lat, nextPoint.lon) +
            routeRemainingFromIndex.getOrElse(nextIndex) { 0.0 }
        val targetRemaining = routeRemainingFromIndex.getOrElse(clampedTarget) { 0.0 }
        return (currentRemaining - targetRemaining).coerceAtLeast(0.0)
    }
    private fun pointsJson(points: List<GeoPoint>): String = points.joinToString(prefix = "[", postfix = "]") {
        "[${num(it.lon)},${num(it.lat)}]"
    }

    private fun formatDistance(meters: Double): String = when {
        meters < 950 -> "${meters.toInt().coerceAtLeast(0)} m"
        else -> String.format(Locale("es", "MX"), "%.1f km", meters / 1000.0)
    }

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private fun formatArrival(seconds: Double): String {
        val time = System.currentTimeMillis() + (seconds * 1000.0).toLong()
        return SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date(time))
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.7f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        com.david.gps3dar.car.CarNavigation.phoneVisible=true
        is3D = viewMode == 1
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webMapView.onResume()
        if (mapReady) jsCall("setTrafficPaused(false)")
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        updateAutoTheme()
        syncVisualSettings()
        recenter(false)
    }

    override fun onPause() {
        com.david.gps3dar.car.CarNavigation.phoneVisible=false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager.unregisterListener(this)
        webMapView.onPause()
        if (mapReady) jsCall("setTrafficPaused(true)")
        tts.stop()
        audioManager.abandonAudioFocusRequest(audioFocus)
        super.onPause()
    }

    override fun onDestroy() {
        com.david.gps3dar.car.CarNavigation.remove(carObserver)
        if (::signalRepository.isInitialized) signalRepository.close()
        ui.removeCallbacks(ticker)
        searchDebounce?.let { ui.removeCallbacks(it) }
        searchCall?.cancel()
        destinationSearch.cancel()
        externalLinkCall?.cancel()
        tollRepository.close()
        placePreviewCall?.cancel()
        placeImageCall?.cancel()
        routeCall?.cancel()
        fusedLocation.removeLocationUpdates(locationCallback)
        tts.stop()
        tts.shutdown()
        audioManager.abandonAudioFocusRequest(audioFocus)
        if (::connectivity.isInitialized) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        mapCache.close()
        rerouteTone.release()
        webMapView.removeJavascriptInterface("AndroidBridge")
        webMapView.loadUrl("about:blank")
        webMapView.destroy()
        super.onDestroy()
    }

    data class GeoPoint(val lat: Double, val lon: Double)
    data class NavStep(
        val lat: Double,
        val lon: Double,
        val instruction: String,
        val icon: String,
        val routeIndex: Int = 0,
        val maneuver: String = "STRAIGHT",
        val alongMeters: Double = Double.NaN
    )
    data class SearchResult(
        val label: String,
        val lat: Double,
        val lon: Double,
        val title: String = label.substringBefore(","),
        val address: String = label.substringAfter(",", "").trim()
    )
    data class RouteMatch(
        val segmentIndex: Int,
        val lat: Double,
        val lon: Double,
        val distanceMeters: Double,
        val segmentBearing: Double,
        val alongMeters: Double = 0.0
    )
    data class RouteOption(
        val points: List<GeoPoint>,
        val steps: List<NavStep>,
        val durationSeconds: Double,
        val distanceMeters: Double,
        val trafficDelaySeconds: Double = 0.0,
        val noTrafficDurationSeconds: Double = durationSeconds,
        val hasTolls: Boolean = false,
        val avoidsTolls: Boolean = false,
        val tollSections: List<IntRange> = emptyList(),
        val pedestrian: Boolean = false
    )

    companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val DEFAULT_FOLLOW_RESUME_MS = 8000L
        const val VR_MAX_SPEED_KMH = 20f
        const val DEFAULT_ZOOM_PRESET_INDEX = 2
        val ZOOM_PRESETS = doubleArrayOf(16.20, 16.80, 17.40, 18.00, 18.65)
    }
}

