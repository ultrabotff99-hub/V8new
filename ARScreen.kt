package com.tonix.wifireader

import android.content.Context
import android.graphics.*
import android.hardware.Camera
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Mini {
    /** minimap rectangle (top-left of the AR screen), shared by the map view and the HUD border */
    fun rect(w: Float, d: Float) = RectF(12f * d, 8f * d, w * 0.58f, 158f * d)
}

private fun sigColor(r: Int) = when {
    r >= -60 -> Color.parseColor("#2BFF5A")     // strong
    r >= -75 -> Color.parseColor("#FFE81F")     // medium
    else -> Color.parseColor("#FF2D2D")         // weak
}

/** Live camera + minimap + floating 3D Wi-Fi pins + guide arrow. All values are real (see comments). */
@Suppress("DEPRECATION")
class ARScreen(ctx: Context, private val targetBssid: String, private val targetSsid: String) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private var camera: Camera? = null
    private val surface = SurfaceView(ctx)
    private val tele = WifiTelemetry(ctx, targetBssid, targetSsid)
    private val beeper = Beeper { if (tele.inRange) tele.rssi else null }
    private val compass = Compass(ctx)
    private val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val map = MapView(ctx)
    private val mapBox = FrameLayout(ctx)
    private val mini = MiniOverlay(d)
    private val hud: HudView
    private val camMsg: TextView
    private val beepBtn: TextView
    private var beepOn = false
    @Volatile private var me: DoubleArray? = null
    private var running = false
    private var lastFit = 0L
    private var centered = false

    private val locListener = LocationListener { l: Location ->
        me = doubleArrayOf(l.latitude, l.longitude)
        compass.setLocation(l)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val mr = Mini.rect(w.toFloat(), d)
        mapBox.layoutParams = LayoutParams(mr.width().toInt(), mr.height().toInt(), Gravity.TOP or Gravity.START).apply {
            setMargins(mr.left.toInt(), mr.top.toInt(), 0, 0)
        }
    }

    private val tick = object : Runnable {
        override fun run() { if (!running) return; onTick(); handler.postDelayed(this, 250) }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // ----- minimap (satellite tiles) -----
        Configuration.getInstance().userAgentValue = ctx.packageName
        map.setTileSource(Tiles.satellite())
        map.setBuiltInZoomControls(false)
        map.setMultiTouchControls(false)
        map.controller.setZoom(19.0)
        map.overlays.add(mini)
        map.setOnTouchListener { _, _ -> true }
        mapBox.addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapBox.clipToOutline = true
        mapBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 14f * d) }
        }
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val mr = Mini.rect(sw, d)
        addView(mapBox, LayoutParams(mr.width().toInt(), mr.height().toInt(), Gravity.TOP or Gravity.START).apply {
            setMargins(mr.left.toInt(), mr.top.toInt(), 0, 0)
        })

        hud = HudView(ctx, tele, compass, targetBssid) { me }
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        camMsg = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FF6B6B")); textSize = 12f; gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24); visibility = View.GONE
        }
        addView(camMsg, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        beepBtn = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#990B1622"))
            setOnClickListener { setBeep(!beepOn) }
        }
        addView(beepBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 14, (112 * d).toInt()) })
        setBeep(false)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { openCamera(holder) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { releaseCamera() }
        })
        startAll()
    }

    private fun startAll() {
        if (running) return
        running = true
        tele.start(); compass.start(); map.onResume()
        try {
            val last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) { me = doubleArrayOf(last.latitude, last.longitude); compass.setLocation(last) }
        } catch (e: SecurityException) { }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { locMgr.requestLocationUpdates(p, 1000L, 0f, locListener) } catch (e: Exception) { }
        }
        handler.post(tick)
    }

    private fun stopAll() {
        running = false
        handler.removeCallbacksAndMessages(null)
        tele.stop(); compass.stop(); map.onPause()
        try { locMgr.removeUpdates(locListener) } catch (e: Exception) { }
    }

    /** feed real samples to the locator + move the minimap */
    private fun onTick() {
        val m = me
        if (m != null) {
            val r = tele.rssi
            if (tele.inRange && r != null) ApLocator.add(targetBssid, r, m[0], m[1])
            for (o in tele.others) ApLocator.add(o.bssid, o.level, m[0], m[1])
        }
        updateMap()
    }

    private fun updateMap() {
        val m = me ?: return
        val gp = GeoPoint(m[0], m[1])
        mini.me = gp
        mini.heading = compass.heading
        val r = tele.rssi
        mini.rangeM = if (tele.inRange && r != null) SignalMath.estimateDistanceMeters(r) else 0.0
        mini.tgtColor = if (r != null) sigColor(r) else Color.parseColor("#FF2D2D")
        mini.tgtLabel = if (targetSsid.length > 10) targetSsid.take(9) + "…" else targetSsid
        val est = ApLocator.estimate(targetBssid)
        if (est != null && est.locked) {
            val tp = GeoPoint(est.lat, est.lon)
            mini.tgt = tp
            val dist = ApLocator.distM(m[0], m[1], est.lat, est.lon)
            mini.distText = SignalMath.distShort(dist)
            val now = SystemClock.elapsedRealtime()
            if (dist >= 3.0 && map.width > 0) {
                if (now - lastFit > 1500) {
                    lastFit = now
                    try { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(listOf(gp, tp)), false, (36 * d).toInt()) } catch (e: Exception) { }
                }
            } else { map.controller.setZoom(19.0); map.controller.setCenter(gp) }
        } else {
            mini.tgt = null; mini.distText = ""
            map.controller.setCenter(gp)
            if (!centered) { map.controller.setZoom(19.0); centered = true }
        }
        map.invalidate()
    }

    private fun setBeep(on: Boolean) {
        beepOn = on
        beepBtn.text = if (on) "🔊 BEEP ON" else "🔈 BEEP OFF"
        if (on) beeper.start() else beeper.stop()
    }

    private fun displayRotation(): Int {
        val w = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (w.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun openCamera(holder: SurfaceHolder) {
        try {
            releaseCamera()
            val info = Camera.CameraInfo()
            var id = 0
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break }
            }
            Camera.getCameraInfo(id, info)
            val cam = Camera.open(id)

            val rot = displayRotation()
            val orient = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                (360 - (info.orientation + rot) % 360) % 360
            else (info.orientation - rot + 360) % 360
            cam.setDisplayOrientation(orient)
            val portraitLike = orient == 90 || orient == 270

            val vw = if (width > 0) width else resources.displayMetrics.widthPixels
            val vh = if (height > 0) height else resources.displayMetrics.heightPixels
            val params = cam.parameters
            val target = if (portraitLike) vh.toDouble() / vw else vw.toDouble() / vh
            val best = params.supportedPreviewSizes
                .filter { it.width * it.height <= 1920 * 1080 }
                .minWithOrNull(compareBy<Camera.Size>({ (abs(it.width.toDouble() / it.height - target) * 100).toInt() }, { -(it.width * it.height) }))
                ?: params.supportedPreviewSizes.first()
            params.setPreviewSize(best.width, best.height)
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true)
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            try { cam.parameters = params } catch (e: Exception) { }
            cam.setPreviewDisplay(holder)
            cam.startPreview()
            camera = cam
            camMsg.visibility = View.GONE

            val dw = if (portraitLike) best.height else best.width
            val dh = if (portraitLike) best.width else best.height
            val scale = max(vw.toFloat() / dw, vh.toFloat() / dh)
            val lp = LayoutParams((dw * scale).toInt(), (dh * scale).toInt(), Gravity.CENTER)
            post { surface.layoutParams = lp }
        } catch (e: Exception) {
            camMsg.text = "Camera unavailable (${e.message ?: "busy"}).\nClose other camera apps and reopen."
            camMsg.visibility = View.VISIBLE
        }
    }

    private fun releaseCamera() {
        try { camera?.stopPreview() } catch (e: Exception) { }
        try { camera?.release() } catch (e: Exception) { }
        camera = null
    }

    fun resume() { startAll(); if (beepOn) beeper.start() }
    fun pause() { beeper.stop(); releaseCamera(); stopAll() }
    fun stop() { pause() }
}

