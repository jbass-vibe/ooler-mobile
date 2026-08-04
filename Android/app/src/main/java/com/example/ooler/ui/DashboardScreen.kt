package com.example.ooler.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ooler.domain.OolerMode
import com.example.ooler.domain.TemperatureUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: OolerViewModel) {
    val state by viewModel.oolerState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose {
            viewModel.stopPolling()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val unitString = if (state.displayUnit == TemperatureUnit.CELSIUS) "°C" else "°F"
    val actualTemp = viewModel.getTemperatureInPreferredUnit(state.actualTemperature)
    val setTemp = viewModel.getTemperatureInPreferredUnit(state.setTemperatureF)
    val ambientTemp = viewModel.getTemperatureInPreferredUnit(state.ambientTemperatureF)

    val bgColor = if (state.powerOn) {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                MaterialTheme.colorScheme.background
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                MaterialTheme.colorScheme.background
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { if (state.isConnected) viewModel.disconnect() else viewModel.connect() }) {
                        Icon(
                            imageVector = if (state.isConnected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                            contentDescription = "Connection",
                            tint = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Compact Hero Pill: Layered Rows
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 20.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Row 1: Current Temperature
                    Text(
                        text = "CURRENT",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$actualTemp",
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = unitString,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp).width(200.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // Row 2: Target Temperature + Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { viewModel.setTemperature(state.setTemperatureF - 1) },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(20.dp))
                        }

                        Spacer(modifier = Modifier.width(24.dp))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "TARGET",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$setTemp",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Light,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                                Text(
                                    text = unitString,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(24.dp))

                        FilledTonalIconButton(
                            onClick = { viewModel.setTemperature(state.setTemperatureF + 1) },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Power and Mode Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.powerOn) "System Active" else "System Inactive",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(
                            checked = state.powerOn,
                            onCheckedChange = { viewModel.togglePower() }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Text("Operating Mode", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OolerMode.entries.forEach { mode ->
                            val selected = state.mode == mode
                            val containerColor by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                label = "modeBg"
                            )
                            val contentColor by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "modeContent"
                            )
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(containerColor)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = { viewModel.setMode(mode) },
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                        color = contentColor,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Unified telemetry row centered
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactTelemetryCard(
                    label = "Water",
                    value = "${state.waterLevel}%",
                    icon = Icons.Default.Waves,
                    modifier = Modifier.weight(1f)
                )
                CompactTelemetryCard(
                    label = "Humidity",
                    value = if (state.humidity == 129) "--" else "${state.humidity}%",
                    icon = Icons.Default.WaterDrop,
                    modifier = Modifier.weight(1f)
                )
                CompactTelemetryCard(
                    label = "Ambient",
                    value = if (state.ambientTemperatureF == 129) "--" else "$ambientTemp$unitString",
                    icon = Icons.Default.Air,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun CompactTelemetryCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}
