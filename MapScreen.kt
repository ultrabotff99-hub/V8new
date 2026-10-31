package com.tonix.wifireader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Live satellite map with REAL GPS:
 *  - "You" moves with your GPS (blue dot, accuracy circle, walking trail).
 *  - every nearby Wi-Fi is a coloured circle with its name (green/yellow/red = signal strength).
 *  - Wi-Fi not located yet: it sits on a dashed ring = its real signal range around you.
 *    Walk around: the circle jumps to the estimated router position (shared with the AR view).
 *  - tap a circle: distance from you (m / km), dBm, band, and "open in AR".
 */
@Suppress("DEPRECATION")
class MapScreen(ctx: Context, myLocation: GeoPoint?, networks: List<ScanResult>, private val onAr: (String) -> Unit) : FrameLayout(ctx) {
    private class Track(var ssid: String) {
        var level = -100; var freq = 0; var caps = ""; var seen = 0L
    }
    private class PinData(val bssid: String, val ssid: String, val level: Int, val freq: Int, val caps: String,
                          val distM: Double, val est: ApLocator.Estimate?)
    private class Hit(val bssid: String, val x: Float, val y: Float)

    private val d = resources.displayMetrics.density
    private val map = MapView(ctx)
    private val tracks = LinkedHashMap<String, Track>()
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var selfPoint: GeoPoint? = myLocation
    private var accuracy = 0f
    private val trail = ArrayList<GeoPoint>()
    private var gotFix = myLocation != null
    private var lastGpsT = 0L
    private var follow = true
    private var running = false
    private var locReg = false
    private var satellite = true
    private var selected: String? = null
    private var receiver: BroadcastReceiver? = null
    private val overlay = WifiMapOverlay()
    private val note: TextView
    private val followBtn: TextView
    private val layerBtn: TextView
    private val card: LinearLayout
    private val cTitle: TextView
    private val cSub: TextView
    private val cArBtn: TextView

    private val listener = LocationListener { loc: Location -> onFix(loc) }

