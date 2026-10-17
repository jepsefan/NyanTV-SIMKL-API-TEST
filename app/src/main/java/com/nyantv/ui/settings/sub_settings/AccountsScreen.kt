package com.nyantv.ui.settings.sub_settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.nyantv.data.ServiceType
import com.nyantv.ui.utils.SectionCard
import com.nyantv.ui.utils.SubScreenHeader
import com.nyantv.ui.utils.focusBorder
import com.nyantv.viewmodel.AppViewModel

@Composable
fun AccountsScreen(vm: AppViewModel, navController: NavController) {
    val context  = LocalContext.current
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val profile  by vm.profile.collectAsStateWithLifecycle()
    val service  by vm.serviceType.collectAsStateWithLifecycle()
    val simklSession by vm.simklDeviceSession.collectAsStateWithLifecycle()
    val simklStatus by vm.simklDeviceStatus.collectAsStateWithLifecycle()
    var showSimklLogin by remember { mutableStateOf(false) }

    if (service == ServiceType.SIMKL && showSimklLogin && !loggedIn) {
        AlertDialog(
            onDismissRequest = {
                showSimklLogin = false
                vm.cancelSimklDeviceLogin()
            },
            title = { Text("Connect to Simkl") },
            text = {
                Column(
                    modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val session = simklSession
                    if (session != null) {
                        val qrBitmap = remember(session.qrUrl) {
                            runCatching {
                                val bits = QRCodeWriter().encode(session.qrUrl, BarcodeFormat.QR_CODE, 300, 300)
                                val pixels = IntArray(300 * 300) { index ->
                                    if (bits[index % 300, index / 300]) android.graphics.Color.BLACK
                                    else android.graphics.Color.WHITE
                                }
                                Bitmap.createBitmap(pixels, 300, 300, Bitmap.Config.ARGB_8888)
                                    .apply { setPixels(pixels, 0, 300, 0, 0, 300, 300) }
                                    .asImageBitmap()
                            }.getOrNull()
                        }
                        qrBitmap?.let { bitmap ->
                            Image(bitmap = bitmap, contentDescription = "Simkl activation QR code",
                                modifier = Modifier.size(220.dp))
                        }
                        Text("Scan the QR code or open Simkl and approve this login.", style = MaterialTheme.typography.bodyMedium)
                        Text(session.qrUrl, style = MaterialTheme.typography.bodySmall)
                        Text(session.userCode, style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold)
                        Text("Open simkl.com/pin to enter this code if needed.",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (simklStatus.isBlank() || simklStatus.startsWith("Requesting")) {
                        CircularProgressIndicator()
                    }
                    Text(simklStatus, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.startSimklDeviceLogin() }) { Text("New code") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSimklLogin = false
                    vm.cancelSimklDeviceLogin()
                }) { Text("Cancel") }
            }
        )
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) showSimklLogin = false
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SubScreenHeader(title = "Accounts & Services", navController = navController)

        // ── Account ────────────────────────────────────────────────────────────
        SectionCard(title = "Account") {
            if (loggedIn && profile != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.padding(12.dp)
                ) {
                    AsyncImage(
                        model              = profile!!.avatar,
                        contentDescription = null,
                        modifier           = Modifier.size(56.dp).clip(CircleShape)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile!!.name ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(service.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        profile?.animeCount?.let {
                            Text("$it anime · ${profile?.episodesWatched ?: "?"} eps watched",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    OutlinedButton(onClick = { vm.logout() }) { Text("Logout") }
                }
            } else {
                Row(
                    modifier              = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Person, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Text("Not logged in", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Button(onClick = {
                            if (service == ServiceType.SIMKL) {
                                showSimklLogin = true
                                vm.startSimklDeviceLogin()
                            } else vm.login(context)
                        }, modifier = Modifier.focusBorder(CircleShape)) {
                            Text("Login")
                        }
                    }
                }
            }
        }

        // ── Tracking Service ───────────────────────────────────────────────────
        SectionCard(title = "Tracking Service") {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ServiceType.entries.forEach { svc ->
                    val selected = service == svc
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                            .clickable { vm.switchService(svc) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { vm.switchService(svc) } )
                        Text(svc.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        // ── Tracking Automation ────────────────────────────────────────────────────────
        val trackingMode by vm.trackingMode.collectAsStateWithLifecycle()

        SectionCard(title = "Tracking Automation") {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    AppViewModel.TrackingMode.ALWAYS_ASK  to "Always ask for tracking permission",
                    AppViewModel.TrackingMode.ALWAYS_AUTO to "Always track automatically",
                    AppViewModel.TrackingMode.NEVER_AUTO  to "Never track automatically"
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(if (trackingMode == mode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                            .clickable { vm.setTrackingMode(mode) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = trackingMode == mode, onClick = { vm.setTrackingMode(mode) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ── Sync Tracking ──────────────────────────────────────────────────────────────
        val syncMal by vm.syncMalWithAnilist.collectAsStateWithLifecycle()

        SectionCard(title = "Sync Tracking") {
            Row(
                modifier              = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sync MyAnimeList with AniList", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Automatically sync progress across both services", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Switch(
                        checked = syncMal,
                        onCheckedChange = { vm.setSyncMalWithAnilist(it) },
                        modifier = Modifier.focusBorder(RoundedCornerShape(50))
                    )
                }
            }
        }

        // ── Manage Homescreen Lists ────────────────────────────────────────────────────
        val anilistContinue  by vm.anilistShowContinue.collectAsStateWithLifecycle()
        val anilistPlanned   by vm.anilistShowPlanned.collectAsStateWithLifecycle()
        val malContinue      by vm.malShowContinue.collectAsStateWithLifecycle()
        val malPlanned       by vm.malShowPlanned.collectAsStateWithLifecycle()
        val simklContMovies  by vm.simklShowContMovies.collectAsStateWithLifecycle()
        val simklPlanMovies  by vm.simklShowPlanMovies.collectAsStateWithLifecycle()
        val simklContSeries  by vm.simklShowContSeries.collectAsStateWithLifecycle()
        val simklPlanSeries  by vm.simklShowPlanSeries.collectAsStateWithLifecycle()

        SectionCard(
            title = "Manage AniList Homescreen",
            dialogContent = {
                HomescreenToggleRow("Continue Watching", anilistContinue) { vm.setAnilistShowContinue(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                HomescreenToggleRow("Planned Anime", anilistPlanned) { vm.setAnilistShowPlanned(it) }
            }
        ) {}

        SectionCard(
            title = "Manage MyAnimeList Homescreen",
            dialogContent = {
                HomescreenToggleRow("Continue Watching", malContinue) { vm.setMalShowContinue(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                HomescreenToggleRow("Planned Anime", malPlanned) { vm.setMalShowPlanned(it) }
            }
        ) {}

        SectionCard(
            title = "Manage Simkl Homescreen",
            dialogContent = {
                HomescreenToggleRow("Continue Watching (Movies)", simklContMovies) { vm.setSimklShowContMovies(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                HomescreenToggleRow("Planned (Movies)", simklPlanMovies) { vm.setSimklShowPlanMovies(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                HomescreenToggleRow("Continue Watching (Series)", simklContSeries) { vm.setSimklShowContSeries(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                HomescreenToggleRow("Planned (Series)", simklPlanSeries) { vm.setSimklShowPlanSeries(it) }
            }
        ) {}
    }
}

@Composable
private fun HomescreenToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Switch(
                checked         = checked,
                onCheckedChange = onToggle,
                modifier        = Modifier.focusBorder(RoundedCornerShape(50))
            )
        }
    }
}