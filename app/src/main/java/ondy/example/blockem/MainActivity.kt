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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.*
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import ondy.example.blockem.ui.theme.BlockEmTheme
import androidx.compose.foundation.background

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

    val ignoreFirst by dataStore.ignoreFirstScrollFlow.collectAsState(initial = true)
    val ignoreIgHome by dataStore.ignoreIgHomeFlow.collectAsState(initial = true)
    val ignoreDmGlobal by dataStore.ignoreDmGlobalFlow.collectAsState(initial = true)
    val ignoreDmIg by dataStore.ignoreDmIgFlow.collectAsState(initial = true)
    val ignoreDmTt by dataStore.ignoreDmTtFlow.collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Scroll Counter") }, navigationIcon = {
                Button(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("Back") }
            })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(scrollState)) {

            // Global Switch
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Enable Engine", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Switch(checked = global, onCheckedChange = { scope.launch { dataStore.setSwitch(SettingsDataStore.GLOBAL_ENABLED, it) } })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // -- NEW: SCROLL LIMIT PANEL --
            Text("Hard Limit", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Max Scrolls / Day", fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { scope.launch { dataStore.setMaxScrolls(maxOf(1, maxScrolls - 5)) } }) {
                        Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("$maxScrolls", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    FilledTonalIconButton(onClick = { scope.launch { dataStore.setMaxScrolls(maxScrolls + 5) } }) {
                        Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Target Apps", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
            AppSwitchRow("Instagram Reels", ig, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.IG_ENABLED, it) } }
            AppSwitchRow("TikTok", tt, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.TT_ENABLED, it) } }
            AppSwitchRow("YouTube Shorts", yt, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.YT_ENABLED, it) } }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Exclusions", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
            AppSwitchRow("Ignore First Scroll on App Open", ignoreFirst, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_FIRST_SCROLL, it) } }
            AppSwitchRow("Ignore IG Home Feed Videos", ignoreIgHome, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_IG_HOME, it) } }

            Spacer(modifier = Modifier.height(8.dp))
            AppSwitchRow("Ignore DM Videos (Global)", ignoreDmGlobal, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_DM_GLOBAL, it) } }

            if (ignoreDmGlobal) {
                AppSwitchRow("   ↳ Instagram DMs", ignoreDmIg, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_DM_IG, it) } }
                AppSwitchRow("   ↳ TikTok DMs", ignoreDmTt, enabled = global) { scope.launch { dataStore.setSwitch(SettingsDataStore.IGNORE_DM_TT, it) } }
            }
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
    val scrollsToday by dataStore.dailyScrollsFlow.collectAsState(initial = 0)

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Scrolls Today", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$scrollsToday", fontSize = 80.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun PermissionsScreen(onPermissionsGranted: () -> Unit = {}, isSettingsTab: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentPage by remember { mutableStateOf(0) }
    var resumeTrigger by remember { mutableStateOf(0) }

    // This guarantees the UI updates the EXACT millisecond you return from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 1. Instantly re-check system permissions
                val overlayGranted = checkOverlayPermission(context)
                val accessibilityGranted = checkAccessibilityPermission(context)

                // 2. Instantly auto-advance the page if they granted it
                if (!isSettingsTab) {
                    if (overlayGranted && accessibilityGranted) {
                        onPermissionsGranted()
                    } else if (overlayGranted && currentPage == 1) {
                        currentPage = 2
                    }
                }

                // 3. Force the UI to refresh (grays out the buttons)
                resumeTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Read the permissions dynamically on every refresh
    val isAccessibilityGranted = remember(resumeTrigger) { checkAccessibilityPermission(context) }
    val isOverlayGranted = remember(resumeTrigger) { checkOverlayPermission(context) }

    // SETTINGS TAB VIEW
    if (isSettingsTab) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Permissions",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            PermissionCard("Display Over Other Apps", "Required to show the counter.", isOverlayGranted) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            Spacer(modifier = Modifier.height(16.dp))
            PermissionCard("Accessibility Service", "Required to detect scrolling.", isAccessibilityGranted) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        return
    }

    // ONBOARDING VIEW
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Bar: Progress and Skip Button
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val progress = (currentPage + 1) / 3f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f).height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            TextButton(onClick = onPermissionsGranted, modifier = Modifier.padding(start = 16.dp)) {
                Text("Skip All", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Page Content
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (currentPage) {
                0 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Welcome to BlockEm", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
                        Text(
                            text = "Take back control of your attention. To make this work seamlessly, we need two quick permissions.",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(bottom = 48.dp)
                        )
                        Button(
                            onClick = {
                                if (isOverlayGranted && isAccessibilityGranted) onPermissionsGranted()
                                else if (isOverlayGranted) currentPage = 2
                                else currentPage = 1
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Let's Go", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                1 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Step 1: Overlay", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
                        Text(
                            text = "We need permission to draw the scroll counter and block screen over your addictive apps.",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(bottom = 48.dp)
                        )
                        PermissionCard("Display Over Other Apps", "Required to show the counter.", isOverlayGranted) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                    }
                }
                2 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Step 2: Accessibility", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
                        Text(
                            text = "This allows the engine to securely count your scrolls and detect when you open Reels or Shorts.",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(bottom = 48.dp)
                        )
                        PermissionCard("Accessibility Service", "Required to detect scrolling.", isAccessibilityGranted) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCard(title: String, desc: String, isGranted: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = onClick,
                enabled = !isGranted,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (isGranted) "Granted" else "Grant Permission")
            }
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