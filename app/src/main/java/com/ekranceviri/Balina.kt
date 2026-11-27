package com.ekranceviri

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

// ================= Ana ekran =================
class MainActivity : Activity() {
    private val reqOverlay = 1
    private val reqCapture = 2
    private val reqNotif = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("balina", Context.MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Balina Translate"
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        root.addView(title)

        val info = TextView(this)
        info.text = "Çeviri modunu seç, Başlat'a bas ve izinleri ver.\n\n" +
            "Yüzen buton:\n" +
            "• Dokun: aç / kapat (tıklamalı modda çevir)\n" +
            "• Uzun bas: modu değiştir\n" +
            "• Tamamen kapatmak için bildirimdeki Kapat'a bas ya da buradaki Durdur'a bas."
        info.textSize = 15f
        info.setPadding(0, pad / 2, 0, pad / 2)
        root.addView(info)

        val group = RadioGroup(this)
        val rbLive = RadioButton(this)
        rbLive.id = 101
        rbLive.text = "Anlık çeviri (kendiliğinden çevirir)"
        val rbTap = RadioButton(this)
        rbTap.id = 102
        rbTap.text = "Tıklamalı çeviri (butona basınca çevirir)"
        group.addView(rbLive)
        group.addView(rbTap)
        group.check(if (prefs.getBoolean("live", true)) 101 else 102)
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putBoolean("live", checkedId == 101).apply()
        }
        root.addView(group)

        val startBtn = Button(this)
        startBtn.text = "Başlat"
        startBtn.setOnClickListener { start() }
        root.addView(startBtn)

        val stopBtn = Button(this)
        stopBtn.text = "Durdur"
        stopBtn.setOnClickListener {
            startService(
                Intent(this, OverlayService::class.java).setAction("com.ekranceviri.STOP")
            )
        }
        root.addView(stopBtn)

        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                reqOverlay
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqOverlay) {
            if (Settings.canDrawOverlays(this)) start()
        } else if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqNotif) requestCapture()
    }
}

// ================= Çevrilmiş ekranı çizen katman =================
class OverlayView(context: Context) : View(context) {

