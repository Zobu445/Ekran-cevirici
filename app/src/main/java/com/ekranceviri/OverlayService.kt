package com.ekranceviri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    // Ekran bu oranda küçültülüp okunur (hız için). 1.0f = tam çözünürlük
    private val scale = 0.75f

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var button: TextView? = null
    private var buttonShape: GradientDrawable? = null
    private var overlay: OverlayView? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, String>()

    private var ready = false
    private var auto = false
    private var busy = false
    private var dirty = false
    private var latestBmp: Bitmap? = null
    private var lastChange = 0L
    private var ignoreUntil = 0L
    private var lastNudge = 0L
    private var nudgeFlip = false

    private var sw = 0
    private var sh = 0
    private var cw = 0
    private var ch = 0

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 150)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        startAsForeground()
        setupCapture(code, data)
        setupTranslator()
        setupViews()
        handler.post(ticker)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ceviri", "Ekran Çeviri", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ceviri")
            .setContentTitle("Ekran Çeviri çalışıyor")
            .setContentText("Kapatmak için Ç butonuna uzun bas")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        projection = p
        val b = wm.currentWindowMetrics.bounds
        sw = b.width()
        sh = b.height()
        cw = (sw * scale).toInt()
        ch = (sh * scale).toInt()
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        vDisplay = p.createVirtualDisplay(
            "ceviri", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun setupTranslator() {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val t = Translation.getClient(opts)
        translator = t
        toast("Çeviri modeli hazırlanıyor...")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ready = true
                toast("Hazır! Otomatik çeviri açık")
                setAuto(true)
            }
            .addOnFailureListener { toast("Model indirilemedi, interneti kontrol et") }
    }

    private fun setupViews() {
        val d = resources.displayMetrics.density

        val ov = OverlayView(this)
        ov.visibility = View.GONE
        val olp = WindowManager.LayoutParams(
            sw, sh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        olp.gravity = Gravity.TOP or Gravity.START
        olp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(ov, olp)
        overlay = ov

        val size = (48 * d).toInt()
        val tv = TextView(this)
        tv.text = "Ç"
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        tv.typeface = Typeface.DEFAULT_BOLD
        val bgShape = GradientDrawable()
        bgShape.shape = GradientDrawable.OVAL
        bgShape.setColor(0xCC1E88E5.toInt())
        tv.background = bgShape
        buttonShape = bgShape
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = sw - size - (16 * d).toInt()
        lp.y = sh / 3

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                stopSelf()
            }
        })
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        tv.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_DOWN) {
                startX = lp.x
                startY = lp.y
                downX = e.rawX
                downY = e.rawY
            } else if (e.action == MotionEvent.ACTION_MOVE) {
                lp.x = startX + (e.rawX - downX).toInt()
                lp.y = startY + (e.rawY - downY).toInt()
                wm.updateViewLayout(tv, lp)
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
    }

    private fun onTap() {
        if (!ready) {
            toast("Model henüz hazır değil, biraz bekle")
            return
        }
        setAuto(!auto)
        toast(if (auto) "Otomatik çeviri açık" else "Çeviri kapalı")
    }

    private fun setAuto(on: Boolean) {
        auto = on
        buttonShape?.setColor(if (on) 0xCC2E7D32.toInt() else 0xCC1E88E5.toInt())
        if (on) {
            dirty = true
            lastChange = SystemClock.uptimeMillis()
        } else {
            overlay?.visibility = View.GONE
            latestBmp?.recycle()
            latestBmp = null
            dirty = false
        }
    }

    private fun tick() {
        val r = reader ?: return
        val img: Image? = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (!auto) {
            img?.close()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (img != null) {
            if (now < ignoreUntil) {
                img.close()
            } else {
                val bmp = toBitmap(img)
                if (bmp != null) {
                    latestBmp?.recycle()
                    latestBmp = bmp
                    lastChange = now
                    dirty = true
                    if (overlay?.visibility == View.VISIBLE) {
                        overlay?.visibility = View.GONE
                    }
                }
            }
        }
        if (dirty && !busy) {
            if (latestBmp != null) {
                if (now - lastChange >= 200) {
                    dirty = false
                    runPipeline(now)
                }
            } else if (now - lastNudge > 600) {
                lastNudge = now
                nudgeFlip = !nudgeFlip
                button?.alpha = if (nudgeFlip) 0.95f else 1f
            }
        }
    }

    private fun toBitmap(img: Image): Bitmap? {
        var result: Bitmap? = null
        try {
            val plane = img.planes[0]
            val ps = plane.pixelStride
            val bmp = Bitmap.createBitmap(plane.rowStride / ps, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            result = bmp
        } catch (e: Exception) {
            result = null
        } finally {
            img.close()
        }
        return result
    }

    private fun runPipeline(startedAt: Long) {
        val bmp = latestBmp ?: return
        latestBmp = null
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text -> translateBlocks(bmp, text, startedAt) }
            .addOnFailureListener {
                bmp.recycle()
                endRun(startedAt)
            }
    }

    private fun endRun(startedAt: Long) {
        busy = false
        if (auto && lastChange > startedAt) dirty = true
    }

    private fun translateBlocks(bmp: Bitmap, text: Text, startedAt: Long) {
        val blocks = ArrayList<Text.TextBlock>()
        for (b in text.textBlocks) {
            val box = b.boundingBox ?: continue
            if (box.height() < 10 || b.text.length < 2) continue
            if (!b.text.any { it.isLetter() }) continue
            if (b.text.any { it in "çğıöşüÇĞİÖŞÜ" }) continue
            blocks.add(b)
        }
        if (blocks.isEmpty()) {
            bmp.recycle()
            endRun(startedAt)
            return
        }
        if (cache.size > 2000) cache.clear()
        val srcs = ArrayList<String>()
        for (b in blocks) {
            srcs.add(b.lines.joinToString(" ") { it.text })
        }
        val results = arrayOfNulls<String>(blocks.size)
        var remaining = blocks.size
        for (i in blocks.indices) {
            val src = srcs[i]
            val done = { t: String? ->
                results[i] = t
                remaining--
                if (remaining == 0) showResult(bmp, blocks, srcs, results, startedAt)
            }
            val cached = cache[src]
            if (cached != null) {
                done(cached)
            } else {
                translator!!.translate(src)
                    .addOnSuccessListener {
                        cache[src] = it
                        done(it)
                    }
                    .addOnFailureListener { done(null) }
            }
        }
    }

    private fun showResult(
        bmp: Bitmap, blocks: List<Text.TextBlock>, srcs: List<String>,
        results: Array<String?>, startedAt: Long
    ) {
        val items = ArrayList<OverlayView.Item>()
        for (i in blocks.indices) {
            val t = results[i] ?: continue
            if (t.equals(srcs[i], ignoreCase = true)) continue
            val r = blocks[i].boundingBox ?: continue
            val bg = bgColor(bmp, r)
            val fg = fgColor(bmp, r, bg)
            val big = Rect(
                (r.left / scale).toInt(), (r.top / scale).toInt(),
                (r.right / scale).toInt(), (r.bottom / scale).toInt()
            )
            val centered = abs(big.centerX() - sw / 2f) < sw * 0.04f
            items.add(OverlayView.Item(big, t, bg, fg, blocks[i].lines.size, centered))
        }
        bmp.recycle()
        busy = false
        if (!auto) return
        if (lastChange > startedAt) {
            dirty = true
            return
        }
        if (items.isEmpty()) return
        overlay?.setItems(items)
        overlay?.visibility = View.VISIBLE
        ignoreUntil = SystemClock.uptimeMillis() + 350
    }

    private fun pix(bmp: Bitmap, x: Int, y: Int): Int {
        val maxX = min(bmp.width, cw) - 1
        return bmp.getPixel(x.coerceIn(0, maxX), y.coerceIn(0, bmp.height - 1))
    }

    private fun bgColor(bmp: Bitmap, r: Rect): Int {
        val counts = HashMap<Int, Int>()
        val rep = HashMap<Int, Int>()
        fun add(x: Int, y: Int) {
            val px = pix(bmp, x, y)
            val key = px and 0xFFF8F8F8.toInt()
            counts[key] = (counts[key] ?: 0) + 1
            if (!rep.containsKey(key)) rep[key] = px
        }
        val stepX = max(4, r.width() / 12)
        var x = r.left
        while (x <= r.right) {
            add(x, r.top - 3)
            add(x, r.bottom + 3)
            x += stepX
        }
        val stepY = max(4, r.height() / 6)
        var y = r.top
        while (y <= r.bottom) {
            add(r.left - 3, y)
            add(r.right + 3, y)
            y += stepY
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return Color.WHITE
        return (rep[best] ?: Color.WHITE) or 0xFF000000.toInt()
    }

    private fun fgColor(bmp: Bitmap, r: Rect, bg: Int): Int {
        var best = bg
        var bestD = -1
        val stepX = max(2, r.width() / 24)
        val stepY = max(2, r.height() / 8)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val px = pix(bmp, x, y)
                val dist = abs(Color.red(px) - Color.red(bg)) +
                    abs(Color.green(px) - Color.green(bg)) +
                    abs(Color.blue(px) - Color.blue(bg))
                if (dist > bestD) {
                    bestD = dist
                    best = px
                }
                x += stepX
            }
            y += stepY
        }
        if (bestD < 150) {
            val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
            return if (lum > 150) 0xFF111111.toInt() else Color.WHITE
        }
        return best or 0xFF000000.toInt()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        button?.let { runCatching { wm.removeView(it) } }
        overlay?.let { runCatching { wm.removeView(it) } }
        vDisplay?.release()
        reader?.close()
        projection?.stop()
        translator?.close()
        recognizer.close()
        latestBmp?.recycle()
        super.onDestroy()
    }
}
