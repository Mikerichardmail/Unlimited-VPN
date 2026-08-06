package com.vpn.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn.android.BuildConfig

@Composable
fun SettingsScreen(
    viewModel: VpnViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val expiry            by viewModel.subscriptionExpiry.collectAsState()
    val isKillSwitch      by viewModel.isKillSwitchEnabled.collectAsState()
    val emailState        by viewModel.userEmail.collectAsState()
    val isSubscribed      by viewModel.isSubscriptionActive.collectAsState()
    val devicesCount      by viewModel.devicesCount.collectAsState()
    val context           = LocalContext.current

    var emailInput by remember { mutableStateOf(emailState) }
    var emailError by remember { mutableStateOf(false) }
    LaunchedEffect(emailState) { emailInput = emailState }

    var showProtocolDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    val currentProtocol by viewModel.currentProtocol.collectAsState()

    val (statusLabel, statusColor, statusBg) = if (isSubscribed) {
        Triple("PREMIUM ACTIVE", ConnectGreen, ConnectGreen.copy(0.1f))
    } else {
        Triple("NOT SUBSCRIBED", DisconnectRed, DisconnectRed.copy(0.1f))
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DeepNavy).verticalScroll(rememberScrollState())
    ) {
        // ── Header ──────────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(SurfaceCard)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(20.dp))

            // ── Profile / Subscription Card ───────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Avatar circle
                        Box(
                            modifier = Modifier.size(52.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(PurpleAccent, ConnectingBlue))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (emailState.isNotEmpty()) emailState.first().uppercaseChar().toString() else "U",
                                fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (emailState.isNotEmpty()) emailState else "No email set",
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Status badge
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(statusBg)
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(statusLabel, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = statusColor, letterSpacing = 1.sp)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = SurfaceElevated)

                    // Subscription details row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SubDetailItem(label = "STATUS", value = statusLabel, color = statusColor)
                        SubDetailItem(
                            label = "EXPIRES",
                            value = if (isSubscribed && expiry.isNotEmpty()) expiry else "—",
                            color = Color.White
                        )
                        SubDetailItem(label = "DEVICES", value = "$devicesCount / 2", color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Deregister device link
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Deregister this device", fontSize = 13.sp, color = DisconnectRed,
                            modifier = Modifier.clickable {
                                viewModel.deregisterCurrentDevice { if (it) onLogout() }
                            })
                        Text("Manage Subscription ›", fontSize = 12.sp, color = PurpleAccent,
                            modifier = Modifier.clickable {
                                // Opens Google Play subscriptions
                                try {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://play.google.com/store/account/subscriptions"))
                                    )
                                } catch (_: Exception) {}
                            })
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Security ─────────────────────────────────────────
            SectionHeader("🔐  SECURITY")
            Spacer(modifier = Modifier.height(10.dp))

            // Kill Switch row
            SettingsToggleRow(
                icon = Icons.Default.Warning,
                iconColor = DisconnectRed,
                title = "VPN Kill Switch",
                subtitle = "Block all internet if VPN drops unexpectedly",
                checked = isKillSwitch,
                onCheckedChange = { enabled ->
                    viewModel.setKillSwitch(enabled)
                    if (enabled) {
                        android.widget.Toast.makeText(
                            context,
                            "Enable 'Always-on VPN' + 'Block without VPN' in system settings for full protection.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        try { context.startActivity(viewModel.getVpnSettingsIntent()) } catch (_: Exception) {}
                    }
                }
            )

            // Warning banner when Kill Switch is ON
            AnimatedVisibility(
                visible = isKillSwitch,
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DisconnectRed.copy(alpha = 0.1f))
                        .border(1.dp, DisconnectRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = DisconnectRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Kill Switch is ON — all internet will be blocked if VPN disconnects",
                            fontSize = 11.sp,
                            color = DisconnectRed,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsLinkRow(
                icon = Icons.Default.Lock,
                iconColor = ConnectingBlue,
                title = "Protocol",
                value = if (currentProtocol == "openvpn") "OpenVPN" else "WireGuard",
                onClick = { showProtocolDialog = true }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Account ──────────────────────────────────────────
            SectionHeader("👤  ACCOUNT")
            Spacer(modifier = Modifier.height(10.dp))

            // Email input row
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Recovery Email", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Used to recover your subscription on a new device.", fontSize = 11.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = {
                                emailInput = it
                                emailError = it.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches()
                            },
                            placeholder = { Text("your@email.com", color = TextMuted, fontSize = 13.sp) },
                            singleLine = true,
                            isError = emailError,
                            supportingText = if (emailError) {
                                { Text("Enter a valid email address", color = DisconnectRed, fontSize = 11.sp) }
                            } else null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PurpleAccent,
                                unfocusedBorderColor = SurfaceElevated,
                                errorBorderColor = DisconnectRed
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { if (!emailError && emailInput.isNotEmpty()) viewModel.saveEmail(emailInput) },
                            enabled = emailInput.isNotEmpty() && !emailError,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Support ──────────────────────────────────────────
            SectionHeader("💬  SUPPORT")
            Spacer(modifier = Modifier.height(10.dp))

            SettingsLinkRow(icon = Icons.Default.Email, iconColor = ConnectingBlue, title = "Contact Support", value = AppUrls.SUPPORT_EMAIL) {
                try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:${AppUrls.SUPPORT_EMAIL}")
                    })
                } catch (_: Exception) {}
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsLinkRow(icon = Icons.Default.Info, iconColor = PurpleAccent, title = "Privacy Policy", value = "View Policy Online") {
                try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AppUrls.PRIVACY_POLICY)))
                } catch (_: Exception) {}
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsLinkRow(icon = Icons.Default.Info, iconColor = TextMuted, title = "App Version", value = "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})") {}

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Frequently Asked Questions ───────────────────────
            SectionHeader("❓  FREQUENTLY ASKED QUESTIONS")
            Spacer(modifier = Modifier.height(10.dp))
            FaqAccordionSection()

            Spacer(modifier = Modifier.height(28.dp))

            // ── Sign Out ─────────────────────────────────────────────────
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = DisconnectRed,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = TextMuted
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, DisconnectRed.copy(alpha = 0.4f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = DisconnectRed, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out", fontWeight = FontWeight.SemiBold, color = DisconnectRed)
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // ── Delete Account ───────────────────────────────────────────
            OutlinedButton(
                onClick = { showDeleteAccountDialog = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = DisconnectRed,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = TextMuted
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, DisconnectRed.copy(alpha = 0.4f))
            ) {
                Icon(Icons.Default.Delete, null, tint = DisconnectRed, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Account", fontWeight = FontWeight.SemiBold, color = DisconnectRed)
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (showProtocolDialog) {
        AlertDialog(
            onDismissRequest = { showProtocolDialog = false },
            title = { Text("Select Protocol", color = Color.White) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.setProtocol("wireguard")
                            showProtocolDialog = false
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentProtocol == "wireguard",
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = PurpleAccent)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("WireGuard (Recommended)", color = Color.White)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = false,
                            onClick = null,
                            enabled = false,
                            colors = RadioButtonDefaults.colors(disabledUnselectedColor = TextMuted)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("OpenVPN", color = TextMuted, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(TextMuted.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("COMING SOON", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = TextMuted, letterSpacing = 0.5.sp)
                                }
                            }
                            Text("Available in a future update", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProtocolDialog = false }) {
                    Text("Cancel", color = PurpleAccent)
                }
            },
            containerColor = SurfaceCard
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text("Delete Account", color = Color.White) },
            text = { Text("Are you sure you want to permanently delete your account? This action cannot be undone.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        viewModel.deleteAccount { success ->
                            if (success) onLogout()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DisconnectRed)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = SurfaceCard
        )
    }
}