    private val pulseLoop = object : Runnable {
        override fun run() {
            if (!running) return
            map.invalidate()
            handler.postDelayed(this, 50)
        }
    }
    private val scanLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try { wm.startScan() } catch (e: Exception) { }
            ensureLocation()
            refreshCard()
            handler.postDelayed(this, 4000)
        }
    }

    private fun sat() = Tiles.satellite()

    private fun pill(t: TextView, accent: Int) {
        t.setTextColor(accent); t.textSize = 11f; t.setPadding(22, 14, 22, 14)
        t.background = GradientDrawable().apply { setColor(Color.parseColor("#CC05070D")); cornerRadius = 16f; setStroke(2, accent) }
    }

    init {
        Configuration.getInstance().userAgentValue = ctx.packageName
        map.setTileSource(sat())
        map.setBuiltInZoomControls(false)
        map.setMultiTouchControls(true)
        if (myLocation != null) { map.controller.setZoom(19.0); map.controller.setCenter(myLocation) }
        else { map.controller.setZoom(4.0); map.controller.setCenter(GeoPoint(20.5937, 78.9629)) }
        map.overlays.add(overlay)
        var dx = 0f; var dy = 0f
        map.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) { dx = ev.x; dy = ev.y }
            else if (ev.action == MotionEvent.ACTION_MOVE && hypot(ev.x - dx, ev.y - dy) > 28 * d) setFollow(false)
            false
        }
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        followBtn = TextView(ctx).apply { pill(this, Color.parseColor("#00E5FF")); setOnClickListener { setFollow(true); selfPoint?.let { map.controller.animateTo(it) } } }
        addView(followBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, 12, 12, 0) })
        layerBtn = TextView(ctx).apply {
            pill(this, Color.parseColor("#FFC400")); text = "🛰 SATELLITE"
            setOnClickListener {
                satellite = !satellite
                map.setTileSource(if (satellite) sat() else TileSourceFactory.MAPNIK)
                text = if (satellite) "🛰 SATELLITE" else "🗺 STREET"
            }
        }
        addView(layerBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(12, 12, 0, 0) })

        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 14, 24, 14)
            setBackgroundColor(Color.parseColor("#E605070D"))
        }
        note = TextView(ctx).apply { setTextColor(Color.parseColor("#B2EBF2")); textSize = 11f }
        cTitle = TextView(ctx).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        cSub = TextView(ctx).apply { textSize = 11.5f; setTextColor(Color.parseColor("#B2EBF2")) }
        cArBtn = TextView(ctx).apply {
            text = "📷 OPEN IN AR VIEW"; gravity = Gravity.CENTER
            pill(this, Color.parseColor("#00FFC6"))
            setOnClickListener { selected?.let { onAr(it) } }
        }
        card.addView(note); card.addView(cTitle); card.addView(cSub)
        card.addView(cArBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10; gravity = Gravity.END })
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setFollow(true)
        absorb(networks)
        onResumeMap()
    }

    private fun setFollow(on: Boolean) {
        follow = on
        followBtn.text = if (on) "◎ FOLLOWING YOU" else "◌ TAP TO FOLLOW"
    }

    // ---------------- real GPS ----------------
    private fun hasLoc() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocation() {
        if (locReg || !hasLoc()) return
        try {
            val last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) onFix(last)
        } catch (e: SecurityException) { }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { locMgr.requestLocationUpdates(p, 1000L, 0f, listener); locReg = true } catch (e: Exception) { }
        }
    }

    private fun onFix(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsT = now
        else if (now - lastGpsT < 10000) return                 // keep the accurate GPS fix, ignore coarse network fixes
        val gp = GeoPoint(loc.latitude, loc.longitude)
        selfPoint = gp
        accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
        val lt = trail.lastOrNull()
        if (lt == null || ApLocator.distM(lt.latitude, lt.longitude, gp.latitude, gp.longitude) >= 2.0) {
            trail.add(gp); if (trail.size > 400) trail.removeAt(0)
        }
        if (!gotFix) { gotFix = true; map.controller.setZoom(19.0); map.controller.setCenter(gp) }
        else if (follow) map.controller.animateTo(gp)
        refreshCard()
    }

    // ---------------- Wi-Fi data ----------------
    private fun absorb(list: List<ScanResult>) {
        val now = SystemClock.elapsedRealtime()
        val me = selfPoint
        val okFix = me != null && (accuracy == 0f || accuracy <= 40f)
        for (r in list) {
            val key = r.BSSID ?: continue
            val t = tracks.getOrPut(key) { Track("") }
            t.ssid = if (r.SSID.isNullOrEmpty()) "<hidden>" else r.SSID
            t.level = r.level; t.freq = r.frequency; t.caps = r.capabilities ?: ""; t.seen = now
            if (okFix && me != null) ApLocator.add(key, r.level, me.latitude, me.longitude)   // real (position, signal) sample
        }
        val it = tracks.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.seen > 30000) it.remove()
        refreshCard()
    }

    private fun buildPins(): List<PinData> {
        val out = ArrayList<PinData>(tracks.size)
        for ((k, t) in tracks) {
            val est = ApLocator.estimate(k)?.takeIf { it.locked }
            out.add(PinData(k, t.ssid, t.level, t.freq, t.caps, SignalMath.estimateDistanceMeters(t.level), est))
        }
        return out
    }

    private fun fmtDist(m: Double): String = when {
        m >= 1000 -> String.format("%.2f km", m / 1000)
        m >= 10 -> String.format("%.0f m", m)
        else -> String.format("%.1f m", m)
    }

    private fun color(l: Int) = when {
        l >= -60 -> Color.parseColor("#2BFF5A")
        l >= -75 -> Color.parseColor("#FFE81F")
        else -> Color.parseColor("#FF2D2D")
    }

    private fun compassName(b: Double): String = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((b + 22.5) % 360) / 45).toInt()]

    private fun band(f: Int) = when { f >= 5925 -> "6G"; f >= 4900 -> "5G"; f > 0 -> "2.4G"; else -> "--" }
    private fun chan(f: Int) = when { f <= 0 -> 0; f < 3000 -> (f - 2407) / 5; f >= 5925 -> (f - 5950) / 5; else -> (f - 5000) / 5 }

    /** select a Wi-Fi circle (also called from the Wi-Fi list: "Show on Map") */
    fun select(b: String?) { selected = b; refreshCard() }

    private fun refreshCard() {
        val me = selfPoint
        val acc = if (accuracy > 0) " ±${accuracy.toInt()} m" else ""
        note.text = if (!gotFix) "⏳ Waiting for GPS fix... turn ON Location and go near a window / outside"
        else "● You: live GPS$acc   ● ${tracks.size} Wi-Fi live   · tap a circle for distance"
        val sel = selected
        val t = if (sel != null) tracks[sel] else null
        if (sel == null || t == null) {
            cTitle.visibility = GONE; cSub.visibility = GONE; cArBtn.visibility = GONE; note.visibility = VISIBLE
            return
        }
        note.visibility = GONE; cTitle.visibility = VISIBLE; cSub.visibility = VISIBLE; cArBtn.visibility = VISIBLE
        val est = ApLocator.estimate(sel)?.takeIf { it.locked }
        val dm = SignalMath.estimateDistanceMeters(t.level)
        cTitle.text = "📶 ${t.ssid}   ${t.level} dBm"
        cTitle.setTextColor(color(t.level))
        val sec = if (t.caps.contains("WPA") || t.caps.contains("WEP") || t.caps.contains("SAE")) "🔒" else "🔓"
        var s = "Distance from you: ≈ ${fmtDist(dm)} (from signal)\n"
        if (est != null && me != null) {
            val md = ApLocator.distM(me.latitude, me.longitude, est.lat, est.lon)
            val br = ApLocator.bearing(me.latitude, me.longitude, est.lat, est.lon)
            s += "Located on map: ${fmtDist(md)} ${compassName(br)} of you\n"
        } else s += "Direction unknown - walk around so the circle locks on the router\n"
        s += "${band(t.freq)} · CH ${chan(t.freq)} · $sec"
        cSub.text = s
    }

    // ---------------- lifecycle ----------------
    fun onResumeMap() {
        if (running) return
        running = true
        map.onResume()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { try { absorb(wm.scanResults) } catch (e: Exception) { } }
        }
        try { context.applicationContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) } catch (e: Exception) { }
        ensureLocation()
        handler.post(pulseLoop); handler.post(scanLoop)
    }

    fun onPauseMap() {
        running = false
        handler.removeCallbacksAndMessages(null)
        map.onPause()
        try { locMgr.removeUpdates(listener) } catch (e: Exception) { }
        locReg = false
        try { receiver?.let { context.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
    }

    // ---------------- drawing ----------------
    private inner class WifiMapOverlay : Overlay() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val rect = RectF()
        private val path = Path()
        private val hits = ArrayList<Hit>()

        private fun chip(c: Canvas, s: String, x: Float, y: Float, border: Int, size: Float) {
            tp.textSize = size * d
            val w = tp.measureText(s) + 14 * d
            rect.set(x - w / 2, y, x + w / 2, y + size * d + 9 * d)
            p.style = Paint.Style.FILL; p.shader = null; p.pathEffect = null; p.color = Color.argb(225, 5, 12, 22)
            c.drawRoundRect(rect, 7 * d, 7 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.color = border
            c.drawRoundRect(rect, 7 * d, 7 * d, p)
            tp.color = Color.WHITE
            c.drawText(s, x, y + size * d + 2.5f * d, tp)
        }

        private fun badge(c: Canvas, x: Float, y: Float, col: Int, r: Float, sel: Boolean) {
            p.style = Paint.Style.FILL; p.pathEffect = null
            for (g in 3 downTo 1) { p.color = col; p.alpha = 30 * (4 - g); c.drawCircle(x, y, r + g * 3 * d, p) }
            p.color = col; p.alpha = 240; c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = (if (sel) 3.2f else 2f) * d; p.color = Color.WHITE; p.alpha = 255
            c.drawCircle(x, y, r, p)
            p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 2.2f * d
            for (i in 1..3) {
                val rr = i * r * 0.30f
                rect.set(x - rr, y + r * 0.28f - rr, x + rr, y + r * 0.28f + rr)
                c.drawArc(rect, 225f, 90f, false, p)
            }
            p.strokeCap = Paint.Cap.BUTT
            p.style = Paint.Style.FILL; c.drawCircle(x, y + r * 0.28f, 1.9f * d, p)
        }

        override fun draw(c: Canvas, proj: Projection) {
            hits.clear()
            val me = selfPoint
            val mp = me?.let { proj.toPixels(it, null) }
            val phase = (System.currentTimeMillis() % 1500L) / 1500f
            val cosLat = if (me != null) max(0.2, cos(Math.toRadians(me.latitude))).toFloat() else 1f
            fun mpx(m: Double) = proj.metersToEquatorPixels(m.toFloat()) / cosLat

            // walking trail
            if (trail.size >= 2) {
                path.reset()
                for ((i, g) in trail.withIndex()) {
                    val q = proj.toPixels(g, null)
                    if (i == 0) path.moveTo(q.x.toFloat(), q.y.toFloat()) else path.lineTo(q.x.toFloat(), q.y.toFloat())
                }
                p.style = Paint.Style.STROKE; p.shader = null; p.pathEffect = null
                p.strokeWidth = 7 * d; p.color = Color.parseColor("#00E5FF"); p.alpha = 70; c.drawPath(path, p)
                p.strokeWidth = 3 * d; p.alpha = 230; c.drawPath(path, p)
            }
            // GPS accuracy circle
            if (mp != null && accuracy > 0) {
                p.style = Paint.Style.FILL; p.color = Color.argb(34, 41, 121, 255)
                val ar = max(mpx(accuracy.toDouble()), 12 * d)
                c.drawCircle(mp.x.toFloat(), mp.y.toFloat(), ar, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Color.argb(150, 41, 121, 255)
                c.drawCircle(mp.x.toFloat(), mp.y.toFloat(), ar, p)
            }

            val pins = buildPins().sortedBy { it.level }          // strongest drawn last (on top)
            val sel = selected
            // positions
            class Pos(val pin: PinData, val x: Float, val y: Float, val onRing: Boolean, val ringR: Float)
            val poss = ArrayList<Pos>()
            for (pin in pins) {
                if (pin.est != null) {
                    val q = proj.toPixels(GeoPoint(pin.est.lat, pin.est.lon), null)
                    poss.add(Pos(pin, q.x.toFloat(), q.y.toFloat(), false, 0f))
                } else if (mp != null) {
                    val rr = max(mpx(pin.distM), 24 * d)
                    val a = Math.toRadians(((pin.bssid.hashCode() and 0x7fffffff) % 360).toDouble())
                    poss.add(Pos(pin, mp.x + (rr * Math.cos(a)).toFloat(), mp.y + (rr * Math.sin(a)).toFloat(), true, rr))
                }
            }
            // dashed signal-range rings (real range around you)
            if (mp != null) for (ps in poss) if (ps.onRing) {
                val isSel = ps.pin.bssid == sel
                p.style = Paint.Style.STROKE; p.shader = null
                p.strokeWidth = (if (isSel) 3f else 1.4f) * d; p.color = color(ps.pin.level); p.alpha = if (isSel) 230 else 70
                p.pathEffect = DashPathEffect(floatArrayOf(8 * d, 7 * d), phase * 15 * d)
                c.drawCircle(mp.x.toFloat(), mp.y.toFloat(), ps.ringR, p); p.pathEffect = null
            }
            // selected: line + distance
            if (mp != null) for (ps in poss) if (ps.pin.bssid == sel) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = Color.parseColor("#29B6F6"); p.alpha = 255
                p.pathEffect = DashPathEffect(floatArrayOf(9 * d, 7 * d), -phase * 16 * d)
                c.drawLine(mp.x.toFloat(), mp.y.toFloat(), ps.x, ps.y, p); p.pathEffect = null
                val dist = if (ps.onRing) ps.pin.distM
                    else ApLocator.distM(me!!.latitude, me.longitude, ps.pin.est!!.lat, ps.pin.est.lon)
                chip(c, "≈ " + fmtDist(dist), (mp.x + ps.x) / 2, (mp.y + ps.y) / 2 - 10 * d, Color.parseColor("#29B6F6"), 11f)
            }
            // wifi circles with names
            val labelSet = poss.sortedByDescending { it.pin.level }.take(7).map { it.pin.bssid }.toSet()
            for (ps in poss) {
                val col = color(ps.pin.level)
                val isSel = ps.pin.bssid == sel
                badge(c, ps.x, ps.y, col, (if (isSel) 19 else 15) * d, isSel)
                if (isSel || labelSet.contains(ps.pin.bssid)) {
                    var n = ps.pin.ssid; if (n.length > 14) n = n.take(13) + "…"
                    chip(c, "$n  ${ps.pin.level}" + (if (ps.onRing) " ?" else ""), ps.x, ps.y + 20 * d, col, 10f)
                }
                hits.add(Hit(ps.pin.bssid, ps.x, ps.y + 8 * d))
            }
            // You
            if (mp != null) {
                val mx = mp.x.toFloat(); val my = mp.y.toFloat()
                p.style = Paint.Style.FILL; p.shader = RadialGradient(mx, my, 22 * d, Color.argb(190, 41, 121, 255), Color.argb(0, 41, 121, 255), Shader.TileMode.CLAMP)
                c.drawCircle(mx, my, 22 * d + 4 * d * phase, p); p.shader = null
                p.color = Color.WHITE; c.drawCircle(mx, my, 9.5f * d, p)
                p.color = Color.parseColor("#2979FF"); c.drawCircle(mx, my, 7 * d, p)
                chip(c, "You", mx, my + 13 * d, Color.parseColor("#2979FF"), 10f)
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            var best: Hit? = null; var bd = 38 * d
            for (h in hits) { val dd = hypot(h.x - e.x, h.y - e.y); if (dd < bd) { bd = dd; best = h } }
            select(best?.bssid)
            return best != null
        }
    }
}
