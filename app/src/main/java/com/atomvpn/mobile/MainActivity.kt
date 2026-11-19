package com.atomvpn.mobile

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.pow.*
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private val Bg = Color(0xFF080D19)
private val Panel = Color(0xF0142033)
private val PanelRaised = Color(0xFF1A2940)
private val Blue = Color(0xFF8B7CFF)
private val Cyan = Color(0xFF45D6D0)
private val Green = Color(0xFF36D9A2)
private val Amber = Color(0xFFFFB44A)
private val Red = Color(0xFFFF7A7A)
private val Muted = Color(0xFF9AA8BC)

private enum class UiState { IDLE, CONNECTING, CONNECTED, FAILED }

private fun pageDepth(page: String) = when (page) {
    "settings" -> 1
    "countries" -> 2
    else -> 0
}

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

// ───────────────────────────── Root ─────────────────────────────

@Composable
private fun AtomPowApp(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val status by PowStatusStore.status.collectAsStateWithLifecycle()
    val exitIpState by ExitIpInfoRepository.state.collectAsStateWithLifecycle()
    val engine = remember(context) { PowEngineStore.get(context) }
    val settings by engine.settings.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("home") }
    var countryQuery by remember { mutableStateOf("") }

    val connected = connection == ConnectionState.CONNECTED
    val exitCountryLabel = when {
        !connected -> "—"
        status.exitRegion.isNotBlank() -> PowRegions.name(status.exitRegion, Locale("fa")).ifBlank { status.exitRegion }
        !exitIpState.info?.countryCode.isNullOrBlank() -> PowRegions.name(exitIpState.info!!.countryCode, Locale("fa")).ifBlank { exitIpState.info!!.countryCode }
        exitIpState.isLoading -> "در حال شناسایی…"
        else -> "نامشخص"
    }
    val connecting = connection == ConnectionState.CONNECTING ||
        status.phase in setOf(PowPhase.STARTING, PowPhase.OUTER, PowPhase.INNER, PowPhase.BRIDGING)
    // پیام "Job was cancelled" یعنی کاربر خودش لغو کرده؛ شکست حساب نمی‌شود
    val cancelled = status.detail.contains("cancel", ignoreCase = true)
    val failed = (connection == ConnectionState.ERROR || status.phase == PowPhase.FAILED) && !cancelled
    val ui = when {
        connected -> UiState.CONNECTED
        connecting -> UiState.CONNECTING
        failed -> UiState.FAILED
        else -> UiState.IDLE
    }

    val carrierLabel = when {
        settings.carrierMode == PowCoreConfig.CARRIER_OFF -> "تشخیص خاموش · مسیر عمومی"
        settings.carrierMode == PowCoreConfig.CARRIER_MANUAL &&
            settings.firewallProfile == PowCoreConfig.FIREWALL_MCI -> "همراه اول · انتخاب دستی"
        settings.carrierMode == PowCoreConfig.CARRIER_MANUAL -> "ایرانسل · انتخاب دستی"
        else -> "خودکار · بدون تشخیص اپراتور"
    }

    // دکمه Back سیستم: قبلاً نبود و برنامه مستقیم بسته می‌شد
    BackHandler(enabled = page != "home") {
        page = if (page == "countries") "settings" else "home"
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Bg, surface = Panel, onBackground = Color.White, onSurface = Color.White
    )) {
        // رنگ پیش‌فرض متن بیرون از Card سفید می‌شود (قبلاً «ATOM» و «UAC PoW» مشکی و نامرئی بودند)
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF080D19), Color(0xFF101B2D), Color(0xFF0A1726))))) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val dir = if (pageDepth(targetState) > pageDepth(initialState)) 1 else -1
                        val ease = FastOutSlowInEasing
                        (fadeIn(tween(300, delayMillis = 70, easing = ease)) +
                            slideInHorizontally(tween(380, easing = ease)) { (it * 0.14f * dir).toInt() }) togetherWith
                            (fadeOut(tween(150)) +
                                slideOutHorizontally(tween(380, easing = ease)) { (-it * 0.14f * dir).toInt() })
                    },
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
                            val faLocale = remember { Locale("fa") }
                            // نام‌ها یک بار محاسبه می‌شوند، نه در هر فریم اسکرول
                            val names = remember(countries) { countries.associateWith { PowRegions.name(it, faLocale) } }
                            val filtered = remember(countries, countryQuery) {
                                countries.filter { PowRegions.matches(it, countryQuery) }
                            }
                            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp)) {
                                ScreenHeader("کشور خروجی", "انتخاب مسیر خروجی PoW") { page = "settings" }
                                OutlinedTextField(
                                    value = countryQuery,
                                    onValueChange = { countryQuery = it },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    placeholder = { Text("جستجوی کشور", color = Muted) },
                                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted) },
                                    trailingIcon = {
                                        AnimatedVisibility(countryQuery.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                            IconButton(onClick = { countryQuery = "" }) {
                                                Icon(Icons.Outlined.Close, "پاک کردن", tint = Muted)
                                            }
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Blue,
                                        unfocusedBorderColor = Color.White.copy(alpha = .1f),
                                        cursorColor = Blue,
                                        focusedContainerColor = Panel,
                                        unfocusedContainerColor = Panel
                                    )
                                )
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                    item(key = "auto-row") {
                                        CountryRow("خودکار", "AUTO", settings.exitCountryCode.isBlank()) {
                                            engine.save(settings.copy(exitCountryCode = "").validated())
                                            page = "settings"
                                        }
                                    }
                                    items(filtered, key = { it }) { code ->
                                        CountryRow(names[code] ?: code, code, settings.exitCountryCode == code) {
                                            engine.save(settings.copy(exitCountryCode = code).validated())
                                            page = "settings"
                                        }
                                    }
                                    if (filtered.isEmpty()) {
                                        item(key = "empty-row") {
                                            Text(
                                                "کشوری پیدا نشد",
                                                color = Muted,
                                                fontSize = 13.sp,
                                                modifier = Modifier.fillMaxWidth().padding(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> HomePage(
                            ui = ui,
                            progress = status.progressPercent / 100f,
                            detail = if (cancelled && !connecting && !connected) "اتصال لغو شد" else status.detail,
                            outerLabel = status.outerLabel,
                            settings = settings,
                            carrierLabel = carrierLabel,
                            exitCountryLabel = exitCountryLabel,
                            exitIpState = exitIpState,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                            onOpenSettings = { page = "settings" },
                            onOpenCountries = { page = "countries" }
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Home ─────────────────────────────

@Composable
private fun HomePage(
    ui: UiState,
    progress: Float,
    detail: String,
    outerLabel: String,
    settings: PowEngineSettings,
    carrierLabel: String,
    exitCountryLabel: String,
    exitIpState: ExitIpInfoState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCountries: () -> Unit
) {
    val connected = ui == UiState.CONNECTED
    val connecting = ui == UiState.CONNECTING
    val active = connected || connecting

    // انیمیشن‌های رنگ فقط همین صفحه را بازترکیب می‌کنند، نه کل برنامه
    val accent by animateColorAsState(
        when (ui) {
            UiState.CONNECTED -> Green
            UiState.FAILED -> Red
            else -> Blue
        },
        tween(650), label = "connection-accent"
    )
    val statusColor by animateColorAsState(
        when (ui) {
            UiState.CONNECTED -> Green
            UiState.CONNECTING -> Amber
            UiState.FAILED -> Red
            UiState.IDLE -> Muted
        },
        tween(400), label = "status-color"
    )
    val animatedProgress by animateFloatAsState(progress.coerceIn(0f, 1f), tween(500, easing = FastOutSlowInEasing), label = "progress")

    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = .16f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.34f),
                    radius = size.width * 0.95f
                )
            )
        }
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Reveal(0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).background(Brush.linearGradient(listOf(Blue, Cyan)), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Bolt, null, tint = Color.White, modifier = Modifier.size(25.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text("ATOM", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.5.sp)
                            Text("PRIVATE NETWORK", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Tune, "تنظیمات", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Reveal(1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, accent.copy(alpha = .34f), RoundedCornerShape(30.dp))
                ) {
                    Column(Modifier.padding(24.dp).animateContentSize(tween(350, easing = FastOutSlowInEasing))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(statusColor, pulsing = active)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                AnimatedContent(
                                    targetState = ui,
                                    transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
                                    label = "status-text"
                                ) { s ->
                                    Text(
                                        when (s) {
                                            UiState.CONNECTED -> "متصل"
                                            UiState.CONNECTING -> "در حال برقراری تونل"
                                            UiState.FAILED -> "اتصال ناموفق بود"
                                            UiState.IDLE -> "آماده برای اتصال"
                                        },
                                        color = if (s == UiState.CONNECTED) Green else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Text(
                                    when {
                                        connected -> "ترافیک از تونل عبور می‌کند"
                                        connecting -> "لطفاً چند لحظه صبر کن"
                                        ui == UiState.FAILED -> "برای تلاش دوباره، دکمه را لمس کن"
                                        else -> "برای شروع، دکمه اتصال را لمس کن"
                                    },
                                    color = Muted, fontSize = 10.sp
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text("${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ConnectButton(ui = ui, accent = accent) {
                                if (active) onDisconnect() else onConnect()
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = accent,
                            trackColor = Color.White.copy(alpha = .08f)
                        )
                        Spacer(Modifier.height(10.dp))
                        AnimatedContent(
                            targetState = detail.ifBlank { "موتور اتصال آماده است" },
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                            label = "detail-text"
                        ) { text ->
                            Text(text, color = if (ui == UiState.FAILED) Red else Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            Reveal(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoTile("کشور خروجی", exitCountryLabel, Modifier.weight(1f), Icons.Outlined.Public) { onOpenCountries() }
                    InfoTile("پروتکل اتصال", settings.linkRecipe.uppercase(), Modifier.weight(1f), Icons.Outlined.Hub) { onOpenSettings() }
                }
            }
            Spacer(Modifier.height(14.dp))

            Reveal(3) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(22.dp))
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Public, null, tint = Cyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("IP خروجی عمومی", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            if (exitIpState.isLoading && connected) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Cyan)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            exitIpState.info?.ipAddress ?: when {
                                !connected -> "پس از اتصال نمایش داده می‌شود"
                                exitIpState.isLoading -> "در حال دریافت IP از مسیر تونل…"
                                exitIpState.errorMessage != null -> "دریافت IP ناموفق بود"
                                else -> "در انتظار اطلاعات خروجی"
                            },
                            color = if (exitIpState.info != null && connected) Color.White else Muted,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (exitIpState.info != null && connected) "این IP برای سایت‌ها و سرویس‌ها قابل مشاهده است"
                            else "درخواست شناسایی فقط از طریق SOCKS موتور PoW ارسال می‌شود",
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            Reveal(4) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(22.dp))
                ) {
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
                                outerLabel.ifBlank { "پس از شروع اتصال مشخص می‌شود" }
                            }
                        )
                        PathLine(
                            "تونل VPN",
                            when {
                                connected -> "متصل"
                                connecting -> "در حال برقراری"
                                else -> "قطع"
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("ATOM  •  PRIVATE NETWORK", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

// ───────────────────────── Connect button ─────────────────────────

@Composable
private fun ConnectButton(ui: UiState, accent: Color, onClick: () -> Unit) {
    val connected = ui == UiState.CONNECTED
    val connecting = ui == UiState.CONNECTING
    val active = connected || connecting
    val haptic = LocalHapticFeedback.current

    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "button-press"
    )
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(600), label = "button-glow")

    // لرزش کوتاه و لرزش لمسی هنگام ورود به حالت ناموفق
    val shake = remember { Animatable(0f) }
    LaunchedEffect(ui) {
        if (ui == UiState.FAILED) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            for (v in listOf(-12f, 10f, -8f, 6f, -3f, 0f)) shake.animateTo(v, tween(60))
        }
    }

    Box(
        Modifier.size(252.dp).drawBehind {
            drawCircle(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = .20f * glow), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f
                )
            )
        },
        contentAlignment = Alignment.Center
    ) {
        // حلقه‌ها فقط وقتی فعال‌اند ساخته می‌شوند، پس در حالت بیکار هزینه‌ای ندارند
        AnimatedVisibility(
            visible = active,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400))
        ) {
            ConnectionRings(accent = accent, connecting = connecting, modifier = Modifier.fillMaxSize())
        }

        Box(
            Modifier.size(184.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale; translationX = shake.value.dp.toPx() }
                .background(Brush.radialGradient(listOf(Color(0xFF223250), PanelRaised)), CircleShape)
                .border(2.dp, accent.copy(alpha = .82f), CircleShape)
                .clip(CircleShape)
                .clickable(interactionSource = source, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = ui,
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 80)) + scaleIn(tween(260, delayMillis = 80), initialScale = 0.85f)) togetherWith
                        fadeOut(tween(120))
                },
                label = "button-content"
            ) { s ->
                val on = s == UiState.CONNECTED || s == UiState.CONNECTING
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        when {
                            on -> Icons.Outlined.PowerSettingsNew
                            s == UiState.FAILED -> Icons.Outlined.Refresh
                            else -> Icons.Outlined.Bolt
                        },
                        null, tint = accent, modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (s) {
                            UiState.CONNECTED -> "قطع اتصال"
                            UiState.CONNECTING -> "لغو اتصال"
                            UiState.FAILED -> "تلاش دوباره"
                            UiState.IDLE -> "اتصال"
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        when (s) {
                            UiState.CONNECTED -> "اتصال امن برقرار است"
                            UiState.CONNECTING -> "در حال برقراری اتصال"
                            UiState.FAILED -> "برای تلاش دوباره لمس کنید"
                            else -> "برای اتصال لمس کنید"
                        },
                        color = Muted, fontSize = 9.sp, letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

/**
 * انیمیشن‌ها فقط در مرحله‌ی draw خوانده می‌شوند، پس هیچ بازترکیبی (recomposition) در هر فریم رخ نمی‌دهد.
 * حالت connecting: کمان چرخان. حالت connected: موج‌های آرام.
 */
@Composable
private fun ConnectionRings(accent: Color, connecting: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "rings")
    val ripple by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearOutSlowInEasing)),
        label = "ripple"
    )
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spin"
    )
    val spinAlpha by animateFloatAsState(if (connecting) 1f else 0f, tween(400), label = "spin-alpha")

    Canvas(modifier) {
        val c = center
        val buttonRadius = 92.dp.toPx()
        val maxRadius = size.minDimension / 2f

        for (i in 0..1) {
            val p = (ripple + i * 0.5f) % 1f
            drawCircle(
                color = accent.copy(alpha = (1f - p) * .32f * (1f - spinAlpha)),
                radius = buttonRadius + (maxRadius - buttonRadius) * p,
                center = c,
                style = Stroke(2.dp.toPx())
            )
        }

        if (spinAlpha > 0.01f) {
            val r = buttonRadius + 10.dp.toPx()
            val head = accent.copy(alpha = spinAlpha)
            rotate(spin, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(0f to Color.Transparent, .83f to head, 1f to head, center = c),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (pulsing) PulseHalo(color)
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

@Composable
private fun PulseHalo(color: Color) {
    val transition = rememberInfiniteTransition(label = "halo")
    val p by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearOutSlowInEasing)),
        label = "halo-progress"
    )
    Box(
        Modifier.size(22.dp)
            .graphicsLayer {
                val s = 0.45f + 0.55f * p
                scaleX = s; scaleY = s; alpha = 0.55f * (1f - p)
            }
            .background(color, CircleShape)
    )
}

