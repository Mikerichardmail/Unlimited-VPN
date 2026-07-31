package com.vpn.android.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vpn.android.data.models.Server
import com.wireguard.android.backend.Tunnel
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

enum class AppScreen {
    Splash,
    Paywall,
    Home,
    ServerSelect,
    Settings
}

// Design System Tokens (from VPN_App_UI_Redesign_Plan.md)
val DarkBg = Color(0xFF0E1116)       // Near-black main background
val CardBg = Color(0xFF171B22)       // Elevated surface
val AccentGreen = Color(0xFF4ADE80)  // Active protection green
val AccentBlue = Color(0xFF6C8CFF)   // Secondary interactive blue
val TextPrimary = Color(0xFFF3F5F7)  // Primary text
val TextMuted = Color(0xFF8B93A1)    // Captions & secondary info
val BrandPurple = AccentBlue
val NeonCyan = AccentBlue
val GlowGreen = AccentGreen
val DisconnectedRed = Color(0xFFFF4D4D)
val AmberYellow = Color(0xFFFFB800)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = DarkBg,
                    surface = CardBg,
                    primary = BrandPurple,
                    onPrimary = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VpnAppNavigation()
                }
            }
        }
    }
}

@Composable
fun VpnAppNavigation(viewModel: VpnViewModel = viewModel()) {
    val context = LocalContext.current
    val effectiveSubscribed by viewModel.effectiveSubscriptionActive.collectAsState()
    var currentScreen by remember { mutableStateOf(AppScreen.Splash) }

    // Synchronize subscription state
    LaunchedEffect(effectiveSubscribed) {
        if (effectiveSubscribed && currentScreen == AppScreen.Splash) {
            currentScreen = AppScreen.Home
        }
    }

    if (currentScreen == AppScreen.Splash) {
        OnboardingScreen(
            onStartTrial = { currentScreen = AppScreen.Paywall },
            onRestore = { viewModel.restorePurchases() }
        )
    } else if (currentScreen == AppScreen.Paywall && !effectiveSubscribed) {
        PaywallScreen(
            onClose = {
                // Hard-gated paywall: if trial/sub not active, remain on Paywall
                if (effectiveSubscribed) {
                    currentScreen = AppScreen.Home
                }
            },
            onPurchaseComplete = { plan -> viewModel.buySubscription(context as Activity, plan) }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = CardBg,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = currentScreen == AppScreen.Home,
                        onClick = { currentScreen = AppScreen.Home },
                        icon = { Icon(Icons.Default.Lock, contentDescription = "Shield") },
                        label = { Text("Shield", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = BrandPurple.copy(alpha = 0.25f)
                        )
                    )
                    NavigationBarItem(
                        selected = currentScreen == AppScreen.ServerSelect,
                        onClick = { currentScreen = AppScreen.ServerSelect },
                        icon = { Icon(Icons.Default.LocationOn, contentDescription = "Servers") },
                        label = { Text("Servers", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = BrandPurple.copy(alpha = 0.25f)
                        )
                    )
                    NavigationBarItem(
                        selected = currentScreen == AppScreen.Settings,
                        onClick = { currentScreen = AppScreen.Settings },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Protection") },
                        label = { Text("Protection", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = BrandPurple.copy(alpha = 0.25f)
                        )
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    AppScreen.Home -> HomeScreen(
                        viewModel = viewModel,
                        onOpenServers = { currentScreen = AppScreen.ServerSelect },
                        onOpenSettings = { currentScreen = AppScreen.Settings },
                        onOpenPaywall = { currentScreen = AppScreen.Paywall }
                    )
                    AppScreen.ServerSelect -> ServerSelectionScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = AppScreen.Home }
                    )
                    AppScreen.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = AppScreen.Home },
                        onLogout = { currentScreen = AppScreen.Splash }
                    )
                    else -> {}
                }
            }
        }
    }
}

// ----------------- SCREEN 1: SPLASH & ONBOARDING -----------------
@Composable
fun OnboardingScreen(onStartTrial: () -> Unit, onRestore: () -> Unit) {
    var slideIndex by remember { mutableIntStateOf(0) }
    val slides = listOf(
        Triple(Icons.Default.Lock, "Military-Grade Security", "Secure your online identity with the powerful, industry-standard WireGuard protocol."),
        Triple(Icons.Default.Send, "Lightning Speeds", "Stream, download, and browse in high-definition without ISP throttling or network delays."),
        Triple(Icons.Default.Home, "Zero Logs, Maximum Trust", "Your history is strictly your business. We do not store, trace, or share any traffic logs.")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App Identity
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Shield Logo",
                tint = BrandPurple,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ShieldVPN",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Onboarding Slider content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = slides[slideIndex].first,
                contentDescription = null,
                tint = BrandPurple,
                modifier = Modifier.size(100.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = slides[slideIndex].second,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = slides[slideIndex].third,
                fontSize = 14.sp,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(30.dp))
            
            // Indicator Dots
            Row {
                slides.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(if (index == slideIndex) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (index == slideIndex) BrandPurple else Color.DarkGray)
                    )
                }
            }
        }

        // Action Buttons
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (slideIndex < slides.size - 1) {
                Button(
                    onClick = { slideIndex++ },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Next", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartTrial,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Start 3-Day Free Trial with Google", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "I already have a subscription",
                fontSize = 14.sp,
                color = Color.LightGray,
                modifier = Modifier
                    .clickable { onRestore() }
                    .padding(8.dp)
            )
        }
    }
}

