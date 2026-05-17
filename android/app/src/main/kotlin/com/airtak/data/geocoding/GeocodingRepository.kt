// AirTAK Android — data/geocoding/GeocodingRepository.kt

package com.airtak.data.geocoding

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

data class GeocodingResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

@Singleton
class GeocodingRepository @Inject constructor() {
    private val client = HttpClient(Android)

    suspend fun search(query: String, limit: Int = 5): List<GeocodingResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val body: String = client.get("https://nominatim.openstreetmap.org/search") {
                header("User-Agent", "AirTAK/0.1")
                parameter("q", query.trim())
                parameter("format", "json")
                parameter("limit", limit)
                parameter("addressdetails", 0)
            }.body()
            val array = JSONArray(body)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                GeocodingResult(
                    displayName = obj.getString("display_name"),
                    latitude = obj.getString("lat").toDouble(),
                    longitude = obj.getString("lon").toDouble(),
                )
            }
        }.getOrDefault(emptyList())
    }
}
