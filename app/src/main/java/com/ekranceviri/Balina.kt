package com.ekranceviri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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

// Çevrilmiş ekranı tek parça çizen tam ekran katman
class OverlayView(context: Context) : View(context) {

    class Item(
        val rect: Rect,
        val text: String,
        val bg: Int,
        val fg: Int,
        val lines: Int,
        val centered: Boolean
    )

    private var bitmap: Bitmap? = null
    private val src = Rect()
    private val dst = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun show(base: Bitmap, items: List<Item>, w: Int, h: Int) {
        render(base, items)
        bitmap = base
        src.set(0, 0, w, h)
        invalidate()
    }

    fun clear() {
        bitmap = null
        invalidate()
    }

    private fun buildLayout(
        text: String, paint: TextPaint, size: Float, width: Int,
        align: Layout.Alignment, maxLines: Int
    ): StaticLayout {
        paint.textSize = size
        val b = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(align)
        if (maxLines != Int.MAX_VALUE) {
            b.setMaxLines(maxLines)
            b.setEllipsize(TextUtils.TruncateAt.END)
        }
        return b.build()
    }

    private fun render(base: Bitmap, items: List<Item>) {
        val canvas = Canvas(base)
        val fill = Paint()
        for (item in items) {
            val box = Rect(
                item.rect.left - 6, item.rect.top - 4,
                item.rect.right + 6, item.rect.bottom + 4
            )
            fill.color = item.bg
            canvas.drawRect(box, fill)

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.color = item.fg
            val align = if (item.centered) Layout.Alignment.ALIGN_CENTER
            else Layout.Alignment.ALIGN_NORMAL
            val textW = max(1, box.width() - 4)
            val lineH = item.rect.height().toFloat() / max(1, item.lines)
            val startSize = max(12f, lineH * 0.85f)
            val minSize = max(10f, startSize * 0.6f)
            var size = startSize
            var layout = buildLayout(item.text, tp, size, textW, align, Int.MAX_VALUE)
            while (layout.height > box.height() && size > minSize) {
                size -= 1f
                layout = buildLayout(item.text, tp, size, textW, align, Int.MAX_VALUE)
            }
            if (layout.height > box.height()) {
                val oneLine = max(1, layout.height / max(1, layout.lineCount))
                val maxLines = max(1, box.height() / oneLine)
                layout = buildLayout(item.text, tp, size, textW, align, maxLines)
            }
            val dy = max(0, (box.height() - layout.height) / 2)
            canvas.save()
            canvas.translate(box.left + 2f, (box.top + dy).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, src, dst, bitmapPaint)
    }
}

class OverlayService : Service() {

    // OCR için ekran bu oranda küçültülür (hız). Çizim tam çözünürlükte yapılır.
    private val ocrScale = 0.75f

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var button: TextView? = null
    private var buttonShape: GradientDrawable? = null
    private var overlay: OverlayView? = null
    private var watcher: View? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, String>()

    private var ready = false
    private var auto = false
    private var busy = false
    private var dirty = false
    private var overlayShown = false
    private var latestBmp: Bitmap? = null
    private var lastSig: IntArray? = null
    private var baseSig: IntArray? = null
    private var lastChange = 0L
    private var ignoreUntil = 0L
    private var lastNudge = 0L
    private var nudgeFlip = false

    private var sw = 0
    private var sh = 0

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
            NotificationChannel("ceviri", "Balina Translate", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ceviri")
            .setContentTitle("Balina Translate çalışıyor")
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
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ready = true
                setAuto(true)
            }
            .addOnFailureListener { toast("Model indirilemedi, interneti kontrol et") }
    }

