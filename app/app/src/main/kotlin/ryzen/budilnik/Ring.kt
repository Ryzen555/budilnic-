package ryzen.budilnik

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlin.random.Random

class RingActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val a = Store.alarms(this).find { it.id == intent.getIntExtra("id", 0) } ?: Store.def(this)
        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) {
                BackHandler {}
                Ring(a) { startService(Intent(this@RingActivity, AlarmService::class.java).setAction("STOP")); finish() }
            } }
        }
    }
}

@Composable
fun Scanner(modifier: Modifier, onText: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val cb by rememberUpdatedState(onText)
    var v by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    AndroidView(modifier = modifier, factory = { c ->
        DecoratedBarcodeView(c).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
            decodeContinuous { r -> r.text?.let { cb(it) } }
            v = this
        }
    })
    DisposableEffect(v, owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) v?.resume() else if (e == Lifecycle.Event.ON_PAUSE) v?.pause()
        }
        owner.lifecycle.addObserver(o); v?.resume()
        onDispose { owner.lifecycle.removeObserver(o); v?.pause() }
    }
}

@Composable
fun Ring(a: Alarm, done: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%02d:%02d".format(a.hour, a.min), fontSize = 48.sp)
        if (a.label.isNotEmpty()) Text(a.label, fontSize = 20.sp)
        Box(Modifier.padding(12.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures(onPress = { AlarmService.mute(true); try { tryAwaitRelease() } finally { AlarmService.mute(false) } }) }
            .padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text("Держи — звук выключен", color = MaterialTheme.colorScheme.onPrimary)
        }
        when (a.mode) { 0 -> MathTask(a.level, done); 1 -> Taps(done); else -> QrMode(a.mode == 3, done) }
    }
}

fun makeMath(l: Int): Pair<String, Int> {
    val r = Random
    return when (l) {
        1 -> { val a = r.nextInt(10, 60); val b = r.nextInt(10, 60); "$a + $b" to a + b }
        2 -> { val a = r.nextInt(12, 30); val b = r.nextInt(3, 10); val c = r.nextInt(10, 99); "$a × $b + $c" to a * b + c }
        else -> { val a = r.nextInt(12, 30); val b = r.nextInt(12, 30); val c = r.nextInt(10, 99); "$a × $b − $c" to a * b - c }
    }
}

@Composable
fun MathTask(l: Int, done: () -> Unit) {
    var q by remember { mutableStateOf(makeMath(l)) }
    var t by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Spacer(Modifier.height(24.dp))
    Text(q.first + " = ?", fontSize = 36.sp)
    OutlinedTextField(t, { t = it; bad = false }, isError = bad, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Button({ if (t.trim().toIntOrNull() == q.second) done() else { bad = true; q = makeMath(l); t = "" } }) { Text("Проверить") }
}

@Composable
fun Taps(done: () -> Unit) {
    fun gen() = (1..100).shuffled().take(9)
    var nums by remember { mutableStateOf(gen()) }
    var step by remember { mutableStateOf(0) }
    val order = nums.sortedDescending()
    Spacer(Modifier.height(16.dp))
    Text("Жми от большего к меньшему", fontSize = 20.sp)
    for (r in 0..2) Row {
        for (k in 0..2) {
            val n = nums[r * 3 + k]
            Button(onClick = {
                if (n == order[step]) { step++; if (step == 9) done() } else { nums = gen(); step = 0 }
            }, enabled = order.indexOf(n) >= step, modifier = Modifier.size(100.dp).padding(4.dp)) { Text("$n", fontSize = 20.sp) }
        }
    }
}

@Composable
fun QrMode(random: Boolean, done: () -> Unit) {
    val c = LocalContext.current
    val target = remember { if (random) Store.qrs(c).randomOrNull() else null }
    var msg by remember { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    Text(if (target != null) "Найди и отсканируй: ${target.second}" else "Отсканируй любой QR-код", fontSize = 22.sp)
    Text(msg)
    Scanner(Modifier.fillMaxWidth().height(380.dp)) { t -> if (target == null || t == target.first) done() else msg = "Не тот код!" }
}