// ───────────────────────── Settings & countries ─────────────────────────

@Composable
private fun PowSettingsPage(
    settings: PowEngineSettings,
    onBack: () -> Unit,
    onOpenCountries: () -> Unit,
    onSave: (PowEngineSettings) -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp)) {
        ScreenHeader("تنظیمات", "مدیریت اتصال", onBack)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Reveal(0) {
                    SettingCard("مسیر خروجی", "کشور انتخاب‌شده: ${settings.exitCountryCode.ifBlank { "خودکار" }}", "انتخاب کشور", onOpenCountries)
                }
            }
            item {
                val carrierModeLabel = when {
                    settings.carrierMode == PowCoreConfig.CARRIER_OFF -> "خاموش · مسیر عمومی"
                    settings.carrierMode == PowCoreConfig.CARRIER_MANUAL &&
                        settings.firewallProfile == PowCoreConfig.FIREWALL_MCI -> "همراه اول · دستی"
                    settings.carrierMode == PowCoreConfig.CARRIER_MANUAL -> "ایرانسل · دستی"
                    else -> "خودکار · بدون تشخیص اپراتور"
                }
                Reveal(1) {
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
            }
            item {
                Reveal(2) {
                    SettingCard("روش اتصال", settings.linkRecipe, "تغییر روش", {
                        val next = if (settings.linkRecipe == PowCoreConfig.RECIPE_MASQUE_H2) PowCoreConfig.RECIPE_MASQUE_H3 else PowCoreConfig.RECIPE_MASQUE_H2
                        onSave(settings.copy(linkRecipe = next).validated())
                    })
                }
            }
            item {
                Reveal(3) {
                    SettingCard("حالت خروجی", settings.exitMode, "تغییر حالت", {
                        val next = when (settings.exitMode) {
                            PowEngineSettings.EXIT_CHAIN -> PowEngineSettings.EXIT_WARP
                            PowEngineSettings.EXIT_WARP -> PowEngineSettings.EXIT_PSIPHON
                            else -> PowEngineSettings.EXIT_CHAIN
                        }
                        onSave(settings.copy(exitMode = next).validated())
                    })
                }
            }
            item {
                Reveal(4) {
                    SettingCard("حمل‌ونقل بیرونی", settings.outerTransport, "تغییر transport", {
                        val options = listOf(PowCoreConfig.OUTER_AUTO) + PowCoreConfig.OUTER_LADDER
                        val next = options[(options.indexOf(settings.outerTransport).coerceAtLeast(0) + 1) % options.size]
                        onSave(settings.copy(outerTransport = next).validated())
                    })
                }
            }
            item {
                Reveal(5) {
                    SettingCard("بهینه‌سازی اتصال", if (settings.optimizedMode) "فعال" else "غیرفعال", "تغییر", {
                        onSave(settings.copy(optimizedMode = !settings.optimizedMode).validated())
                    })
                }
            }
            item {
                val fragmentModeLabel = when (settings.h2FragmentMode) {
                    PowEngineSettings.H2_FRAG_ON -> "فعال"
                    PowEngineSettings.H2_FRAG_OFF -> "غیرفعال"
                    else -> "خودکار"
                }
                Reveal(6) {
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
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "بازگشت", tint = Color.White) }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}

