package com.vpn.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn.android.R

@Composable
fun ConsentScreen(onAccepted: () -> Unit) {
    val context = LocalContext.current
    var privacyChecked by remember { mutableStateOf(false) }
    var termsChecked   by remember { mutableStateOf(false) }
    val bothAccepted   = privacyChecked && termsChecked

    // Subtle background glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.06f, targetValue = 0.14f,
        animationSpec = infiniteRepeatable(tween(2800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavy)
    ) {
        // Animated radial glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(PurpleAccent.copy(alpha = glowAlpha), Color.Transparent),
                        radius = 800f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            // Logo
            Image(
                painter = painterResource(id = R.drawable.ic_vpn_blue),
                contentDescription = "Unlimited VPN",
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Unlimited VPN",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Before you begin",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Info card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Please review and accept our legal agreements to continue using the app.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = SurfaceElevated)
                    Spacer(modifier = Modifier.height(20.dp))

                    // Privacy Policy checkbox
                    ConsentCheckRow(
                        checked   = privacyChecked,
                        onToggle  = { privacyChecked = !privacyChecked },
                        label     = "Privacy Policy",
                        detail    = "How we collect, use, and protect your data",
                        url       = AppUrls.PRIVACY_POLICY,
                        context   = context
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Terms of Service checkbox
                    ConsentCheckRow(
                        checked  = termsChecked,
                        onToggle = { termsChecked = !termsChecked },
                        label    = "Terms of Service",
                        detail   = "Rules and conditions for using the VPN",
                        url      = AppUrls.TERMS_OF_SERVICE,
                        context  = context
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Trust badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TrustBadge(icon = Icons.Default.Lock,      label = "Encrypted",    modifier = Modifier.weight(1f))
                TrustBadge(icon = Icons.Default.Favorite,  label = "Zero Logs",    modifier = Modifier.weight(1f))
                TrustBadge(icon = Icons.Default.Star,      label = "WireGuard",    modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Continue button
            val buttonBrush = if (bothAccepted)
                Brush.horizontalGradient(listOf(PurpleAccent, ConnectingBlue))
            else
                Brush.horizontalGradient(listOf(SurfaceElevated, SurfaceCard))

            Button(
                onClick = { if (bothAccepted) onAccepted() },
                enabled = bothAccepted,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor   = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(buttonBrush, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bothAccepted) {
                            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            "Continue",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (bothAccepted) Color.White else TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Helper text
            AnimatedVisibility(
                visible = !bothAccepted,
                enter = fadeIn(), exit = fadeOut()
            ) {
                Text(
                    "Please check both boxes above to continue",
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single consent checkbox row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConsentCheckRow(
    checked: Boolean,
    onToggle: () -> Unit,
    label: String,
    detail: String,
    url: String,
    context: android.content.Context
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (checked) PurpleAccent.copy(alpha = 0.07f) else Color.Transparent
            )
            .border(
                1.dp,
                if (checked) PurpleAccent.copy(0.3f) else SurfaceElevated,
                RoundedCornerShape(12.dp)
            )
            .clickable { onToggle() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Animated checkbox
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (checked)
                        Brush.linearGradient(listOf(PurpleAccent, ConnectingBlue))
                    else
                        Brush.linearGradient(listOf(SurfaceElevated, SurfaceElevated))
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = checked,
                enter = scaleIn(tween(150)) + fadeIn(),
                exit  = scaleOut(tween(100)) + fadeOut()
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Label + link
            val annotated = buildAnnotatedString {
                append("I have read the ")
                withLink(LinkAnnotation.Url(url)) {
                    withStyle(SpanStyle(
                        color          = PurpleAccent,
                        fontWeight     = FontWeight.ExtraBold,
                        textDecoration = TextDecoration.Underline
                    )) { append(label) }
                }
            }
            Text(
                text  = annotated,
                style = androidx.compose.ui.text.TextStyle(
                    color    = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(detail, fontSize = 11.sp, color = TextMuted, lineHeight = 16.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Trust badge pill
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrustBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(1.dp, SurfaceElevated, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
    }
}