    class Item(
        val rect: Rect,
        val text: String,
        val bgTop: Int,
        val bgBottom: Int,
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
            val h = item.rect.height()
            val padX = max(6, (h * 0.25f).toInt())
            val padY = max(4, (h * 0.18f).toInt())
            val box = Rect(
                item.rect.left - padX, item.rect.top - padY,
                item.rect.right + padX, item.rect.bottom + padY
            )

            // Arka planı çevredeki zeminle aynı renge boya (geçişliyse geçişi taklit et)
            if (item.bgTop != item.bgBottom) {
                fill.shader = LinearGradient(
                    0f, box.top.toFloat(), 0f, box.bottom.toFloat(),
                    item.bgTop, item.bgBottom, Shader.TileMode.CLAMP
                )
            } else {
                fill.shader = null
                fill.color = item.bgTop
            }
            canvas.drawRect(box, fill)
            fill.shader = null

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.color = item.fg
            val align = if (item.centered) Layout.Alignment.ALIGN_CENTER
            else Layout.Alignment.ALIGN_NORMAL
            val textW = max(1, box.width() - 4)
            val startSize = max(12f, h.toFloat() / (1.2f * item.lines - 0.45f))
            val minSize = max(10f, startSize * 0.65f)
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

// ================= Ana servis =================
class OverlayService : Service() {

    // OCR için ekran bu oranda küçültülür (hız). Çizim tam çözünürlükte yapılır.
    private val ocrScale = 0.75f

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val prefs by lazy { getSharedPreferences("balina", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var button: TextView? = null
    private var buttonShape: GradientDrawable? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var overlay: OverlayView? = null
    private var watcher: View? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, String>()

    private var ready = false
    private var live = true
    private var on = false
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
    private var buttonTouchTime = 0L

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
        if (intent?.action == "com.ekranceviri.STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        live = prefs.getBoolean("live", true)
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
        val stopIntent = Intent(this, OverlayService::class.java)
            .setAction("com.ekranceviri.STOP")
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "ceviri")
            .setContentTitle("Balina Translate çalışıyor")
            .setContentText("Kapatmak için Kapat'a dokun")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", stopPi)
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
                if (live) setOn(true) else updateButton()
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

        // 3) ON / OFF butonu
        val size = (54 * d).toInt()
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.includeFontPadding = false
        val bgShape = GradientDrawable()
        bgShape.shape = GradientDrawable.OVAL
        bgShape.setColor(0xDD757575.toInt())
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
        buttonLp = lp

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                switchMode()
            }
        })
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        tv.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_DOWN) {
                buttonTouchTime = SystemClock.uptimeMillis()
                dragging = false
                startX = lp.x
                startY = lp.y
                downX = e.rawX
                downY = e.rawY
            } else if (e.action == MotionEvent.ACTION_MOVE) {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && (abs(dx) > 16f || abs(dy) > 16f)) {
                    dragging = true
                    buttonTouchTime = SystemClock.uptimeMillis()
                    handleUserTouch()
                }
                lp.x = startX + dx.toInt()
                lp.y = startY + dy.toInt()
                wm.updateViewLayout(tv, lp)
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
        updateButton()
    }

    // ON/OFF yazısı ve renk: gri = model yükleniyor, kırmızı = kapalı,
    // yeşil = anlık açık, mavi = tıklamalı açık
    private fun updateButton() {
        val l1: String
        val l2: String
        val color: Int
        if (!ready) {
            l1 = "..."
            l2 = "MODEL"
            color = 0xDD757575.toInt()
        } else {
            l1 = if (on) "ON" else "OFF"
            l2 = if (live) "ANLIK" else "TIK"
            color = if (!on) 0xDDC62828.toInt()
            else if (live) 0xDD2E7D32.toInt()
            else 0xDD1565C0.toInt()
        }
        val s = SpannableString(l1 + "\n" + l2)
        s.setSpan(RelativeSizeSpan(0.55f), l1.length + 1, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        button?.text = s
        buttonShape?.setColor(color)
    }

    private fun onTap() {
        if (!ready) {
            toast("Model henüz hazır değil, biraz bekle")
            return
        }
        setOn(!on)
    }

    private fun switchMode() {
        live = !live
        prefs.edit().putBoolean("live", live).apply()
        setOn(false)
    }

    private fun setOn(value: Boolean) {
        on = value
        if (value) {
            if (live) markChanged() else dirty = true
        } else {
            hideOverlay()
            latestBmp?.recycle()
            latestBmp = null
            lastSig = null
            dirty = false
        }
        updateButton()
    }

    // Kullanıcı ekrana dokundu (butonun kendi dokunuşları sayılmaz)
    private fun onUserTouch() {
        val t = SystemClock.uptimeMillis()
        handler.postDelayed({
            if (buttonTouchTime < t - 150) handleUserTouch()
        }, 80)
    }

    private fun handleUserTouch() {
        if (!on) return
        if (live) {
            if (overlayShown) hideOverlay()
            markChanged()
        } else {
            setOn(false)
        }
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
        if (!on && live) {
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
                            if (live) {
                                hideOverlay()
                                markChanged()
                            } else {
                                setOn(false)
                            }
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
                    if (live) dirty = true
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
            val need = if (live) 300L else 150L
            if (latestBmp != null) {
                if (now - lastChange >= need) {
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
        if (on && lastChange > startedAt) dirty = true
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
            if (on && !live) setOn(false)
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
        val lpb = buttonLp
        val btn = if (lpb != null) Rect(lpb.x, lpb.y, lpb.x + lpb.width, lpb.y + lpb.height) else null
        val items = ArrayList<OverlayView.Item>()
        for (i in blocks.indices) {
            val t = results[i] ?: continue
            if (t.equals(srcs[i], ignoreCase = true)) continue
            val r = blocks[i].boundingBox ?: continue
            val big = Rect(
                (r.left * inv).toInt(), (r.top * inv).toInt(),
                (r.right * inv).toInt(), (r.bottom * inv).toInt()
            )
            if (btn != null && Rect.intersects(big, btn)) continue
            val padY = max(4, (big.height() * 0.18f).toInt())
            val topC = rowColor(full, big.top - padY - 2, big.left, big.right)
            val botC = rowColor(full, big.bottom + padY + 2, big.left, big.right)
            var bgTop = topC
            var bgBot = botC
            if (colorDist(topC, botC) > 90) {
                val single = bgColor(full, big)
                bgTop = single
                bgBot = single
            }
            val fg = fgColor(full, big, bgTop)
            val centered = abs(big.centerX() - sw / 2f) < sw * 0.04f
            items.add(OverlayView.Item(big, t, bgTop, bgBot, fg, blocks[i].lines.size, centered))
        }
        busy = false
        if (!on || lastChange > startedAt) {
            full.recycle()
            if (on && lastChange > startedAt) dirty = true
            return
        }
        if (items.isEmpty()) {
            full.recycle()
            if (!live) setOn(false)
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

    private fun colorDist(a: Int, b: Int): Int {
        return abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))
    }

    // Bir satır boyunca en çok görülen renk
    private fun rowColor(bmp: Bitmap, y: Int, left: Int, right: Int): Int {
        val counts = HashMap<Int, Int>()
        val rep = HashMap<Int, Int>()
        val step = max(4, (right - left) / 16)
        var x = left
        while (x <= right) {
            val px = pix(bmp, x, y)
            val key = px and 0xFFF8F8F8.toInt()
            counts[key] = (counts[key] ?: 0) + 1
            if (!rep.containsKey(key)) rep[key] = px
            x += step
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return Color.WHITE
        return (rep[best] ?: Color.WHITE) or 0xFF000000.toInt()
    }

    // Kutunun çevresinden en çok görülen renk
    private fun bgColor(bmp: Bitmap, r: Rect): Int {
        val counts = HashMap<Int, Int>()
        val rep = HashMap<Int, Int>()
        fun add(x: Int, y: Int) {
            val px = pix(bmp, x, y)
            val key = px and 0xFFF8F8F8.toInt()
            counts[key] = (counts[key] ?: 0) + 1
            if (!rep.containsKey(key)) rep[key] = px
        }
        val oy = max(4, (r.height() * 0.18f).toInt()) + 2
        val ox = max(6, (r.height() * 0.25f).toInt()) + 2
        val stepX = max(6, r.width() / 12)
        var x = r.left
        while (x <= r.right) {
            add(x, r.top - oy)
            add(x, r.bottom + oy)
            x += stepX
        }
        val stepY = max(6, r.height() / 6)
        var y = r.top
        while (y <= r.bottom) {
            add(r.left - ox, y)
            add(r.right + ox, y)
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
                val dist = colorDist(px, bg)
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
