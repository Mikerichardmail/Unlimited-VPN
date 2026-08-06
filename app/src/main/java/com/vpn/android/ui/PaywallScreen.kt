package com.vpn.android.ui

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn.android.R

@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    onPurchaseComplete: (String) -> Unit,
    onRestore: () -> Unit = {},
    viewModel: VpnViewModel? = null
) {
    var selectedPlan by remember { mutableStateOf("vpn_annual") }

    // Collect live prices from Google Play; fallback to hardcoded ₹ until loaded
    val livePrices by (viewModel?.productPrices ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        .collectAsState()

    // Fetch prices as soon as the screen opens
    LaunchedEffect(Unit) { viewModel?.fetchProductPrices() }

    // Helper: returns live price or fallback
    fun price(sku: String, fallback: String) = livePrices[sku]?.takeIf { it.isNotEmpty() } ?: fallback

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0F0B1A), DeepNavy, DeepNavy))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top Bar ───────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Restore Purchases Button
                TextButton(
                    onClick = onRestore,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, null, tint = ConnectingBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restore Purchases", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ConnectingBlue)
                    }
                }

                // Close Button
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }

            // ── Main Content Container ────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // ── Blue Icon Hero Banner ─────────────────────────────────
                Image(
                    painter = painterResource(id = R.drawable.ic_vpn_blue),
                    contentDescription = "Unlimited VPN Logo",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, PurpleAccent.copy(alpha = 0.5f), CircleShape)
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("Unlimited VPN Premium", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Cancel anytime in Google Play", fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center)

                Spacer(modifier = Modifier.height(16.dp))

                // ── 2-Column Feature Grid ──────────────────────────────────
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) { PaywallFeatureItem("🛡️", "WireGuard Encryption", "256-bit security, always") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) { PaywallFeatureItem("🚫", "Strict Zero-Logs Policy", "We never store activity") }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) { PaywallFeatureItem("⚡", "Ultra-Fast Global Servers", "Mumbai · US · Singapore") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) { PaywallFeatureItem("🛑", "VPN Kill Switch", "Prevents IP leaks on drop") }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) { PaywallFeatureItem("📱", "2 Devices Simultaneously", "Share with family") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) { PaywallFeatureItem("🔑", "Auto Key Rotation", "Keys change every 30 days") }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Plan Selection Header ──────────────────────────────────
                Text("CHOOSE YOUR PLAN", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TextMuted, letterSpacing = 1.2.sp)
                Spacer(modifier = Modifier.height(8.dp))

                // ── 3-Column Plan Cards ───────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactPlanCard(
                        title    = "Monthly",
                        price    = price("vpn_monthly", "₹600"),
                        period   = "/mo",
                        tagline  = "Billed monthly",
                        badge    = null,
                        isSelected = selectedPlan == "vpn_monthly",
                        onSelect = { selectedPlan = "vpn_monthly" },
                        modifier = Modifier.weight(1f)
                    )
                    CompactPlanCard(
                        title    = "6 Months",
                        price    = price("vpn_6month", "₹3,000"),
                        period   = "Save 17%",
                        tagline  = "Billed every 6 months",
                        badge    = "POPULAR",
                        isSelected = selectedPlan == "vpn_6month",
                        onSelect = { selectedPlan = "vpn_6month" },
                        modifier = Modifier.weight(1f)
                    )
                    CompactPlanCard(
                        title    = "Annual",
                        price    = price("vpn_annual", "₹5,000"),
                        period   = "Save 31%",
                        tagline  = "Billed annually",
                        badge    = "BEST VALUE",
                        isSelected = selectedPlan == "vpn_annual",
                        onSelect = { selectedPlan = "vpn_annual" },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Bottom Fixed CTA & Footer ─────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onPurchaseComplete(selectedPlan) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(listOf(PurpleAccent, ConnectingBlue)),
                                shape = RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Subscribe with Google Play", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Secure checkout · Easy 1-tap cancellation in Google Play", fontSize = 10.sp, color = TextMuted, textAlign = TextAlign.Center)

                Spacer(modifier = Modifier.height(6.dp))

                // Terms & Privacy & Restore
                val annotated = buildAnnotatedString {
                    append("By continuing you agree to our ")
                    withLink(LinkAnnotation.Url(AppUrls.TERMS_OF_SERVICE)) {
                        withStyle(SpanStyle(color = PurpleAccent, fontWeight = FontWeight.Bold)) { append("Terms") }
                    }
                    append(" & ")
                    withLink(LinkAnnotation.Url(AppUrls.PRIVACY_POLICY)) {
                        withStyle(SpanStyle(color = PurpleAccent, fontWeight = FontWeight.Bold)) { append("Privacy Policy") }
                    }
                }
                Text(
                    text = annotated,
                    style = TextStyle(fontSize = 10.sp, color = TextMuted, textAlign = TextAlign.Center)
                )
            }
        }
    }
}

@Composable
private fun PaywallFeatureItem(emoji: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            Text(subtitle, fontSize = 9.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun CompactPlanCard(
    title: String,
    price: String,
    period: String,
    tagline: String,
    badge: String?,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) PurpleAccent else SurfaceElevated,
        animationSpec = tween(200),
        label = "PlanBorder"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) PurpleAccent.copy(alpha = 0.12f) else SurfaceCard,
        label = "PlanBg"
    )

    Box(modifier = modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.5.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Extra top padding when badge is present so the badge floats ABOVE the title
                    .padding(
                        top    = if (badge != null) 18.dp else 12.dp,
                        bottom = 12.dp,
                        start  = 6.dp,
                        end    = 6.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(price, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text(period, fontSize = 10.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(tagline, fontSize = 9.sp, color = if (isSelected) PurpleAccent else TextMuted, textAlign = TextAlign.Center)
            }
        }

        // Badge — floats above the top edge of the card
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-8).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (badge == "BEST VALUE") ConnectGreen else PurpleAccent)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(badge, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.4.sp)
            }
        }
    }
}
