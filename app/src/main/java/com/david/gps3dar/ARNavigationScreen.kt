package com.david.gps3dar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

        TopDirectionHud(
            distanceText = distanceText,
            instructionText = instructionText,
            roadText = roadText,
            arStatus = arStatus,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp, start = 14.dp, end = 14.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 142.dp, end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HudRoundButton(
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
            SpeedometerWidget(speedKmh = speedKmh, speedLimitKmh = speedLimitKmh)

            AnimatedVisibility(visible = !drivingFast) {
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
            BottomSearchPanel(onClick = onSearch)
        }
    }
}

@Composable
private fun TopDirectionHud(
    distanceText: String,
    instructionText: String,
    roadText: String,
    arStatus: String,
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
                    Text("↱", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = distanceText,
                        color = Color(0xFFB8CAD8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = instructionText,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
fun BottomSearchPanel(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 104.dp, bottom = 18.dp)
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
                    text = "¿A dónde quieres ir?",
                    color = Color(0xFF27333C),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Buscar destino en el mapa 3D",
                    color = Color(0xFF74828C),
                    fontSize = 11.sp
                )
            }
        }
    }
}
