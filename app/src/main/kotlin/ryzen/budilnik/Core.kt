package ryzen.budilnik

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.json.*
import java.util.Calendar

// mode: 0 пример, 1 девять кнопок, 2 любой QR, 3 случайный QR. days: битовая маска Пн..Вс, 0 = один раз
data class Alarm(var id: Int, var hour: Int = 7, var min: Int = 0, var days: Int = 127, var on: Boolean = true,
                 var label: String = "", var sound: String = "", var soundName: String = "",
                 var mode: Int = 1, var level: Int = 1)

fun Alarm.js() = JSONObject().put("id", id).put("h", hour).put("m", min).put("d", days).put("on", on).put("l", label)
    .put("s", sound).put("sn", soundName).put("mo", mode).put("lv", level)
fun jsAlarm(o: JSONObject) = Alarm(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.getInt("d"), o.getBoolean("on"),
    o.getString("l"), o.getString("s"), o.getString("sn"), o.getInt("mo"), o.getInt("lv"))

object Store {
    private fun p(c: Context) = c.getSharedPreferences("s", 0)
    fun alarms(c: Context): MutableList<Alarm> { val a = JSONArray(p(c).getString("al", "[]")); return MutableList(a.length()) { jsAlarm(a.getJSONObject(it)) } }
    fun saveAlarms(c: Context, l: List<Alarm>) { p(c).edit().putString("al", JSONArray(l.map { it.js() }).toString()).apply() }
    fun def(c: Context): Alarm = p(c).getString("df", null)?.let { jsAlarm(JSONObject(it)) } ?: Alarm(0)
    fun saveDef(c: Context, a: Alarm) { p(c).edit().putString("df", a.js().toString()).apply() }
    fun qrs(c: Context): MutableList<Pair<String, String>> {
        val a = JSONArray(p(c).getString("qr", "[]")); return MutableList(a.length()) { a.getJSONObject(it).let { o -> o.getString("v") to o.getString("n") } }
    }
    fun saveQrs(c: Context, l: List<Pair<String, String>>) {
        p(c).edit().putString("qr", JSONArray(l.map { JSONObject().put("v", it.first).put("n", it.second) }).toString()).apply()
    }
}

object Sched {
    fun next(a: Alarm): Long {
        val mask = if (a.days == 0) 127 else a.days
        for (d in 0..7) {
            val t = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, d); set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.min)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dow = (t.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if (t.timeInMillis > System.currentTimeMillis() && ((mask shr dow) and 1) == 1) return t.timeInMillis
        }
        return 0
    }
    fun set(c: Context, a: Alarm) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, a.id, Intent(c, AlarmReceiver::class.java).putExtra("id", a.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!a.on) { am.cancel(pi); return }
        val t = next(a); if (t == 0L) return
        try { am.setAlarmClock(AlarmManager.AlarmClockInfo(t, pi), pi) } catch (e: SecurityException) {}
    }
    fun cancel(c: Context, a: Alarm) = set(c, a.copy(on = false))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        ContextCompat.startForegroundService(c, Intent(c, AlarmService::class.java).putExtra("id", i.getIntExtra("id", 0)))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Store.alarms(c).forEach { Sched.set(c, it) } }
}

class AlarmService : Service() {
    companion object {
        var player: MediaPlayer? = null
        fun mute(m: Boolean) { val v = if (m) 0f else 1f; try { player?.setVolume(v, v) } catch (e: Exception) {} }
    }
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val id = i?.getIntExtra("id", 0) ?: 0
        val all = Store.alarms(this)
        val a = all.find { it.id == id }
        val ring = Intent(this, RingActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, id, ring, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("a", "Будильник", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val n = Notification.Builder(this, "a").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("будильник++").setContentText(a?.label?.ifEmpty { "Пора вставать!" } ?: "Пора вставать!")
            .setFullScreenIntent(pi, true).setContentIntent(pi).setOngoing(true).setCategory(Notification.CATEGORY_ALARM).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1, n)
        try { startActivity(ring) } catch (e: Exception) {}
        play(a)
        if (a != null) {
            if (a.days == 0) { a.on = false; Store.saveAlarms(this, all) }
            Sched.set(this, a)
        }
        return START_NOT_STICKY
    }

    private fun play(a: Alarm?) {
        val dflt = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val uri = if (a == null || a.sound.isEmpty()) dflt else Uri.parse(a.sound)
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            try { setDataSource(this@AlarmService, uri) } catch (e: Exception) { reset(); setDataSource(this@AlarmService, dflt) }
            isLooping = true; prepare(); start()
        }
    }

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