/** Minimap overlay: "You" (with view cone), router pin, dashed arrow to it. */
class MiniOverlay(private val d: Float) : Overlay() {
    var me: GeoPoint? = null
    var tgt: GeoPoint? = null
    var tgtColor = Color.GREEN
    var tgtLabel = ""
    var rangeM = 0.0
    var heading = Float.NaN
    var distText = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val rect = RectF()
    private val path = Path()

    private fun chip(c: Canvas, s: String, x: Float, y: Float, bg: Int, fg: Int, size: Float) {
        tp.textSize = size * d
        val w = tp.measureText(s) + 14 * d
        rect.set(x - w / 2, y, x + w / 2, y + size * d + 10 * d)
        p.style = Paint.Style.FILL; p.shader = null; p.color = bg
        c.drawRoundRect(rect, 6 * d, 6 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = fg; p.alpha = 220
        c.drawRoundRect(rect, 6 * d, 6 * d, p)
        tp.color = Color.WHITE
        c.drawText(s, x, y + size * d + 3 * d, tp)
    }

    override fun draw(c: Canvas, proj: Projection) {
        val m = me ?: return
        val mp = proj.toPixels(m, null)
        val mx = mp.x.toFloat(); val my = mp.y.toFloat()
        val tg = tgt
        val blue = Color.parseColor("#2979FF")
        val phase = (System.currentTimeMillis() % 1200L) / 1200f

        if (tg != null) {
            val tpnt = proj.toPixels(tg, null)
            val tx = tpnt.x.toFloat(); val ty = tpnt.y.toFloat()
            // dashed arrow You -> Router
            p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f * d; p.color = Color.parseColor("#29B6F6"); p.alpha = 255
            p.pathEffect = DashPathEffect(floatArrayOf(7 * d, 6 * d), -phase * 13 * d)
            c.drawLine(mx, my, tx, ty, p); p.pathEffect = null
            val ang = Math.atan2((ty - my).toDouble(), (tx - mx).toDouble())
            val hx = tx - (22 * d * Math.cos(ang)).toFloat(); val hy = ty - (22 * d * Math.sin(ang)).toFloat()
            path.reset()
            path.moveTo(hx + (10 * d * Math.cos(ang)).toFloat(), hy + (10 * d * Math.sin(ang)).toFloat())
            path.lineTo(hx + (7 * d * Math.cos(ang + 2.3)).toFloat(), hy + (7 * d * Math.sin(ang + 2.3)).toFloat())
            path.lineTo(hx + (7 * d * Math.cos(ang - 2.3)).toFloat(), hy + (7 * d * Math.sin(ang - 2.3)).toFloat())
            path.close(); p.style = Paint.Style.FILL; p.color = Color.parseColor("#29B6F6"); c.drawPath(path, p)

            if (distText.isNotEmpty()) chip(c, distText, (mx + tx) / 2, (my + ty) / 2 - 12 * d, Color.parseColor("#E6071A10"), Color.parseColor("#29B6F6"), 10f)
            // router marker
            rect.set(tx - 17 * d, ty - 28 * d, tx + 17 * d, ty - 2 * d)
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#E6071A10"); c.drawRoundRect(rect, 7 * d, 7 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = tgtColor; c.drawRoundRect(rect, 7 * d, 7 * d, p)
            for (i in 1..2) {
                val rr = i * 5f * d
                rect.set(tx - rr, ty - 16 * d - rr, tx + rr, ty - 16 * d + rr)
                p.strokeWidth = 2f * d; p.color = tgtColor
                c.drawArc(rect, 225f, 90f, false, p)
            }
            p.style = Paint.Style.FILL; c.drawCircle(tx, ty - 16 * d, 1.8f * d, p)
            chip(c, tgtLabel, tx, ty + 2 * d, Color.parseColor("#E6071A10"), tgtColor, 9f)
        } else if (rangeM > 0) {
            // router direction unknown yet: show the real signal range around you
            val rr = proj.metersToEquatorPixels(rangeM.toFloat()).coerceIn(14f * d, 90f * d)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = tgtColor; p.alpha = 220
            p.pathEffect = DashPathEffect(floatArrayOf(6 * d, 6 * d), phase * 12 * d)
            c.drawCircle(mx, my, rr, p); p.pathEffect = null
        }

        // view cone (where the camera points)
        if (!heading.isNaN()) {
            rect.set(mx - 46 * d, my - 46 * d, mx + 46 * d, my + 46 * d)
            p.style = Paint.Style.FILL; p.color = Color.argb(70, 41, 121, 255)
            c.drawArc(rect, heading - 90f - 28f, 56f, true, p)
        }
        // You
        p.style = Paint.Style.FILL; p.shader = RadialGradient(mx, my, 20 * d, Color.argb(190, 41, 121, 255), Color.argb(0, 41, 121, 255), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, 20 * d + 4 * d * phase, p); p.shader = null
        p.color = Color.WHITE; c.drawCircle(mx, my, 9 * d, p)
        p.color = blue; c.drawCircle(mx, my, 6.5f * d, p)
        chip(c, "You", mx, my + 12 * d, blue, Color.WHITE, 10f)
    }
}

/** Floating 3D pins, guide arrow, legend, compass, bottom status bar. Every number comes from real sensors/scans. */
class HudView(ctx: Context, private val t: WifiTelemetry, private val compass: Compass,
              private val targetBssid: String, private val meProvider: () -> DoubleArray?) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var phase = 0f
    private var shownRssi = -100f
    private var shownMbps = 0f
    private val HFOV = 52f

