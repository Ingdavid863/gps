package com.david.gps3dar.car

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.json.JSONArray

/** A live WebGL map on the car's surface; shares the phone's renderer, traffic and animation. */
class CarMapSurface(private val context: Context) : SurfaceCallback {
    private val main=Handler(Looper.getMainLooper())
    private var display: VirtualDisplay?=null
    private var presentation: Presentation?=null
    var webView: WebView?=null; private set
    private var ready=false
    private var renderedRevision=-1L
    private var height=1
    private var top=.05;private var bottom=.05
    private var zoom=17.4
    private var manualUntil=0L
    var dark=false
    private val observer: (CarSnapshot)->Unit = { render(it) }
    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surfaceContainer.surface?.let { connect(it,surfaceContainer.width,surfaceContainer.height,surfaceContainer.dpi) }
    }
    fun connect(surface: Surface,width: Int,height: Int,dpi: Int) {
        close();this.height=height.coerceAtLeast(1)
        display=context.getSystemService(DisplayManager::class.java).createVirtualDisplay("GPS3D navigation",width,height,dpi,surface,0)
        val window=Presentation(context,requireNotNull(display).display)
        presentation=window
        val web=WebView(window.context);webView=web
        web.settings.javaScriptEnabled=true;web.settings.domStorageEnabled=true
        web.settings.allowFileAccess=true;web.settings.mediaPlaybackRequiresUserGesture=false
        web.addJavascriptInterface(object {
            @JavascriptInterface fun onMapReady() { main.post { ready=true;render(CarNavigation.state) } }
            @JavascriptInterface fun onTrafficSeverity(value: String) = Unit
            @JavascriptInterface fun prepareMapViewport(w: Double,s: Double,e: Double,n: Double,z: Double) = Unit
        },"AndroidBridge")
        window.setContentView(web);window.show()
        web.loadUrl("file:///android_asset/map3d.html")
        CarNavigation.observe(observer)
    }
    private fun js(script: String) { if(ready)webView?.evaluateJavascript("window.GPS3D && GPS3D.$script",null) }
    fun render(snapshot: CarSnapshot) {
        if(!ready)return
        js("setDarkTheme($dark)");js("setViewport($top,$bottom)")
        if(renderedRevision!=snapshot.revision) {
            renderedRevision=snapshot.revision
            val points=JSONArray(snapshot.route?.points.orEmpty().map { listOf(it.lon,it.lat) })
            js("setRoutes($points,[],[],false)")
            val steps=JSONArray(snapshot.route?.steps.orEmpty().map { org.json.JSONObject().put("index",it.routeIndex).put("maneuver",it.maneuver) })
            js("setManeuvers($steps)")
        }
        snapshot.location?.let { fix ->
            val onRoute=snapshot.onRoute
            js("setLocation(${fix.longitude},${fix.latitude},${fix.bearing},${fix.speed},$onRoute)")
            if(onRoute)js("setRouteProgress(${fix.longitude},${fix.latitude},${snapshot.segment})")
            if(SystemClock.elapsedRealtime()>=manualUntil)js("follow(${fix.longitude},${fix.latitude},${fix.bearing},$zoom,0,1)")
        }
    }
    override fun onVisibleAreaChanged(rect: Rect) {
        top=(rect.top.toDouble()/height).coerceIn(0.0,.8)
        bottom=(1-rect.bottom.toDouble()/height).coerceIn(0.0,.8)
        render(CarNavigation.state)
    }
    override fun onStableAreaChanged(rect: Rect) { onVisibleAreaChanged(rect) }
    override fun onScroll(distanceX: Float,distanceY: Float) {
        manualUntil=SystemClock.elapsedRealtime()+8000;js("carPan($distanceX,$distanceY)")
    }
    override fun onScale(focusX: Float,focusY: Float,scaleFactor: Float) {
        zoom=(zoom+ kotlin.math.log2(scaleFactor.toDouble().coerceAtLeast(.01))).coerceIn(3.0,19.3)
        js("carZoom($zoom)")
    }
    fun recenter() { manualUntil=0;render(CarNavigation.state) }
    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) { close() }
    fun close() {
        CarNavigation.remove(observer);ready=false;renderedRevision=-1
        webView?.removeJavascriptInterface("AndroidBridge");webView?.destroy();webView=null
        presentation?.dismiss();presentation=null;display?.release();display=null
    }
}
