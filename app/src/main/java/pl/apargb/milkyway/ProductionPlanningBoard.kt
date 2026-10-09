package pl.apargb.milkyway

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** One inbox per line, and a separately scrollable daily production timeline. */
@Composable
internal fun ProductionPlanningBoard(line: ProductionLine, ui: ProductionQueueState, model: ProductionQueueViewModel) {
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleOrder by rememberSaveable { mutableStateOf(false) }
    var completionId by rememberSaveable { mutableStateOf<String?>(null) }
    var notesId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var codeId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.entries, deletingId) {
        if (!ui.loading && !ui.saving && deletingId != null && ui.entries.none { it.id == deletingId }) deletingId = null
    }
    val date = LocalDate.parse(selectedDate)
    val enabled = !ui.loading && !ui.saving && ui.loadError == null
    val activeByDay = ui.entries.filter { it.line == line && !it.pendingOrder && !it.completed }
        .sortedWith(compareBy<ProductionQueueEntry> { it.scheduledTime ?: LocalTime.MIN }.thenBy { it.position }).groupBy { it.date }
    val daily = activeByDay[date].orEmpty()
    val orders = ui.entries.filter { it.line == line && it.pendingOrder }.sortedBy { it.createdAt }
    val firstDate = LocalDate.of(1900, 1, 1)
    val lastDate = LocalDate.of(2100, 12, 31)
    var windowStart by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(1).toString()) }
    var windowEnd by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    val startDate = LocalDate.parse(windowStart)
    val dayCount = ChronoUnit.DAYS.between(startDate, LocalDate.parse(windowEnd)).toInt() + 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 13)
    val scope = rememberCoroutineScope()
    var jumping by remember { mutableStateOf(false) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    var heldId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = heldId != null) { if (!ui.saving) heldId = null }
    var heldAnchor by remember { mutableStateOf(Offset.Zero) }
    var heldDistance by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(ui.entries, heldId) {
        if (heldId != null && !ui.loading && ui.entries.none { it.id == heldId && !it.completed }) heldId = null
    }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var dragged by remember { mutableStateOf<ProductionQueueEntry?>(null) }
    var dragAnchor by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var planBounds by remember { mutableStateOf(Rect.Zero) }
    var ordersBounds by remember { mutableStateOf(Rect.Zero) }
    var previousBounds by remember { mutableStateOf(Rect.Zero) }
    var nextBounds by remember { mutableStateOf(Rect.Zero) }
    val hourBounds = remember { mutableStateMapOf<ProductionPlanSlot, Rect>() }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    val density = LocalDensity.current
    val edge = with(density) { 52.dp.toPx() }
    val highlighted = if (dragged != null && planBounds.contains(pointer)) hourBounds.entries
        .firstOrNull { it.value.contains(pointer) }?.key else null
    val returningToOrders = dragged?.pendingOrder == false && ordersBounds.contains(pointer)
    val deleteThreshold = with(density) { 56.dp.toPx() }
    fun outwardSwipe(entry: ProductionQueueEntry): Boolean {
        val delta = pointer - dragAnchor
        return abs(delta.y) < abs(delta.x) * .75f &&
            (if (entry.pendingOrder) delta.x > deleteThreshold else delta.x < -deleteThreshold)
    }
    val deletingByGesture = dragged?.let { it.producedAmount.signum() == 0 && outwardSwipe(it) } == true
    fun changeDate(value: LocalDate) {
        if (value !in firstDate..lastDate) return
        navigationJob?.cancel()
        navigationJob = scope.launch {
            jumping = true
            try {
                selectedDate = value.toString(); model.clearError()
                windowStart = value.minusDays(1).coerceAtLeast(firstDate).toString()
                windowEnd = value.plusDays(1).coerceAtMost(lastDate).toString()
                withFrameNanos { }
                listState.scrollToItem(if (value == firstDate) 0 else 13)
            } finally { jumping = false }
        }
    }
    // Keep neighbouring days available; observing the visible day synchronizes the date and calendar.
    LaunchedEffect(listState) {
        snapshotFlow {
            if (jumping || listState.layoutInfo.visibleItemsInfo.isEmpty()) null else TimelineViewport(
                windowStart, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                listState.layoutInfo.visibleItemsInfo.last().index, listState.layoutInfo.totalItemsCount)
        }.collect { view ->
            if (view != null) {
                val visibleDay = LocalDate.parse(view.start).plusDays((view.first / 13).toLong())
                selectedDate = visibleDay.toString()
                if (view.last >= view.total - 13 && LocalDate.parse(windowEnd) < lastDate) {
                    windowEnd = LocalDate.parse(windowEnd).plusDays(1).toString()
                }

            }
        }
    }
    fun extendPreviousDay() {
        if (jumping || LocalDate.parse(windowStart) <= firstDate) return
        jumping = true
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        scope.launch {
            try {
                windowStart = LocalDate.parse(windowStart).minusDays(1).toString()
                withFrameNanos { }
                listState.scrollToItem(index + 13, offset)
            } finally { jumping = false }
        }
    }
    val timelineScroll = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0 && !listState.canScrollBackward && !ui.saving) extendPreviousDay()
            return Offset.Zero
        }
    }
    fun edit(entry: ProductionQueueEntry, schedule: Boolean = false) {
        if (heldId != null || dragged != null) return
        model.clearError(); scheduleOrder = schedule; editingId = entry.id
    }
    val startDrag: (ProductionQueueEntry, Offset, Boolean) -> Unit = { entry, point, immediate ->
        pointer = point; dragAnchor = point; heldDistance = Offset.Zero
        if (entry.pendingOrder || immediate) { dragged = entry; heldId = null }
        else { heldId = entry.id; heldAnchor = point }
    }
    val dragBy: (Offset) -> Unit = { amount ->
        pointer += amount
        if (dragged == null && heldId != null) {
            heldDistance += amount
            if (heldDistance.getDistance() > touchSlop) {
                dragged = ui.entries.find { it.id == heldId }; heldId = null
            }
        }
    }
    val finishDrag: () -> Unit = {
        val entry = dragged
        // Resolve the destination from the final pointer, even if rendering has not caught up with the last motion event.
        val destination = if (planBounds.contains(pointer)) hourBounds.entries.firstOrNull { it.value.contains(pointer) }?.key else null
        if (entry != null && enabled) {
            val target = cardBounds.entries.firstOrNull { it.key != entry.id && it.value.contains(pointer) }
                ?.let { hit -> ui.entries.find { it.id == hit.key }?.let { it to hit.value } }
            if (outwardSwipe(entry)) {
                if (entry.producedAmount.signum() == 0) { model.clearError(); deletingId = entry.id }
                else model.showError("Pozycja ma zapisane wykonanie. Wyprodukowany towar usuń w magazynie.")
            }
            else if (!entry.pendingOrder && ordersBounds.contains(pointer)) model.returnToPending(entry)
            else if (!entry.pendingOrder && target != null && !target.first.pendingOrder &&
                target.first.date == entry.date && target.first.scheduledTime == entry.scheduledTime) {
                model.reorder(entry, target.first, pointer.y >= target.second.center.y)
            } else if (destination != null && (entry.pendingOrder || destination.date != entry.date ||
                entry.scheduledTime == null || destination.hour != entry.scheduledTime.hour / 2 * 2)) {
                model.schedule(entry, destination.date, LocalTime.of(destination.hour, 0))
            }
        }
        dragged = null
    }
    // Edge scrolling keeps distant hours reachable without releasing the order. Hover arrows to change day.
    LaunchedEffect(dragged?.id) {
        var lastDayChange = 0L
        while (dragged != null) {
            val now = System.currentTimeMillis()
            if (now - lastDayChange > 850 && (previousBounds.contains(pointer) || nextBounds.contains(pointer))) {
                changeDate(LocalDate.parse(selectedDate).plusDays(if (nextBounds.contains(pointer)) 1 else -1))
                lastDayChange = now
            } else if (planBounds.contains(pointer)) {
                val speed = when {
                    pointer.y < planBounds.top + edge -> -with(density) { 12.dp.toPx() }
                    pointer.y > planBounds.bottom - edge -> with(density) { 12.dp.toPx() }
                    else -> 0f
                }
                if (speed < 0f && !listState.canScrollBackward) extendPreviousDay()
                else if (speed != 0f && !jumping) listState.scrollBy(speed)
            }
            delay(35)
        }
    }
    Box(Modifier.fillMaxSize().testTag("production-queue-page").onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }
        .productionProductGestures(enabled, heldId == null, ui.entries, cardBounds, rootOrigin,
            startDrag, dragBy, finishDrag, { dragged = null })) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kolejka produkcji", style = MaterialTheme.typography.labelSmall, color = line.tone.accent)
                    Text(line.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(onClick = { model.clearError(); scheduleOrder = false; editingId = UUID.randomUUID().toString() },
                    enabled = enabled, modifier = Modifier.testTag("queue-add"), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Zamówienie")
                }
            }
            Text("Przeciągnij między kolumnami. Na zewnątrz: usuń z PIN-em. Przytrzymaj: wykonanie lub zmiana kolejności.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editingId == null && completionId == null) ui.operationError?.let {
                Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.loadError != null) {
                Text(ui.loadError, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::reload) { Text("Ponów odczyt") }
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().background(line.tone.tint, RoundedCornerShape(16.dp)).padding(6.dp)) {
                    Text("Produkcja", Modifier.padding(start = 4.dp, top = 8.dp), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium, color = line.tone.accent)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { changeDate(date.minusDays(1)) }, enabled = !ui.saving && date > LocalDate.of(1900, 1, 1),
                            modifier = Modifier.size(40.dp).onGloballyPositioned { previousBounds = it.boundsInRoot() }) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Poprzedni dzień")
                        }
                        TextButton(onClick = { calendarOpen = true }, enabled = !ui.saving, modifier = Modifier.weight(1f).testTag("queue-date"),
                            contentPadding = PaddingValues(0.dp)) {
                            Text(queueDateLabel(date), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { changeDate(date.plusDays(1)) }, enabled = !ui.saving && date < LocalDate.of(2100, 12, 31),
                            modifier = Modifier.size(40.dp).onGloballyPositioned { nextBounds = it.boundsInRoot() }) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Następny dzień")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Plan: ${daily.size}", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { changeDate(LocalDate.now()) }, enabled = date != LocalDate.now() && !ui.saving,
                            contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Dzisiaj", style = MaterialTheme.typography.labelSmall) }
                    }
                    var horizontal by remember { mutableFloatStateOf(0f) }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue-list")
                        .onGloballyPositioned { planBounds = it.boundsInRoot() }
                        .nestedScroll(timelineScroll)
                        .pointerInput(selectedDate) {
                            detectHorizontalDragGestures(onDragStart = { horizontal = 0f }, onDragEnd = {
                                if (dragged == null && abs(horizontal) > edge * 1.3f) changeDate(date.plusDays(if (horizontal < 0) 1 else -1))
                            }) { _, amount -> horizontal += amount }
                        }, state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(count = dayCount * 13, key = { index ->
                            "${startDate.plusDays((index / 13).toLong())}-${index % 13}"
                        }) { index ->
                            val day = startDate.plusDays((index / 13).toLong())
                            val entries = activeByDay[day].orEmpty()
                            val dayBackground = productionDayBackground(day, line.tone.tint, MaterialTheme.colorScheme.surface)
                            if (index % 13 == 0) {
                                Column(Modifier.fillMaxWidth().testTag("queue-day-$day")
                                    .background(dayBackground, RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(queueDateLabel(day), Modifier.padding(4.dp), fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium, color = line.tone.accent)
                                    if (entries.isEmpty()) Text("Brak produkcji na ten dzień", Modifier.padding(4.dp),
                                        style = MaterialTheme.typography.bodySmall, color = line.tone.accent)
                                    val untimed = entries.filter { it.scheduledTime == null }
                                    if (untimed.isNotEmpty()) Text("Bez godziny", Modifier.padding(4.dp), style = MaterialTheme.typography.labelMedium)
                                    untimed.forEach { entry -> key(entry.id) {
                                        PlanningCard(entry, "${entries.indexOf(entry) + 1}. ${entry.title}", enabled,
                                            { edit(entry) }, { completionId = entry.id; model.clearError() },
                                            { bounds -> if (bounds == null) cardBounds.remove(entry.id) else cardBounds[entry.id] = bounds },
                                            { model.clearError(); notesId = entry.id }, ui.productNotes.firstOrNull { it.entryId == entry.id && !it.isProductionCode() },
                                            productionCode(ui.productNotes, entry.id), { model.clearError(); codeId = entry.id })
                                    } }
                                }
                            } else {
                                val hour = (index % 13 - 1) * 2
                                val slot = ProductionPlanSlot(day, hour)
                                DisposableEffect(slot) { onDispose { hourBounds.remove(slot) } }
                                val scheduled = entries.filter { it.scheduledTime?.hour?.let { h -> h >= hour && h < hour + 2 } == true }
                                Surface(Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("queue-hour-$day-$hour")
                                    .onGloballyPositioned { hourBounds[slot] = it.boundsInRoot() }, shape = RoundedCornerShape(12.dp),
                                    color = if (highlighted == slot) line.tone.accent.copy(alpha = .18f) else dayBackground,
                                    border = if (highlighted == slot) BorderStroke(2.dp, line.tone.accent) else null) {
                                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("%02d:00–%02d:00".format(hour, hour + 2), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = line.tone.accent)
                                        if (scheduled.isEmpty()) Text(if (highlighted == slot) "Upuść tutaj" else "—",
                                            Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        scheduled.forEach { entry -> key(entry.id) {
                                            PlanningCard(entry, "${entries.indexOf(entry) + 1}. ${entry.title}", enabled,
                                                { edit(entry) }, { completionId = entry.id; model.clearError() },
                                                { bounds -> if (bounds == null) cardBounds.remove(entry.id) else cardBounds[entry.id] = bounds },
                                                { model.clearError(); notesId = entry.id }, ui.productNotes.firstOrNull { it.entryId == entry.id && !it.isProductionCode() },
                                                productionCode(ui.productNotes, entry.id), { model.clearError(); codeId = entry.id })
                                        } }
                                    }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().testTag("queue-pending-column")
                    .onGloballyPositioned { ordersBounds = it.boundsInRoot() }
                    .background(if (returningToOrders) line.tone.accent.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(16.dp)).padding(6.dp)) {
                    Text("Wpłynęło\nzamówienie", Modifier.padding(start = 4.dp, top = 8.dp), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (returningToOrders) "Upuść, aby cofnąć do oczekujących" else "Oczekujące: ${orders.size}", Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue-orders"), verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (orders.isEmpty()) item {
                            Text("Brak oczekujących zamówień", Modifier.padding(6.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(orders, key = { it.id }) { entry ->
                            PlanningCard(entry, entry.title, enabled, { edit(entry) }, {},                                 { bounds -> if (bounds == null) cardBounds.remove(entry.id) else cardBounds[entry.id] = bounds },
                                { model.clearError(); notesId = entry.id }, ui.productNotes.firstOrNull { it.entryId == entry.id && !it.isProductionCode() },
                                productionCode(ui.productNotes, entry.id), { model.clearError(); codeId = entry.id })
                        }
                    }
                }
            }
        }
        ui.entries.find { it.id == heldId && !it.pendingOrder && !it.completed }?.let { entry ->
            BoxWithConstraints(Modifier.matchParentSize().imePadding()) {
                Box(Modifier.matchParentSize().clickable(enabled = !ui.saving) { heldId = null })
                val anchor = with(density) { (heldAnchor.y - rootOrigin.y).toDp() }
                val top = (anchor - 70.dp).coerceIn(8.dp, (maxHeight - 360.dp).coerceAtLeast(8.dp))
                key(entry.id) {
                    Surface(Modifier.padding(horizontal = 12.dp).offset(y = top).fillMaxWidth()
                        .heightIn(max = (maxHeight - top - 8.dp).coerceAtLeast(48.dp)).testTag("production-completion-bubble"),
                        shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, entry.line.tone.accent.copy(alpha = .35f)),
                        color = MaterialTheme.colorScheme.surface) {
                        ProductionCompletionBubble(entry, ui, model, onClose = { heldId = null }, onNotes = { model.clearError(); notesId = entry.id })
                    }
                }
            }
        }
        dragged?.let { entry ->
            Surface(Modifier.offset { IntOffset((pointer.x - rootOrigin.x - 70.dp.toPx()).roundToInt(),
                (pointer.y - rootOrigin.y - 35.dp.toPx()).roundToInt()) }.width(150.dp),
                shadowElevation = 8.dp, shape = RoundedCornerShape(12.dp),
                color = if (deletingByGesture) MaterialTheme.colorScheme.error else line.tone.accent) {
                Column(Modifier.padding(10.dp)) {
                    Text(entry.title, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(if (deletingByGesture) "Usuń · PIN 5522" else if (returningToOrders) "Do oczekujących" else highlighted?.let { "${queueDateLabel(it.date)} · %02d:00".format(it.hour) } ?: "Przenieś na godzinę",
                        color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (calendarOpen) QueueDatePicker(date, { calendarOpen = false }, { changeDate(it); calendarOpen = false })
    editingId?.let { id -> key(id) {
        ProductionQueueEditor(id, ui.entries.find { it.id == id }, line, date, ui, model,
            onClose = { editingId = null }, scheduleOrder = scheduleOrder)
    } }
    ui.entries.find { it.id == completionId && !it.pendingOrder }?.let { entry -> key(entry.id) {
        ProductionCompletionDialog(entry, ui, model, onClose = { completionId = null })
    } }
    ui.entries.find { it.id == notesId }?.let { entry -> key(entry.id) {
        ProductNotesDialog(entry, if (entry.pendingOrder) ProductNoteStage.ORDER else ProductNoteStage.PRODUCTION,
            ui, model, onClose = { notesId = null })
    } }
    ui.entries.find { it.id == codeId }?.let { entry -> key(entry.id) {
        ProductionCodeDialog(entry, ui, model, onClose = { codeId = null })
    } }
    ui.entries.find { it.id == deletingId }?.let { entry -> key(entry.id) {
        WarehouseDeleteDialog("Usunąć pozycję?", entry.title + "\nUsunięcie obejmuje również notatki tego produktu.",
            saving = ui.saving, error = ui.operationError, confirmTag = "queue-delete-confirm", pinTag = "queue-delete-pin",
            onDelete = { model.delete(entry, it) }, onClose = { deletingId = null; model.clearError() })
    } }
}

@Composable
private fun PlanningCard(entry: ProductionQueueEntry, title: String, enabled: Boolean, onEdit: () -> Unit,
                         onComplete: () -> Unit, onBounds: (Rect?) -> Unit, onNotes: () -> Unit, latestNote: ProductNote?, code: String, onCode: () -> Unit) {
    val updateBounds by rememberUpdatedState(onBounds)
    DisposableEffect(entry.id) { onDispose { updateBounds(null) } }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val background = productionCardBackground(entry.id)
    val note = latestNote?.text?.takeIf { it.isNotBlank() } ?: entry.description
    val preview = if (entry.pendingOrder) orderNotePreview(note) else note.trim().replace(Regex("[\\s\\u00a0]+"), " ")
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        Surface(onClick = onEdit, enabled = enabled, modifier = Modifier.fillMaxWidth().height(156.dp * fontScale).testTag("queue-entry-${entry.id}")
            .onGloballyPositioned { updateBounds(it.boundsInRoot()) }, color = background, shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, entry.line.tone.accent.copy(alpha = .22f))) {
            Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth().height(32.dp * fontScale), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f).testTag("queue-edit-${entry.id}")
                        .clickable(enabled = enabled, onClickLabel = "Edytuj: ${entry.title}", onClick = onEdit),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = entry.line.tone.accent)
                    IconButton(onClick = onNotes, enabled = enabled, modifier = Modifier.size(32.dp).testTag("product-notes-${entry.id}")) {
                        Icon(Icons.Outlined.NoteAlt, "Notatki: ${entry.title}", Modifier.size(17.dp), tint = entry.line.tone.accent)
                    }
                }
                Text(preview.ifBlank { "—" }, modifier = Modifier.height(28.dp * fontScale).testTag("queue-note-preview-${entry.id}"),
                    style = MaterialTheme.typography.labelSmall, maxLines = if (entry.pendingOrder) 1 else 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().height(32.dp * fontScale), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.remainingAmount?.let { "${if (entry.pendingOrder) "Oczekuje" else "Pozostało"}: ${decimalLabel(it)} ${entry.unit.label}" } ?: "Uzupełnij ilość",
                        Modifier.weight(1f).testTag("queue-${if (entry.pendingOrder) "pending-remaining" else "remaining"}-${entry.id}"),
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = entry.line.tone.accent)
                    OutlinedButton(onClick = onCode, enabled = enabled, modifier = Modifier.width(46.dp).height(28.dp).testTag("queue-code-${entry.id}"),
                        shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(0.dp)) {
                        Text(code.ifEmpty { "Kod" }, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Box(Modifier.fillMaxWidth().height(16.dp * fontScale)) {
                    Text(if (entry.producedAmount.signum() > 0) "Wykonano: ${decimalLabel(entry.producedAmount)} ${entry.unit.label}"
                        else entry.scheduledTime?.let { "Godzina: $it" }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!entry.pendingOrder && entry.date == LocalDate.now()) {
                    OutlinedButton(onClick = onComplete, enabled = enabled && entry.remainingAmount?.signum() == 1,
                        modifier = Modifier.fillMaxWidth().height(28.dp * fontScale).testTag("queue-produced-${entry.id}"),
                        contentPadding = PaddingValues(0.dp)) { Text("Wyprodukowano", style = MaterialTheme.typography.labelSmall) }
                } else Spacer(Modifier.height(28.dp * fontScale))
            }
        }
    }
}

// Observe card gestures on the board so dragging continues when auto-scroll recycles the source card.
@Composable
private fun Modifier.productionProductGestures(enabled: Boolean, canStartGesture: Boolean, entries: List<ProductionQueueEntry>,
    cardBounds: Map<String, Rect>, rootOrigin: Offset, onDragStart: (ProductionQueueEntry, Offset, Boolean) -> Unit,
    onDrag: (Offset) -> Unit, onDragEnd: () -> Unit, onDragCancel: () -> Unit): Modifier {
    val config = LocalViewConfiguration.current
    val haptics = LocalHapticFeedback.current
    val currentEntries by rememberUpdatedState(entries)
    val currentOrigin by rememberUpdatedState(rootOrigin)
    val canStart by rememberUpdatedState(canStartGesture)
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    return pointerInput(enabled, config.touchSlop, config.longPressTimeoutMillis) {
        if (enabled) awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val entry = currentEntries.firstOrNull { value -> cardBounds[value.id]?.contains(currentOrigin + down.position) == true }
            if (entry == null || !canStart) return@awaitEachGesture
            var activation: Offset? = null
            var immediate = false
            var abandoned = false
            // Claim horizontal movement before either list handles it; vertical movement keeps normal scrolling.
            val timed = withTimeoutOrNull(config.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) { abandoned = true; break }
                    val distance = change.position - down.position
                    if (abs(distance.x) > config.touchSlop && abs(distance.x) > abs(distance.y)) {
                        change.consume(); activation = distance; immediate = true; break
                    }
                    if (abs(distance.y) > config.touchSlop || change.isConsumed) { abandoned = true; break }
                }
                true
            }
            if (!abandoned && (activation != null || timed == null)) {
                start(entry, currentOrigin + down.position, immediate)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                activation?.let { drag(it) }
                var finished = false
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume(); end(); finished = true; break
                        }
                        val amount = change.position - change.previousPosition
                        change.consume()
                        if (amount != Offset.Zero) drag(amount)
                    }
                } finally { if (!finished) cancel() }
            }
        }
    }
}

internal fun productionCardBackground(id: String): Color = Color.hsv(Math.floorMod(id.hashCode(), 360).toFloat(), .13f, .99f)

internal data class ProductionPlanSlot(val date: LocalDate, val hour: Int)
internal fun orderNotePreview(text: String): String = text.trim().split(Regex("[\\s\\u00a0]+"))
    .filter { it.isNotEmpty() }.take(2).joinToString(" ")
private data class TimelineViewport(val start: String, val first: Int, val offset: Int, val last: Int, val total: Int)