    private class Pin(val bssid: String, val ssid: String, val level: Int, val dist: Double, val mapDist: Double?,
                      val rel: Float?, val target: Boolean, val line2: String)

    private val loop = object : Runnable {
        override fun run() { phase = (System.currentTimeMillis() % 3000L) / 3000f; invalidate(); postDelayed(this, 40) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(loop) }
    override fun onDetachedFromWindow() { removeCallbacks(loop); super.onDetachedFromWindow() }

    private fun bars(r: Int) = when { r >= -55 -> 4; r >= -65 -> 3; r >= -75 -> 2; r >= -85 -> 1; else -> 0 }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, a: Paint.Align = Paint.Align.CENTER) {
        txt.textSize = size * d; txt.color = color; txt.textAlign = a
        txt.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        c.drawText(s, x, y, txt)
    }

    /** shorten text with "…" so it never runs outside its box */
    private fun fit(s: String, maxW: Float, size: Float, bold: Boolean): String {
        txt.textSize = size * d
        txt.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        if (txt.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && txt.measureText(s.take(n) + "…") > maxW) n--
        return s.take(n) + "…"
    }

    private fun glass(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float, accent: Int, bg: Int = Color.argb(185, 6, 14, 24)) {
        rect.set(l, tp, r, b)
        fill.style = Paint.Style.FILL; fill.shader = null; fill.color = bg
        c.drawRoundRect(rect, rad, rad, fill)
        for (i in 3 downTo 1) { line.color = accent; line.alpha = 26 * (4 - i); line.strokeWidth = (1f + i * 2f) * d; c.drawRoundRect(rect, rad, rad, line) }
        line.color = accent; line.alpha = 240; line.strokeWidth = 1.6f * d; c.drawRoundRect(rect, rad, rad, line)
    }

