package net.klimakontrol.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.klimakontrol.R
import net.klimakontrol.data.Mode
import net.klimakontrol.data.schedule.Schedule
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

private val WD_RES = listOf(
    R.string.wd_mon, R.string.wd_tue, R.string.wd_wed, R.string.wd_thu,
    R.string.wd_fri, R.string.wd_sat, R.string.wd_sun,
)

/** I 7 nomi brevi dei giorni, risolti in contesto @Composable (non nei lambda). */
@Composable
private fun weekdayNames(): List<String> = listOf(
    stringResource(R.string.wd_mon), stringResource(R.string.wd_tue),
    stringResource(R.string.wd_wed), stringResource(R.string.wd_thu),
    stringResource(R.string.wd_fri), stringResource(R.string.wd_sat),
    stringResource(R.string.wd_sun),
)

private fun clockOf(millis: Long): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    return "%02d:%02d".format(t.hour, t.minute)
}

/**
 * Pianificazioni (timer) di un'unità — gestite dal telefono (questo hardware non ha scheduler
 * nativo). Due modi: un **timer rapido** ("tra X", passi di 30') e i **ricorrenti** settimanali.
 */
@Composable
fun TimerScreen(
    unitName: String,
    state: SchedulesState,
    onLoad: () -> Unit,
    onAddQuick: (delayMinutes: Int, turnOn: Boolean, temp: Float?) -> Unit,
    onAddWeekly: (hour: Int, minute: Int, weekday: List<Int>, turnOn: Boolean, temp: Float?) -> Unit,
    onToggle: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { onLoad() }

    // Su Android 13+ chiede una volta il permesso notifiche (per l'esito del timer); best-effort.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* concesso o no: il timer scatta comunque */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(c.ink, onBack)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.timer_title), style = QuadType.title, color = c.ink)
                Text(unitName, style = QuadType.micro, color = c.ink2)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // nota onesta: i timer li gestisce l'app
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(c.mode(Mode.FREDDO).container).padding(12.dp)) {
                Text(stringResource(R.string.timer_phone_note),
                    style = QuadType.micro, color = c.mode(Mode.FREDDO).on)
            }

            // avviso allarmi esatti non concessi (Android 12+)
            if (!state.canExact) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(c.surface2).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.timer_exact_warn), style = QuadType.micro, color = c.ink2)
                    Box(
                        Modifier.pressClickable(onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:" + ctx.packageName))
                                )
                            }.onFailure {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:" + ctx.packageName))
                                    )
                                }
                            }
                        }).clip(RoundedCornerShape(10.dp)).background(c.surface1)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.timer_exact_enable), style = QuadType.micro, color = accent) }
                }
            }

            state.error?.let { Text(it, style = QuadType.micro, color = c.error) }

            QuickTimerCard(accent, onAddQuick)

            WeeklyTimerForm(accent, onAddWeekly)

            // ---- elenco pianificazioni ----
            Text(stringResource(R.string.timer_list_title).uppercase(),
                style = QuadType.overline, color = c.ink3)
            if (state.schedules.isEmpty()) {
                Text(stringResource(R.string.timer_none), style = QuadType.body, color = c.ink3)
            } else {
                state.schedules.forEach { s -> ScheduleRow(s, onToggle, onDelete) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QuickTimerCard(
    accent: Color,
    onAddQuick: (delayMinutes: Int, turnOn: Boolean, temp: Float?) -> Unit,
) {
    val c = Klima.colors
    var delay by rememberSaveable { mutableStateOf(60) }   // minuti, passi da 30
    var turnOn by rememberSaveable { mutableStateOf(false) }  // "tra X ore spegni" è il caso tipico
    var temp by rememberSaveable { mutableStateOf("23") }

    val h = delay / 60
    val m = delay % 60
    val label = buildString {
        if (h > 0) append("${h}h ")
        if (m > 0 || h == 0) append("${m}m")
    }.trim()
    val fireClock = clockOf(System.currentTimeMillis() + delay * 60_000L)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.timer_quick_title), style = QuadType.name, color = c.ink)

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepButton("−", enabled = delay > 30) { if (delay > 30) delay -= 30 }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = QuadType.title, color = c.ink)
                Text(stringResource(R.string.timer_will_fire, fireClock),
                    style = QuadType.micro, color = c.ink2)
            }
            StepButton("+", enabled = delay < 720) { if (delay < 720) delay += 30 }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeChip(stringResource(R.string.timer_action_on), turnOn) { turnOn = true }
            HomeChip(stringResource(R.string.timer_action_off), !turnOn) { turnOn = false }
        }
        if (turnOn) {
            OutlinedTextField(
                value = temp, onValueChange = { temp = it },
                label = { Text(stringResource(R.string.timer_temp_label)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(
            onClick = { onAddQuick(delay, turnOn, tempOrNull(turnOn, temp)) },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF10161A)),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.timer_quick_start), style = QuadType.name) }
    }
}

