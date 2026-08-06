package com.vpn.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn.android.R
import com.wireguard.android.backend.Tunnel

// ─────────────────────────────────────────────────────────────────
// Design System Tokens
// ─────────────────────────────────────────────────────────────────

val DeepNavy        = Color(0xFF080C14)
val SurfaceCard     = Color(0xFF121720)
val SurfaceElevated = Color(0xFF1C2333)
val ConnectGreen    = Color(0xFF22C55E)
val ConnectingBlue  = Color(0xFF6C8CFF)
val DisconnectRed   = Color(0xFFEF4444)
val PurpleAccent    = Color(0xFF8B5CF6)
val TextPrimary     = Color(0xFFFFFFFF)
val TextSecondary   = Color(0xFFB0BAC9)
val TextMuted       = Color(0xFF64748B)

// ─────────────────────────────────────────────────────────────────
// App URLs & Contact Info
// ─────────────────────────────────────────────────────────────────

object AppUrls {
    const val PRIVACY_POLICY   = "https://bestvpnproxy.in/privacy"
    const val TERMS_OF_SERVICE = "https://bestvpnproxy.in/terms"
    const val SUPPORT_EMAIL    = "support@bestvpnproxy.in"
}
val LatencyGreen    = Color(0xFF4ADE80)
val LatencyYellow   = Color(0xFFFBBF24)
val LatencyRed      = Color(0xFFEF4444)
val AmberOrange     = Color(0xFFFB923C)

// Legacy aliases so existing code doesn't break
val DarkBg           = DeepNavy
val CardBg           = SurfaceCard
val AccentGreen      = ConnectGreen
val AccentBlue       = ConnectingBlue
val BrandPurple      = PurpleAccent
val NeonCyan         = ConnectingBlue
val GlowGreen        = ConnectGreen
val DisconnectedRed  = DisconnectRed
val AmberYellow      = LatencyYellow

// ─────────────────────────────────────────────────────────────────
// ConnectOrb — The Hero Component
// ─────────────────────────────────────────────────────────────────

enum class OrbState { DISCONNECTED, CONNECTING, CONNECTED }