    private fun wifiGlyph(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, lit: Int = 3) {
        line.strokeCap = Paint.Cap.ROUND
        for (i in 1..3) {
            val rr = i * 6.2f * s
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            line.strokeWidth = 2.6f * s; line.color = color
            line.alpha = if (i <= lit) 255 else 70
            c.drawArc(rect, 225f, 90f, false, line)
        }
        line.strokeCap = Paint.Cap.BUTT
        fill.style = Paint.Style.FILL; fill.color = color; c.drawCircle(cx, cy, 2.4f * s, fill)
    }

    /** one floating 3D pin: ground ring, beam, glass label (dBm, Mbps/range), teardrop with wifi glyph */
    private fun drawPin(c: Canvas, cx: Float, groundY: Float, s: Float, col: Int, level: Int, line2: String, line3: String, name: String, seed: Float) {
        val bob = (sin((phase + seed) * 2 * Math.PI) * 4 * d * s).toFloat()
        val pulse = (phase + seed) % 1f
        line.strokeWidth = 3f * d * s; line.color = col; line.alpha = 230
        rect.set(cx - 46 * d * s, groundY - 11 * d * s, cx + 46 * d * s, groundY + 11 * d * s); c.drawOval(rect, line)
        line.strokeWidth = 2f * d * s; line.alpha = (200 * (1 - pulse)).toInt()
        val pr = 46f + 34f * pulse
        rect.set(cx - pr * d * s, groundY - pr * 0.24f * d * s, cx + pr * d * s, groundY + pr * 0.24f * d * s); c.drawOval(rect, line)
        fill.style = Paint.Style.FILL; fill.color = col; fill.alpha = 40
        rect.set(cx - 40 * d * s, groundY - 9 * d * s, cx + 40 * d * s, groundY + 9 * d * s); c.drawOval(rect, fill); fill.alpha = 255
        val boxBottom = groundY - 20 * d * s + bob
        line.strokeWidth = 2f * d * s; line.color = col; line.alpha = 140
        c.drawLine(cx, boxBottom, cx, groundY - 4 * d * s, line)
        val bw = 132f * d * s; val bh = 74f * d * s
        val boxTop = boxBottom - bh
        glass(c, cx - bw / 2, boxTop, cx + bw / 2, boxBottom, 12 * d * s, col)
        text(c, "$level dBm", cx, boxTop + 26 * d * s, 19f * s, Color.WHITE, true)
        text(c, line2, cx, boxTop + 45 * d * s, 13f * s, Color.parseColor("#E0F7FA"), true)
        text(c, fit(line3, bw - 10 * d * s, 9.5f * s, false), cx, boxTop + 63 * d * s, 9.5f * s, Color.parseColor("#9FD8E8"))
        val pinY = boxTop - 40 * d * s
        path.reset()
        path.addCircle(cx, pinY, 25 * d * s, Path.Direction.CW)
        path.moveTo(cx - 12 * d * s, pinY + 19 * d * s); path.lineTo(cx + 12 * d * s, pinY + 19 * d * s); path.lineTo(cx, boxTop - 2 * d * s); path.close()
        fill.style = Paint.Style.FILL; fill.color = col; fill.alpha = 235; c.drawPath(path, fill); fill.alpha = 255
        line.color = Color.WHITE; line.alpha = 120; line.strokeWidth = 1.5f * d * s; c.drawCircle(cx, pinY, 25 * d * s, line)
        wifiGlyph(c, cx, pinY + 9 * d * s, d * s, Color.WHITE)
        text(c, fit(name, bw, 12f * s, true), cx, pinY - 33 * d * s, 12f * s, Color.WHITE, true)
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, deg: Float, col: Int) {
        c.save(); c.translate(cx, cy); c.rotate(deg)
        path.reset(); path.moveTo(0f, -s); path.lineTo(s * 0.95f, s * 0.85f); path.lineTo(0f, s * 0.38f); path.lineTo(-s * 0.95f, s * 0.85f); path.close()
        for (i in 3 downTo 1) { line.color = col; line.alpha = 30 * (4 - i); line.strokeWidth = i * 4f * d; c.drawPath(path, line) }
        fill.style = Paint.Style.FILL; fill.color = col; fill.alpha = 235; c.drawPath(path, fill); fill.alpha = 255
        line.color = Color.WHITE; line.alpha = 200; line.strokeWidth = 1.5f * d; c.drawPath(path, line)
        c.restore()
    }