    private fun setupViews() {
        val d = resources.displayMetrics.density

        // 1) Çevrilmiş ekranı gösteren tam ekran katman
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

        // 2) Dokunmayı fark eden minik görünmez pencere
        val w = View(this)
        val wlp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        wlp.gravity = Gravity.TOP or Gravity.START
        w.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) onUserTouch()
            false
        }
        wm.addView(w, wlp)
        watcher = w

        // 3) Ç butonu (gri: model yükleniyor, yeşil: açık, mavi: kapalı)
        val size = (48 * d).toInt()
        val tv = TextView(this)
        tv.text = "Ç"
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        tv.typeface = Typeface.DEFAULT_BOLD
        val bgShape = GradientDrawable()
        bgShape.shape = GradientDrawable.OVAL
        bgShape.setColor(0xCC757575.toInt())
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
    }

    private fun setAuto(on: Boolean) {
        auto = on
        buttonShape?.setColor(if (on) 0xCC2E7D32.toInt() else 0xCC1E88E5.toInt())
        if (on) {
            markChanged()
        } else {
            hideOverlay()
            latestBmp?.recycle()
            latestBmp = null
            lastSig = null
            dirty = false
        }
    }

    private fun onUserTouch() {
        if (!auto) return
        if (overlayShown) hideOverlay()
        markChanged()
    }

    private fun markChanged() {
        latestBmp?.recycle()
        latestBmp = null
        lastSig = null
        dirty = true
        lastChange = SystemClock.uptimeMillis()
    }

    private fun hideOverlay() {
        overlayShown = false
        overlay?.visibility = View.GONE
        overlay?.clear()
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

        if (overlayShown) {
            if (img != null) {
                if (now < ignoreUntil) {
                    img.close()
                } else {
                    val bmp = toBitmap(img)
                    if (bmp != null) {
                        val sig = signature(bmp)
                        bmp.recycle()
                        val base = baseSig
                        if (base == null) {
                            baseSig = sig
                        } else if (diffFraction(base, sig) > 0.05f) {
                            hideOverlay()
                            markChanged()
                        }
                    }
                }
            }
            return
        }

        if (img != null) {
            val bmp = toBitmap(img)
            if (bmp != null) {
                val sig = signature(bmp)
                val prev = lastSig
                val moved = prev == null || diffFraction(prev, sig) > 0.01f
                if (moved) {
                    lastChange = now
                    dirty = true
                }
                lastSig = sig
                if (moved || latestBmp == null) {
                    latestBmp?.recycle()
                    latestBmp = bmp
                } else {
                    bmp.recycle()
                }
            }
        }
        if (dirty && !busy) {
            if (latestBmp != null) {
                if (now - lastChange >= 300) {
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

    private fun signature(bmp: Bitmap): IntArray {
        val cols = 24
        val rows = 48
        val out = IntArray(cols * rows)
        val maxX = min(bmp.width, sw) - 1
        for (j in 0 until rows) {
            val y = ((j + 0.5f) * bmp.height / rows).toInt().coerceIn(0, bmp.height - 1)
            for (i in 0 until cols) {
                val x = ((i + 0.5f) * maxX / cols).toInt().coerceIn(0, maxX)
                out[j * cols + i] = bmp.getPixel(x, y) and 0xFFE0E0E0.toInt()
            }
        }
        return out
    }

    private fun diffFraction(a: IntArray, b: IntArray): Float {
        var n = 0
        for (i in a.indices) {
            if (a[i] != b[i]) n++
        }
        return n.toFloat() / a.size
    }

    private fun runPipeline(startedAt: Long) {
        val full = latestBmp ?: return
        latestBmp = null
        busy = true
        val ow = (sw * ocrScale).toInt()
        val oh = (sh * ocrScale).toInt()
        val small = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.drawBitmap(full, Rect(0, 0, sw, sh), Rect(0, 0, ow, oh), Paint(Paint.FILTER_BITMAP_FLAG))
        recognizer.process(InputImage.fromBitmap(small, 0))
            .addOnSuccessListener { text ->
                small.recycle()
                translateBlocks(full, text, startedAt)
            }
            .addOnFailureListener {
                small.recycle()
                full.recycle()
                endRun(startedAt)
            }
    }

    private fun endRun(startedAt: Long) {
        busy = false
        if (auto && lastChange > startedAt) dirty = true
    }

    private fun translateBlocks(full: Bitmap, text: Text, startedAt: Long) {
        val blocks = ArrayList<Text.TextBlock>()
        for (b in text.textBlocks) {
            val box = b.boundingBox ?: continue
            if (box.height() < 10 || b.text.length < 2) continue
            if (!b.text.any { it.isLetter() }) continue
            if (b.text.any { it in "çğıöşüÇĞİÖŞÜ" }) continue
            blocks.add(b)
        }
        if (blocks.isEmpty()) {
            full.recycle()
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
                if (remaining == 0) showResult(full, blocks, srcs, results, startedAt)
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
        full: Bitmap, blocks: List<Text.TextBlock>, srcs: List<String>,
        results: Array<String?>, startedAt: Long
    ) {
        val inv = 1f / ocrScale
        val items = ArrayList<OverlayView.Item>()
        for (i in blocks.indices) {
            val t = results[i] ?: continue
            if (t.equals(srcs[i], ignoreCase = true)) continue
            val r = blocks[i].boundingBox ?: continue
            val big = Rect(
                (r.left * inv).toInt(), (r.top * inv).toInt(),
                (r.right * inv).toInt(), (r.bottom * inv).toInt()
            )
            val bg = bgColor(full, big)
            val fg = fgColor(full, big, bg)
            val centered = abs(big.centerX() - sw / 2f) < sw * 0.04f
            items.add(OverlayView.Item(big, t, bg, fg, blocks[i].lines.size, centered))
        }
        busy = false
        if (!auto || lastChange > startedAt || items.isEmpty()) {
            full.recycle()
            if (auto && lastChange > startedAt) dirty = true
            return
        }
        overlay?.show(full, items, sw, sh)
        overlay?.visibility = View.VISIBLE
        overlayShown = true
        baseSig = null
        dirty = false
        ignoreUntil = SystemClock.uptimeMillis() + 600
    }

    private fun pix(bmp: Bitmap, x: Int, y: Int): Int {
        val maxX = min(bmp.width, sw) - 1
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
        val stepX = max(6, r.width() / 12)
        var x = r.left
        while (x <= r.right) {
            add(x, r.top - 5)
            add(x, r.bottom + 5)
            x += stepX
        }
        val stepY = max(6, r.height() / 6)
        var y = r.top
        while (y <= r.bottom) {
            add(r.left - 5, y)
            add(r.right + 5, y)
            y += stepY
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return Color.WHITE
        return (rep[best] ?: Color.WHITE) or 0xFF000000.toInt()
    }

    private fun fgColor(bmp: Bitmap, r: Rect, bg: Int): Int {
        var best = bg
        var bestD = -1
        val stepX = max(3, r.width() / 30)
        val stepY = max(2, r.height() / 10)
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
        watcher?.let { runCatching { wm.removeView(it) } }
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
