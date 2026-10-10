package com.david.gps3dar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Driving HUD rendered over the ARCore/SceneView camera.
 *
 * The camera/3D scene is supplied by [arContent]. Controls intentionally fade down to
 * essentials once the car is moving above [interactionSpeedThresholdKmh].
 */
@Composable
fun ARNavigationScreen(
    speedKmh: Int,
    speedLimitKmh: Int?,
    distanceText: String,
    instructionText: String,
    roadText: String,
    arStatus: String,
    voiceEnabled: Boolean,
    trafficEnabled: Boolean,
    walkingMode: Boolean = false,
    routeActive: Boolean = false,
    maneuver: String = "",
    destinationIndicator: ArDestinationIndicator? = null,
    destinationDistance: String = "",
    onAnchorFloor: () -> Unit = {},
    onAutomaticGround: () -> Unit = {},
    interactionSpeedThresholdKmh: Int = 20,
    onOpenMap: () -> Unit,
    onSearch: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleTraffic: () -> Unit,
    onReportHazard: () -> Unit,
    onReportPolice: () -> Unit,
    arContent: @Composable () -> Unit
) {
    val drivingFast = speedKmh > interactionSpeedThresholdKmh
    var showActionMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        arContent()

        if (walkingMode && destinationIndicator != null) {
            DestinationSkyFlag(destinationIndicator, destinationDistance)
        }
        TopDirectionHud(
            distanceText = distanceText,
            instructionText = instructionText,
            roadText = roadText,
            arStatus = arStatus,
            maneuver = maneuver,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp, start = 14.dp, end = 14.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = if (walkingMode) 184.dp else 142.dp, end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!walkingMode) HudRoundButton(
                label = if (trafficEnabled) "Tráfico" else "Sin tráfico",
                active = trafficEnabled,
                onClick = onToggleTraffic
            ) {
                Icon(Icons.Filled.Traffic, contentDescription = "Tráfico")
            }
            HudRoundButton(
                label = if (voiceEnabled) "Voz" else "Silencio",
                active = voiceEnabled,
                onClick = onToggleVoice
            ) {
                Icon(
                    imageVector = if (voiceEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = "Voz"
                )
            }
            HudRoundButton(label = "Mapa", active = true, onClick = onOpenMap) {
                Icon(Icons.Filled.Map, contentDescription = "Abrir mapa 3D")
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = if (drivingFast) 28.dp else 108.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!walkingMode) SpeedometerWidget(speedKmh = speedKmh, speedLimitKmh = speedLimitKmh)

            AnimatedVisibility(visible = !drivingFast && !walkingMode) {
                FloatingReportButton(
                    expanded = showActionMenu,
                    onToggle = { showActionMenu = !showActionMenu },
                    onHazard = {
                        showActionMenu = false
                        onReportHazard()
                    },
                    onPolice = {
                        showActionMenu = false
                        onReportPolice()
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = !drivingFast,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomSearchPanel(onClick = onSearch, walkingRoute = walkingMode)
        }
        if (walkingMode && routeActive) {
            Text("Para alinear: apunta al centro del camino, unos metros delante de ti.",
                color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = 156.dp)
                    .background(Color(0xCC102231), RoundedCornerShape(8.dp)).padding(8.dp))
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width * .5f, size.height * .72f)
                val arm = 9.dp.toPx()
                drawLine(Color.White, center - Offset(arm, 0f), center + Offset(arm, 0f), 2.dp.toPx())
                drawLine(Color.White, center - Offset(0f, arm), center + Offset(0f, arm), 2.dp.toPx())
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAnchorFloor, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9A5700))) {
                    Text("Alinear ruta")
                }
                Button(onClick = onAutomaticGround, modifier = Modifier.weight(1f)) { Text("Automático") }
            }
        }
    }
}

