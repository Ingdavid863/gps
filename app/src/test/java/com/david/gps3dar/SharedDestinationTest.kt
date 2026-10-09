package com.david.gps3dar

import org.junit.Assert.*
import org.junit.Test

class SharedDestinationTest {
    @Test fun sharedDrivingDoesNotInheritWalkingAndExplicitWalkingLinksKeepTheirMode() {
        assertFalse(SharedDestination.parse("https://www.google.com/maps/dir/?api=1&destination=19.5,-99.4&travelmode=driving")!!.walking)
        assertTrue(SharedDestination.parse("google.navigation:q=19.5,-99.4&mode=w")!!.walking)
        assertTrue(SharedDestination.parse("https://www.google.com/maps/dir/?api=1&destination=19.5,-99.4&travelmode=walking")!!.walking)
        assertTrue(SharedDestination.parse("https://www.google.com/maps/dir/Origen/19.5,-99.4/data=!3e2")!!.walking)
    }
    @Test fun opaqueWhatsAppCoordinate() {
        val d = SharedDestination.parse("geo:0,0?q=19.432608,-99.133209(Casa)")!!
        assertEquals(19.432608, d.point!!.lat, 1e-9)
        assertEquals(-99.133209, d.point!!.lon, 1e-9)
    }
    @Test fun opaqueCoordinateWithoutQuery() {
        assertEquals(19.4, SharedDestination.parse("geo:19.4,-99.2")!!.point!!.lat, 1e-9)
    }
    @Test fun encodedAddressDoesNotNavigateToZero() {
        val d = SharedDestination.parse("geo:0,0?q=San%20Jos%C3%A9%20del%20Jarral%2C%20Atizap%C3%A1n")!!
        assertNull(d.point)
        assertEquals("San José del Jarral, Atizapán", d.query)
    }
    @Test fun navigationCoordinatesAndAddress() {
        assertNotNull(SharedDestination.parse("google.navigation:q=19.4,-99.2&mode=d")!!.point)
        assertEquals("Calle Morelia 12", SharedDestination.parse("google.navigation:q=Calle+Morelia+12")!!.query)
    }
    @Test fun googleDestinationBeatsMapCenter() {
        val d = SharedDestination.parse("https://www.google.com/maps/place/Casa/@19.3,-99.1,14z/data=!4m2!3d19.5!4d-99.4")!!
        assertEquals(19.5, d.point!!.lat, 1e-9)
    }
    @Test fun mapsAndWazeQueries() {
        assertEquals(19.5, SharedDestination.parse("https://www.google.com/maps/dir/?api=1&destination=19.5%2C-99.4")!!.point!!.lat, 1e-9)
        assertEquals(-99.4, SharedDestination.parse("https://waze.com/ul?ll=19.5%2C-99.4&navigate=yes")!!.point!!.lon, 1e-9)
    }
    @Test fun sharedTextAndShortLink() {
        assertNotNull(SharedDestination.parse("Mi casa: https://maps.app.goo.gl/abc123")!!.shortUrl)
    }
    @Test fun malformedAndUntrustedInputs() {
        for (input in listOf("geo:not-coordinates", "geo:0,0?q=%zz", "geo:NaN,Infinity", "geo:91,0", "geo:19,-181", "https://example.com/?q=19,-99")) {
            assertNull(input, SharedDestination.parse(input))
        }
    }
}

