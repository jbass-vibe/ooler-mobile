package com.example.ooler.ui

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ooler.domain.OolerConstants
import com.example.ooler.domain.OolerSchedule
import com.example.ooler.domain.ScheduleRow
import java.time.format.DateTimeFormatter
import java.util.*

private val AppBlack = Color(0xFF000000)
private val AppGreen = Color(0xFF4CAF50)
private val AppRed = Color(0xFFF44336)
private val AppInactiveGrey = Color(0xFF333333)
private val AppTextGrey = Color(0xFF9E9E9E)

@SuppressLint("NewApi")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    viewModel: OolerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.oolerState.collectAsState()
    val schedule by viewModel.schedule.collectAsState()
    
    // Local state to manage edits before saving
    var currentRows by remember { mutableStateOf(schedule.rows) }
    
    // Sync local state when VM schedule changes (e.g. on load)
    LaunchedEffect(schedule.rows) {
        currentRows = schedule.rows
    }
    
    var allTimersEnabled by remember { mutableStateOf(true) }

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
        containerColor = Color.Transparent,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, start = 48.dp, end = 48.dp)
            ) {
                Button(
                    onClick = { 
                        viewModel.saveSchedule(OolerSchedule(rows = currentRows))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        "SAVE & SYNC",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                }
            }
        },
        modifier = modifier.background(bgColor)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // Header
            Text(
                "OOLER",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 4.sp,
                    color = Color.White
                )
            )
            
            Text(
                "WEEKLY SLEEP SCHEDULE".toUpperCase(Locale.current),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp
                )
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Clock and Global Toggle Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeStr = state.deviceTime?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: "--:--"
                
                Column {
                    Text(
                        "Ooler Time",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        timeStr,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "All Timers",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = allTimersEnabled,
                        onCheckedChange = { enabled ->
                            allTimersEnabled = enabled
                            currentRows = currentRows.map { it.copy(enabled = enabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = AppTextGrey,
                            uncheckedTrackColor = AppInactiveGrey,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Schedule List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(currentRows, key = { it.id }) { row ->
                    ScheduleRowCard(
                        row = row,
                        onUpdate = { updatedRow ->
                            currentRows = currentRows.map { if (it.id == updatedRow.id) updatedRow else it }
                        },
                        onDelete = {
                            currentRows = currentRows.filter { it.id != row.id }
                        }
                    )
                }
                
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = { 
                                currentRows = currentRows + ScheduleRow()
                            }
                        ) {
                            Text(
                                "+ ADD SCHEDULED TIME",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleRowCard(
    row: ScheduleRow,
    onUpdate: (ScheduleRow) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showTempDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ON Time Box
                TimeBox(
                    label = "ON",
                    minutes = row.onTimeMinutes,
                    color = AppGreen,
                    onClick = {
                        showTimePicker(context, row.onTimeMinutes) { newMinutes ->
                            onUpdate(row.copy(onTimeMinutes = newMinutes))
                        }
                    }
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // OFF Time Box
                TimeBox(
                    label = "OFF",
                    minutes = row.offTimeMinutes,
                    color = AppRed,
                    onClick = {
                        showTimePicker(context, row.offTimeMinutes) { newMinutes ->
                            onUpdate(row.copy(offTimeMinutes = newMinutes))
                        }
                    }
                )
                
                // Temp Box
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .clickable { showTempDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("TEMP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("${row.temperatureF}°", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }

                if (showTempDialog) {
                    TemperaturePickerDialog(
                        initialTemp = row.temperatureF,
                        onDismiss = { showTempDialog = false },
                        onConfirm = { 
                            onUpdate(row.copy(temperatureF = it))
                            showTempDialog = false
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = "Delete", 
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            
            // Bottom Section: Day Bubbles (Sun to Sat)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val dayOrder = listOf(6, 0, 1, 2, 3, 4, 5)
                val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
                
                dayOrder.forEachIndexed { index, oolerDayIndex ->
                    val isSelected = row.days.contains(oolerDayIndex)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else AppInactiveGrey)
                            .clickable {
                                val newDays = if (isSelected) {
                                    row.days - oolerDayIndex
                                } else {
                                    row.days + oolerDayIndex
                                }
                                onUpdate(row.copy(days = newDays))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabels[index],
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else AppTextGrey
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TimeBox(
    label: String,
    minutes: Int,
    color: Color,
    onClick: () -> Unit
) {
    val timeStr = formatMinutes(minutes)
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            )
            Text(
                timeStr,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }
    }
}

@Composable
fun TemperaturePickerDialog(
    initialTemp: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var temp by remember { mutableStateOf(initialTemp) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Temperature") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${temp}°F",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Slider(
                    value = temp.toFloat(),
                    onValueChange = { temp = it.toInt() },
                    valueRange = OolerConstants.TEMP_MIN_F.toFloat()..OolerConstants.TEMP_MAX_F.toFloat()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilledTonalIconButton(
                        onClick = { temp = (temp - 1).coerceAtLeast(OolerConstants.TEMP_MIN_F) },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease")
                    }
                    FilledTonalIconButton(
                        onClick = { temp = (temp + 1).coerceAtMost(OolerConstants.TEMP_MAX_F) },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(temp) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatMinutes(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    val ampm = if (hours >= 12) "PM" else "AM"
    val h = if (hours % 12 == 0) 12 else hours % 12
    return String.format("%d:%02d %s", h, mins, ampm)
}

private fun showTimePicker(context: android.content.Context, initialMinutes: Int, onTimeSelected: (Int) -> Unit) {
    val hour = initialMinutes / 60
    val minute = initialMinutes % 60
    TimePickerDialog(
        context,
        { _, selectedHour, selectedMinute ->
            onTimeSelected(selectedHour * 60 + selectedMinute)
        },
        hour,
        minute,
        false
    ).show()
}
