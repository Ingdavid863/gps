package com.david.gps3dar

import android.os.Handler
import android.os.Looper
import com.google.ar.core.*
import java.util.Locale
import kotlin.math.*

/** Owns anchors independently of Compose. Removing a mesh must never detach a live route anchor. */
class GroundRouteController {
    data class Ribbon(val id: String, val pose: Pose, val x: Float, val y: Float,
                      val z: Float, val length: Float, val pitch: Float, val yaw: Float)
    data class State(val ribbons: List<Ribbon> = emptyList(), val visible: Boolean = false,
                     val manual: Boolean = false, val status: String = "Mueve el teléfono para detectar el suelo",
                     val along: Double = 0.0, val distanceFromRoute: Double = 0.0,
                     val location: RouteGeometry.Point? = null, val geographicFrame: Pose? = null)
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
    private var lastGoodAt = 0L
    private var lastState = State()
    private var floorAltitude: Double? = null
    private var terrainError = ""
    var geospatialConfigured = false
    var requestManualPlacement = false

    fun setRoute(route: WalkingRoute) {
        if (geometry?.points == route.points) return
        // A network refresh or actual reroute changes the polyline, not the user's world alignment.
        clearTerrain()
        geometry = ArRouteGeometry(route.points)
        lastState = State(manual = manual != null)
    }

    private fun clearTerrain() {
        generation++
        entries.values.forEach {
            runCatching { it.future?.cancel() }; runCatching { it.anchor?.detach() }
        }
        entries.clear(); terrainError = ""
    }

    fun clear() {
        clearTerrain()
        runCatching { manual?.anchor?.detach() }
        manual = null; stableSince = 0L; lastGoodAt = 0L; floorAltitude = null
        requestManualPlacement = false; lastState = State()
    }

    fun useAutomatic() { clear() }

    fun update(session: Session, frame: Frame, here: RouteGeometry.Point?, now: Long,
               width: Int, height: Int): State {
        val route = geometry ?: return State(status = "Esperando la ruta para caminar")
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return lastState.copy(visible = false,
                status = "Seguimiento pausado · mueve el teléfono despacio y busca luz")
        }
        val earth = if (geospatialConfigured) session.earth else null
        val geo = earth?.takeIf { it.earthState == Earth.EarthState.ENABLED &&
            it.trackingState == TrackingState.TRACKING }?.cameraGeospatialPose
        var location = manualLocation(frame) ?: geo?.let { RouteGeometry.Point(it.latitude, it.longitude) } ?: here
            ?: return lastState.copy(visible = false, status = "Esperando ubicación · activa ubicación precisa")
        var match = RouteGeometry.project(route.points, location)
            ?: return lastState.copy(visible = false, status = "Esperando el recorrido")

