package com.vpn.android.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.google.android.play.core.review.ReviewManagerFactory
import com.vpn.android.R
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: VpnViewModel,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPaywall: () -> Unit
) {
    val vpnState        by viewModel.vpnState.collectAsState()
    val selectedServer  by viewModel.selectedServer.collectAsState()
    val clientIp        by viewModel.isConnectedIp.collectAsState()
    val bandwidthUsed   by viewModel.bandwidthUsed.collectAsState()
    val bandwidthLimit  by viewModel.bandwidthLimit.collectAsState()
    val isConnecting    by viewModel.isConnecting.collectAsState()
    val isSubscribed    by viewModel.effectiveSubscriptionActive.collectAsState()
    val timerSeconds    by viewModel.connectionTimerSeconds.collectAsState()
    val downloadSpeed   by viewModel.downloadSpeed.collectAsState()
    val uploadSpeed     by viewModel.uploadSpeed.collectAsState()
    val errorMessage    by viewModel.errorMessage.collectAsState()
    val triggerReview   by viewModel.triggerReview.collectAsState()

    val orbState   = vpnState.toOrbState(isConnecting)
    val context    = LocalContext.current
    val haptic     = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    // #4 Haptic on permission result
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.connectVpn()
        }
    }

    // #2 Show error snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // #5 In-app review
    LaunchedEffect(triggerReview) {
        if (triggerReview) {
            val manager = ReviewManagerFactory.create(context)
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    manager.launchReviewFlow(context as Activity, task.result)
                }
            }
            viewModel.onReviewLaunched()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SurfaceElevated,
                    contentColor = Color.White,
                    actionColor = PurpleAccent,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        containerColor = Color.Transparent
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = when (orbState) {
                            OrbState.CONNECTED    -> listOf(Color(0xFF071A10), DeepNavy)
                            OrbState.CONNECTING   -> listOf(Color(0xFF080F1F), DeepNavy)
                            OrbState.DISCONNECTED -> listOf(Color(0xFF180808), DeepNavy)
                        }
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // ── Header ──────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Header logo — switches red (disconnected) / blue (protected)
                        val headerIconRes = if (orbState == OrbState.DISCONNECTED)
                            R.drawable.ic_vpn_red
                        else
                            R.drawable.ic_vpn_blue
                        Image(
                            painter = painterResource(id = headerIconRes),
                            contentDescription = "App Logo",
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Unlimited VPN", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text("WIREGUARD SECURED", fontSize = 9.sp, color = ConnectingBlue, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── Connect Orb ─────────────────────────────────────────
                ConnectOrb(
                    orbState = orbState,
                    onClick  = {
                        // #4 Haptic feedback on every tap
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!isSubscribed && orbState == OrbState.DISCONNECTED) {
                            onOpenPaywall()
                        } else {
                            viewModel.toggleVpn {
                                val intent = viewModel.prepareVpnIntent()
                                if (intent != null) permissionLauncher.launch(intent)
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Status Banner + Timer ────────────────────────────────
                StatusBanner(orbState = orbState, clientIp = clientIp)

                // #1 Connection timer — only visible when connected
                AnimatedVisibility(
                    visible = orbState == OrbState.CONNECTED,
                    enter = fadeIn() + expandVertically(),
                    exit  = fadeOut() + shrinkVertically()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = viewModel.formatTimer(timerSeconds),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = ConnectGreen,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "CONNECTION TIME",
                            fontSize = 9.sp,
                            color = TextMuted,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // Real-time speed meter
                        SpeedMeterRow(
                            downloadSpeed = viewModel.formatSpeed(downloadSpeed),
                            uploadSpeed   = viewModel.formatSpeed(uploadSpeed)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── Server Card ─────────────────────────────────────────
                ServerSelectorCard(selectedServer = selectedServer, onClick = onOpenServers)

                Spacer(modifier = Modifier.height(12.dp))

                // ── Stats / Bandwidth ────────────────────────────────────
                AnimatedVisibility(
                    visible = orbState == OrbState.CONNECTED,
                    enter = fadeIn() + expandVertically(),
                    exit  = fadeOut() + shrinkVertically()
                ) {
                    ConnectionStatsRow(bandwidthUsed = bandwidthUsed, bandwidthLimit = bandwidthLimit)
                }
                AnimatedVisibility(visible = orbState != OrbState.CONNECTED) {
                    BandwidthCard(used = bandwidthUsed, limit = bandwidthLimit)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server Selector Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServerSelectorCard(
    selectedServer: com.vpn.android.data.models.Server?,
    onClick: () -> Unit
) {
    // Universal flag: converts any 2-letter ISO country code to its flag emoji
    val flag = countryCodeToFlag(selectedServer?.id?.uppercase() ?: "")
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceElevated)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(SurfaceElevated)
                        .border(1.dp, TextMuted.copy(0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!selectedServer?.icon.isNullOrEmpty()) {
                        AsyncImage(
                            model = selectedServer?.icon,
                            contentDescription = selectedServer?.country,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Text(flag, fontSize = 22.sp, textAlign = TextAlign.Center)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            selectedServer?.city ?: "Auto Select",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        if (selectedServer == null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(ConnectingBlue.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("FASTEST", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = ConnectingBlue, letterSpacing = 1.sp)
                            }
                        }
                    }
                    Text(
                        selectedServer?.country ?: "Best Available Server",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Change Server",
                tint = TextMuted,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Speed Meter Row (shown when connected)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SpeedMeterRow(downloadSpeed: String, uploadSpeed: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .background(ConnectGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Text("↓", fontSize = 14.sp, color = ConnectGreen, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(downloadSpeed, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = ConnectGreen)
                Text("DOWNLOAD", fontSize = 8.sp, color = TextMuted, letterSpacing = 0.8.sp)
            }
        }
        Box(modifier = Modifier.width(1.dp).height(28.dp).background(SurfaceElevated))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .background(ConnectingBlue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Text("↑", fontSize = 14.sp, color = ConnectingBlue, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(uploadSpeed, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = ConnectingBlue)
                Text("UPLOAD", fontSize = 8.sp, color = TextMuted, letterSpacing = 0.8.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bandwidth Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BandwidthCard(used: Long, limit: Long) {
    val progress = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    val usedGb   = String.format("%.2f", used / 1e9)
    val limitGb  = String.format("%.0f", limit / 1e9)
    val barColor = when {
        progress < 0.6f  -> ConnectGreen
        progress < 0.85f -> LatencyYellow
        else             -> DisconnectRed
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Data Usage", fontSize = 13.sp, color = TextSecondary)
                Text("$usedGb / $limitGb GB", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(SurfaceElevated)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.horizontalGradient(listOf(barColor, barColor.copy(0.7f))))
                )
            }
        }
    }
}

