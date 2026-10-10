package com.david.gps3dar.car

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.david.gps3dar.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.cos
import kotlin.math.max

data class CarSnapshot(
    val revision: Long = 0, val source: String = "phone",
    val route: RealisticMapActivity.RouteOption? = null,
    val destination: RealisticMapActivity.GeoPoint? = null, val label: String = "Destino",
    val location: Location? = null, val segment: Int = 0, val along: Double = 0.0,
    val remaining: Double = 0.0, val seconds: Double = 0.0,
    val instruction: String = "Busca un destino para comenzar", val maneuver: String = "STRAIGHT",
    val turnMeters: Double = 0.0, val pending: Boolean = false, val error: String? = null, val onRoute: Boolean = false
)

/** The phone and car consume one immutable route; neither reconstructs a different polyline. */
object CarNavigation {
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(CarSnapshot) -> Unit>()
    var state = CarSnapshot(); private set
    private var geometry: NavigationRoutePosition? = null
    private var call: Call? = null
    private var generation = 0
    private var tolls: TollRepository? = null
    private var appContext: Context? = null
    private var offRouteAt=0L;private var lastRerouteAt=0L
    private var rawLocation: Location? = null
    var phoneVisible=false
    var voiceEnabled=true
    internal var routeClient=OkHttpClient()
    fun initialize(context: Context) { appContext=context.applicationContext }
    fun observe(listener: (CarSnapshot) -> Unit) { observers.add(listener); listener(state) }
    fun remove(listener: (CarSnapshot) -> Unit) { observers.remove(listener) }
    private fun publish(snapshot: CarSnapshot) { state = snapshot; observers.toList().forEach { it(snapshot) } }
    fun setRoute(route: RealisticMapActivity.RouteOption, destination: RealisticMapActivity.GeoPoint, label: String, source: String) {
        if (route.pedestrian) return
        generation++;call?.cancel();call=null
        geometry = NavigationRoutePosition(route.points.map { RouteGeometry.Point(it.lat,it.lon) })
        val normalized=route.copy(steps=route.steps.map { step -> if(step.alongMeters.isFinite())step else step.copy(
            alongMeters=geometry!!.instructionAlong(RouteGeometry.Point(step.lat,step.lon),step.routeIndex)) })
        publish(state.copy(revision=state.revision+1,source=source,route=normalized,destination=destination,
            label=label,along=0.0,segment=0,remaining=geometry!!.length,seconds=route.durationSeconds,pending=false,error=null))
        (rawLocation ?: state.location)?.let { fix(it, force=true) }
    }
    fun stop(source: String = "car") {
        generation++;call?.cancel();geometry=null
        publish(CarSnapshot(revision=state.revision+1,source=source,location=state.location))
    }
    fun fix(location: Location, force: Boolean = false) {
        if (!force && state.location?.elapsedRealtimeNanos?.let { it >= location.elapsedRealtimeNanos } == true) return
        rawLocation=Location(location)
        val path=geometry;val route=state.route
        if (path==null || route==null) { publish(state.copy(location=Location(location)));return }
        val matched=path.match(RouteGeometry.Point(location.latitude,location.longitude),
            if(location.hasBearing() && location.speed>1) location.bearing.toDouble() else null,
            if(state.along>0) state.along else null,location.speed.toDouble(),accuracy=location.accuracy.toDouble())
        val onRoute=matched!=null && matched.distance<25
        val now=android.os.SystemClock.elapsedRealtime()
        if(onRoute)offRouteAt=0L else if(offRouteAt==0L)offRouteAt=now
        val along=if(onRoute) max(state.along,matched!!.along) else state.along
        val point=if(onRoute) matched!!.point else RouteGeometry.Point(location.latitude,location.longitude)
        val shown=Location(location).apply { latitude=point.lat;longitude=point.lon }
        val step=route.steps.firstOrNull { !it.maneuver.startsWith("DEPART") && it.alongMeters>=along-3 } ?: route.steps.lastOrNull()
        val remaining=(path.length-along).coerceAtLeast(0.0)
        publish(state.copy(location=shown,segment=matched?.segment ?: state.segment,along=along,remaining=remaining,
            seconds=route.durationSeconds*remaining/path.length.coerceAtLeast(1.0),
            instruction=step?.instruction ?: "Continúa hacia tu destino",maneuver=step?.maneuver ?: "STRAIGHT",
            turnMeters=((step?.alongMeters ?: path.length)-along).coerceAtLeast(0.0),onRoute=onRoute))
        if(!phoneVisible && !onRoute && now-offRouteAt>2400 && now-lastRerouteAt>6500 && !state.pending) {
            appContext?.let { context -> state.destination?.let { destination ->
                lastRerouteAt=now;routeTo(context,destination,state.label,route.avoidsTolls)
            } }
        }
    }
    fun key(context: Context): String = context.packageManager.getApplicationInfo(context.packageName,
        PackageManager.GET_META_DATA).metaData?.getString("com.david.gps3dar.TOMTOM_API_KEY").orEmpty()
    fun routeTo(context: Context, destination: RealisticMapActivity.GeoPoint, label: String, avoid: Boolean = state.route?.avoidsTolls ?: false) {
        initialize(context)
        val current=rawLocation ?: state.location ?: return publish(state.copy(error="Esperando ubicación GPS…"))
        val key=key(context)
        if (key.isBlank()) return publish(state.copy(error="El servicio de rutas no está configurado"))
        if(tolls==null)tolls=TollRepository(OkHttpClient(),context.assets.open("mx-tolls.json").bufferedReader().use { it.readText() })
        val id=++generation;call?.cancel();publish(state.copy(pending=true,error=null))
        val url=HttpUrl.Builder().scheme("https").host("api.tomtom.com")
            .addPathSegment("routing").addPathSegment("1").addPathSegment("calculateRoute")
            .addPathSegment("${current.latitude},${current.longitude}:${destination.lat},${destination.lon}").addPathSegment("json")
            .addQueryParameter("key",key).addQueryParameter("traffic","true").addQueryParameter("routeType","fastest")
            .addQueryParameter("travelMode","car").addQueryParameter("instructionsType","text")
            .addQueryParameter("language","es-ES").addQueryParameter("sectionType","toll")
            .addQueryParameter("sectionType","tollVignette").addQueryParameter("maxAlternatives","2")
            .apply { if(avoid)addQueryParameter("avoid","tollRoads") }.build()
        fun fail(message: String) { main.post { if(id==generation)publish(state.copy(pending=false,error=message)) } }
        fun fetch(exclusions: List<TollCatalog.Plaza> = emptyList()) {
            val request=Request.Builder().url(url)
            if(exclusions.isNotEmpty())request.post(JSONObject().put("avoidAreas",JSONObject().put("rectangles",JSONArray(
                exclusions.distinctBy { it.id }.take(100).map { plaza ->
                    val d=20.0/111320;val dx=d/cos(Math.toRadians(plaza.point.lat))
                    JSONObject().put("southWestCorner",JSONObject().put("latitude",plaza.point.lat-d).put("longitude",plaza.point.lon-dx))
                        .put("northEastCorner",JSONObject().put("latitude",plaza.point.lat+d).put("longitude",plaza.point.lon+dx))
                }))).toString().toRequestBody("application/json".toMediaType()))
            call=routeClient.newCall(request.build())
            call!!.enqueue(object : Callback {
                override fun onFailure(call: Call,e: IOException) { if(!call.isCanceled())fail("No se pudo consultar la ruta") }
                override fun onResponse(call: Call,response: Response) {
                    val crossed=mutableListOf<TollCatalog.Plaza>()
                    val routes=response.use { r -> if(!r.isSuccessful)emptyList() else runCatching {
                        val array=JSONObject(r.body!!.string()).getJSONArray("routes")
                        (0 until array.length()).mapNotNull { n ->
                            val json=array.getJSONObject(n);val legs=json.getJSONArray("legs")
                            val points=(0 until legs.length()).flatMap { l -> val p=legs.getJSONObject(l).getJSONArray("points")
                                (0 until p.length()).map { i -> p.getJSONObject(i).let {
                                    RealisticMapActivity.GeoPoint(it.getDouble("latitude"),it.getDouble("longitude")) } } }
                            if(points.size<2)return@mapNotNull null
                            val path=NavigationRoutePosition(points.map { RouteGeometry.Point(it.lat,it.lon) })
                            val known=tolls!!.crossedPlazas(path.points);crossed.addAll(known)
                            val sections=json.optJSONArray("sections") ?: JSONArray()
                            val paid=known.isNotEmpty() || (0 until sections.length()).any {
                                sections.getJSONObject(it).optString("sectionType") in listOf("TOLL","TOLL_ROAD","TOLL_VIGNETTE") }
                            if(avoid && paid)return@mapNotNull null
                            val guidance=json.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
                            val steps=(0 until guidance.length()).map { i ->
                                val item=guidance.getJSONObject(i);val p=item.getJSONObject("point");val index=item.optInt("pointIndex",0).coerceIn(points.indices)
                                RealisticMapActivity.NavStep(p.getDouble("latitude"),p.getDouble("longitude"),
                                    item.optString("message","Continúa hacia tu destino"),"↑",index,item.optString("maneuver","STRAIGHT"),
                                    path.instructionAlong(RouteGeometry.Point(p.getDouble("latitude"),p.getDouble("longitude")),index,
                                        item.optDouble("routeOffsetInMeters",Double.NaN))) }
                            val summary=json.getJSONObject("summary")
                            RealisticMapActivity.RouteOption(points,steps,summary.getDouble("travelTimeInSeconds"),
                                summary.getDouble("lengthInMeters"),hasTolls=paid,avoidsTolls=avoid)
                        }
                    }.getOrDefault(emptyList()) }
                    main.post {
                        if(id!=generation || call.isCanceled())return@post
                        if(routes.isNotEmpty())setRoute(routes.first(),destination,label,"car")
                        else if(avoid && exclusions.isEmpty() && crossed.isNotEmpty())fetch(crossed)
                        else fail(if(avoid)"No se encontró una ruta disponible sin casetas" else "No se encontró una ruta disponible")
                    }
                }
            })
        }
        fetch()
    }
}
