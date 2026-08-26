package com.vpn.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint

// Screen destinations
enum class AppScreen { Splash, Paywall, Home, ServerSelect, Settings }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ FIX #9: Request POST_NOTIFICATIONS at runtime on Android 13+.
        // Without this, the VPN status notification is silently dropped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = DeepNavy,
                    surface = SurfaceCard,
                    primary = PurpleAccent,
                    onPrimary = Color.White
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = DeepNavy) {
                    VpnAppNavigation()
                }
            }
        }
    }
}

@Composable
fun VpnAppNavigation(viewModel: VpnViewModel = viewModel()) {
    val isInitialized       by viewModel.isInitialized.collectAsState()
    val effectiveSubscribed by viewModel.effectiveSubscriptionActive.collectAsState()
    val consentAccepted     by viewModel.consentAccepted.collectAsState()
    var currentScreen       by remember { mutableStateOf(AppScreen.Splash) }

    // Wait until all async init tasks complete before making navigation decisions.
    // This prevents the race where consentAccepted and effectiveSubscribed are both
    // false (default) before DataStore/EncryptedPrefs have finished loading.
    if (!isInitialized) {
        SplashLoadingScreen()
        return
    }

    // ── Initial screen selection — runs ONCE when isInitialized first becomes true ──
    // BUG FIX: The old code used LaunchedEffect(effectiveSubscribed, consentAccepted)
    // which only fires when those values *change*. But both are already loaded from
    // disk BEFORE isInitialized becomes true, so for a returning subscribed user the
    // values never change (true→true) and the effect never navigates away from Splash,
    // which falls through to Paywall. Fix: use a one-shot LaunchedEffect(isInitialized)
    // to set the correct starting screen as soon as init completes.
    LaunchedEffect(isInitialized) {
        currentScreen = when {
            effectiveSubscribed -> AppScreen.Home
            consentAccepted     -> AppScreen.Paywall
            else                -> AppScreen.Splash  // first launch — show consent
        }
    }

    // React to subscription becoming active AFTER the initial screen is set.
    // Handles: mid-session purchase, background restore, or subscription re-activation.
    LaunchedEffect(effectiveSubscribed) {
        if (effectiveSubscribed && currentScreen == AppScreen.Paywall) {
            currentScreen = AppScreen.Home
        }
    }

    // Close Paywall immediately when Google Play confirms PURCHASED.
    // Don't wait for server verify — that happens in the background.
    LaunchedEffect(Unit) {
        viewModel.purchaseJustConfirmed.collect {
            currentScreen = AppScreen.Home
        }
    }

    when {
        // ── Consent / First Launch ────────────────────────────────────
        currentScreen == AppScreen.Splash -> {
            ConsentScreen(
                onAccepted = {
                    viewModel.acceptConsent()
                    currentScreen = if (effectiveSubscribed) AppScreen.Home else AppScreen.Paywall
                }
            )
        }

        // ── Paywall ───────────────────────────────────────────────────
        currentScreen == AppScreen.Paywall -> {
            val activity = LocalContext.current as Activity
            PaywallScreen(
                onClose = { currentScreen = AppScreen.Home },
                onPurchaseComplete = { plan ->
                    viewModel.buySubscription(activity, plan)
                },
                onRestore = { viewModel.restorePurchases() },
                viewModel = viewModel
            )
        }

        // ── Main App with Bottom Nav ──────────────────────────────────
        else -> {
            Scaffold(
                containerColor = DeepNavy,
                bottomBar = {
                    AppBottomNav(
                        current = currentScreen,
                        onNavigate = { currentScreen = it }
                    )
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    when (currentScreen) {
                        AppScreen.Home -> HomeScreen(
                            viewModel    = viewModel,
                            onOpenServers  = { currentScreen = AppScreen.ServerSelect },
                            onOpenSettings = { currentScreen = AppScreen.Settings },
                            onOpenPaywall  = { currentScreen = AppScreen.Paywall }
                        )
                        AppScreen.ServerSelect -> ServerSelectionScreen(
                            viewModel = viewModel,
                            onBack    = { currentScreen = AppScreen.Home }
                        )
                        AppScreen.Settings -> SettingsScreen(
                            viewModel = viewModel,
                            onBack    = { currentScreen = AppScreen.Home },
                            onLogout  = { currentScreen = AppScreen.Splash }
                        )
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun SplashLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavy),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = PurpleAccent,
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Loading…",
                fontSize = 13.sp,
                color = TextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AppBottomNav(current: AppScreen, onNavigate: (AppScreen) -> Unit) {
    NavigationBar(
        containerColor = SurfaceCard,
        tonalElevation = 0.dp
    ) {
        listOf(
            Triple(AppScreen.Home,         Icons.Default.Lock,        "Shield"),
            Triple(AppScreen.ServerSelect, Icons.Default.LocationOn,  "Servers"),
            Triple(AppScreen.Settings,     Icons.Default.Settings,    "Settings")
        ).forEach { (screen, icon, label) ->
            val selected = current == screen
            NavigationBarItem(
                selected = selected,
                onClick  = { onNavigate(screen) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(if (selected) 24.dp else 22.dp)
                    )
                },
                label = {
                    Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor   = PurpleAccent,
                    selectedTextColor   = PurpleAccent,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor      = PurpleAccent.copy(alpha = 0.15f)
                )
            )
        }
    }
}

