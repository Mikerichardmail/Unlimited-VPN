package com.vpn.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vpn.android.data.models.Server

@Composable
fun ServerSelectionScreen(viewModel: VpnViewModel, onBack: () -> Unit) {
    val servers         by viewModel.servers.collectAsState()
    val selectedServer  by viewModel.selectedServer.collectAsState()
    val isLoading       by viewModel.isLoadingServers.collectAsState()
    val liveLatencies   by viewModel.serverLatencies.collectAsState()
    var searchQuery     by remember { mutableStateOf("") }

    val filtered = remember(servers, searchQuery) {
        if (searchQuery.isBlank()) servers
        else servers.filter {
            it.country.contains(searchQuery, ignoreCase = true) ||
                it.city.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DeepNavy)
    ) {
        // ── Header ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Text("Choose Server", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search countries or cities...", color = TextMuted, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, null, tint = TextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PurpleAccent,
                        unfocusedBorderColor = SurfaceElevated,
                        focusedContainerColor = SurfaceElevated,
                        unfocusedContainerColor = SurfaceElevated,
                        cursorColor = PurpleAccent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isLoading) {
                // #3 Skeleton while loading
                items(4) { ServerRowSkeleton() }
            } else {
                // Auto Select (always visible when not searching)
                if (searchQuery.isBlank()) {
                    item {
                        SectionLabel("⚡  RECOMMENDED")
                        Spacer(modifier = Modifier.height(8.dp))
                        ServerRow(
                            flag = "🌍",
                            icon = null,
                            city = "Auto Select",
                            country = "Best Available Server",
                            latencyMs = null,
                            loadPercent = null,
                            isSelected = selectedServer == null,
                            isFastest = true,
                            onClick = { viewModel.selectAutoServer(); onBack() }
                        )
                    }
                }

                if (filtered.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionLabel("🌍  ALL SERVERS")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(filtered) { server ->
                        // Derive the 2-letter country code from the server ID.
                        // VPNResellers IDs are formatted as "<cc>_<numeric_id>" (e.g. "us_2",
                        // "de_7") so we take everything before the first underscore or digit.
                        val cc = server.id.lowercase()
                            .substringBefore("_")
                            .take(2)
                        val flag = countryCodeToFlag(cc)
                        val displayLatency = liveLatencies[server.id] ?: server.latencyMs
                        ServerRow(
                            flag = flag,
                            icon = server.icon,
                            city = server.city,
                            country = server.country,
                            latencyMs = displayLatency,
                            loadPercent = server.loadPercent,
                            isSelected = server.id == selectedServer?.id,
                            isFastest = false,
                            onClick = { viewModel.selectServer(server); onBack() }
                        )
                    }
                } else {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("No servers match \"$searchQuery\"", color = TextMuted, fontSize = 14.sp)
                            }
                        }
                    }
                }
            } // end else (not loading)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TextMuted, letterSpacing = 1.5.sp)
}

// ── #3 Loading skeleton placeholder ──────────────────────────────────────────

@Composable
fun ServerRowSkeleton() {
    val infiniteTransition = rememberInfiniteTransition(label = "Shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue  = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ShimmerAlpha"
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flag circle placeholder
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(SurfaceElevated.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier.width(100.dp).height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceElevated.copy(alpha = alpha))
                )
                Box(
                    modifier = Modifier.width(70.dp).height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(SurfaceElevated.copy(alpha = alpha * 0.7f))
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier.width(40.dp).height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceElevated.copy(alpha = alpha))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ServerRow(
    flag: String,
    icon: String?,
    city: String,
    country: String,
    latencyMs: Int?,
    loadPercent: Int?,
    isSelected: Boolean,
    isFastest: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) PurpleAccent.copy(alpha = 0.1f) else SurfaceCard,
        animationSpec = tween(200),
        label = "RowBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) PurpleAccent.copy(alpha = 0.6f) else SurfaceElevated,
        label = "RowBorder"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flag + Country
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(SurfaceElevated)
                        .border(1.dp, TextMuted.copy(0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!icon.isNullOrEmpty()) {
                        AsyncImage(
                            model = icon,
                            contentDescription = country,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Text(flag, fontSize = 22.sp)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(city, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        if (isFastest) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(ConnectGreen.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("FASTEST", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = ConnectGreen, letterSpacing = 1.sp)
                            }
                        }
                    }
                    Text(country, fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Latency + load + check
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (latencyMs != null) LatencyBadge(ms = latencyMs)
                if (loadPercent != null) SignalBars(loadPercent = loadPercent)
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, "Selected", tint = PurpleAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Country flag emoji helper
// Converts any ISO 3166-1 alpha-2 code (e.g. "us", "de", "jp") into the
// correct Unicode flag emoji using Regional Indicator Symbol letters.
// Works for all 250+ countries — no manual mapping table needed.
// ─────────────────────────────────────────────────────────────────────────────
fun countryCodeToFlag(cc: String): String {
    if (cc.length != 2) return "🌍"
    val upper = cc.uppercase()
    if (!upper.all { it in 'A'..'Z' }) return "🌍"
    // Each letter maps to a Regional Indicator Symbol: 'A' -> U+1F1E6, etc.
    val first  = String(Character.toChars(0x1F1E6 + (upper[0] - 'A')))
    val second = String(Character.toChars(0x1F1E6 + (upper[1] - 'A')))
    return first + second
}