    private fun rel180(a: Double): Float = (((a + 540.0) % 360.0) - 180.0).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val live = t.inRange && t.rssi != null
        val r = t.rssi ?: -100
        shownRssi += (r - shownRssi) * 0.35f
        val rs = Math.round(shownRssi)
        val col = if (live) sigColor(rs) else sigColor(-100)
        val me = meProvider()
        val heading = compass.heading
        val cyan = Color.parseColor("#00E5FF")

        // ---------- minimap border, compass, legend ----------
        val mr = Mini.rect(w, d)
        line.color = cyan; line.alpha = 255; line.strokeWidth = 2f * d
        rect.set(mr); c.drawRoundRect(rect, 14 * d, 14 * d, line)
        if (me == null) {
            rect.set(mr); fill.style = Paint.Style.FILL; fill.color = Color.argb(190, 5, 10, 18); c.drawRoundRect(rect, 14 * d, 14 * d, fill)
            text(c, "Waiting for GPS...", mr.centerX(), mr.centerY(), 12f, Color.parseColor("#FFC400"), true)
        }
        val ccx = mr.left + 34 * d; val ccy = mr.bottom - 34 * d
        fill.style = Paint.Style.FILL; fill.color = Color.argb(215, 5, 14, 26); c.drawCircle(ccx, ccy, 25 * d, fill)
        line.color = cyan; line.alpha = 220; line.strokeWidth = 1.6f * d; c.drawCircle(ccx, ccy, 25 * d, line)
        if (!heading.isNaN()) {
            c.save(); c.rotate(-heading, ccx, ccy); text(c, "N", ccx, ccy - 12 * d, 11f, Color.WHITE, true); c.restore()
        } else text(c, "N", ccx, ccy - 12 * d, 11f, Color.WHITE, true)
        path.reset(); path.moveTo(ccx, ccy - 8 * d); path.lineTo(ccx + 7 * d, ccy + 10 * d); path.lineTo(ccx, ccy + 5 * d); path.lineTo(ccx - 7 * d, ccy + 10 * d); path.close()
        fill.color = Color.WHITE; c.drawPath(path, fill)
        // legend (text kept inside the card)
        val ll = mr.right + 8 * d; val lr = w - 12 * d
        glass(c, ll, mr.top, lr, mr.bottom, 14 * d, cyan)
        val rows = arrayOf(Triple("Strong", "-30 to -60 dBm", sigColor(-50)), Triple("Medium", "-61 to -75 dBm", sigColor(-70)), Triple("Weak", "-76 to -90 dBm", sigColor(-85)))
        val rh = (mr.height() - 12 * d) / 3
        for ((i, row) in rows.withIndex()) {
            val y = mr.top + 6 * d + rh * i + rh / 2
            fill.style = Paint.Style.FILL; fill.color = row.third
            for (g in 3 downTo 1) { fill.alpha = 25 * (4 - g); c.drawCircle(ll + 18 * d, y, (7 + g * 2) * d, fill) }
            fill.alpha = 255; c.drawCircle(ll + 18 * d, y, 7 * d, fill)
            text(c, row.first, ll + 33 * d, y - 1 * d, 11.5f, Color.WHITE, true, Paint.Align.LEFT)
            text(c, fit(row.second, lr - ll - 38 * d, 8f, false), ll + 33 * d, y + 12 * d, 8f, Color.parseColor("#9FD8E8"), false, Paint.Align.LEFT)
        }