@Composable
private fun TopDirectionHud(
    distanceText: String,
    instructionText: String,
    roadText: String,
    arStatus: String,
    maneuver: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF102231).copy(alpha = 0.88f),
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color(0xFF0B78F6), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(ArSkyGeometry.maneuverSymbol(maneuver), color = Color.White,
                        fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = distanceText,
                        color = Color(0xFFB8CAD8),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = instructionText,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (roadText.isNotBlank()) {
                        Text(
                            text = roadText,
                            color = Color(0xFFD8E4EC),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(9.dp))
            Text(
                text = arStatus,
                color = Color(0xFFA9C4D8),
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DestinationSkyFlag(indicator: ArDestinationIndicator, distance: String) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = 148.dp
        val left = (maxWidth * indicator.x - cardWidth / 2).coerceIn(8.dp, (maxWidth - cardWidth - 8.dp).coerceAtLeast(8.dp))
        val top = maxHeight * indicator.y
        Column(Modifier.offset(x = left, y = top).width(cardWidth),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(50.dp, 54.dp)) {
                val poleX = size.width * .18f
                drawLine(Color.White, Offset(poleX, 0f), Offset(poleX, size.height), 4.dp.toPx())
                val flag = Path().apply {
                    moveTo(poleX, 0f)
                    lineTo(size.width, size.height * .12f)
                    lineTo(size.width * .85f, size.height * .52f)
                    lineTo(poleX, size.height * .40f)
                    close()
                }
                drawPath(flag, Color(0xFFFFCB35))
                drawLine(Color(0xFF173E63), Offset(poleX + 10.dp.toPx(), 8.dp.toPx()),
                    Offset(size.width * .77f, 14.dp.toPx()), 7.dp.toPx())
                drawLine(Color(0xFF173E63), Offset(poleX + 10.dp.toPx(), 21.dp.toPx()),
                    Offset(size.width * .71f, 27.dp.toPx()), 7.dp.toPx())
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xEF102231)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Destino · " + distance, color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                    if (indicator.arrow.isNotBlank()) Text(indicator.arrow, color = Color(0xFFFFCB35),
                        fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SpeedometerWidget(speedKmh: Int, speedLimitKmh: Int?) {
    val overLimit = speedLimitKmh != null && speedKmh > speedLimitKmh
    val ring = if (overLimit) Color(0xFFE53935) else Color.White.copy(alpha = 0.86f)

    Surface(
        modifier = Modifier.size(82.dp),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.72f),
        border = BorderStroke(3.dp, ring),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = speedKmh.coerceAtLeast(0).toString(),
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
            Text("km/h", color = Color(0xFFCBD4DA), fontSize = 10.sp)
            if (speedLimitKmh != null) {
                Text("máx $speedLimitKmh", color = ring, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HudRoundButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (active) Color(0xEFFFFFFF) else Color(0xB51A2530),
        contentColor = if (active) Color(0xFF164D78) else Color.White,
        shadowElevation = 7.dp
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
fun FloatingReportButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    onHazard: () -> Unit,
    onPolice: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = expanded) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.clickable(onClick = onHazard),
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFFF57C00),
                    contentColor = Color.White,
                    shadowElevation = 7.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Peligro", fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    modifier = Modifier.clickable(onClick = onPolice),
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF1976D2),
                    contentColor = Color.White,
                    shadowElevation = 7.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.LocalPolice, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Policía", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onToggle,
            containerColor = Color(0xFF24A35A),
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.AddLocationAlt,
                contentDescription = "Reportar",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun BottomSearchPanel(onClick: () -> Unit, walkingRoute: Boolean = false) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = if (walkingRoute) 16.dp else 104.dp, bottom = 18.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 9.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF66747F))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (walkingRoute) "Ver ruta a pie" else "¿A dónde quieres ir?",
                    color = Color(0xFF27333C),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (walkingRoute) "Volver al mapa del recorrido" else "Buscar destino en el mapa 3D",
                    color = Color(0xFF74828C),
                    fontSize = 11.sp
                )
            }
        }
    }
}

