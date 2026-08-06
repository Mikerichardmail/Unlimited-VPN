package com.vpn.android.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint

// Screen destinations
enum class AppScreen { Splash, Paywall, Home, ServerSelect, Settings }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val effectiveSubscribed by viewModel.effectiveSubscriptionActive.collectAsState()
    val consentAccepted by viewModel.consentAccepted.collectAsState()
    var currentScreen by remember { mutableStateOf(AppScreen.Splash) }

    // Auto-navigate logic
    LaunchedEffect(effectiveSubscribed, consentAccepted) {
        if (effectiveSubscribed) {
            currentScreen = AppScreen.Home
        } else if (consentAccepted && currentScreen == AppScreen.Splash) {
            // Already consented but not subscribed -> jump straight to Paywall
            currentScreen = AppScreen.Paywall
        }
    }

    when {
        // ── Consent / First Launch ────────────────────────────────────
        currentScreen == AppScreen.Splash -> {
            ConsentScreen(
                onAccepted = {
                    viewModel.acceptConsent()
                    currentScreen = AppScreen.Paywall
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
