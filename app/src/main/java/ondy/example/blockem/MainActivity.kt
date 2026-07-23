package ondy.example.blockem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import kotlinx.coroutines.launch
import ondy.example.blockem.ui.theme.BlockEmTheme
import java.time.LocalDate
import androidx.compose.ui.input.pointer.pointerInput
import java.time.format.DateTimeFormatter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlockEmTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPerms by remember { mutableStateOf(checkAllPermissions(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPerms = checkAllPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(
        navController = navController,
        startDestination = if (hasPerms) "main_dashboard" else "permissions"
    ) {
        composable("permissions") {
            PermissionsScreen(
                onPermissionsGranted = {
                    navController.navigate("main_dashboard") {
                        popUpTo("permissions") { inclusive = true }
                    }
                }
            )
        }
        composable("main_dashboard") { MainDashboardScreen(navController) }
        composable("scroll_category") { ScrollCategoryScreen(onBack = { navController.popBackStack() }) }
    }
}

@Composable
fun MainDashboardScreen(navController: NavHostController) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = "Stats") },
                    label = { Text("Stats") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeScreen(navController)
                1 -> StatsScreen()
                2 -> PermissionsScreen(isSettingsTab = true)
            }
        }
    }
}

@Composable
fun HomeScreen(navController: NavHostController) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Categories", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 32.dp, bottom = 16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().clickable { navController.navigate("scroll_category") },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.padding(24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Scroll Counter & Blocker", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Manage tracking & hard limits", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollCategoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dataStore = remember { SettingsDataStore(context) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val global by dataStore.globalEnabledFlow.collectAsState(initial = true)
    val ig by dataStore.igEnabledFlow.collectAsState(initial = true)
    val tt by dataStore.ttEnabledFlow.collectAsState(initial = true)
    val yt by dataStore.ytEnabledFlow.collectAsState(initial = true)

    val maxScrolls by dataStore.maxScrollsFlow.collectAsState(initial = 50)
    val showScrollsLeft by dataStore.showScrollsLeftFlow.collectAsState(initial = true)

    val ignoreIgHome by dataStore.ignoreIgHomeFlow.collectAsState(initial = false)
    // The Global DM flow is still collected in the background, just not shown
    val ignoreDmGlobal by dataStore.ignoreDmGlobalFlow.collectAsState(initial = true)
    val ignoreDmIg by dataStore.ignoreDmIgFlow.collectAsState(initial = false)
    val ignoreDmTt by dataStore.ignoreDmTtFlow.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Scroll Counter") }, navigationIcon = {
                Button(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("Back") }
            })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(scrollState)) {

            //Eneble Engine
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Enable Engine", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Switch(checked = global, onCheckedChange = { scope.launch { dataStore.setSwitch(SettingsDataStore.GLOBAL_ENABLED, it) } })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            //Harad Limit
            Text("Hard Limit", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Max Scrolls / Day", fontSize = 16.sp, color = if (global) MaterialTheme.colorScheme.onSurface else Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { scope.launch { dataStore.setMaxScrolls(maxOf(1, maxScrolls - 5)) } }, enabled = global) { Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    Text("$maxScrolls", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp), color = if (global) MaterialTheme.colorScheme.onSurface else Color.Gray)
                    FilledTonalIconButton(onClick = { scope.launch { dataStore.setMaxScrolls(maxScrolls + 5) } }, enabled = global) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            AppSwitchRow("Show 'Scrolls Left' on Overlay", showScrollsLeft, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.SHOW_SCROLLS_LEFT, it) } }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            //TikTok
            Text("TikTok", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
            AppSwitchRow("Count TT Scrolls", tt, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.TT_ENABLED, it) } }
            AppSwitchRow("Ignore TT DMs", ignoreDmTt, enabled = global && tt) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_DM_TT, it) } }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            //Instagram
            Text("Instagram", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
            AppSwitchRow("Count IG Scrolls", ig, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.IG_ENABLED, it) } }
            AppSwitchRow("Ignore IG DMs", ignoreDmIg, enabled = global && ig) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_DM_IG, it) } }
            AppSwitchRow("Ignore IG Home Feed Videos", ignoreIgHome, enabled = global && ig) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_IG_HOME, it) } }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            //Youtube
            Text("YouTube", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
            AppSwitchRow("Count YT Scrolls", yt, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.YT_ENABLED, it) } }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun AppSwitchRow(name: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 16.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val dataStore = remember { SettingsDataStore(context) }
    val scope = rememberCoroutineScope()

    val textMeasurer = rememberTextMeasurer()

    val scrollsToday by dataStore.dailyScrollsFlow.collectAsState(initial = 0)
    val history by dataStore.historyFlow.collectAsState(initial = emptyMap())

    val past30Days = remember { (0..29).map { LocalDate.now().minusDays(it.toLong()).toString() }.reversed() }

    val formattedDates = remember {
        past30Days.map { dateStr ->
            try {
                LocalDate.parse(dateStr).format(DateTimeFormatter.ofPattern("MMM dd"))
            } catch (e: Exception) { "" }
        }
    }

    val graphData = past30Days.map { history[it] ?: 0 }

    val yesterdayDate = LocalDate.now().minusDays(1).toString()
    val yesterdayScrolls = history[yesterdayDate] ?: 0
    val diff = scrollsToday - yesterdayScrolls

    val primaryColor = MaterialTheme.colorScheme.primary
    val barInactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val tooltipBgColor = MaterialTheme.colorScheme.onSurface
    val tooltipTextColor = MaterialTheme.colorScheme.surface

    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Overview", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text("$scrollsToday", fontSize = 80.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)

            val percentText = when {
                yesterdayScrolls == 0 && scrollsToday == 0 -> "0%"
                yesterdayScrolls == 0 && scrollsToday > 0 -> "+100%"
                diff > 0 -> "+${((diff.toFloat() / yesterdayScrolls) * 100).toInt()}%"
                else -> "${((diff.toFloat() / yesterdayScrolls) * 100).toInt()}%"
            }

            val percentColor = when {
                diff > 0 -> Color(0xFFE53935)
                diff < 0 -> Color(0xFF43A047)
                else -> Color.Gray
            }

            Surface(
                color = percentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.offset(y = (-8).dp)
            ) {
                Text(
                    text = percentText,
                    color = percentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Past 30 Days", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start).padding(bottom = 16.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val isPressed = event.changes.any { it.pressed }

                            if (isPressed) {
                                val position = event.changes.firstOrNull()?.position
                                if (position != null) {
                                    val barWidth = size.width / 30f
                                    selectedIndex = (position.x / barWidth).toInt().coerceIn(0, 29)
                                }
                            } else {
                                selectedIndex = null
                            }
                        }
                    }
                }
        ) {
            val barWidth = size.width / 30f

            val rawMax = graphData.maxOrNull()?.coerceAtLeast(1) ?: 1
            val maxScrolls = rawMax * 1.3f

            graphData.forEachIndexed { index, value ->
                val barHeight = (value.toFloat() / maxScrolls) * size.height

                val color = if (index == 29) primaryColor else barInactiveColor
                val finalColor = if (selectedIndex != null && selectedIndex != index) color.copy(alpha = 0.4f) else color

                drawRoundRect(
                    color = finalColor,
                    topLeft = Offset(x = index * barWidth + (barWidth * 0.1f), y = size.height - barHeight),
                    size = Size(width = barWidth * 0.8f, height = barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }

            if (selectedIndex != null) {
                val index = selectedIndex!!
                val value = graphData[index]
                val dateStr = formattedDates[index]

                val centerX = index * barWidth + (barWidth / 2f)

                drawLine(
                    color = primaryColor,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, size.height),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                )

                val textToDraw = "$value scrolls\n$dateStr"
                val textLayout = textMeasurer.measure(
                    text = textToDraw,
                    style = TextStyle(
                        color = tooltipTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                )

                val tooltipWidth = textLayout.size.width.toFloat() + 32f
                val tooltipHeight = textLayout.size.height.toFloat() + 16f

                var tooltipX = centerX + 16f
                if (tooltipX + tooltipWidth > size.width) {
                    tooltipX = centerX - tooltipWidth - 16f
                }

                drawRoundRect(
                    color = tooltipBgColor,
                    topLeft = Offset(tooltipX, 0f),
                    size = Size(tooltipWidth, tooltipHeight),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )

                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(tooltipX + 16f, 8f)
                )
            }
        }
    }
}