        // ---------- AR pins ----------
        val pins = ArrayList<Pin>()
        val tr = t.rssi
        if (t.inRange && tr != null) {
            val dm = SignalMath.estimateDistanceMeters(tr)
            val est = ApLocator.estimate(targetBssid)
            var rel: Float? = null
            var mapD: Double? = null
            if (me != null && est != null && est.locked) {
                mapD = ApLocator.distM(me[0], me[1], est.lat, est.lon)
                if (!heading.isNaN()) rel = rel180(ApLocator.bearing(me[0], me[1], est.lat, est.lon) - heading)
            }
            val l2 = when {
                t.connected && t.downMbps != null -> "%.0f Mbps".format(shownMbps)
                t.connected -> "measuring..."
                else -> "-- Mbps"
            }
            pins.add(Pin(targetBssid, t.targetSsid, tr, dm, mapD, rel, true, l2))
        }
        val others = ArrayList<NearAp>()
        for (o in t.others) {
            val est = ApLocator.estimate(o.bssid)
            if (me != null && est != null && est.locked && !heading.isNaN()) {
                val rel = rel180(ApLocator.bearing(me[0], me[1], est.lat, est.lon) - heading)
                val dm = SignalMath.estimateDistanceMeters(o.level)
                pins.add(Pin(o.bssid, o.ssid, o.level, dm, ApLocator.distM(me[0], me[1], est.lat, est.lon), rel, false, "≈ " + SignalMath.distShort(dm)))
            } else others.add(o)
        }
        val mbNow = t.downMbps
        if (t.connected && mbNow != null) shownMbps += (mbNow.toFloat() - shownMbps) * 0.35f else if (!t.connected) shownMbps = 0f

