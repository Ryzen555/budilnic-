@file:OptIn(ExperimentalMaterial3Api::class)
package ryzen.budilnik

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val asked = mutableSetOf<String>()
    private var runtimeDone = false
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runtimeDone = true; special() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) { p += Manifest.permission.POST_NOTIFICATIONS; p += Manifest.permission.READ_MEDIA_AUDIO }
        else p += Manifest.permission.READ_EXTERNAL_STORAGE
        perm.launch(p.toTypedArray())
        setContent { MaterialTheme { App() } }
    }

    override fun onResume() { super.onResume(); special() }

    private fun special() {
        if (!runtimeDone) return
        val nm = getSystemService(NotificationManager::class.java)
        val u = Uri.parse("package:$packageName")
        val steps = listOf(
            Triple("exact", Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms(), Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, u)),
            Triple("overlay", Settings.canDrawOverlays(this), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, u)),
            Triple("battery", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName), Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, u)),
            Triple("fsi", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(), Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", u)))
        for ((k, ok, i) in steps) if (!ok && asked.add(k)) { try { startActivity(i) } catch (e: Exception) {}; return }
    }
}

val DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
val MODES = listOf("Пример", "9 кнопок", "Любой QR", "Случайный QR")

fun daysText(d: Int) = if (d == 0) "Один раз" else if (d == 127) "Каждый день" else DAYS.filterIndexed { i, _ -> (d shr i) and 1 == 1 }.joinToString(" ")

@Composable
fun App() {
    val c = LocalContext.current
    var screen by remember { mutableStateOf(0) }
    var alarms by remember { mutableStateOf(Store.alarms(c).toList()) }
    var edit by remember { mutableStateOf<Alarm?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var editDef by remember { mutableStateOf(false) }
    fun sync(l: List<Alarm>) { Store.saveAlarms(c, l); l.forEach { Sched.set(c, it) }; alarms = l.toList() }

    if (screen == 1) { QrScreen { screen = 0 }; return }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("будильник++", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Row {
                TextButton({ screen = 1 }) { Text("QR-коды") }
                TextButton({ editDef = true; isNew = false; edit = Store.def(c) }) { Text("Профиль по умолчанию") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(alarms, key = { it.id }) { a ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editDef = false; isNew = false; edit = a }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("%02d:%02d".format(a.hour, a.min), fontSize = 32.sp)
                                Text(listOf(a.label, daysText(a.days), MODES[a.mode]).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 13.sp)
                            }
                            Switch(a.on, { sync(alarms.map { x -> if (x.id == a.id) x.copy(on = it) else x }) })
                        }
                    }
                }
            }
            Text("Автор: ryzen_5_", fontSize = 12.sp)
        }
        FloatingActionButton({
            editDef = false; isNew = true
            edit = Store.def(c).copy(id = (System.currentTimeMillis() / 1000).toInt(), hour = 7, min = 0, on = true)
        }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+", fontSize = 28.sp) }
    }
    edit?.let { e ->
        Editor(e, editDef, { n ->
            if (editDef) Store.saveDef(c, n) else sync(if (isNew) alarms + n else alarms.map { if (it.id == n.id) n else it })
            edit = null
        }, if (!editDef && !isNew) ({ Sched.cancel(c, e); sync(alarms.filter { it.id != e.id }); edit = null }) else null, { edit = null })
    }
}

