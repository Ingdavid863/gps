package com.david.gps3dar

import android.os.Handler
import android.os.Looper
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.ResolveAnchorOnTerrainFuture
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.Locale
import kotlin.math.*

/** Owns bounded ARCore anchors, not screen coordinates. All mutations run on the main thread. */
class GroundRouteController {
    data class Ribbon(val id: String, val anchor: Anchor, val x: Float, val y: Float,
                      val z: Float, val length: Float, val pitch: Float, val yaw: Float,
                      val visible: Boolean = true)
    data class State(val ribbons: List<Ribbon> = emptyList(), val visible: Boolean = false,
                     val manual: Boolean = false, val status: String = "Mueve el teléfono para detectar el suelo",
                     val along: Double = 0.0, val distanceFromRoute: Double = 0.0)
    private class Entry(val sample: ArRouteGeometry.Sample, var startedAt: Long) {
        var anchor: Anchor? = null
        var future: ResolveAnchorOnTerrainFuture? = null
        var failed = false
    }
    private data class ManualReference(val anchor: Anchor, val origin: RouteGeometry.Point)
    private val main = Handler(Looper.getMainLooper())
    private val entries = linkedMapOf<String, Entry>()
    private var geometry: ArRouteGeometry? = null
    private var manual: ManualReference? = null
    private var generation = 0
    private var stableSince = 0L
    private var lastState = State()
    private var terrainError = ""
    var geospatialConfigured = false
    var requestManualPlacement = false

    fun setRoute(route: WalkingRoute) {
        clear()
        geometry = ArRouteGeometry(route.points)
    }

    fun clear() {
        generation++
        entries.values.forEach { entry ->
            runCatching { entry.future?.cancel() }; runCatching { entry.anchor?.detach() }
        }
        entries.clear()
        runCatching { manual?.anchor?.detach() }
        manual = null; stableSince = 0L; terrainError = ""; requestManualPlacement = false
        lastState = State()
    }

    fun useAutomatic() { clear() }

    fun update(session: Session, frame: Frame, here: RouteGeometry.Point?, now: Long,
               width: Int, height: Int): State {
        val route = geometry ?: return State(status = "Esperando la ruta para caminar")
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            stableSince = 0L
            return lastState.copy(visible = false, status = "Seguimiento pausado · mueve el teléfono despacio y busca luz")
        }
        val earth = if (geospatialConfigured) session.earth else null
        val geo = earth?.takeIf { it.earthState == Earth.EarthState.ENABLED && it.trackingState == TrackingState.TRACKING }
            ?.cameraGeospatialPose
        val location = manual?.let { ref ->
            if (ref.anchor.trackingState != TrackingState.TRACKING) return lastState.copy(visible = false,
                status = "Anclaje manual perdido · vuelve a anclar al piso")
            val relative = ref.anchor.pose.inverse().compose(frame.camera.pose)
            ArRouteGeometry.fromOffset(ref.origin, relative.tx().toDouble(), relative.tz().toDouble())
        } ?: geo?.let { RouteGeometry.Point(it.latitude, it.longitude) } ?: here
        val match = location?.let { RouteGeometry.project(route.points, it) }
            ?: return lastState.copy(visible = false, status = "Esperando ubicación · activa ubicación precisa")

