package com.david.gps3dar.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.car.app.*
import androidx.car.app.model.*
import androidx.car.app.navigation.model.*
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.david.gps3dar.*
import java.util.TimeZone

class GpsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    override fun onCreateSession(): Session = GpsCarSession(false)
    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        GpsCarSession(sessionInfo.displayType==SessionInfo.DISPLAY_TYPE_CLUSTER)
}

private class GpsCarSession(private val cluster: Boolean) : Session() {
    companion object { private var connections=0 }
    private var surface: CarMapSurface?=null
    private var navigating=false
    private val navigationObserver: (CarSnapshot)->Unit = { state ->
        val manager=carContext.getCarService(NavigationManager::class.java)
        if(state.route!=null && !state.arrived) {
            if(!navigating) { manager.navigationStarted();navigating=true }
            val step=GpsCarScreen.step(state)
            manager.updateTrip(Trip.Builder()
                .addDestination(Destination.Builder().setName(state.label).build(),GpsCarScreen.estimate(state.remaining,state.seconds))
                .addStep(step,GpsCarScreen.estimate(state.turnMeters,
                    state.seconds*state.turnMeters/state.remaining.coerceAtLeast(1.0))).build())
        } else if(navigating) { manager.navigationEnded();navigating=false }
    }
    override fun onCreateScreen(intent: Intent): Screen {
        connections++;CarNavigation.connected=true
        CarNavigation.initialize(carContext)
        carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { CarNavigation.stop() }
        })
        CarNavigation.observe(navigationObserver)
        val map=CarMapSurface(carContext).apply { dark=carContext.isDarkMode };surface=map
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(map)
        if(ContextCompat.checkSelfPermission(carContext,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
            runCatching { ContextCompat.startForegroundService(carContext,Intent(carContext,CarLocationService::class.java)) }
                .onFailure { CarToast.makeText(carContext,"Abre GPS3D en el teléfono para activar la ubicación",CarToast.LENGTH_LONG).show() }
        } else carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION), { granted, _ ->
            if(granted.contains(Manifest.permission.ACCESS_FINE_LOCATION))
                ContextCompat.startForegroundService(carContext,Intent(carContext,CarLocationService::class.java))
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                CarNavigation.remove(navigationObserver)
                if(navigating)carContext.getCarService(NavigationManager::class.java).navigationEnded()
                surface?.close();surface=null;connections--;CarNavigation.connected=connections>0
                if(connections==0)carContext.stopService(Intent(carContext,CarLocationService::class.java))
            }
        })
        return GpsCarScreen(carContext,map,cluster)
    }
    override fun onCarConfigurationChanged(newConfig: Configuration) {
        surface?.apply { dark=carContext.isDarkMode;render(CarNavigation.state) }
    }
}

class GpsCarScreen(context: CarContext,private val map: CarMapSurface,private val cluster: Boolean = false) : Screen(context) {
    companion object {
        fun step(state: CarSnapshot): Step {
            val type=when {
                state.maneuver.contains("LEFT") -> Maneuver.TYPE_TURN_NORMAL_LEFT
                state.maneuver.contains("RIGHT") -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                state.maneuver.contains("ARRIVE") -> Maneuver.TYPE_DESTINATION
                else -> Maneuver.TYPE_STRAIGHT
            }
            return Step.Builder(if(state.pending)"Recalculando ruta…" else state.instruction)
                .setManeuver(Maneuver.Builder(type).build()).build()
        }
        fun estimate(meters: Double,seconds: Double): TravelEstimate = TravelEstimate.Builder(
            Distance.create(meters.coerceAtLeast(0.0)/1000,Distance.UNIT_KILOMETERS),
            DateTimeWithZone.create(System.currentTimeMillis()+(seconds.coerceAtLeast(0.0)*1000).toLong(),TimeZone.getDefault()))
            .setRemainingTimeSeconds(seconds.coerceAtLeast(0.0).toLong()).build()
    }
    private var error: String?=null
    private val observer: (CarSnapshot)->Unit = { snapshot ->
        if(snapshot.error!=null && error!=snapshot.error)CarToast.makeText(carContext,snapshot.error,CarToast.LENGTH_LONG).show()
        error=snapshot.error;invalidate()
    }
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { CarNavigation.observe(observer) }
            override fun onStop(owner: LifecycleOwner) { CarNavigation.remove(observer) }
        })
    }
    private fun icon(id: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext,id)).build()
    override fun onGetTemplate(): Template {
        val state=CarNavigation.state
        val actions=ActionStrip.Builder().addAction(Action.APP_ICON)
        if(!cluster) {
            actions.addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_menu_search))
                .setOnClickListener { screenManager.push(GpsCarSearch(carContext)) }.build())
            if(state.route!=null) {
                actions.addAction(Action.Builder().setTitle(if(state.route.avoidsTolls)"Permitir casetas" else "Sin casetas")
                    .setOnClickListener { if(!state.pending)state.destination?.let {
                        CarNavigation.routeTo(carContext,it,state.label,!state.route.avoidsTolls) } }.build())
                actions.addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_menu_close_clear_cancel))
                    .setOnClickListener { CarNavigation.stop() }.build())
            }
        }
        val template=NavigationTemplate.Builder().setActionStrip(actions.build())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN)
                .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_menu_mylocation)).setOnClickListener { map.recenter() }.build()).build())
        if(state.route!=null) {
            template.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(step(state),Distance.create(state.turnMeters,Distance.UNIT_METERS)).build())
            template.setDestinationTravelEstimate(estimate(state.remaining,state.seconds))
        }
        return template.build()
    }
}

private class GpsCarSearch(context: CarContext) : Screen(context) {
    private val search=DestinationSearch(okhttp3.OkHttpClient())
    private var epoch=0;private var loading=false
    private var found=emptyList<DestinationSearch.Result>()
    init { lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { epoch++;search.cancel() }
    }) }
    private fun find(query: String) {
        val id=++epoch;loading=true;invalidate()
        val bias=CarNavigation.state.location?.let { SharedDestination.Point(it.latitude,it.longitude) }
        search.search(query,CarNavigation.key(carContext),bias,true) { results, _ ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if(id==epoch) { found=results;loading=false;invalidate() }
            }
        }
    }
    override fun onGetTemplate(): Template {
        val rows=ItemList.Builder()
        found.take(6).forEach { place -> rows.addItem(Row.Builder().setTitle(place.title.ifBlank { place.label })
            .addText(place.address).setOnClickListener {
                CarNavigation.routeTo(carContext,RealisticMapActivity.GeoPoint(place.lat,place.lon),place.label)
                screenManager.pop()
            }.build()) }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) { find(searchText) }
            override fun onSearchTextChanged(searchText: String) {
                if(searchText.isBlank()) { epoch++;search.cancel();found=emptyList();loading=false;invalidate() }
            }
        }).setHeaderAction(Action.BACK).setSearchHint("¿A dónde vas?").setLoading(loading)
            .apply { if(!loading)setItemList(rows.build()) }.build()
    }
}
