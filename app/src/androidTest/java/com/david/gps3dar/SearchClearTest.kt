package com.david.gps3dar

import android.location.Location
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SearchClearTest {
    private fun field(name: String) = RealisticMapActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun select(activity: RealisticMapActivity, place: RealisticMapActivity.SearchResult) {
        field("suppressSearchWatcher").setBoolean(activity,true)
        activity.findViewById<EditText>(R.id.searchInput).setText(place.label)
        field("suppressSearchWatcher").setBoolean(activity,false)
        RealisticMapActivity::class.java.getDeclaredMethod("showPlacePreview",RealisticMapActivity.SearchResult::class.java,Boolean::class.javaPrimitiveType)
            .apply { isAccessible=true }.invoke(activity,place,true)
    }
    @Test fun crossClearsTheWholeDestinationAndTheLowerGoButtonStillRequestsTheNewDestination() {
        val routes=LinkedBlockingQueue<String>()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.contains("calculateRoute")) routes.put(chain.request().url.pathSegments[3])
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Test")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val first=RealisticMapActivity.SearchResult("Jardín de Eventos del Sol, Calle Sol 9, 54770",19.721,-99.221)
        val next=RealisticMapActivity.SearchResult("Parque, otro destino",19.722,-99.222)
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                field("http").set(activity,client)
                field("avoidTolls").setBoolean(activity,false)
                val current=Location("fixture").apply {
                    latitude=19.72;longitude=-99.22;accuracy=3f
                    time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()+120_000_000_000L
                }
                field("lastAcceptedLocation").set(activity,current)
                field("rawLocation").set(activity,current);field("displayLocation").set(activity,current)
                select(activity,first)
                assertEquals("Ir",activity.findViewById<TextView>(R.id.placePreviewGo).text.toString())
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.placePreview).visibility)
                val epoch=field("searchEpoch").getInt(activity)
                val clear=activity.findViewById<TextView>(R.id.searchButton)
                assertEquals("×",clear.text.toString());assertEquals("Limpiar destino",clear.contentDescription)
                clear.performClick()
                val input=activity.findViewById<EditText>(R.id.searchInput)
                assertEquals("",input.text.toString());assertTrue(input.hasFocus())
                assertNull(field("selectedMapPoint").get(activity))
                assertEquals(View.GONE,activity.findViewById<View>(R.id.placePreview).visibility)
                assertTrue(field("searchEpoch").getInt(activity)>epoch)
                // A result already queued by the previous search cannot restore its preview.
                RealisticMapActivity::class.java.getDeclaredMethod("deliverSearchResults",String::class.java,
                    Boolean::class.javaPrimitiveType,List::class.java).apply { isAccessible=true }
                    .invoke(activity,first.label,true,listOf(first))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("",activity.findViewById<EditText>(R.id.searchInput).text.toString())
                assertNull(field("selectedMapPoint").get(activity))
                select(activity,next)
                val go=activity.findViewById<TextView>(R.id.placePreviewGo)
                assertEquals("Ir",go.text.toString());assertTrue(go.isEnabled)
                go.performClick()
            }
            val coordinates=routes.poll(8,TimeUnit.SECONDS) ?: error("Lower Go did not request a route")
            assertEquals("19.72,-99.22:${next.lat},${next.lon}",coordinates)
            assertNull(routes.poll())
        }
    }
}