// ───────────────────────────── Components ─────────────────────────────

/** ورود تدریجی (fade + حرکت از پایین) بدون جابه‌جایی layout، چون محتوا همیشه ساخته شده است. */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 28.dp.toPx()
        }
    ) { content() }
}

/** حالت فشرده شدن نرم و فنری برای کارت‌ها. */
@Composable
private fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press-scale"
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
private fun CountryRow(title: String, code: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor by animateColorAsState(if (selected) Blue.copy(alpha = .7f) else Color.White.copy(alpha = .07f), tween(250), label = "country-border")
    val tint by animateColorAsState(if (selected) Blue.copy(alpha = .10f) else Color.Transparent, tween(250), label = "country-tint")
    Row(
        Modifier.fillMaxWidth()
            .background(Panel, shape)
            .background(tint, shape)
            .border(1.dp, borderColor, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(code, color = Muted, fontSize = 11.sp)
        }
        AnimatedVisibility(selected, enter = fadeIn() + scaleIn(initialScale = 0.5f), exit = fadeOut() + scaleOut(targetScale = 0.5f)) {
            Icon(Icons.Outlined.CheckCircle, null, tint = Blue)
        }
    }
}

@Composable
private fun InfoTile(title: String, value: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = source,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.pressScale(source).border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(18.dp))
    ) {
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
    val source = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = source,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().pressScale(source).border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(18.dp))
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                // وقتی مقدار با لمس عوض می‌شود، با اسلاید نرم جایگزین می‌شود
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 40)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(tween(200)) { -it / 2 })
                    },
                    label = "setting-value"
                ) { v ->
                    Text(v, color = Muted, fontSize = 12.sp)
                }
            }
            Text(
                action, color = Blue, fontSize = 12.sp,
                modifier = Modifier.background(Blue.copy(alpha = .12f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun PathLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, color = Muted, modifier = Modifier.width(112.dp))
        AnimatedContent(
            targetState = value,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "path-value"
        ) { v ->
            Text(v, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}