        if (requestManualPlacement) {
            requestManualPlacement = false
            val hit = floorHit(frame, width, height)
                ?: return lastState.copy(status = "No se detectó el piso · apunta al suelo y mueve el teléfono despacio")
            val direction = frame.camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))
            if (hypot(direction[0].toDouble(), direction[2].toDouble()) < .35)
                return lastState.copy(status = "Levanta un poco el teléfono y apunta en el sentido del camino")
            val origin = route.at(match.along)
            val next = route.at((match.along + 4.0).coerceAtMost(route.length))
            val yaw = ArRouteGeometry.alignmentYaw(ArRouteGeometry.heading(origin, next),
                direction[0].toDouble(), direction[2].toDouble())
            val pose = Pose(floatArrayOf(frame.camera.pose.tx(), hit.hitPose.ty() + .055f, frame.camera.pose.tz()),
                floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))
            clearTerrain()
            runCatching { manual?.anchor?.detach() }
            manual = ManualReference(session.createAnchor(pose), origin)
            location = manualLocation(frame) ?: origin
            match = RouteGeometry.project(route.points, location) ?: match
        }

        val isManual = manual != null
        val ref = manual
        if (isManual && ref!!.anchor.trackingState != TrackingState.TRACKING)
            return lastState.copy(visible = false, status = "Anclaje manual perdido · vuelve a anclar al piso")
        if (!isManual) {
            val accurate = geo != null && ArRouteGeometry.automaticAccuracy(geo.horizontalAccuracy, geo.orientationYawAccuracy)
            // Hysteresis prevents a single noisy GPS/VPS frame from erasing an already aligned street.
            val maintaining = geo != null && lastState.visible && geo.horizontalAccuracy <= 8.0 &&
                geo.orientationYawAccuracy <= 18.0
            if (!accurate && !maintaining) {
                val status = when {
                    earth != null && earth.earthState != Earth.EarthState.ENABLED ->
                        "Localización visual no disponible · puedes anclar al piso manualmente"
                    geo != null -> String.format(Locale("es", "MX"),
                        "Mejorando ubicación ±%.1f m / giro ±%.0f° · mira edificios o ancla al piso",
                        geo.horizontalAccuracy, geo.orientationYawAccuracy)
                    !geospatialConfigured -> "Sin localización visual · apunta en el sentido del camino y ancla al piso"
                    else -> "Localizando · mira edificios alrededor o ancla al piso manualmente"
                }
                if (lastGoodAt != 0L && now - lastGoodAt < 4000L && lastState.visible)
                    return lastState.copy(status = "Manteniendo la última alineación · mejorando ubicación")
                stableSince = 0L
                return lastState.copy(visible = false, status = status,
                    along = match.along, distanceFromRoute = match.distance, location = location)
            }
            if (stableSince == 0L) stableSince = now
            if (!lastState.visible && now - stableSince < 1200L)
                return lastState.copy(visible = false, status = "Estabilizando la ubicación del camino…",
                    along = match.along, distanceFromRoute = match.distance, location = location)
            if (match.distance > 15.0) return lastState.copy(visible = false,
                status = "Estás fuera del recorrido · recalculando la ruta para caminar",
                along = match.along, distanceFromRoute = match.distance, location = location)
        }

        if (isManual) {
            // Renew the nearby tracking reference as the user walks; preserve geographic origin and rotation.
            val reference = requireNotNull(manual)
            val relative = reference.anchor.pose.inverse().compose(frame.camera.pose)
            if (hypot(relative.tx().toDouble(), relative.tz().toDouble()) > 6.0) {
                val origin = ArRouteGeometry.fromOffset(reference.origin, relative.tx().toDouble(), relative.tz().toDouble())
                val floor = floorHit(frame, width, height)
                val pose = Pose(floatArrayOf(frame.camera.pose.tx(),
                    floor?.hitPose?.ty()?.plus(.055f) ?: reference.anchor.pose.ty(), frame.camera.pose.tz()),
                    reference.anchor.pose.rotationQuaternion)
                val renewed = runCatching { session.createAnchor(pose) }.getOrNull()
                if (renewed != null) {
                    manual = ManualReference(renewed, origin)
                    reference.anchor.detach()
                }
            }
            location = manualLocation(frame) ?: location
            match = RouteGeometry.project(route.points, location) ?: match
        }
        val along = match.along
        val samples = route.window(along, 160.0)
        val blocks = samples.zipWithNext().map { (a, _) -> route.block(a) }.distinctBy { it.id }
        val ids = blocks.map { it.id }.toSet()
        entries.keys.filter { it !in ids }.forEach { id ->
            entries.remove(id)?.let { runCatching { it.future?.cancel() }; runCatching { it.anchor?.detach() } }
        }
        val floor = if (!isManual) floorHit(frame, width, height) else null
        if (floor != null && earth != null && geo != null) {
            runCatching { earth.getGeospatialPose(floor.hitPose).altitude + .055 }.getOrNull()?.let { floorAltitude = it }
        }

        if (!isManual && earth != null && geo != null) {
            blocks.forEach { entries.getOrPut(it.id) { Entry(it, now) } }
            var pending = entries.values.count { it.future != null }
            // Resolve the closest blocks first. A pending lookup never cuts an otherwise measured ribbon.
            for (entry in entries.values.sortedBy { abs(it.sample.along - along) }) {
                if (entry.anchor != null) continue
                if (entry.future != null && now - entry.startedAt > 12000L) {
                    entry.future?.cancel(); entry.future = null; entry.failed = true; pending--
                }
                if (entry.future != null || entry.failed || pending >= 4) continue
                val epoch = generation
                entry.startedAt = now
                entry.future = runCatching { earth.resolveAnchorOnTerrainAsync(entry.sample.point.lat,
                    entry.sample.point.lon, .055, 0f, 0f, 0f, 1f) { anchor, state ->
                    main.post {
                        if (epoch != generation || entries[entry.sample.id] !== entry || entry.failed) {
                            runCatching { anchor?.detach() }
                        } else {
                            entry.future = null
                            if (state == Anchor.TerrainAnchorState.SUCCESS && anchor != null) entry.anchor = anchor
                            else {
                                runCatching { anchor?.detach() }; entry.failed = true
                                terrainError = "Altura del terreno no disponible"
                            }
                        }
                    }
                } }.getOrElse { entry.failed = true; null }
                if (entry.future != null) pending++
            }
        }

        var estimatedHeight = false
        val geographicFrame = if (isManual) manual!!.anchor.pose else geo?.let {
            runCatching { earth!!.getPose(it.latitude, it.longitude, it.altitude, 0f, 0f, 0f, 1f) }.getOrNull()
        }
        val ribbons = samples.zipWithNext().mapNotNull { (a, b) ->
            val block = route.block(a)
            val pose = if (isManual) {
                val reference = manual!!
                val offset = ArRouteGeometry.offset(reference.origin, block.point)
                reference.anchor.pose.compose(Pose.makeTranslation(offset.east.toFloat(), 0f, offset.south.toFloat()))
            } else {
                val anchor = entries[block.id]?.anchor?.takeIf { it.trackingState == TrackingState.TRACKING }
                anchor?.pose ?: run {
                    val measuredAltitude = floorAltitude ?: entries.values.mapNotNull { entry ->
                        entry.anchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.let {
                            runCatching { earth!!.getGeospatialPose(it.pose).altitude }.getOrNull()
                        }
                    }.firstOrNull() ?: return@mapNotNull null
                    estimatedHeight = true
                    runCatching { earth!!.getPose(block.point.lat, block.point.lon, measuredAltitude,
                        0f, 0f, 0f, 1f) }.getOrNull() ?: return@mapNotNull null
                }
            }
            val start = ArRouteGeometry.offset(block.point, a.point)
            val end = ArRouteGeometry.offset(block.point, b.point)
            val dx = end.east - start.east
            val dz = end.south - start.south
            val length = hypot(dx, dz)
            if (length < .02) return@mapNotNull null
            Ribbon(a.id, pose, ((start.east + end.east) / 2).toFloat(), 0f,
                ((start.south + end.south) / 2).toFloat(), length.toFloat(), 0f,
                Math.toDegrees(atan2(dx, dz)).toFloat())
        }
        val status = when {
            isManual -> "Ruta continua · alineación manual; confirma la calle y vuelve a anclar si se desvía"
            ribbons.isEmpty() && terrainError.isNotEmpty() -> "$terrainError · detecta el piso o usa anclaje manual"
            ribbons.isEmpty() -> "Ubicación lista · detectando la altura del piso…"
            estimatedHeight -> "Ruta continua · ubicación visual; altura lejana aproximada"
            else -> String.format(Locale("es", "MX"), "Ruta continua · ubicación ±%.1f m / giro ±%.0f°",
                geo!!.horizontalAccuracy, geo.orientationYawAccuracy)
        }
        if (ribbons.isNotEmpty()) lastGoodAt = now
        return State(ribbons, ribbons.isNotEmpty(), isManual, status, along, match.distance,
            location, geographicFrame).also { lastState = it }
    }

    private fun manualLocation(frame: Frame): RouteGeometry.Point? = manual?.let {
        if (it.anchor.trackingState != TrackingState.TRACKING) return@let null
        val relative = it.anchor.pose.inverse().compose(frame.camera.pose)
        ArRouteGeometry.fromOffset(it.origin, relative.tx().toDouble(), relative.tz().toDouble())
    }

    private fun floorHit(frame: Frame, width: Int, height: Int): HitResult? {
        if (width <= 0 || height <= 0) return null
        return listOf(.72f, .82f, .62f).asSequence().flatMap { y ->
            frame.hitTest(width * .5f, height * y).asSequence()
        }.firstOrNull { hit ->
            val trackable = hit.trackable
            val below = frame.camera.pose.ty() - hit.hitPose.ty()
            hit.distance < 8f && below in .6f..3f && trackable is Plane &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(hit.hitPose)
        }
    }
}
