package com.atomvpn.mobile

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.pow.*
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import kotlinx.coroutines.launch
import java.util.Locale

private val Bg = Color(0xFF080D19)
private val Panel = Color(0xF0142033)
private val PanelRaised = Color(0xFF1A2940)
private val Blue = Color(0xFF8B7CFF)
private val Cyan = Color(0xFF45D6D0)
private val Green = Color(0xFF36D9A2)
private val Muted = Color(0xFF9AA8BC)

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK || VpnService.prepare(this) == null) {
            startService(Intent(this, PowVpnService::class.java).setAction(PowVpnService.ACTION_CONNECT))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 25)
        window.navigationBarColor = android.graphics.Color.rgb(8, 13, 25)
        setContent { AtomPowApp(
            onConnect = {
                val intent = VpnService.prepare(this)
                if (intent == null) startService(Intent(this, PowVpnService::class.java).setAction(PowVpnService.ACTION_CONNECT))
                else vpnPermission.launch(intent)
            },
            onDisconnect = {
                startService(Intent(this, PowVpnService::class.java).setAction(PowVpnService.ACTION_DISCONNECT))
            }
        ) }
    }
}

@Composable
private fun AtomPowApp(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val status by PowStatusStore.status.collectAsStateWithLifecycle()
    val engine = remember(context) { PowEngineStore.get(context) }
    val settings by engine.settings.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("home") }
    var countryQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val connected = connection == ConnectionState.CONNECTED
    val connecting = connection == ConnectionState.CONNECTING || status.phase in setOf(PowPhase.STARTING, PowPhase.OUTER, PowPhase.INNER, PowPhase.BRIDGING)
    val targetAccent = if (connected) Green else Blue
    val accent by animateColorAsState(targetAccent, tween(650), label = "connection-accent")
    val pulseTransition = rememberInfiniteTransition(label = "connection-pulse")
    val pulseScale = pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(animation = tween(1800), repeatMode = RepeatMode.Reverse),
        label = "connection-pulse-scale"
    ).value
    val carrierLabel = when {
        settings.carrierMode == PowCoreConfig.CARRIER_OFF -> "تشخیص خاموش · مسیر عمومی"
        settings.carrierMode == PowCoreConfig.CARRIER_MANUAL &&
            settings.firewallProfile == PowCoreConfig.FIREWALL_MCI -> "همراه اول · انتخاب دستی"
        settings.carrierMode == PowCoreConfig.CARRIER_MANUAL -> "ایرانسل · انتخاب دستی"
        else -> "خودکار · بدون تشخیص اپراتور"
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Bg, surface = Panel, onBackground = Color.White, onSurface = Color.White
    )) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF080D19), Color(0xFF101B2D), Color(0xFF0A1726))))) {
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "page-transition"
            ) { currentPage ->
            when (currentPage) {
                "settings" -> PowSettingsPage(
                    settings = settings,
                    onBack = { page = "home" },
                    onOpenCountries = { page = "countries" },
                    onSave = { engine.save(it) }
                )
                "countries" -> {
                    val countries = remember { PowRegions.options(engine.availableRegions()) }
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { page = "settings" }) { Icon(Icons.Outlined.ArrowBack, null, tint = Color.White) }
                            Column(Modifier.weight(1f)) {
                                Text("کشور خروجی", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("انتخاب مسیر خروجی PoW", color = Muted, fontSize = 12.sp)
                            }
                        }
                        OutlinedTextField(
                            value = countryQuery, onValueChange = { countryQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            singleLine = true, placeholder = { Text("جستجوی کشور") }
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                CountryRow("خودکار", "AUTO", settings.exitCountryCode.isBlank()) {
                                    engine.save(settings.copy(exitCountryCode = "").validated())
                                    page = "settings"
                                }
                            }
                            items(countries.filter { PowRegions.matches(it, countryQuery) }) { code ->
                                CountryRow(PowRegions.name(code, Locale("fa")), code, settings.exitCountryCode == code) {
                                    engine.save(settings.copy(exitCountryCode = code).validated())
                                    page = "settings"
                                }
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Blue, Cyan)), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.Bolt, null, tint = Color.White, modifier = Modifier.size(25.dp))
                                    }
                                    Spacer(Modifier.width(11.dp))
                                    Column {
                                        Text("ATOM", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.5.sp)
                                        Text("UAC POW  •  PRIVATE NETWORK", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                                    }
                                }
                            }
                            IconButton(onClick = { page = "settings" }) {
                                Icon(Icons.Outlined.Tune, "تنظیمات", tint = Color.White)
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(30.dp),
                            modifier = Modifier.fillMaxWidth()
                                .border(1.dp, accent.copy(alpha = .34f), RoundedCornerShape(30.dp))
                        ) {
                            Column(Modifier.padding(24.dp).animateContentSize(tween(350))) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).background(if (connected) Green else if (connecting) Color(0xFFFFB44A) else Muted, CircleShape).border(3.dp, (if (connected) Green else if (connecting) Color(0xFFFFB44A) else Muted).copy(alpha = .18f), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        AnimatedContent(
                                            targetState = when { connected -> "اتصال امن برقرار است"; connecting -> "در حال برقراری تونل"; connection == ConnectionState.ERROR || status.phase == PowPhase.FAILED -> "اتصال ناموفق بود"; else -> "آماده برای اتصال" },
                                            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
                                            label = "status-text"
                                        ) { label ->
                                            Text(label, color = if (connected) Green else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                        Text(if (connected) "ترافیک از تونل عبور می‌کند" else "برای شروع، دکمه اتصال را لمس کن", color = Muted, fontSize = 10.sp)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text("${status.progressPercent}%", color = Muted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(18.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Surface(
                                        onClick = { if (connected || connecting) onDisconnect() else onConnect() },
                                        shape = CircleShape,
                                        color = PanelRaised,
                                        modifier = Modifier.size(184.dp)
                                            .graphicsLayer { scaleX = if (connecting || connected) pulseScale else 1f; scaleY = if (connecting || connected) pulseScale else 1f }
                                            .border(2.dp, accent.copy(alpha = .82f), CircleShape)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(if (connected || connecting) Icons.Outlined.PowerSettingsNew else Icons.Outlined.Bolt, null, tint = accent, modifier = Modifier.size(42.dp))
                                                Spacer(Modifier.height(8.dp))
                                                Text(if (connected || connecting) "قطع اتصال" else "اتصال", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                Text(if (connected) "PROTECTED" else "TAP TO CONNECT", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(18.dp))
                                LinearProgressIndicator(
                                    progress = { status.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = accent,
                                    trackColor = Color.White.copy(alpha = .08f)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(status.detail.ifBlank { "موتور UAC PoW آماده است" }, color = Muted, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            InfoTile("موقعیت خروجی", settings.exitCountryCode.ifBlank { "خودکار" }, Modifier.weight(1f), Icons.Outlined.Public) { page = "countries" }
                            InfoTile("پروتکل اتصال", settings.linkRecipe.uppercase(), Modifier.weight(1f), Icons.Outlined.Hub) { page = "settings" }
                        }
                        Spacer(Modifier.height(14.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(22.dp))) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AccountTree, null, tint = Cyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("جزئیات مسیر اتصال", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Spacer(Modifier.height(10.dp))
                                PathLine("دستگاه", "ترافیک برنامه‌ها")
                                PathLine("اپراتور سیم‌کارت", carrierLabel)
                                PathLine(
                                    "مسیر انتخابی",
                                    when (settings.exitMode) {
                                        PowEngineSettings.EXIT_WARP -> "WARP مستقیم"
                                        PowEngineSettings.EXIT_PSIPHON -> "Psiphon مستقیم"
                                        else -> "Psiphon روی WARP"
                                    }
                                )
                                PathLine(
                                    "WARP / Aether",
                                    if (settings.exitMode == PowEngineSettings.EXIT_PSIPHON) {
                                        "در این حالت استفاده نمی‌شود"
                                    } else {
                                        status.outerLabel.ifBlank { "پس از شروع اتصال مشخص می‌شود" }
                                    }
                                )
                                PathLine(
                                    "تونل VPN",
                                    when {
                                        connected -> "فعال"
                                        connecting -> "در حال برقراری"
                                        else -> "قطع"
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("ATOM  •  POWERED BY UAC PoW", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun PowSettingsPage(
    settings: PowEngineSettings,
    onBack: () -> Unit,
    onOpenCountries: () -> Unit,
    onSave: (PowEngineSettings) -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null, tint = Color.White) }
            Column {
                Text("UAC PoW", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("تنظیمات موتور اتصال", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SettingCard("مسیر خروجی", "کشور انتخاب‌شده: ${settings.exitCountryCode.ifBlank { "خودکار" }}", "انتخاب کشور", onOpenCountries)
            }
            item {
                val carrierModeLabel = when {
                    settings.carrierMode == PowCoreConfig.CARRIER_OFF -> "خاموش · مسیر عمومی"
                    settings.carrierMode == PowCoreConfig.CARRIER_MANUAL &&
                        settings.firewallProfile == PowCoreConfig.FIREWALL_MCI -> "همراه اول · دستی"
                    settings.carrierMode == PowCoreConfig.CARRIER_MANUAL -> "ایرانسل · دستی"
                    else -> "خودکار · بدون تشخیص اپراتور"
                }
                SettingCard("اپراتور شبکه", carrierModeLabel, "تغییر", {
                    val next = when {
                        settings.carrierMode == PowCoreConfig.CARRIER_AUTO ->
                            settings.copy(carrierMode = PowCoreConfig.CARRIER_MANUAL, firewallProfile = PowCoreConfig.FIREWALL_MCI)
                        settings.carrierMode == PowCoreConfig.CARRIER_MANUAL &&
                            settings.firewallProfile == PowCoreConfig.FIREWALL_MCI ->
                            settings.copy(carrierMode = PowCoreConfig.CARRIER_MANUAL, firewallProfile = PowCoreConfig.FIREWALL_IRANCELL)
                        settings.carrierMode == PowCoreConfig.CARRIER_MANUAL ->
                            settings.copy(carrierMode = PowCoreConfig.CARRIER_OFF)
                        else -> settings.copy(carrierMode = PowCoreConfig.CARRIER_AUTO)
                    }
                    onSave(next.validated())
                })
            }
            item {
                SettingCard("روش اتصال", settings.linkRecipe, "تغییر روش", {
                    val next = if (settings.linkRecipe == PowCoreConfig.RECIPE_MASQUE_H2) PowCoreConfig.RECIPE_MASQUE_H3 else PowCoreConfig.RECIPE_MASQUE_H2
                    onSave(settings.copy(linkRecipe = next).validated())
                })
            }
            item {
                SettingCard("حالت خروجی", settings.exitMode, "تغییر حالت", {
                    val next = when (settings.exitMode) {
                        PowEngineSettings.EXIT_CHAIN -> PowEngineSettings.EXIT_WARP
                        PowEngineSettings.EXIT_WARP -> PowEngineSettings.EXIT_PSIPHON
                        else -> PowEngineSettings.EXIT_CHAIN
                    }
                    onSave(settings.copy(exitMode = next).validated())
                })
            }
            item {
                SettingCard("حمل‌ونقل بیرونی", settings.outerTransport, "تغییر transport", {
                    val options = listOf(PowCoreConfig.OUTER_AUTO) + PowCoreConfig.OUTER_LADDER
                    val next = options[(options.indexOf(settings.outerTransport).coerceAtLeast(0) + 1) % options.size]
                    onSave(settings.copy(outerTransport = next).validated())
                })
            }
            item {
                SettingCard("بهینه‌سازی اتصال", if (settings.optimizedMode) "فعال" else "غیرفعال", "تغییر", {
                    onSave(settings.copy(optimizedMode = !settings.optimizedMode).validated())
                })
            }
            item {
                val fragmentModeLabel = when (settings.h2FragmentMode) {
                    PowEngineSettings.H2_FRAG_ON -> "فعال"
                    PowEngineSettings.H2_FRAG_OFF -> "غیرفعال"
                    else -> "خودکار"
                }
                SettingCard("تکه‌تکه‌سازی H2", fragmentModeLabel, "تغییر حالت", {
                    val nextMode = when (settings.h2FragmentMode) {
                        PowEngineSettings.H2_FRAG_AUTO -> PowEngineSettings.H2_FRAG_ON
                        PowEngineSettings.H2_FRAG_ON -> PowEngineSettings.H2_FRAG_OFF
                        else -> PowEngineSettings.H2_FRAG_AUTO
                    }
                    onSave(settings.copy(
                        h2FragmentMode = nextMode,
                        h2Fragmentation = nextMode != PowEngineSettings.H2_FRAG_OFF
                    ).validated())
                })
            }
        }
    }
}

@Composable
private fun CountryRow(title: String, code: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) Blue.copy(alpha = .7f) else Color.White.copy(alpha = .07f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(code, color = Muted, fontSize = 11.sp) }
        if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = Blue)
    }
}

@Composable
private fun InfoTile(title: String, value: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = modifier.border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(18.dp))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Muted, fontSize = 10.sp)
                Text(value, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, value: String, action: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(18.dp))) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(value, color = Muted, fontSize = 12.sp) }
            Text(action, color = Blue, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PathLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, color = Muted, modifier = Modifier.width(112.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
