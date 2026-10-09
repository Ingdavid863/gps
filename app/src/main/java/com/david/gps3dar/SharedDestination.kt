package com.david.gps3dar

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Android geo/navigation URIs are opaque: never call Uri.getQueryParameter on them. */
object SharedDestination {
    data class Point(val lat: Double, val lon: Double)
    data class Destination(val point: Point? = null, val query: String? = null, val shortUrl: String? = null, val walking: Boolean = false)

    fun coordinates(value: String?): Point? {
        val match = Regex("^\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*,\\s*([+-]?\\d+(?:\\.\\d+)?)(?:\\s*\\([^)]*\\))?\\s*$")
            .matchEntire(value.orEmpty()) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) Point(lat, lon) else null
    }

    fun isMapHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.US).orEmpty()
        return h in setOf("maps.app.goo.gl", "goo.gl", "maps.google.com", "google.com", "www.google.com",
            "maps.google.com.mx", "google.com.mx", "www.google.com.mx", "waze.com", "www.waze.com")
    }

    fun parse(text: String?): Destination? = runCatching {
        val input = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        coordinates(input)?.let { return Destination(point = it) }
        val link = Regex("(?:https?://|geo:|google\\.navigation:|waze://)\\S+", RegexOption.IGNORE_CASE)
            .find(input)?.value ?: input
        val uri = URI(link.replace(" ", "%20"))
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        val rawQuery = if (uri.isOpaque) {
            uri.rawSchemeSpecificPart.substringAfter('?', if (scheme == "google.navigation") uri.rawSchemeSpecificPart else "")
        } else uri.rawQuery.orEmpty()
        val params = rawQuery.split('&').mapNotNull { part ->
            if (part.isBlank()) null else decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }.toMap()
        val walking = params["mode"] == "w" || params["travelmode"] == "walking" ||
            uri.rawPath.orEmpty().contains("!3e2")
        if (scheme == "geo") {
            val q = params["q"]?.trim().orEmpty()
            coordinates(q)?.let { return Destination(point = it) }
            if (q.isNotBlank()) return Destination(query = q)
            val point = coordinates(uri.schemeSpecificPart.substringBefore('?')) ?: return null
            return Destination(point = point)
        }
        if (scheme == "google.navigation") {
            val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Destination(point = coordinates(q), query = if (coordinates(q) == null) q else null, walking = walking)
        }
        if (scheme != "waze" && (scheme !in setOf("http", "https") || !isMapHost(uri.host))) return null
        for (name in listOf("destination", "daddr", "ll", "q", "query")) {
            val q = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return Destination(point = coordinates(q), query = if (coordinates(q) == null) q else null, walking = walking)
        }
        val path = decode(uri.rawPath.orEmpty())
        // Destination data has priority over the @latitude,longitude camera center.
        Regex("!3d([+-]?[0-9.]+)!4d([+-]?[0-9.]+)").find(path)?.let {
            coordinates(it.groupValues[1] + "," + it.groupValues[2])?.let { p -> return Destination(point = p, walking = walking) }
        }
        if (path.contains("/dir/")) {
            val dest = path.substringAfter("/dir/").split('/').filter { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") }.lastOrNull()
            if (dest != null) return Destination(point = coordinates(dest), query = if (coordinates(dest) == null) dest else null, walking = walking)
        }
        if (path.contains("/place/")) {
            val q = path.substringAfter("/place/").substringBefore('/').replace('+', ' ')
            if (q.isNotBlank()) return Destination(query = q)
        }
        Regex("@([+-]?[0-9.]+),([+-]?[0-9.]+)").find(path)?.let {
            coordinates(it.groupValues[1] + "," + it.groupValues[2])?.let { p -> return Destination(point = p) }
        }
        if (uri.host?.lowercase(Locale.US) in setOf("maps.app.goo.gl", "goo.gl"))
            return Destination(shortUrl = uri.toString().replaceFirst("http://", "https://"))
        null
    }.getOrNull()

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}