// ----------------- SCREEN 2: PAYWALL SCREEN -----------------
@Composable
fun PaywallScreen(onClose: () -> Unit, onPurchaseComplete: (String) -> Unit) {
    var selectedPlan by remember { mutableStateOf("vpn_annual") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(BrandPurple.copy(alpha = 0.2f))
                    .border(1.dp, BrandPurple, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("🎁 3-DAY FREE TRIAL", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandPurple)
            }
            Spacer(modifier = Modifier.width(48.dp))
        }

        // Core Pitch
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Try ShieldVPN Risk-Free", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(6.dp))
            Text("No payment due today. Enjoy 3 days free, then cancel anytime in Google Play.", fontSize = 13.sp, color = Color.LightGray, textAlign = TextAlign.Center)
            
            Spacer(modifier = Modifier.height(14.dp))
            
            // Features Included List
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg.copy(alpha = 0.6f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FeatureRow("🛡️ Military-Grade Encryption", "WireGuard 256-bit security")
                FeatureRow("⚡ Ultra-Fast Servers", "Mumbai, US & Singapore nodes")
                FeatureRow("📱 Multi-Device Access", "Connect up to 2 devices")
                FeatureRow("🚫 Strict No-Logs Policy", "Zero activity or DNS tracking")
                FeatureRow("🛑 Built-in Kill-Switch", "Prevents IP leaks on drop")
            }
        }

        // Plans Section
        Column(modifier = Modifier.fillMaxWidth()) {
            PlanCard(
                title = "Monthly Plan",
                price = "₹600/mo",
                tagline = "3 Days Free, then ₹600/month",
                isSelected = selectedPlan == "vpn_monthly",
                onSelect = { selectedPlan = "vpn_monthly" }
            )
            Spacer(modifier = Modifier.height(12.dp))
            PlanCard(
                title = "6-Month Plan",
                price = "₹3,000",
                tagline = "3 Days Free, then ₹500/mo (Save ₹600)",
                badge = "Popular",
                isSelected = selectedPlan == "vpn_6month",
                onSelect = { selectedPlan = "vpn_6month" }
            )
            Spacer(modifier = Modifier.height(12.dp))
            PlanCard(
                title = "Annual Plan",
                price = "₹5,000/yr",
                tagline = "3 Days Free, then ₹416/mo (Save ₹2,200)",
                badge = "Best Value",
                isSelected = selectedPlan == "vpn_annual",
                onSelect = { selectedPlan = "vpn_annual" }
            )
        }

        // Purchase Button
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = { onPurchaseComplete(selectedPlan) },
                colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Start 3-Day Free Trial with Google", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("₹0 today • Easy 1-click cancellation", fontSize = 11.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            val uriHandler = LocalUriHandler.current
            val annotatedText = buildAnnotatedString {
                append("By continuing, you agree to our ")
                
                pushStringAnnotation(tag = "TOS", annotation = "https://vpnapp.in/terms")
                withStyle(style = SpanStyle(color = BrandPurple, fontWeight = FontWeight.Bold)) {
                    append("Terms of Service")
                }
                pop()
                
                append(" & ")
                
                pushStringAnnotation(tag = "PRIVACY", annotation = "https://vpnapp.in/privacy")
                withStyle(style = SpanStyle(color = BrandPurple, fontWeight = FontWeight.Bold)) {
                    append("Privacy Policy")
                }
                pop()
                
                append(".")
            }

            ClickableText(
                text = annotatedText,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center
                ),
                onClick = { offset ->
                    annotatedText.getStringAnnotations(tag = "TOS", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            uriHandler.openUri(annotation.item)
                        }
                    annotatedText.getStringAnnotations(tag = "PRIVACY", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            uriHandler.openUri(annotation.item)
                        }
                }
            )
        }
    }
}

@Composable
fun FeatureRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(subtitle, fontSize = 11.sp, color = Color.LightGray)
    }
}