@Composable
fun ConnectOrb(
    orbState: OrbState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glowColor = when (orbState) {
        OrbState.CONNECTED    -> ConnectGreen
        OrbState.CONNECTING   -> ConnectingBlue
        OrbState.DISCONNECTED -> DisconnectRed
    }

    val animatedColor by animateColorAsState(
        targetValue = glowColor,
        animationSpec = tween(600),
        label = "OrbColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")

    // Outer ring pulsing
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "Ring1"
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.04f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "Ring1Alpha"
    )
    // Second ring, offset phase
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 1.05f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "Ring2"
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "Ring2Alpha"
    )

    // Button press scale
    val pressScale by animateFloatAsState(
        targetValue = if (orbState == OrbState.CONNECTING) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PressScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(260.dp)
    ) {
        // Outermost glow ring
        Box(
            modifier = Modifier
                .size(240.dp * ring2Scale)
                .clip(CircleShape)
                .background(animatedColor.copy(alpha = ring2Alpha))
        )
        // Inner pulse ring
        Box(
            modifier = Modifier
                .size(220.dp * ring1Scale)
                .clip(CircleShape)
                .background(animatedColor.copy(alpha = ring1Alpha))
        )

        // The orb itself
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(180.dp * pressScale)
                .coloredShadow(animatedColor, blurRadius = 24.dp, alpha = if (orbState == OrbState.CONNECTED) 0.55f else 0.25f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SurfaceElevated,
                            SurfaceCard,
                            DeepNavy
                        )
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // ── Custom PNG state icon ─────────────────────────────────
                // Blue = protected/connecting, Red = disconnected (unprotected)
                val iconPainter = painterResource(
                    id = if (orbState == OrbState.DISCONNECTED)
                        R.drawable.ic_vpn_red
                    else
                        R.drawable.ic_vpn_blue
                )

                // Slow rotation while connecting to signal activity
                val connectingRotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue  = 360f,
                    animationSpec = infiniteRepeatable(
                        tween(3000, easing = LinearEasing),
                        RepeatMode.Restart
                    ),
                    label = "IconRotation"
                )
                val iconRotation = if (orbState == OrbState.CONNECTING) connectingRotation else 0f
                val iconAlpha   = if (orbState == OrbState.CONNECTING) 0.75f else 1f

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(80.dp)
                ) {
                    Image(
                        painter = iconPainter,
                        contentDescription = when (orbState) {
                            OrbState.CONNECTED    -> "VPN Connected"
                            OrbState.CONNECTING   -> "VPN Connecting"
                            OrbState.DISCONNECTED -> "VPN Disconnected"
                        },
                        modifier = Modifier
                            .size(80.dp)
                            .rotate(iconRotation),
                        alpha = iconAlpha
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when (orbState) {
                        OrbState.CONNECTED    -> "PROTECTED"
                        OrbState.CONNECTING   -> "CONNECTING"
                        OrbState.DISCONNECTED -> "TAP TO CONNECT"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = animatedColor,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Status Banner
// ─────────────────────────────────────────────────────────────────

@Composable
fun StatusBanner(orbState: OrbState, clientIp: String) {
    val (label, color) = when (orbState) {
        OrbState.CONNECTED    -> "● YOUR CONNECTION IS ENCRYPTED" to ConnectGreen
        OrbState.CONNECTING   -> "● ESTABLISHING SECURE TUNNEL..." to ConnectingBlue
        OrbState.DISCONNECTED -> "● YOUR CONNECTION IS EXPOSED" to DisconnectRed
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.5.sp)
    }

    if (orbState == OrbState.CONNECTED && clientIp.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = clientIp,
            fontSize = 12.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Stats Row (download / upload / timer) — shown when connected
// ─────────────────────────────────────────────────────────────────

@Composable
fun ConnectionStatsRow(bandwidthUsed: Long, bandwidthLimit: Long) {
    val usedMb  = String.format("%.1f", bandwidthUsed / 1e6)
    val limitGb = String.format("%.0f", bandwidthLimit / 1e9)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(label = "DATA USED", value = "$usedMb MB", color = ConnectingBlue)
        VerticalDivider()
        StatItem(label = "LIMIT", value = "$limitGb GB", color = TextSecondary)
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = TextMuted, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .height(32.dp)
            .width(1.dp)
            .background(SurfaceElevated)
    )
}

// ─────────────────────────────────────────────────────────────────
// Latency Badge
// ─────────────────────────────────────────────────────────────────

@Composable
fun LatencyBadge(ms: Int) {
    val color = when {
        ms < 50  -> LatencyGreen
        ms < 100 -> LatencyYellow
        else     -> LatencyRed
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text("${ms}ms", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ─────────────────────────────────────────────────────────────────
// Signal Bars (server load)
// ─────────────────────────────────────────────────────────────────

@Composable
fun SignalBars(loadPercent: Int, modifier: Modifier = Modifier) {
    val filled = when {
        loadPercent < 33 -> 3
        loadPercent < 66 -> 2
        else             -> 1
    }
    val color = when {
        loadPercent < 33 -> LatencyGreen
        loadPercent < 66 -> LatencyYellow
        else             -> LatencyRed
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(8.dp, 12.dp, 16.dp).forEachIndexed { idx, height ->
            val isFilled = idx < filled
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isFilled) color else TextMuted.copy(alpha = 0.4f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Glow shadow extension
// ─────────────────────────────────────────────────────────────────

fun Modifier.coloredShadow(
    color: Color,
    blurRadius: Dp = 20.dp,
    alpha: Float = 0.35f,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp
) = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(
                    blurRadius.toPx(),
                    offsetX.toPx(),
                    offsetY.toPx(),
                    android.graphics.Color.argb(
                        (alpha * 255).toInt(),
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt()
                    )
                )
            }
        }
        canvas.drawCircle(
            center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2),
            radius = size.width / 2,
            paint = paint
        )
    }
}

// Map Tunnel.State → OrbState
fun Tunnel.State.toOrbState(isConnecting: Boolean): OrbState = when {
    isConnecting        -> OrbState.CONNECTING
    this == Tunnel.State.UP -> OrbState.CONNECTED
    else                -> OrbState.DISCONNECTED
}