        if (requestManualPlacement) {
            requestManualPlacement = false
            val hit = floorHit(frame, width, height)
            if (hit == null) return lastState.copy(status = "No se detectó el piso · apunta al suelo y mueve el teléfono despacio")
            val direction = frame.camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
            if (hypot(direction[0].toDouble(), direction[2].toDouble()) < .35)
                return lastState.copy(status = "Levanta un poco el teléfono y apunta en el sentido del camino")
            // The user confirms the first leg's direction. We never label this as VPS localization.
            val origin = route.at(match.along)
            val next = route.at((match.along + 4.0).coerceAtMost(route.length))
            val routeHeading = ArRouteGeometry.heading(origin, next)
            val yaw = ArRouteGeometry.alignmentYaw(routeHeading, direction[0].toDouble(), direction[2].toDouble())
            val pose = Pose(floatArrayOf(frame.camera.pose.tx(), hit.hitPose.ty() + .025f, frame.camera.pose.tz()),
                floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))
            clear()
            manual = ManualReference(hit.trackable.createAnchor(pose), origin)
        }

        val isManual = manual != null
        if (!isManual && (geo == null || !ArRouteGeometry.automaticAccuracy(geo.horizontalAccuracy, geo.orientationYawAccuracy))) {
            stableSince = 0L
            val status = when {
                earth != null && earth.earthState != Earth.EarthState.ENABLED -> when (earth.earthState.name) {
                    "ERROR_NOT_AUTHORIZED" -> "Localización visual no autorizada · puedes anclar al piso manualmente"
                    "ERROR_RESOURCE_EXHAUSTED" -> "Servicio visual sin cuota · puedes anclar al piso manualmente"
                    else -> "Localización visual no disponible · puedes anclar al piso manualmente"
                }
                geo != null -> String.format(Locale("es", "MX"),
                    "Mejorando ubicación ±%.1f m / giro ±%.0f° · mira edificios o ancla al piso", geo.horizontalAccuracy, geo.orientationYawAccuracy)
                !geospatialConfigured -> "Sin localización visual · apunta en el sentido del camino y ancla al piso"
                else -> "Localizando · mira edificios alrededor o ancla al piso manualmente"
            }
            return lastState.copy(visible = false, status = status, along = match.along, distanceFromRoute = match.distance)
        }
        if (!isManual) {
            if (stableSince == 0L) stableSince = now
            if (now - stableSince < 1200L) return lastState.copy(visible = false, status = "Estabilizando la ubicación del camino…",
                along = match.along, distanceFromRoute = match.distance)
        }
        if (match.distance > 12.0 && !isManual) return lastState.copy(visible = false,
            status = "Estás fuera del recorrido · recalculando la ruta para caminar", along = match.along, distanceFromRoute = match.distance)

        // Recompute progress after manual placement, using AR's local motion rather than noisy GPS.
        val along = if (isManual) {
            val ref = requireNotNull(manual)
            val relative = ref.anchor.pose.inverse().compose(frame.camera.pose)
            RouteGeometry.project(route.points, ArRouteGeometry.fromOffset(ref.origin,
                relative.tx().toDouble(), relative.tz().toDouble()))?.along ?: match.along
        } else match.along
        val samples = route.window(along, if (isManual) 12.0 else 32.0)
        val ids = samples.map { it.id }.toSet()
        entries.keys.filter { it !in ids }.forEach { id ->
            entries.remove(id)?.let { runCatching { it.future?.cancel() }; runCatching { it.anchor?.detach() } }
        }
        samples.forEach { sample -> entries.getOrPut(sample.id) { Entry(sample, now) } }
        val floor = if (!isManual) floorHit(frame, width, height) else null
        val floorGeo = if (floor != null && earth != null && geo != null)
            runCatching { earth.getGeospatialPose(floor.hitPose) }.getOrNull() else null

        var pending = entries.values.count { it.future != null }
        for (entry in entries.values) {
            if (entry.anchor != null) continue
            if (isManual) {
                val ref = requireNotNull(manual)
                val offset = ArRouteGeometry.offset(ref.origin, entry.sample.point)
                entry.anchor = session.createAnchor(ref.anchor.pose.compose(Pose.makeTranslation(
                    offset.east.toFloat(), 0f, offset.south.toFloat())))
                continue
            }
            if (entry.future != null && now - entry.startedAt > 10000L) {
                entry.future?.cancel(); entry.future = null; entry.failed = true; pending--
            }
            // Nearby detected pavement supplies measured height if terrain data is unavailable.
            // Bound this fallback to 12 m; extrapolating a plane down a whole street is unreliable.
            if (entry.failed && floorGeo != null && entry.sample.along <= along + 12.0) {
                entry.anchor = runCatching { earth!!.createAnchor(entry.sample.point.lat, entry.sample.point.lon,
                    floorGeo.altitude + .025, 0f, 0f, 0f, 1f) }.getOrNull()
                continue
            }
            if (entry.future != null || entry.failed || pending >= 3) continue
            val epoch = generation
            entry.startedAt = now
            entry.future = runCatching { earth!!.resolveAnchorOnTerrainAsync(entry.sample.point.lat,
                entry.sample.point.lon, .025, 0f, 0f, 0f, 1f) { anchor, state ->
                main.post {
                    if (epoch != generation || entries[entry.sample.id] !== entry || entry.failed) {
                        runCatching { anchor?.detach() }
                    } else {
                        entry.future = null
                        if (state == Anchor.TerrainAnchorState.SUCCESS && anchor != null) entry.anchor = anchor
                        else {
                            runCatching { anchor?.detach() }; entry.failed = true
                            terrainError = if (state == Anchor.TerrainAnchorState.ERROR_NOT_AUTHORIZED)
                                "Terreno no autorizado" else "Terreno sin datos"
                        }
                    }
                }
            } }.getOrElse { entry.failed = true; null }
            if (entry.future != null) pending++
        }
        val old = lastState.ribbons.associateBy { it.id }
        val ribbons = samples.zipWithNext().mapNotNull { (a, b) ->
            val first = entries[a.id]?.anchor ?: return@mapNotNull null
            val second = entries[b.id]?.anchor ?: return@mapNotNull old[a.id]?.copy(visible = false)
            if (first.trackingState != TrackingState.TRACKING || second.trackingState != TrackingState.TRACKING)
                return@mapNotNull old[a.id]
            val p = first.pose.inverse().compose(second.pose)
            val horizontal = hypot(p.tx().toDouble(), p.tz().toDouble())
            val length = hypot(horizontal, p.ty().toDouble())
            if (length < .05 || length > max(2.0, (b.along - a.along) * 1.8) || abs(p.ty()) > 1.5f)
                return@mapNotNull old[a.id]?.copy(visible = false)
            Ribbon(a.id, first, p.tx() / 2, p.ty() / 2, p.tz() / 2, length.toFloat(),
                Math.toDegrees(-atan2(p.ty().toDouble(), horizontal)).toFloat(),
                Math.toDegrees(atan2(p.tx().toDouble(), p.tz().toDouble())).toFloat())
        }
        val status = when {
            isManual -> "Anclaje manual · confirma dirección y banqueta; vuelve a anclar si se desvía"
            ribbons.isEmpty() && terrainError.isNotEmpty() -> "$terrainError · detecta el piso o usa anclaje manual"
            ribbons.isEmpty() -> "Ubicación lista · colocando la línea sobre el terreno…"
            else -> String.format(Locale("es", "MX"), "Ruta a pie sobre el suelo · ubicación ±%.1f m / giro ±%.0f°",
                geo!!.horizontalAccuracy, geo.orientationYawAccuracy)
        }
        return State(ribbons, ribbons.any { it.visible }, isManual, status, along, match.distance).also { lastState = it }
    }

    private fun floorHit(frame: Frame, width: Int, height: Int): HitResult? {
        if (width <= 0 || height <= 0) return null
        return listOf(.72f, .82f, .62f).asSequence().flatMap { y ->
            frame.hitTest(width * .5f, height * y).asSequence()
        }.firstOrNull { hit ->
            val trackable = hit.trackable
            val heightBelowCamera = frame.camera.pose.ty() - hit.hitPose.ty()
            hit.distance < 8f && heightBelowCamera in .6f..3f &&
                trackable is Plane && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(hit.hitPose)
        }
    }
}