@Composable
fun FeatureChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.sp, color = Color.LightGray, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PlanCard(title: String, price: String, tagline: String, badge: String? = null, isSelected: Boolean, onSelect: () -> Unit) {
    val borderColor = if (isSelected) BrandPurple else Color.DarkGray
    val borderThickness = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(borderThickness, borderColor, RoundedCornerShape(16.dp))
            .clickable { onSelect() }
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(price, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(tagline, fontSize = 12.sp, color = Color.LightGray)
        }

        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = (-24).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrandPurple)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

// ----------------- SCREEN 3: HOME SCREEN -----------------
@Composable
fun HomeScreen(
    viewModel: VpnViewModel,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPaywall: () -> Unit
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val clientIp by viewModel.isConnectedIp.collectAsState()
    val bandwidthUsed by viewModel.bandwidthUsed.collectAsState()
    val bandwidthLimit by viewModel.bandwidthLimit.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isSubscribed by viewModel.isSubscriptionActive.collectAsState()
    val isTrialActive by viewModel.isTrialActive.collectAsState()
    val trialTimeRemaining by viewModel.trialTimeRemainingMillis.collectAsState()

    val trialHoursRemaining = (trialTimeRemaining / (1000 * 60 * 60)).coerceAtLeast(0)
    val trialDaysRemaining = trialHoursRemaining / 24
    val trialHoursModulo = trialHoursRemaining % 24

    // Request permissions launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectVpn()
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = when {
            isConnecting -> AmberYellow
            vpnState == Tunnel.State.UP -> GlowGreen
            else -> DisconnectedRed
        },
        label = "GlowColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (vpnState == Tunnel.State.UP || isConnecting) 1.18f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (vpnState == Tunnel.State.UP || isConnecting) 0.05f else 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(BrandPurple, NeonCyan)))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("ShieldVPN", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("QUANTUM SECURE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = NeonCyan, letterSpacing = 1.sp)
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.LightGray)
                }
            }

            if (!isSubscribed && isTrialActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(listOf(BrandPurple.copy(alpha = 0.2f), NeonCyan.copy(alpha = 0.1f))))
                        .border(1.dp, Brush.horizontalGradient(listOf(BrandPurple, NeonCyan)), RoundedCornerShape(12.dp))
                        .clickable { onOpenPaywall() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🎁 3-Day Free Trial (${trialDaysRemaining}d ${trialHoursModulo}h left)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Upgrade",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }
            }
        }

        // Connection Glow Circle Button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(230.dp)
            ) {
                // Futuristic Pulsing Outer Radar Ring
                Box(
                    modifier = Modifier
                        .size(210.dp * pulseScale)
                        .clip(CircleShape)
                        .background(animatedColor.copy(alpha = pulseAlpha))
                        .border(1.5.dp, animatedColor.copy(alpha = pulseAlpha * 2), CircleShape)
                )

                // Main Connection Orb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(190.dp)
                        .shadow(
                            elevation = if (vpnState == Tunnel.State.UP) 28.dp else 6.dp,
                            shape = CircleShape,
                            ambientColor = animatedColor,
                            spotColor = animatedColor
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(CardBg, DarkBg),
                                radius = 300f
                            )
                        )
                        .border(3.dp, Brush.sweepGradient(listOf(animatedColor, NeonCyan, animatedColor)), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.toggleVpn {
                                val intent = viewModel.prepareVpnIntent()
                                if (intent != null) {
                                    permissionLauncher.launch(intent)
                                }
                            }
                        }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Connect",
                            tint = animatedColor,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                isConnecting -> "CONNECTING..."
                                vpnState == Tunnel.State.UP -> "CONNECTED"
                                else -> "TAP TO PROTECT"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = animatedColor,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Display assigned IP address when connected
            if (vpnState == Tunnel.State.UP && clientIp.isNotEmpty()) {
                Text(
                    text = "Secure IP: $clientIp",
                    fontSize = 14.sp,
                    color = GlowGreen,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = "Your data is unencrypted",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
            }
        }

        // Active Server Selector Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenServers() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val serverFlag = when (selectedServer?.id?.lowercase()) {
                        "in" -> "🇮🇳"
                        "us" -> "🇺🇸"
                        "sg" -> "🇸🇬"
                        else -> "🌍"
                    }
                    if (!selectedServer?.icon.isNullOrEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .border(1.dp, Color(0xFF2A3142), CircleShape)
                        ) {
                            AsyncImage(
                                model = selectedServer?.icon,
                                contentDescription = selectedServer?.country,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Text(serverFlag, fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(selectedServer?.city ?: "Auto Select", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(selectedServer?.country ?: "Best Latency", fontSize = 11.sp, color = Color.LightGray)
                    }
                }
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Change Server", tint = Color.LightGray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bandwidth Meter Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Data Usage", fontSize = 13.sp, color = Color.LightGray)
                    val usedGb = String.format("%.2f", bandwidthUsed / 1e9)
                    val limitGb = String.format("%.0f", bandwidthLimit / 1e9)
                    Text("$usedGb GB / $limitGb GB", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val progress = (bandwidthUsed.toFloat() / bandwidthLimit.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = BrandPurple,
                    trackColor = Color.DarkGray
                )
            }
        }
    }
}

