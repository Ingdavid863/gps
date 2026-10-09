package com.david.gps3dar

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Only destinations the driver selects are persisted, never typed search queries. */
class RecentDestinations(private val read: () -> String, private val write: (String) -> Unit) {
    fun list(query: String = ""): List<DestinationSearch.Result> {
        val needle = normalize(query)
        val array = runCatching { JSONArray(read()) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val item = array.getJSONObject(i)
                val lat = item.getDouble("lat"); val lon = item.getDouble("lon")
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@runCatching null
                DestinationSearch.Result(lat, lon, item.getString("title"), item.optString("address"))
            }.getOrNull()
        }.filter { result -> needle.split(' ').filter { it.isNotBlank() }.all { normalize(result.label).contains(it) } }.take(24)
    }

    fun select(result: DestinationSearch.Result) {
        if (result.lat !in -90.0..90.0 || result.lon !in -180.0..180.0 || result.label.isBlank()) return
        val entries = (listOf(result) + list().filter { identity(it) != identity(result) }).take(24)
        write(JSONArray(entries.map { JSONObject().put("lat", it.lat).put("lon", it.lon)
            .put("title", it.title).put("address", it.address) }).toString())
    }

    companion object {
        fun identity(result: DestinationSearch.Result) = "${(result.lat * 100000).toLong()},${(result.lon * 100000).toLong()}"
        fun normalize(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")
    }
}
