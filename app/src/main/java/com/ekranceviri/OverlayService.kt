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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlin.math.max

class OverlayService : Service() {

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var button: TextView? = null
    private var overlay: OverlayView? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, String>()
    private var ready = false
    private var busy = false
    private var sw = 0
    private var sh = 0

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
        val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        reader = r
        vDisplay = p.createVirtualDisplay(
            "ceviri", sw, sh, resources.displayMetrics.densityDpi,
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
                toast("Hazır! Ç butonuna dokunabilirsin")
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(tv, lp)
                }
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
    }

    private fun onTap() {
        if (busy) return
        val ov = overlay ?: return
        if (ov.visibility == View.VISIBLE) {
            ov.visibility = View.GONE
            return
        }
        if (!ready) {
            toast("Model henüz hazır değil, biraz bekle")
            return
        }
        busy = true
        button?.visibility = View.INVISIBLE
        handler.postDelayed({ capture(0) }, 250)
    }

    private fun capture(tries: Int) {
        val bmp = grab()
        if (bmp == null) {
            if (tries < 3) {
                handler.postDelayed({ capture(tries + 1) }, 200)
            } else {
                button?.visibility = View.VISIBLE
                busy = false
                toast("Ekran alınamadı")
            }
            return
        }
        button?.visibility = View.VISIBLE
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text -> translateBlocks(bmp, text) }
            .addOnFailureListener {
                busy = false
                toast("Metin okunamadı")
            }
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val wide = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            wide.copyPixelsFromBuffer(plane.buffer)
            val out = Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
            if (out !== wide) wide.recycle()
            return out
        } finally {
            img.close()
        }
    }

    private fun translateBlocks(bmp: Bitmap, text: Text) {
        val blocks = text.textBlocks.filter { b ->
            b.boundingBox != null && b.text.length > 1 && b.text.any { it.isLetter() }
        }
        if (blocks.isEmpty()) {
            bmp.recycle()
            busy = false
            toast("Metin bulunamadı")
            return
        }
        val srcs = blocks.map { b -> b.lines.joinToString(" ") { it.text } }
        val results = arrayOfNulls<String>(blocks.size)
        var remaining = blocks.size
        for (i in blocks.indices) {
            val src = srcs[i]
            val done = { t: String? ->
                results[i] = t
                remaining--
                if (remaining == 0) showResult(bmp, blocks, srcs, results)
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
        bmp: Bitmap, blocks: List<Text.TextBlock>, srcs: List<String>, results: Array<String?>
    ) {
        val items = ArrayList<OverlayView.Item>()
        for (i in blocks.indices) {
            val t = results[i] ?: continue
            if (t.equals(srcs[i], ignoreCase = true)) continue
            val r = blocks[i].boundingBox ?: continue
            items.add(OverlayView.Item(Rect(r), t, bgColor(bmp, r), blocks[i].lines.size))
        }
        bmp.recycle()
        busy = false
        if (items.isEmpty()) {
            toast("Çevrilecek metin yok")
            return
        }
        overlay?.setItems(items)
        overlay?.visibility = View.VISIBLE
    }

    private fun bgColor(bmp: Bitmap, r: Rect): Int {
        val counts = HashMap<Int, Int>()
        val rep = HashMap<Int, Int>()
        fun add(x: Int, y: Int) {
            val px = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
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
        super.onDestroy()
    }
}