// ── Reusable setting row components ──────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TextMuted, letterSpacing = 1.5.sp)
}

@Composable
private fun SubDetailItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = TextMuted, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconColor.copy(0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    Text(subtitle, fontSize = 11.sp, color = TextSecondary)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PurpleAccent)
            )
        }
    }
}



@Composable
private fun SettingsLinkRow(icon: ImageVector, iconColor: Color, title: String, value: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconColor.copy(0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
            }
            Text(value, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FAQ Accordion Section
// ─────────────────────────────────────────────────────────────────────────────

private data class FaqItem(val question: String, val answer: String)

@Composable
private fun FaqAccordionSection() {
    val faqs = remember {
        listOf(
            FaqItem(
                question = "How does Unlimited VPN protect my privacy?",
                answer = "We use bank-grade WireGuard AES-256 encryption. We enforce a strict zero-logs policy, meaning we never track, store, or monitor your browsing activity or DNS queries."
            ),
            FaqItem(
                question = "What is the Kill Switch feature?",
                answer = "If your VPN drops unexpectedly, Kill Switch instantly halts all internet traffic so unencrypted data never leaks onto public networks."
            ),
            FaqItem(
                question = "Why does connection speed vary between servers?",
                answer = "Speed depends on distance to the server and network congestion. Tap 'Select Server' to pick the location with the lowest ping latency."
            ),
            FaqItem(
                question = "How do I manage or cancel my subscription?",
                answer = "You can manage or cancel your subscription anytime via Google Play Store → Profile → Payments & Subscriptions."
            ),
            FaqItem(
                question = "What should I do if VPN fails to connect?",
                answer = "Try toggling Airplane mode on/off, selecting a different server, or checking your Wi-Fi/mobile data connection."
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        faqs.forEach { faq ->
            FaqItemCard(faq = faq)
        }
    }
}

@Composable
private fun FaqItemCard(faq: FaqItem) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = faq.question,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = PurpleAccent,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = SurfaceElevated, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = faq.answer,
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