fun sounds(c: Context): List<Pair<String, String>> {
    val l = mutableListOf<Pair<String, String>>()
    try {
        val rm = RingtoneManager(c); rm.setType(RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
        val cur = rm.cursor
        while (cur.moveToNext()) l += cur.getString(RingtoneManager.TITLE_COLUMN_INDEX) to rm.getRingtoneUri(cur.position).toString()
    } catch (e: Exception) {}
    return l
}

@Composable
fun Editor(a: Alarm, def: Boolean, onSave: (Alarm) -> Unit, onDel: (() -> Unit)?, onClose: () -> Unit) {
    val c = LocalContext.current
    val ts = rememberTimePickerState(a.hour, a.min, true)
    var days by remember { mutableStateOf(a.days) }
    var label by remember { mutableStateOf(a.label) }
    var mode by remember { mutableStateOf(a.mode) }
    var level by remember { mutableStateOf(a.level) }
    var snd by remember { mutableStateOf(a.sound) }
    var sn by remember { mutableStateOf(a.soundName) }
    var pick by remember { mutableStateOf(false) }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            snd = u.toString(); sn = "Свой: " + (u.lastPathSegment ?: ""); pick = false
        }
    }
    AlertDialog(onDismissRequest = onClose,
        title = { Text(if (def) "Профиль по умолчанию" else "Будильник") },
        confirmButton = { TextButton({ onSave(a.copy(hour = ts.hour, min = ts.minute, days = days, label = label, mode = mode, level = level, sound = snd, soundName = sn, on = true)) }) { Text("Сохранить") } },
        dismissButton = { Row { onDel?.let { TextButton(it) { Text("Удалить") } }; TextButton(onClose) { Text("Отмена") } } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!def) TimeInput(ts)
                Text("Дни (ничего не выбрано = один раз)", fontSize = 12.sp)
                Row {
                    DAYS.forEachIndexed { i, n ->
                        val on = (days shr i) and 1 == 1
                        Box(Modifier.weight(1f).padding(1.dp).background(if (on) MaterialTheme.colorScheme.primary else Color.LightGray, RoundedCornerShape(8.dp))
                            .clickable { days = days xor (1 shl i) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(n, fontSize = 11.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else Color.Black)
                        }
                    }
                }
                OutlinedTextField(label, { label = it }, label = { Text("Название") }, singleLine = true)
                Text("Как выключать", fontSize = 12.sp)
                MODES.forEachIndexed { i, n -> Row(Modifier.clickable { mode = i }, verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == i, { mode = i }); Text(n) } }
                if (mode == 0) Row { listOf("Лёгкий", "Средний", "Сложный").forEachIndexed { i, n -> FilterChip(level == i + 1, { level = i + 1 }, { Text(n) }, Modifier.padding(2.dp)) } }
                TextButton({ pick = true }) { Text("Звук: " + sn.ifEmpty { "по умолчанию" }) }
            }
        })
    if (pick) {
        val list = remember { sounds(c) }
        AlertDialog(onDismissRequest = { pick = false }, title = { Text("Звук") },
            confirmButton = { TextButton({ doc.launch(arrayOf("audio/*")) }) { Text("Свой файл…") } },
            dismissButton = { TextButton({ snd = ""; sn = ""; pick = false }) { Text("По умолчанию") } },
            text = { LazyColumn { items(list) { (n, u) -> Text(n, Modifier.fillMaxWidth().clickable { snd = u; sn = n; pick = false }.padding(vertical = 10.dp)) } } })
    }
}

@Composable
fun QrScreen(back: () -> Unit) {
    val c = LocalContext.current
    var list by remember { mutableStateOf(Store.qrs(c).toList()) }
    var scanning by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(back) { Text("← Назад") }
            Button({ scanning = !scanning }) { Text(if (scanning) "Отмена" else "Добавить QR") }
        }
        if (scanning && pending == null) Scanner(Modifier.fillMaxWidth().height(320.dp)) { t -> if (pending == null) { pending = t; name = "" } }
        LazyColumn {
            items(list) { (v, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) { Text(n, fontWeight = FontWeight.Bold); Text(v.take(40), fontSize = 11.sp) }
                    TextButton({ list = list - (v to n); Store.saveQrs(c, list) }) { Text("Удалить") }
                }
            }
        }
    }
    pending?.let { p ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Название места") },
            confirmButton = { TextButton({
                val l = list.filter { it.first != p } + (p to name.ifBlank { "QR ${list.size + 1}" })
                Store.saveQrs(c, l); list = l; pending = null; scanning = false
            }) { Text("Сохранить") } },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) })
    }
}
