package pl.apargb.milkyway

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = MilkywayColors) { MilkywayRoot() }
        }
    }
}

private enum class Section(val title: String, val icon: ImageVector) {
    Home("Start", Icons.Outlined.Home),
    Dashboard("Panel zmiany", Icons.Outlined.Dashboard),
    Tanks("Zbiorniki", Icons.Outlined.WaterDrop),
    Softlab("Softlab", Icons.Outlined.Description),
    Reminders("Przypomnienia", Icons.Outlined.Notifications),
    Notes("Notatki", Icons.Outlined.NoteAlt),
    Laboratory("Laboratorium", Icons.Outlined.Science),
    Production("Produkcja", Icons.Outlined.Factory),
    Completed(WAREHOUSE_TITLE, Icons.Outlined.Inventory2),
    Controls("Kontrola parametrów", Icons.Outlined.Tune)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MilkywayApp(cloud: CloudSessionState, session: CloudSession) {
    val inventoryModel: InventoryViewModel = viewModel()
    val inventory by inventoryModel.state.collectAsState()
    val history by inventoryModel.history.collectAsState()
    val topUps by inventoryModel.topUps.collectAsState()
    val notesModel: WorkNotesViewModel = viewModel()
    val notes by notesModel.state.collectAsState()
    val productionModel: ProductionViewModel = viewModel()
    val assumptions by productionModel.state.collectAsState()
    val queueModel: ProductionQueueViewModel = viewModel()
    val queue by queueModel.state.collectAsState()
    val saving = inventory.saving || notes.saving || queue.saving
    LaunchedEffect(cloud.uid, cloud.revision, saving) {
        if (cloud.configured && cloud.ready && !saving) {
            inventoryModel.reload(); notesModel.reload(); queueModel.reload()
            history.tankId?.let(inventoryModel::showHistory)
            topUps.tankId?.let(inventoryModel::showTopUps)
        }
    }
    var labUnlocked by remember { mutableStateOf(false) }
    var editorTankId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorMode by rememberSaveable { mutableStateOf(TankEditor.STATE.name) }
    var routingTankId by rememberSaveable { mutableStateOf<String?>(null) }
    var oilTypeTankId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsTankId by rememberSaveable { mutableStateOf<String?>(null) }
    var sectionName by rememberSaveable { mutableStateOf(Section.Home.name) }
    var productionReturnSection by rememberSaveable { mutableStateOf(Section.Home.name) }
    var selectedShift by rememberSaveable { mutableIntStateOf(0) }
    var departmentName by rememberSaveable { mutableStateOf(Department.Reception.name) }
    var controlName by rememberSaveable { mutableStateOf<String?>(null) }
    var productionDestinationName by rememberSaveable { mutableStateOf(ProductionDestination.MENU.name) }
    val productionDestination = ProductionDestination.valueOf(productionDestinationName)
    val section = Section.valueOf(sectionName)
    val controlKind = controlName?.let(NoteKind::valueOf)
    val inProduction = section == Section.Production || (section == Section.Controls && controlKind == NoteKind.PRODUCTION)
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) labUnlocked = false }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(section, context) {
        val window = (context as? ComponentActivity)?.window
        if (section == Section.Laboratory) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (section == Section.Laboratory) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun goBack() {
        if (section == Section.Laboratory) labUnlocked = false
        if (inProduction && productionDestination.parent != null) productionDestinationName = productionDestination.parent!!.name
        else if (section == Section.Controls && controlKind != null) controlName = null
        else sectionName = when (section) {
            Section.Production -> productionReturnSection
            Section.Completed, Section.Dashboard -> Section.Home.name
            else -> Section.Dashboard.name
        }
    }
    BackHandler(enabled = section != Section.Home && editorTankId == null && routingTankId == null && oilTypeTankId == null && detailsTankId == null && history.tankId == null && topUps.tankId == null) { goBack() }
    fun openEditor(tank: Tank, mode: TankEditor) {
        inventoryModel.clearError()
        editorTankId = tank.id
        editorMode = mode.name
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Milkyway", fontSize = 22.sp, letterSpacing = (-0.4).sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (selectedShift == 0) "Zarządzanie produkcją" else "Wybrana zmiana: $selectedShift",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }, navigationIcon = {
                if (section != Section.Home) IconButton(onClick = ::goBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = when {
                        section == Section.Dashboard -> "Zmień zmianę"
                        inProduction && productionDestination.parent == ProductionDestination.QUEUE -> "Wróć do kolejki produkcji"
                        inProduction && productionDestination.parent == ProductionDestination.MENU -> "Wróć do produkcji"
                        section == Section.Controls && controlKind != null -> "Wróć do kontroli parametrów"
                        section == Section.Completed || section == Section.Production && productionReturnSection == Section.Home.name -> "Wróć do ekranu głównego"
                        else -> "Wróć do panelu zmiany"
                    })
                }
            }, actions = {
                if (cloud.configured) IconButton(onClick = session::signOut, enabled = !saving, modifier = Modifier.testTag("cloud-sign-out")) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Wyloguj konto ${cloud.number}")
                }
                if (section == Section.Laboratory && labUnlocked) IconButton(onClick = { labUnlocked = false }) {
                    Icon(Icons.Outlined.Lock, contentDescription = "Zablokuj laboratorium")
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = if (section == Section.Home || section == Section.Dashboard)
                MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background))
        },
        bottomBar = { if (cloud.configured || section == Section.Home) CloudStatus(cloud, saving) }
    ) { padding ->
        if (section == Section.Tanks) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                TankDepartmentPage(Department.valueOf(departmentName), inventory,
                    onDepartment = { departmentName = it.name }, onTank = { detailsTankId = it.id },
                    onEdit = { openEditor(it, TankEditor.STATE) },
                    onOilType = { inventoryModel.clearError(); oilTypeTankId = it.id },
                    onRouting = { inventoryModel.clearError(); routingTankId = it.id }, onRetry = inventoryModel::reload)
            }
        } else if (section == Section.Laboratory) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (cloud.configured && !cloud.laboratory) {
                    Text("To konto jest operatorem. Pomiary laboratoryjne wymagają konta z uprawnieniami Laboratorium.", Modifier.padding(24.dp))
                } else if (labUnlocked) LaboratoryPage(inventory, inventoryModel, selectedShift, inventoryModel::reload)
                else LaboratoryLockPage(onUnlock = { labUnlocked = true })
            }
        } else if (section == Section.Completed) {
            Box(Modifier.fillMaxSize().padding(padding)) { ProductionCompletedPage(queue, queueModel) }
        } else if (inProduction) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                ProductionPage(selectedShift, notesModel, notes, inventory, assumptions, productionModel,
                    onEdit = { openEditor(it, TankEditor.STATE) }, onRetry = inventoryModel::reload,
                    destination = productionDestination, onNavigate = { productionDestinationName = it.name },
                    queue = queue, queueModel = queueModel)
            }
        } else if (section == Section.Reminders || section == Section.Notes || (section == Section.Controls && controlKind != null)) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                val kind = when (section) {
                    Section.Reminders -> NoteKind.REMINDER
                    Section.Notes -> NoteKind.CURRENT_NOTES
                    else -> controlKind!!
                }
                key(kind, selectedShift) {
                    WorkNotesPage(kind, selectedShift, notesModel, notes)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("main-list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (section != Section.Home && section != Section.Dashboard) item {
                    Text(section.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                when (section) {
                    Section.Home -> {
                        item { HomeOverview(null) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Wybierz zmianę", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                Text("Wybierz zmianę, na której pracujesz.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..3).forEach { shift ->
                                        ShiftSelectionCard(shift, selectedShift == shift) {
                                            selectedShift = shift; sectionName = Section.Dashboard.name
                                        }
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HomeProductionAction("Produkcja", "Planowanie i kolejka", Icons.Outlined.Factory, Department.Butter,
                                        Modifier.testTag("home-Production")) {
                                        productionReturnSection = Section.Home.name
                                        productionDestinationName = ProductionDestination.MENU.name
                                        sectionName = Section.Production.name
                                    }
                                    HomeProductionAction(Section.Completed.title, "Towar gotowy i nadwyżki", Icons.Outlined.Inventory2, Department.Processing,
                                        Modifier.testTag("home-Completed"), compactIcon = true) { sectionName = Section.Completed.name }
                                }
                            }
                        }
                    }
                    Section.Tanks -> Unit
                    Section.Softlab -> item {
                        val description = AppContent.softlabDescription
                        if (description.isNullOrBlank()) {
                            EmptyCard("Jak działa Softlab", "Instrukcje i wskazówki do programu pojawią się tutaj po dodaniu opisu.", Icons.Outlined.Description)
                        } else {
                            Card(Modifier.fillMaxWidth()) { Text(description, Modifier.padding(20.dp)) }
                        }
                    }
                    Section.Dashboard -> {
                        item { HomeOverview(selectedShift) }
                        item { MainMenu { sectionName = it.name; controlName = null; productionDestinationName = ProductionDestination.MENU.name } }
                    }
                    Section.Controls -> item { ControlMenu { controlName = it.name; productionDestinationName = ProductionDestination.MENU.name } }
                    Section.Reminders, Section.Notes, Section.Laboratory, Section.Production, Section.Completed -> Unit
                }
            }
        }
    }
    if (editorTankId == null && routingTankId == null && oilTypeTankId == null && history.tankId == null && topUps.tankId == null) {
        AppContent.tanks.find { it.id == detailsTankId }?.let { tank ->
            TankDetailsDialog(tank, inventory.overview.states[tank.id] ?: TankStatus(), inventory.overview.latest[tank.id],
                onClose = { detailsTankId = null }, onEdit = { openEditor(tank, TankEditor.STATE) },
                onReceive = { openEditor(tank, TankEditor.RECEIVE) }, onTransfer = { openEditor(tank, TankEditor.TRANSFER) },
                onHistory = { inventoryModel.showHistory(tank.id) },
                onTopUps = { inventoryModel.showTopUps(tank.id) },
                onRouting = { inventoryModel.clearError(); routingTankId = tank.id },
                onOilType = { inventoryModel.clearError(); oilTypeTankId = tank.id })
        }
    }
    AppContent.tanks.find { it.id == editorTankId }?.let { tank ->
        key(tank.id, editorMode) {
            TankOperationDialog(tank, TankEditor.valueOf(editorMode), inventory, selectedShift.takeIf { it != 0 },
                inventoryModel, onClose = { editorTankId = null })
        }
    }
    if (topUps.tankId != null) {
        TankTopUpsDialog(topUps, inventoryModel::hideTopUps, inventoryModel::loadMoreTopUps)
    }
    if (history.tankId != null) {
        TankHistoryDialog(history, inventoryModel::hideHistory, onRetry = { inventoryModel.showHistory(history.tankId!!) })
    }
    AppContent.tanks.find { it.id == routingTankId }?.let { tank ->
        key(tank.id) {
            TankRoutingDialog(tank, inventory, inventoryModel, selectedShift.takeIf { it != 0 }, onClose = { routingTankId = null })
        }
    }
    AppContent.tanks.find { it.id == oilTypeTankId }?.let { tank ->
        key(tank.id) {
            TankOilTypeDialog(tank, inventory, inventoryModel, selectedShift.takeIf { it != 0 }, onClose = { oilTypeTankId = null })
        }
    }
}

