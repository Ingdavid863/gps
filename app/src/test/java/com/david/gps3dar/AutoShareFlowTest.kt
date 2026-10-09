package com.david.gps3dar

import org.junit.Assert.*
import org.junit.Test

class AutoShareFlowTest {
    private fun preview(destination: String = "Parque, Privadas del Valle Manzana 002", directShare: Boolean = false) =
        AutoShareFlow.Screen(AutoShareFlow.MAPS, listOf(
            AutoShareFlow.Item("0", "Tu ubicación", editable = true), AutoShareFlow.Item("1", destination, editable = true),
            AutoShareFlow.Item("2", "Iniciar", clickable = true), AutoShareFlow.Item("3", description = "Más opciones", clickable = true)) +
            if (directShare) listOf(AutoShareFlow.Item("4", "Compartir", clickable = true)) else emptyList())
    private fun shareMenu() = AutoShareFlow.Screen(AutoShareFlow.MAPS,
        preview().items + listOf(AutoShareFlow.Item("5", "Compartir tu ubicación", clickable = true),
            AutoShareFlow.Item("6", "Compartir indicaciones", clickable = true)))
    private fun begin(flow: AutoShareFlow, screen: AutoShareFlow.Screen = preview()): AutoShareFlow.Action {
        assertNull(flow.next(screen, 1000)); return flow.next(screen, 2200)!!
    }
    @Test fun newRouteUsesDirectionsSharingAndOnlySelectsGps3dAfterOpeningItsOwnSharePanel() {
        val flow = AutoShareFlow()
        val chooser = AutoShareFlow.Screen("android", listOf(AutoShareFlow.Item("7", "GPS3D AR David", clickable = true)))
        assertNull(flow.next(chooser, 1000))
        assertEquals("3", begin(flow).path)
        assertEquals("6", flow.next(shareMenu(), 3200)!!.path)
        assertEquals("7", flow.next(chooser, 4300)!!.path)
        flow.delivered(4400)
        assertEquals(64, flow.lastDelivered.length)
        assertNull(flow.next(preview(), 7000))
        assertFalse(flow.needsPoll)
        assertNull(flow.next(preview("Otro destino, calle nueva 12"), 8000))
        assertNotNull(flow.next(preview("Otro destino, calle nueva 12"), 9300))
    }
    @Test fun activeNavigationExitsToThePreviewThenSharesTheRealDirections() {
        val flow = AutoShareFlow()
        val nav = AutoShareFlow.Screen(AutoShareFlow.MAPS, listOf(AutoShareFlow.Item("exit", description = "Salir de la navegación", clickable = true)))
        assertEquals("exit", flow.next(nav, 10000)!!.path)
        assertEquals(AutoShareFlow.Stage.EXIT_NAVIGATION, flow.stage)
        assertEquals("4", flow.next(preview(directShare = true), 11200)!!.path)
        assertEquals(AutoShareFlow.Stage.CHOOSE_APP, flow.stage)
    }
    @Test fun unrelatedAppsPlaceCardsAndShareLocationAreNotAutomated() {
        val flow = AutoShareFlow()
        assertNull(flow.next(AutoShareFlow.Screen("com.didi.driver", preview().items), 1000))
        assertNull(flow.next(AutoShareFlow.Screen(AutoShareFlow.MAPS,
            listOf(AutoShareFlow.Item("location", "Compartir tu ubicación", clickable = true),
                AutoShareFlow.Item("share", "Compartir", clickable = true))), 2200))
        assertEquals(AutoShareFlow.Stage.IDLE, flow.stage)
        assertFalse(flow.needsPoll)
    }
    @Test fun anUnrelatedForegroundAppCancelsPendingAutomationAndCannotTriggerItsChooser() {
        val flow = AutoShareFlow(); begin(flow)
        flow.next(AutoShareFlow.Screen("unrelated.app", emptyList()), 3300)
        assertNull(flow.next(AutoShareFlow.Screen("android", listOf(AutoShareFlow.Item("gps", "GPS3D AR David"))), 4400))
        assertEquals(AutoShareFlow.Stage.IDLE, flow.stage)
    }
    @Test fun missingGps3dHasBoundedScrollingAndTimeoutAndDoesNotChooseAnotherApplication() {
        val flow = AutoShareFlow(); begin(flow, preview(directShare = true))
        val missing = AutoShareFlow.Screen("com.android.intentresolver",
            listOf(AutoShareFlow.Item("other", "WhatsApp", clickable = true), AutoShareFlow.Item("list", scrollable = true)))
        repeat(4) { assertEquals(AutoShareFlow.Kind.SCROLL, flow.next(missing, 3300L + it * 1100)!!.kind) }
        assertNull(flow.next(missing, 8000))
        assertNull(flow.next(missing, 23000))
        assertEquals(AutoShareFlow.Stage.IDLE, flow.stage)
        assertTrue(flow.status.contains("manualmente"))
        assertNull(flow.next(preview(directShare = true), 25000))
    }
    @Test fun rapidEditsWaitForTheActualDestinationAndCancellingAllowsTheSameDestinationAgain() {
        val flow = AutoShareFlow()
        assertNull(flow.next(preview("Calle 1"), 1000))
        assertNull(flow.next(preview("Calle 12"), 1600))
        assertNull(flow.next(preview("Calle 12"), 2100))
        assertNotNull(flow.next(preview("Calle 12"), 2900))
        flow.delivered(3900); flow.forgetDelivered()
        assertNull(flow.next(preview("Calle 12"), 5000))
        assertNotNull(flow.next(preview("Calle 12"), 6200))
    }
    @Test fun originOnlyOrEmptyDestinationNeverStartsSharing() {
        val flow = AutoShareFlow()
        val empty = preview().copy(items = preview().items.filter { it.path != "1" })
        assertNull(flow.next(empty, 1000)); assertNull(flow.next(empty, 2500))
        assertEquals(AutoShareFlow.Stage.IDLE, flow.stage)
    }
}