@Composable
private fun WeeklyTimerForm(
    accent: Color,
    onAddWeekly: (hour: Int, minute: Int, weekday: List<Int>, turnOn: Boolean, temp: Float?) -> Unit,
) {
    val c = Klima.colors
    var time by rememberSaveable { mutableStateOf("07:00") }
    var days by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var turnOn by rememberSaveable { mutableStateOf(true) }
    var temp by rememberSaveable { mutableStateOf("23") }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.timer_recurring_title), style = QuadType.name, color = c.ink)

        OutlinedTextField(
            value = time, onValueChange = { time = it },
            label = { Text(stringResource(R.string.timer_time_label)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),   // tastiera testo: serve il ':'
        )

        Text(stringResource(R.string.timer_days).uppercase(), style = QuadType.overline, color = c.ink3)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in 0..6) {
                HomeChip(stringResource(WD_RES[d]), days.contains(d)) {
                    days = if (days.contains(d)) days - d else days + d
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeChip(stringResource(R.string.timer_action_on), turnOn) { turnOn = true }
            HomeChip(stringResource(R.string.timer_action_off), !turnOn) { turnOn = false }
        }
        if (turnOn) {
            OutlinedTextField(
                value = temp, onValueChange = { temp = it },
                label = { Text(stringResource(R.string.timer_temp_label)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val hm = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim())
        val valid = hm != null &&
            hm.groupValues[1].toInt() in 0..23 && hm.groupValues[2].toInt() in 0..59
        Button(
            onClick = {
                val hh = hm!!.groupValues[1].toInt()
                val mm = hm.groupValues[2].toInt()
                onAddWeekly(hh, mm, days.sorted(), turnOn, tempOrNull(turnOn, temp))
            },
            enabled = valid,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF10161A)),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.timer_add), style = QuadType.name) }
    }
}

@Composable
private fun ScheduleRow(s: Schedule, onToggle: (String) -> Unit, onDelete: (String) -> Unit) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent
    val names = weekdayNames()

    val whenText = if (s.recurring) "%02d:%02d".format(s.hour, s.minute) else clockOf(s.fireAtMillis)
    val sub = if (s.recurring) {
        val days = if (s.weekday.isEmpty()) stringResource(R.string.timer_every_day)
        else s.weekday.joinToString(" ") { names[it] }
        days
    } else stringResource(R.string.timer_once)
    val action = if (s.turnOn)
        stringResource(R.string.timer_action_on) + (s.targetTemp?.let { " · ${it / 10.0}°" } ?: "")
    else stringResource(R.string.timer_action_off)

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (s.enabled) c.surface1 else c.surface2).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(whenText, style = QuadType.name, color = if (s.enabled) c.ink else c.ink3)
            Text("$sub · $action", style = QuadType.micro, color = c.ink2)
        }
        Switch(
            checked = s.enabled, onCheckedChange = { onToggle(s.id) },
            colors = SwitchDefaults.colors(checkedTrackColor = accent),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.pressClickable(onClick = { onDelete(s.id) })
                .clip(RoundedCornerShape(10.dp)).background(c.surface2)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("✕", style = QuadType.name, color = c.error) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Klima.colors
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2)
            .pressClickable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = QuadType.title, color = if (enabled) c.ink else c.ink3) }
}

/** Temperatura scelta (in gradi) solo se si accende e il campo è un numero valido. */
private fun tempOrNull(turnOn: Boolean, temp: String): Float? =
    if (!turnOn) null else temp.trim().replace(',', '.').toFloatOrNull()