// ----------------- SCREEN 4: SERVER SELECTION SCREEN -----------------
@Composable
fun ServerSelectionScreen(viewModel: VpnViewModel, onBack: () -> Unit) {
    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("Select Server", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // List of Servers
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                val isAutoSelected = selectedServer == null
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (isAutoSelected) Color(0xFF231F3F) else CardBg),
                    shape = RoundedCornerShape(12.dp),
                    border = if (isAutoSelected) BorderStroke(1.dp, BrandPurple) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectAutoServer()
                            onBack()
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🌍", fontSize = 28.sp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Auto Select", fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Best Latency", fontSize = 12.sp, color = Color.LightGray)
                            }
                        }
                        if (isAutoSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = BrandPurple)
                        }
                    }
                }
            }

            items(servers) { server ->
                val isSelected = server.id == selectedServer?.id
                ServerListItem(
                    server = server,
                    isSelected = isSelected,
                    onClick = {
                        viewModel.selectServer(server)
                        onBack()
                    }
                )
            }
        }
    }
}

@Composable
fun ServerListItem(server: Server, isSelected: Boolean, onClick: () -> Unit) {
    val flag = when (server.id.lowercase()) {
        "in" -> "🇮🇳"
        "us" -> "🇺🇸"
        "sg" -> "🇸🇬"
        else -> "🌍"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF231F3F) else CardBg),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(1.dp, BrandPurple) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!server.icon.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .border(1.dp, Color(0xFF2A3142), CircleShape)
                    ) {
                        AsyncImage(
                            model = server.icon,
                            contentDescription = server.country,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Text(flag, fontSize = 28.sp)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(server.city, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(server.country, fontSize = 12.sp, color = Color.LightGray)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Latency Indicator
                Icon(Icons.Default.Info, contentDescription = null, tint = GlowGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("${server.latencyMs}ms", fontSize = 12.sp, color = Color.LightGray)
                
                Spacer(modifier = Modifier.width(12.dp))
                
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = BrandPurple)
                }
            }
        }
    }
}

// ----------------- SCREEN 5: SETTINGS & ACCOUNT SCREEN -----------------
@Composable
fun SettingsScreen(
    viewModel: VpnViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val expiry by viewModel.subscriptionExpiry.collectAsState()
    val isKillSwitchEnabled by viewModel.isKillSwitchEnabled.collectAsState()
    val emailState by viewModel.userEmail.collectAsState()

    var emailInput by remember { mutableStateOf(emailState) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 1. Subscription Details Card
            val devicesCount by viewModel.devicesCount.collectAsState()
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Subscription Status", fontSize = 12.sp, color = Color.LightGray)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Premium Activated", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlowGreen)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Expiry: $expiry", fontSize = 12.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Devices: $devicesCount of 2 connected", fontSize = 12.sp, color = Color.LightGray)
                        
                        Text(
                            text = "Deregister Device",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BrandPurple,
                            modifier = Modifier
                                .clickable {
                                    viewModel.deregisterCurrentDevice { success ->
                                        if (success) {
                                            onLogout()
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Optional Email Entry
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Account Recovery Email", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Protect your account from device switches.", fontSize = 11.sp, color = Color.LightGray)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            placeholder = { Text("Enter recovery email", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandPurple,
                                unfocusedBorderColor = Color.DarkGray
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.saveEmail(emailInput) },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
                        ) {
                            Text("Save", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Kill Switch Setting
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VPN Kill Switch", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Block internet if VPN drops unexpectedly.", fontSize = 11.sp, color = Color.LightGray)
                    }
                    val context = LocalContext.current
                    Switch(
                        checked = isKillSwitchEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setKillSwitch(enabled)
                            if (enabled) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Please enable 'Always-on VPN' and 'Block connections without VPN' in system settings.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                try {
                                    context.startActivity(viewModel.getVpnSettingsIntent())
                                } catch (e: Exception) {
                                    // Fallback
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = BrandPurple)
                    )
                }
            }
        }

        // Support and App Version
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Contact Support: support@shieldvpn.in",
                fontSize = 13.sp,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ShieldVPN Version 1.0 (Build 100)",
                fontSize = 11.sp,
                color = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, Color.DarkGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign Out", color = Color.LightGray)
            }
        }
    }
}
