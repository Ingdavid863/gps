package com.david.gps3dar

import com.david.gps3dar.RealisticMapActivity.GeoPoint
import com.david.gps3dar.RealisticMapActivity.NavStep
import com.david.gps3dar.RealisticMapActivity.RouteOption
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OfflineNavigationTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun sparseLongSegmentsAndTheLastKilometreHaveEveryOfflineZoom() {
        val route = listOf(RouteGeometry.Point(23.1,-110.0), RouteGeometry.Point(24.1,-110.0))
        val tiles = MapTilePlanner.plan(route).toSet()
        for (z in 3..MapTilePlanner.OFFLINE_MAX_ZOOM) for (i in 0..100) {
            assertTrue("whole 110 km segment at zoom $z, sample $i", tiles.contains(MapTilePlanner.tile(-110.0,23.1+i*.01,z)))
        }
        assertEquals(tiles, MapTilePlanner.plan(route,1).toSet())
        assertEquals(emptyList<MapTilePlanner.Tile>(), MapTilePlanner.plan(listOf(RouteGeometry.Point(Double.NaN,0.0),route.last())))
    }
    @Test fun datelineRouteDoesNotDownloadTheOtherSideOfTheWorld() {
        val tiles = MapTilePlanner.plan(listOf(RouteGeometry.Point(23.0,179.99),RouteGeometry.Point(23.0,-179.99)))
        assertTrue(tiles.size < 2000)
        assertTrue(tiles.contains(MapTilePlanner.tile(-179.99,23.0,16)))
        assertFalse(tiles.contains(MapTilePlanner.tile(0.0,23.0,16)))
    }
    @Test fun completeRouteResourcesSurviveCacheEvictionRestartAndKeyRotation() {
        val failFirst = AtomicBoolean(true); val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().cacheControl.onlyIfCached) return@addInterceptor chain.proceed(chain.request())
            calls.incrementAndGet()
            val fail = failFirst.compareAndSet(true,false)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if(fail)503 else 200).message("Fixture")
                .header("Cache-Control","no-store")
                .body("saved:${chain.request().url.encodedPath}".toResponseBody("application/x-protobuf".toMediaType())).build()
        }.build()
        val route = listOf(RouteGeometry.Point(23.1,-110.0),RouteGeometry.Point(23.25,-110.0))
        val directory = File(temp.newFolder("map"),"http-cache")
        val cache = MapResourceCache(directory,client); cache.configure("old")
        val states = LinkedBlockingQueue<OfflineRoutePack.Progress>()
        cache.prepare(route) { states.put(it) }
        fun await(state: String) {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            while(System.nanoTime()<end) if(states.poll(1,TimeUnit.SECONDS)?.state==state)return
            error("Expected preparation state $state")
        }
        await("retrying")
        val last=MapTilePlanner.tile(-110.0,23.25,16)
        val url="https://b.api.tomtom.com/map/1/tile/basic/main/${last.z}/${last.x}/${last.y}.pbf?key=new"
        assertNotNull("a failed early resource does not abandon the destination",cache.bytes(url))
        cache.retryPreparation(); await("ready")
        cache.close(); directory.deleteRecursively()
        val count=calls.get()
        val restarted=MapResourceCache(directory,client); restarted.configure("new"); restarted.setNetworkAvailable(false)
        assertNotNull(restarted.bytes(url))
        val ready=CountDownLatch(1)
        restarted.prepare(route) { if(it.ready)ready.countDown() }
        assertTrue("all resources are protected outside HTTP cache",ready.await(8,TimeUnit.SECONDS))
        assertEquals("offline restart must not access the service",count,calls.get())
        restarted.close()
    }
    @Test fun interruptedPackResumesMissingResourcesWithoutRedownloadingCompletedOnes() {
        val directory=temp.newFolder("pack"); val requests=mutableListOf<String>()
        val online=AtomicBoolean(true)
        val first=OfflineRoutePack(directory) { url -> synchronized(requests){requests.add(url)}
            if(online.get())("data".toByteArray() to "application/x-protobuf")else null }
        first.setOnline(false)
        val paused=CountDownLatch(1)
        first.prepare("route",{(0..20).map{"https://api.tomtom.com/style/1/$it"}}) { if(it.state=="offline")paused.countDown() }
        assertTrue(paused.await(3,TimeUnit.SECONDS));assertFalse(first.current().ready);assertEquals(0,requests.size)
        val finished=CountDownLatch(1)
        first.prepare("route",{error("same route must reuse its plan")}) { if(it.ready)finished.countDown() }
        first.setOnline(true)
        assertTrue(finished.await(5,TimeUnit.SECONDS));assertEquals(21,requests.size)
        first.close()
        val second=OfflineRoutePack(directory) { error("no redownload needed") };second.setOnline(false)
        val restored=CountDownLatch(1)
        second.prepare("route",{(0..20).map{"https://api.tomtom.com/style/1/$it"}}) { if(it.ready)restored.countDown() }
        assertTrue(restored.await(3,TimeUnit.SECONDS));second.close()
    }
    @Test fun changingTheSelectedRouteCancelsTheOldPackBeforeItCanOverwriteTheNewOne() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val pack=OfflineRoutePack(temp.newFolder("replace")) { url ->
            if(url.startsWith("old")){entered.countDown();release.await(3,TimeUnit.SECONDS)}
            url.toByteArray() to "application/x-protobuf"
        }
        pack.prepare("old",{(0..20).map{"old$it"}}){}
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val ready=CountDownLatch(1)
        pack.prepare("new",{listOf("new")}) { if(it.ready)ready.countDown() }
        release.countDown();assertTrue(ready.await(3,TimeUnit.SECONDS))
        assertNotNull(pack.read("new"));assertNull(pack.read("old0"));assertEquals(1,pack.current().total)
        pack.close()
    }
    @Test fun selectedRouteRestoresAllPointsTurnsTollsAndWalkingMode() {
        val points=(0..500).map { GeoPoint(23.0+it*.001,-110.0) }
        val option=RouteOption(points,listOf(NavStep(points[200].lat,-110.0,"Gira a la derecha","↱",200,"TURN_RIGHT",22000.0),
            NavStep(points.last().lat,-110.0,"Llegaste","●",500,"ARRIVE")),3600.0,55000.0,120.0,3480.0,
            true,true,listOf(10..30),true)
        val original=NavigationSessionStore.Session(option,points.last(),"La Paz",1)
        val restored=NavigationSessionStore.decode(NavigationSessionStore.encode(original))
        assertEquals(original,restored)
        val file=File(temp.newFolder("session"),"route.json");val store=NavigationSessionStore(file)
        store.save(original);val saved=CountDownLatch(1)
        store.load { assertEquals(original,it);saved.countDown() }
        assertTrue(saved.await(3,TimeUnit.SECONDS))
        store.clear();val cleared=CountDownLatch(1);store.load { assertNull(it);cleared.countDown() }
        assertTrue(cleared.await(3,TimeUnit.SECONDS));store.close()
    }
    @Test fun aFailedSessionWriteCannotBeReportedAsASavedRoute() {
        val points=listOf(GeoPoint(23.0,-110.0),GeoPoint(23.1,-110.0))
        val session=NavigationSessionStore.Session(RouteOption(points,emptyList(),60.0,1000.0),points.last(),"Destino",1)
        val store=NavigationSessionStore(temp.newFolder("cannot-replace-directory"));val finished=CountDownLatch(1)
        var saved=true
        store.save(session){saved=it;finished.countDown()}
        assertTrue(finished.await(3,TimeUnit.SECONDS));assertFalse(saved);store.close()
    }
    @Test fun confirmedDepartureStartsWithinOneSecondWithoutTheOldCooldown() {
        val p=ReroutePolicy()
        assertFalse(p.shouldRequest(1000,1,40.0,5.0,26.0,90.0))
        assertTrue(p.shouldRequest(1300,2,45.0,5.0,26.0,90.0))
        p.reset()
        assertFalse(p.shouldRequest(1400,3,20.0,5.0,8.0,90.0))
        assertTrue(p.shouldRequest(1850,4,23.0,5.0,8.0,90.0))
    }
    @Test fun gpsNoiseRepeatedFixesAndAPoorCourseDoNotTriggerRerouting() {
        val p=ReroutePolicy()
        assertFalse(p.shouldRequest(1000,1,40.0,5.0,26.0,90.0))
        assertFalse(p.shouldRequest(1600,1,40.0,5.0,26.0,90.0))
        assertFalse(p.shouldRequest(1700,2,2.0,5.0,26.0,0.0))
        assertFalse(p.shouldRequest(1800,3,40.0,55.0,26.0,90.0))
        assertFalse(p.shouldRequest(5000,4,8.0,5.0,0.0,null))
    }
    @Test fun reconnectionResumesConfirmedDepartureImmediatelyButNotAfterReturningToTheRoute() {
        val p=ReroutePolicy()
        assertTrue(p.shouldRequest(1000,1,40.0,5.0,26.0,90.0,true))
        p.failed(1100)
        assertFalse(p.shouldRequest(1500,2,40.0,5.0,26.0,90.0,true))
        assertTrue(p.shouldRequest(3100,3,40.0,5.0,26.0,90.0,true))
        assertFalse(p.shouldRequest(3200,4,2.0,5.0,26.0,0.0,true))
    }
}