@Composable
private fun EmptyCard(title: String, description: String, icon: ImageVector = Icons.Outlined.WaterDrop) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                Icon(icon, null, Modifier.padding(16.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MainMenu(onSelect: (Section) -> Unit) {
    val items = listOf(Section.Tanks to Department.Reception,
        Section.Laboratory to Department.Processing, Section.Controls to Department.Powder,
        Section.Reminders to Department.Butter, Section.Softlab to Department.Processing)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(onClick = { onSelect(Section.Notes) }, modifier = Modifier.fillMaxWidth().testTag("menu-Notes"),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = Department.Powder.tint, shape = RoundedCornerShape(14.dp)) {
                    Icon(Section.Notes.icon, null, Modifier.padding(10.dp).size(22.dp), tint = Department.Powder.accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Section.Notes.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Uwagi i przekazanie zmiany", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Department.Powder.accent)
            }
        }
        Text("Obszary pracy", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        items.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (section, tone) ->
                    DashboardTile(section.title, when (section) {
                        Section.Tanks -> "Stany i przepływ materiału"
                        Section.Softlab -> "Opis działania programu"
                        Section.Reminders -> "Zadania i przypomnienia"
                        Section.Laboratory -> "Pomiary · dostęp przez PIN"
                        Section.Production -> "Plan, kolejka i wykonanie"
                        else -> "Klimatyzacja, mycie i kontrola"
                    }, section.icon, tone, Modifier.weight(1f).fillMaxHeight().testTag("menu-${section.name}")) { onSelect(section) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
