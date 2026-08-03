package com.example.ooler.ui

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ooler.domain.OolerConstants
import com.example.ooler.domain.OolerSchedule
import com.example.ooler.domain.ScheduleRow
import com.example.ooler.domain.ScheduleStep
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.launch

private val AppGreen = Color(0xFF4CAF50)
private val AppRed = Color(0xFFF44336)
private val AppInactiveGrey = Color(0xFF555555)
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    var currentRows by remember { mutableStateOf(schedule.rows) }
    
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
        topBar = {
            TopAppBar(
                title = { Text("Weekly Schedule", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.background(bgColor)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            // Ooler Time and Global Toggle Row
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
                            viewModel.onScheduleChanged()
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
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(currentRows, key = { it.id }) { row ->
                    ScheduleRowCard(
                        row = row,
                        onUpdate = { updatedRow ->
                            // Validation: Duration < 24h
                            val startStep = updatedRow.steps.first()
                            val endStep = updatedRow.steps.last()
                            val duration = if (endStep.timeMinutes > startStep.timeMinutes) {
                                endStep.timeMinutes - startStep.timeMinutes
                            } else {
                                1440 - startStep.timeMinutes + endStep.timeMinutes
                            }

                            if (duration >= 1440) {
                                scope.launch { snackbarHostState.showSnackbar("Sequence cannot exceed 24 hours") }
                                return@ScheduleRowCard
                            }

                            // Validation: Conflicts with other rows
                            val otherRows = currentRows.filter { it.id != row.id && it.enabled }
                            val conflicts = updatedRow.days.any { day ->
                                otherRows.any { other -> 
                                    other.days.contains(day) && isRowConflicting(updatedRow, other)
                                }
                            }

                            if (conflicts) {
                                scope.launch { snackbarHostState.showSnackbar("Schedule conflict detected on selected days") }
                            }

                            currentRows = currentRows.map { if (it.id == updatedRow.id) updatedRow else it }
                            viewModel.onScheduleChanged()
                        },
                        onDelete = {
                            currentRows = currentRows.filter { it.id != row.id }
                            viewModel.onScheduleChanged()
                        },
                        onNotifyShift = { 
                            scope.launch { snackbarHostState.showSnackbar("Time adjusted to maintain sequence") }
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
                                viewModel.onScheduleChanged()
                            }
                        ) {
                            Text(
                                "+ ADD SCHEDULED SEQUENCE",
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
    onDelete: () -> Unit,
    onNotifyShift: () -> Unit
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val startStep = row.steps.firstOrNull()
                val endStep = row.steps.lastOrNull()
                val tempChanges = (row.steps.size - 2).coerceAtLeast(0)
                
                val title = if (startStep != null && endStep != null) {
                    val tempsPart = if (tempChanges > 1) " Temps: $tempChanges" else ""
                    "ON: ${formatMinutes(startStep.timeMinutes)} OFF: ${formatMinutes(endStep.timeMinutes)}$tempsPart"
                } else {
                    "Sequence"
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = "Delete", 
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Timeline of Steps
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.steps.forEachIndexed { index, step ->
                    StepBox(
                        step = step,
                        isFirst = index == 0,
                        isLast = index == row.steps.size - 1,
                        onUpdateTime = { newMinutes ->
                            val newSteps = row.steps.toMutableList()
                            var shifted = false
                            
                            // Apply shift only to the current step if it would be before the previous one
                            var finalMinutes = newMinutes
                            if (index > 0 && finalMinutes <= newSteps[index - 1].timeMinutes) {
                                finalMinutes = (newSteps[index - 1].timeMinutes + 1) % 1440
                                shifted = true
                            }
                            
                            newSteps[index] = step.copy(timeMinutes = finalMinutes)
                            
                            if (shifted) onNotifyShift()
                            onUpdate(row.copy(steps = newSteps))
                        },
                        onUpdateTemp = { newTemp ->
                            val newSteps = row.steps.toMutableList()
                            newSteps[index] = step.copy(temperatureF = newTemp)
                            onUpdate(row.copy(steps = newSteps))
                        },
                        onRemove = if (row.steps.size > 2 && index != 0 && index != row.steps.size - 1) {
                            {
                                val newSteps = row.steps.toMutableList()
                                newSteps.removeAt(index)
                                onUpdate(row.copy(steps = newSteps))
                            }
                        } else null
                    )
                    
                    if (index < row.steps.size - 1) {
                        IconButton(
                            onClick = {
                                val nextStep = row.steps[index + 1]
                                var midTime = (step.timeMinutes + nextStep.timeMinutes) / 2
                                if (nextStep.timeMinutes <= step.timeMinutes) {
                                    midTime = (step.timeMinutes + (nextStep.timeMinutes + 1440)) / 2 % 1440
                                }
                                val newSteps = row.steps.toMutableList()
                                newSteps.add(index + 1, ScheduleStep(timeMinutes = midTime, temperatureF = step.temperatureF))
                                onUpdate(row.copy(steps = newSteps))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.AddCircleOutline,
                                contentDescription = "Insert Point",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            
            // Bottom Section: Day Bubbles
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
                                val newDays = if (isSelected) row.days - oolerDayIndex else row.days + oolerDayIndex
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
fun StepBox(
    step: ScheduleStep,
    isFirst: Boolean,
    isLast: Boolean,
    onUpdateTime: (Int) -> Unit,
    onUpdateTemp: (Int) -> Unit,
    onRemove: (() -> Unit)?
) {
    val context = LocalContext.current
    var showTempDialog by remember { mutableStateOf(false) }

    val boxColor = when {
        isFirst -> AppGreen
        isLast -> AppRed
        else -> MaterialTheme.colorScheme.primary
    }

    Box(contentAlignment = Alignment.TopStart) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, start = 8.dp) 
                .width(110.dp)
                .height(110.dp) 
                .border(1.dp, boxColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .background(boxColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isFirst) "START" else if (isLast) "END" else "CHANGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = boxColor,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = formatMinutes(step.timeMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        showTimePicker(context, step.timeMinutes, onUpdateTime)
                    }
                )
                
                if (!isLast) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        onClick = { showTempDialog = true },
                        color = boxColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${step.temperatureF}°",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "OFF",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppTextGrey,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f), CircleShape)
                    .clip(CircleShape)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showTempDialog) {
        TemperaturePickerDialog(
            initialTemp = step.temperatureF,
            onDismiss = { showTempDialog = false },
            onConfirm = { 
                onUpdateTemp(it)
                showTempDialog = false
            }
        )
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

private fun isRowConflicting(row1: ScheduleRow, row2: ScheduleRow): Boolean {
    val start1 = row1.steps.first().timeMinutes
    val end1 = row1.steps.last().timeMinutes
    val start2 = row2.steps.first().timeMinutes
    val end2 = row2.steps.last().timeMinutes

    fun inRange(time: Int, start: Int, end: Int) = if (end > start) time in start until end else time >= start || time < end
    
    return inRange(start1, start2, end2) || inRange(start2, start1, end1)
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