        val topLimit = mr.bottom + 52 * d
        for (p in pins.sortedByDescending { it.dist }) {
            val pc = sigColor(p.level)
            val near = 1f - sqrt((p.dist.coerceIn(1.0, 40.0) / 40.0)).toFloat()
            val s = (0.62f + 0.40f * near) * (if (p.target) 1.1f else 0.9f)
            val pinH = 192f * d * s
            val gyMax = h - 296 * d
            var gy = h * 0.55f + h * 0.12f * near
            gy = if (topLimit + pinH > gyMax) topLimit + pinH else gy.coerceIn(topLimit + pinH, gyMax)
            val rangeTxt = if (p.mapDist != null) "map " + SignalMath.distText(p.mapDist) else "range ≈ " + SignalMath.distText(p.dist)
            val rel = p.rel
            if (rel == null) {
                drawPin(c, w / 2, gy, s, pc, p.level, p.line2, rangeTxt, p.ssid, 0f)
            } else if (abs(rel) <= HFOV / 2 + 6) {
                drawPin(c, w / 2 + rel / HFOV * w, gy, s, pc, p.level, p.line2, rangeTxt, p.ssid, (p.bssid.hashCode() and 7) / 8f)
            } else {
                val left = rel < 0
                val ex = if (left) 26 * d else w - 26 * d
                arrow(c, ex, h * 0.5f, 14 * d, if (left) -90f else 90f, pc)
                text(c, fit(p.ssid, 60 * d, 9f, true), ex, h * 0.5f + 36 * d, 9f, Color.WHITE, true)
                text(c, SignalMath.distShort(p.mapDist ?: p.dist), ex, h * 0.5f + 48 * d, 9f, pc)
            }
        }

        // ---------- guide arrow / locate hint ----------
        val tp = pins.firstOrNull { it.target }
        val ay = h - 214 * d
        if (tp != null) {
            val rel = tp.rel
            val dd = tp.mapDist ?: tp.dist
            if (rel != null) {
                val close = abs(rel) < 15
                val ac = if (close) sigColor(-50) else sigColor(-70)
                for (i in 0..2) {
                    val f = ((phase + i / 3f) % 1f)
                    fill.style = Paint.Style.FILL; fill.color = ac; fill.alpha = (200 * (1 - f)).toInt()
                    c.drawCircle(w / 2, ay - 34 * d - f * 70 * d, 3.5f * d, fill)
                }
                fill.alpha = 255
                arrow(c, w / 2, ay, 30 * d, rel.coerceIn(-80f, 80f), ac)
                val msg = if (dd < 2.5) "YOU ARE AT THE ROUTER"
                    else if (close) "GO STRAIGHT · " + SignalMath.distShort(dd)
                    else (if (rel < 0) "TURN LEFT " else "TURN RIGHT ") + abs(rel).toInt() + "° · " + SignalMath.distShort(dd)
                text(c, msg, w / 2, ay + 56 * d, 12f, Color.WHITE, true)
            } else {
                val est = ApLocator.estimate(targetBssid)
                val prog = if (est == null) 0f else min(1f, (est.spreadM / 4.0).toFloat())
                val pulse = 0.6f + 0.4f * sin(phase * 2 * Math.PI).toFloat()
                glass(c, w / 2 - 150 * d, ay - 20 * d, w / 2 + 150 * d, ay + 46 * d, 14 * d, sigColor(-70))
                val m1 = when {
                    heading.isNaN() -> "No compass on this phone - direction unavailable"
                    me == null -> "Turn ON GPS to locate the router"
                    else -> "WALK 5-10 m ANY DIRECTION TO LOCATE"
                }
                text(c, m1, w / 2, ay + 2 * d, 11f, Color.argb((255 * pulse).toInt(), 255, 232, 31), true)
                rect.set(w / 2 - 110 * d, ay + 14 * d, w / 2 + 110 * d, ay + 22 * d)
                fill.style = Paint.Style.FILL; fill.color = Color.argb(70, 255, 255, 255); c.drawRoundRect(rect, 4 * d, 4 * d, fill)
                rect.set(w / 2 - 110 * d, ay + 14 * d, w / 2 - 110 * d + 220 * d * prog, ay + 22 * d)
                fill.color = sigColor(-50); c.drawRoundRect(rect, 4 * d, 4 * d, fill)
                text(c, "signal samples spread %.0f / 4 m".format(est?.spreadM ?: 0.0), w / 2, ay + 38 * d, 9f, Color.parseColor("#B2EBF2"))
            }
        }