@Composable
fun PermissionsScreen(onPermissionsGranted: () -> Unit = {}, isSettingsTab: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentPage by remember { mutableStateOf(0) }
    var resumeTrigger by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val overlayGranted = checkOverlayPermission(context)
                val accessibilityGranted = checkAccessibilityPermission(context)

                if (!isSettingsTab) {
                    if (overlayGranted && accessibilityGranted) onPermissionsGranted()
                    else if (overlayGranted && currentPage == 1) currentPage = 2
                }
                resumeTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isAccessibilityGranted = remember(resumeTrigger) { checkAccessibilityPermission(context) }
    val isOverlayGranted = remember(resumeTrigger) { checkOverlayPermission(context) }

    if (isSettingsTab) {
        val scope = rememberCoroutineScope()
        val dataStore = remember { SettingsDataStore(context) }

        val showDebugUi by dataStore.showDebugUiFlow.collectAsState(initial = false)

        var showMockDataDialog by remember { mutableStateOf(false) }
        var showClearDataDialog by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Permissions", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 24.dp))
            PermissionCard("Display Over Other Apps", "Required to show the counter.", isOverlayGranted) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            Spacer(modifier = Modifier.height(16.dp))
            PermissionCard("Accessibility Service", "Required to detect scrolling.", isAccessibilityGranted) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

            Spacer(modifier = Modifier.height(32.dp))

            Text("Developer Options", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 8.dp).align(Alignment.Start))
            AppSwitchRow("Show Debug Overlay (Green Text)", showDebugUi, enabled = true) { scope.launch { dataStore.setSwitch(SettingsDataStore.SHOW_DEBUG_UI, it) } }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Data Management", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))

            Button(
                onClick = { showClearDataDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Clear Today's Data", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showMockDataDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Generate Mock Data (Graph)", color = MaterialTheme.colorScheme.onSurface)
            }
        }

        if (showClearDataDialog) {
            AlertDialog(
                onDismissRequest = { showClearDataDialog = false },
                title = { Text("Clear Today's Data?") },
                text = { Text("This will reset your scroll count for today to 0. This action cannot be undone!") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { dataStore.clearTodaysData() }
                        showClearDataDialog = false
                    }) { Text("Clear", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text("Cancel") } }
            )
        }

        if (showMockDataDialog) {
            AlertDialog(
                onDismissRequest = { showMockDataDialog = false },
                title = { Text("Generate Mock Data?") },
                text = { Text("This will overwrite your whole scrolls history with random fake data. This action cannot be undone!") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { dataStore.injectMockData() }
                        showMockDataDialog = false
                    }) { Text("Generate", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { showMockDataDialog = false }) { Text("Cancel") } }
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val progress = (currentPage + 1) / 3f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(8.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            TextButton(onClick = onPermissionsGranted, modifier = Modifier.padding(start = 16.dp)) { Text("Skip All", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when (currentPage) {
                0 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Are you cooked?", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
                        Text("Cut the slop from your life and unfry your brain. To make this work seamlessly, we need two quick permissions.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(bottom = 48.dp))
                        Button(onClick = { if (isOverlayGranted && isAccessibilityGranted) onPermissionsGranted() else if (isOverlayGranted) currentPage = 2 else currentPage = 1 }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Let's Go", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                1 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Step 1: Overlay", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
                        Text("We need permission to draw the scroll counter and block screen over your addictive apps.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(bottom = 48.dp))
                        PermissionCard("Display Over Other Apps", "Required to show overlays.", isOverlayGranted) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                    }
                }
                2 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Step 2: Accessibility", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
                        Text("This allows us to count your scrolls and detect when you open Reels or Shorts.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(bottom = 48.dp))
                        PermissionCard("Accessibility Service", "Required for general functionality.", isAccessibilityGranted) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCard(title: String, desc: String, isGranted: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = onClick, enabled = !isGranted, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (isGranted) "Granted" else "Grant Permission") }
        }
    }
}

fun checkAllPermissions(context: Context) = checkAccessibilityPermission(context) && checkOverlayPermission(context)
fun checkAccessibilityPermission(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, BlockerService::class.java).flattenToString()
    val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabledServicesSetting.split(':').contains(expectedComponentName)
}
fun checkOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)