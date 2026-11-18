package com.atomvpn.mobile

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val Bg = Color(0xFF06111D)
private val Panel = Color(0xE6142231)
private val Blue = Color(0xFF299EFF)
private val Green = Color(0xFF25F58A)
private val Muted = Color(0xFF8D99A6)

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK || VpnService.prepare(this) == null) {
            startService(Intent(this, PowVpnService::class.java).setAction(PowVpnService.ACTION_CONNECT))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(6, 17, 29)
        window.navigationBarColor = android.graphics.Color.rgb(6, 17, 29)
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
    val accent = if (connected) Green else Blue

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Bg, surface = Panel, onBackground = Color.White, onSurface = Color.White
    )) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF020913), Bg, Color(0xFF0A1B2A))))) {
            when (page) {
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
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("ATOM", fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                                Text("POWERED BY UAC PoW", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                            }
                            IconButton(onClick = { page = "settings" }) {
                                Icon(Icons.Outlined.Tune, "تنظیمات", tint = Color.White)
                            }
                        }
                        Spacer(Modifier.height(30.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = .25f), RoundedCornerShape(20.dp))
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).background(if (connected) Green else if (connecting) Color(0xFFFFB44A) else Muted, CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when { connected -> "متصل"; connecting -> "در حال اتصال"; connection == ConnectionState.ERROR || status.phase == PowPhase.FAILED -> "خطا در اتصال"; else -> "آماده اتصال" },
                                        color = if (connected) Green else Color.White, fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text("${status.progressPercent}%", color = Muted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(18.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Surface(
                                        onClick = { if (connected || connecting) onDisconnect() else onConnect() },
                                        shape = CircleShape,
                                        color = Color(0xFF101F30),
                                        modifier = Modifier.size(190.dp).border(2.dp, accent.copy(alpha = .65f), CircleShape)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(if (connected || connecting) Icons.Outlined.PowerSettingsNew else Icons.Outlined.FlashOn, null, tint = accent, modifier = Modifier.size(42.dp))
                                                Spacer(Modifier.height(8.dp))
                                                Text(if (connected || connecting) "قطع اتصال" else "اتصال", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                                Text(if (connected) "SECURE TUNNEL" else "UAC POW ENGINE", color = Muted, fontSize = 9.sp)
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
                            InfoTile("کشور خروجی", settings.exitCountryCode.ifBlank { "خودکار" }, Modifier.weight(1f)) { page = "countries" }
                            InfoTile("پروتکل", settings.linkRecipe.uppercase(), Modifier.weight(1f)) { page = "settings" }
                        }
                        Spacer(Modifier.height(14.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("وضعیت مسیر زنده", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                PathLine("دستگاه", "ترافیک برنامه‌ها")
                                PathLine("WARP / Aether", status.outerLabel.ifBlank { "در انتظار اتصال" })
                                PathLine("Psiphon", status.exitRegion.ifBlank { "مسیر خروجی" })
                                PathLine("اینترنت", if (connected) "فعال" else "غیرفعال")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("ATOM VPN  •  UAC PoW Standalone", color = Muted, fontSize = 10.sp)
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
                val carrierModeLabel = when (settings.carrierMode) {
                    PowCoreConfig.CARRIER_MANUAL -> "دستی"
                    PowCoreConfig.CARRIER_OFF -> "خاموش · مسیر عمومی"
                    else -> "خودکار · تشخیص اپراتور"
                }
                SettingCard("تشخیص اپراتور", carrierModeLabel, "تغییر حالت", {
                    val next = when (settings.carrierMode) {
                        PowCoreConfig.CARRIER_AUTO -> PowCoreConfig.CARRIER_MANUAL
                        PowCoreConfig.CARRIER_MANUAL -> PowCoreConfig.CARRIER_OFF
                        else -> PowCoreConfig.CARRIER_AUTO
                    }
                    onSave(settings.copy(carrierMode = next).validated())
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
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) Blue.copy(alpha = .6f) else Color.White.copy(alpha = .06f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(code, color = Muted, fontSize = 11.sp) }
        if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = Blue)
    }
}

@Composable
private fun InfoTile(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Panel), modifier = modifier) {
        Column(Modifier.padding(14.dp)) { Text(title, color = Muted, fontSize = 11.sp); Text(value, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

@Composable
private fun SettingCard(title: String, value: String, action: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
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