        // ---------- nearby networks: one compact row under the minimap (never covers the pin) ----------
        if (others.isNotEmpty()) {
            val n = min(3, others.size)
            val gap = 6 * d
            val cw = (w - 24 * d - gap * (n - 1)) / n
            val oy = mr.bottom + 8 * d
            for (i in 0 until n) {
                val ap = others[i]
                val x0 = 12 * d + i * (cw + gap)
                val ac = sigColor(ap.level)
                glass(c, x0, oy, x0 + cw, oy + 28 * d, 9 * d, ac)
                val lv = "${ap.level}"
                text(c, lv, x0 + cw - 8 * d, oy + 19 * d, 11f, ac, true, Paint.Align.RIGHT)
                text(c, fit(ap.ssid, cw - 46 * d, 10f, false), x0 + 8 * d, oy + 19 * d, 10f, Color.WHITE, false, Paint.Align.LEFT)
            }
        }

        // ---------- bottom status bar ----------
        val bl = 12 * d; val br = w - 12 * d; val bb = h - 12 * d; val bt = bb - 84 * d
        glass(c, bl, bt, br, bb, 20 * d, cyan)
        wifiGlyph(c, bl + 34 * d, bt + 48 * d, 2f * d, Color.parseColor("#2979FF"))
        val tx = bl + 66 * d
        val rightW = 156 * d
        val nm = fit(t.targetSsid, br - rightW - tx, 17f, true)
        text(c, nm, tx, bt + 36 * d, 17f, Color.WHITE, true, Paint.Align.LEFT)
        val (stText, stCol) = when {
            t.connected -> Pair("Connected", sigColor(-50))
            t.inRange -> Pair("In range", sigColor(-70))
            else -> Pair("Not in range", sigColor(-85))
        }
        text(c, stText, tx, bt + 62 * d, 14f, stCol, true, Paint.Align.LEFT)
        val nb = if (live) bars(rs) else 0
        val bx = br - rightW + 6 * d
        for (i in 0 until 4) {
            val bh2 = (9 + i * 7) * d
            rect.set(bx + i * 11 * d, bb - 22 * d - bh2, bx + i * 11 * d + 8 * d, bb - 22 * d)
            fill.style = Paint.Style.FILL; fill.color = if (i < nb) col else Color.argb(70, 255, 255, 255)
            c.drawRoundRect(rect, 2 * d, 2 * d, fill)
        }
        text(c, if (live) "$rs dBm" else "-- dBm", br - 14 * d, bt + 38 * d, 15f, Color.WHITE, false, Paint.Align.RIGHT)
        val mbTxt = when {
            t.connected && t.downMbps != null -> "%.0f Mbps".format(shownMbps)
            t.connected -> "measuring"
            else -> "-- Mbps"
        }
        text(c, mbTxt, br - 14 * d, bt + 62 * d, 15f, Color.parseColor("#B2EBF2"), false, Paint.Align.RIGHT)
        val src = when {
            !t.inRange -> "TARGET NOT IN RANGE"
            t.source == "LIVE" -> "● LIVE · ${t.standard}"
            else -> "◌ scan ${t.scanAgeSec}s old"
        }
        text(c, src, bl + 14 * d, bt - 6 * d, 9f, Color.parseColor("#B2EBF2"), false, Paint.Align.LEFT)
        if (!t.locationOn) text(c, "Turn ON Location (GPS)", w / 2, h * 0.5f, 12f, Color.parseColor("#FFC400"), true)
    }
}
