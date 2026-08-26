package net.klimakontrol.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.klimakontrol.R
import net.klimakontrol.data.Mode
import net.klimakontrol.data.tasks.Timer
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType
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

/**
 * Pianificazioni (timer) di un'unità: elenco + aggiunta di un timer ricorrente on/off.
 * MVP dei tipi ricorrenti; il formato del task e' ricostruito ma da confermare su HW.
 */
@Composable
fun TimerScreen(
    unitName: String,
    state: TimersState,
    onLoad: () -> Unit,
    onAdd: (Timer) -> Unit,
    onDelete: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent

    LaunchedEffect(Unit) { onLoad() }

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
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(c.mode(Mode.FREDDO).container).padding(12.dp)) {
                Text(stringResource(R.string.timer_experimental),
                    style = QuadType.micro, color = c.mode(Mode.FREDDO).on)
            }

            state.error?.let { Text(it, style = QuadType.micro, color = c.error) }

            // ---- elenco (MVP: solo i ricorrenti) ----
            val shown = state.timers.filter { it.type == Timer.TYPE_PERIOD }
            if (state.busy && shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp))
                }
            } else if (shown.isEmpty()) {
                Text(stringResource(R.string.timer_none), style = QuadType.body, color = c.ink3)
            } else {
                shown.forEach { t -> TimerRow(t, enabled = !state.busy, onDelete = onDelete) }
            }

            // ---- nuova pianificazione ----
            AddTimerForm(accent, busy = state.busy, onAdd = onAdd)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimerRow(t: Timer, enabled: Boolean, onDelete: (Int, Int) -> Unit) {
    val c = Klima.colors
    val on = (t.action["pwr"] ?: 0) != 0
    val temp = t.action["save_temp"]
    val names = weekdayNames()
    val days = if (t.weekday.isEmpty()) stringResource(R.string.timer_every_day)
    else t.weekday.joinToString(" ") { names[it] }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface1)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("%02d:%02d".format(t.hour, t.minute), style = QuadType.name, color = c.ink)
            val action = if (on)
                stringResource(R.string.timer_action_on) + (temp?.let { " · ${it / 10.0}°" } ?: "")
            else stringResource(R.string.timer_action_off)
            Text("$days · $action", style = QuadType.micro, color = c.ink2)
        }
        if (t.index != null) {
            Box(
                Modifier.pressClickable(onClick = { onDelete(t.type, t.index) }, enabled = enabled)
                    .clip(RoundedCornerShape(10.dp)).background(c.surface2)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("✕", style = QuadType.name, color = if (enabled) c.error else c.ink3) }
        }
    }
}

@Composable
private fun AddTimerForm(accent: Color, busy: Boolean, onAdd: (Timer) -> Unit) {
    val c = Klima.colors
    var time by rememberSaveable { mutableStateOf("07:00") }
    var days by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var turnOn by rememberSaveable { mutableStateOf(true) }
    var temp by rememberSaveable { mutableStateOf("23") }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.timer_new), style = QuadType.name, color = c.ink)

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

        Text(stringResource(R.string.timer_action).uppercase(), style = QuadType.overline, color = c.ink3)
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
                val h = hm!!.groupValues[1].toInt()
                val m = hm.groupValues[2].toInt()
                val action = if (turnOn) {
                    val a = linkedMapOf("pwr" to 1)
                    temp.trim().replace(',', '.').toDoubleOrNull()?.let {
                        a["save_temp"] = (it.coerceIn(16.0, 31.0) * 10).roundToInt()
                    }
                    a
                } else linkedMapOf("pwr" to 0)
                onAdd(Timer(type = Timer.TYPE_PERIOD, hour = h, minute = m,
                    weekday = days.sorted(), action = action))
            },
            enabled = valid && !busy,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF10161A)),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.timer_add), style = QuadType.name) }
    }
}
