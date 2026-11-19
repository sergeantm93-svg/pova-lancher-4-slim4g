package com.pova.launcher

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class AppItem(
    val label: String,
    val pkg: String,
    val info: ResolveInfo,
    val installTime: Long,
    val group: Int
)

class MainActivity : Activity() {

    companion object {
        const val HOME = 0
        const val LIB = 1
        const val DOCK = 2
        const val SHEET = 3
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())
    private val groupNames = arrayOf("الأخيرة", "اجتماعي", "ترفيه", "ألعاب", "إنتاجية", "أدوات")

    private var all: List<AppItem> = emptyList()
    private var dockPkgs: List<String> = emptyList()
    private val shown = ArrayList<AppItem>()
    private var sheetSource: List<AppItem> = emptyList()
    private val jiggles = ArrayList<ObjectAnimator>()

    private var screenW = 0
    private var screenH = 0
    private var statusH = 0
    private var editMode = false
    private var curPage = 0
    private var pageCount = 2
    private var torchOn = false
    private var libFrac = 0f
    private var lastBlur = -1
    private var edgeDir = 0

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastTapT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private lateinit var root: FrameLayout
    private lateinit var pager: Pager
    private lateinit var pagesBox: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var dotsRow: LinearLayout
    private lateinit var pill: TextView
    private lateinit var dockView: LinearLayout
    private lateinit var doneBtn: TextView
    private lateinit var edgeL: FrameLayout
    private lateinit var edgeR: FrameLayout
    private lateinit var sheet: LinearLayout
    private lateinit var sheetSearch: EditText
    private lateinit var sheetTitle: TextView
    private lateinit var webRow: TextView
    private lateinit var sheetAdapter: SheetAdapter
    private lateinit var cc: FrameLayout
    private lateinit var brightBar: SeekBar
    private lateinit var volBar: SeekBar
    private var torchCol: LinearLayout? = null

    private var dateDay: TextView? = null
    private var dateNum: TextView? = null
    private var dateMonth: TextView? = null
    private var ring: RingView? = null
    private var ringText: TextView? = null
    private var ramText: TextView? = null
    private var diskText: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            updateDate()
            updateStats()
            handler.postDelayed(this, 30000)
        }
    }

    private val edgeRun = object : Runnable {
        override fun run() {
            val t = (curPage + edgeDir).coerceIn(0, maxOf(0, pageCount - 2))
            if (t != curPage) pager.goTo(t)
            handler.postDelayed(this, 900)
        }
    }

    private val badgeRun = Runnable {
        if (!editMode && sheet.visibility != View.VISIBLE) rebuildHome()
    }

    // ------------------------------------------------------------ أدوات

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun lpw(w: Int, h: Int, wt: Float) = LinearLayout.LayoutParams(w, h, wt)

    private fun glass(radius: Int, alpha: Int = 46): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.argb(alpha, 255, 255, 255))
        g.cornerRadius = dp(radius).toFloat()
        g.setStroke(dp(1), Color.argb(60, 255, 255, 255))
        return g
    }

    private fun circle(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setShape(GradientDrawable.OVAL)
        g.setColor(color)
        return g
    }

    private fun prefs() = getSharedPreferences("pova", MODE_PRIVATE)

    private fun getList(key: String): MutableList<String> =
        (prefs().getString(key, "") ?: "").split(",").filter { it.isNotEmpty() }.toMutableList()

    private fun putList(key: String, l: List<String>) {
        prefs().edit().putString(key, l.joinToString(",")).apply()
    }

    // ------------------------------------------------------------ الإنشاء

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        screenW = resources.displayMetrics.widthPixels
        screenH = resources.displayMetrics.heightPixels
        val sid = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusH = if (sid > 0) resources.getDimensionPixelSize(sid) else dp(28)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

        root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        root.setBackgroundColor(Color.argb(60, 0, 0, 0))

        // الصفحات
        pager = Pager(this)
        pager.isFillViewport = true
        pagesBox = LinearLayout(this)
        pagesBox.orientation = LinearLayout.HORIZONTAL
        pager.addView(pagesBox, FrameLayout.LayoutParams(WRAP, MATCH))
        pager.onSettle = { p ->
            curPage = p
            buildDots()
        }
        pager.onScroll = { x -> onPagerScroll(x) }
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        // الشريط السفلي: نقاط + بحث + الدوك
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.gravity = Gravity.CENTER_HORIZONTAL

        dotsRow = LinearLayout(this)
        dotsRow.orientation = LinearLayout.HORIZONTAL
        dotsRow.gravity = Gravity.CENTER
        bottomBar.addView(dotsRow, lp(WRAP, dp(14)))

        pill = TextView(this)
        pill.text = "⌕  بحث"
        pill.textSize = 14f
        pill.setTextColor(Color.WHITE)
        pill.gravity = Gravity.CENTER
        pill.background = glass(18, 50)
        pill.setOnClickListener { openSpotlight() }
        val pillLp = lp(dp(140), dp(36))
        pillLp.topMargin = dp(6)
        bottomBar.addView(pill, pillLp)

        dockView = LinearLayout(this)
        dockView.orientation = LinearLayout.HORIZONTAL
        dockView.gravity = Gravity.CENTER
        dockView.background = glass(30, 60)
        dockView.setPadding(dp(10), dp(8), dp(10), dp(8))
        val dockLp = lp(screenW - dp(24), dp(86))
        dockLp.topMargin = dp(10)
        dockLp.bottomMargin = dp(10)
        bottomBar.addView(dockView, dockLp)

        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // حواف السحب أثناء إعادة الترتيب
        edgeL = FrameLayout(this)
        edgeL.visibility = View.GONE
        edgeL.setOnDragListener(edgeListener(-1))
        root.addView(edgeL, FrameLayout.LayoutParams(dp(28), MATCH, Gravity.START))
        edgeR = FrameLayout(this)
        edgeR.visibility = View.GONE
        edgeR.setOnDragListener(edgeListener(1))
        root.addView(edgeR, FrameLayout.LayoutParams(dp(28), MATCH, Gravity.END))

        // زر "تم"
        doneBtn = TextView(this)
        doneBtn.text = "تم"
        doneBtn.textSize = 15f
        doneBtn.typeface = Typeface.DEFAULT_BOLD
        doneBtn.setTextColor(Color.WHITE)
        doneBtn.setPadding(dp(18), dp(8), dp(18), dp(8))
        doneBtn.background = glass(18, 90)
        doneBtn.visibility = View.GONE
        doneBtn.setOnClickListener { setEdit(false) }
        val doneLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        doneLp.topMargin = statusH + dp(8)
        doneLp.marginEnd = dp(16)
        root.addView(doneBtn, doneLp)

        buildSheet()
        root.addView(sheet, FrameLayout.LayoutParams(MATCH, MATCH))

        buildCC()
        root.addView(cc, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        NotifService.onChange = {
            handler.removeCallbacks(badgeRun)
            handler.postDelayed(badgeRun, 400)
        }
        rebuildHome()
        buildDock()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        NotifService.onChange = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(badgeRun)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (cc.visibility == View.VISIBLE) closeCC()
        if (sheet.visibility == View.VISIBLE) closeSheet()
        if (editMode) {
            setEdit(false)
        } else if (curPage != 0) {
            pager.goTo(0)
        }
    }

    override fun onBackPressed() {
        when {
            cc.visibility == View.VISIBLE -> closeCC()
            sheet.visibility == View.VISIBLE -> closeSheet()
            editMode -> setEdit(false)
            curPage != 0 -> pager.goTo(0)
        }
    }

    // ------------------------------------------------------------ البيانات

    private fun groupOf(r: ResolveInfo, pkg: String): Int {
        val cat = if (Build.VERSION.SDK_INT >= 26) r.activityInfo.applicationInfo.category else -1
        return when (cat) {
            ApplicationInfo.CATEGORY_SOCIAL -> 1
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_IMAGE -> 2
            ApplicationInfo.CATEGORY_GAME -> 3
            ApplicationInfo.CATEGORY_PRODUCTIVITY,
            ApplicationInfo.CATEGORY_NEWS -> 4
            else -> {
                val p = pkg.lowercase()
                if (listOf("whatsapp", "telegram", "facebook", "instagram", "messenger", "twitter", "snap", "tiktok", "message", "mms", "contacts", "dialer", "phone").any { p.contains(it) }) {
                    1
                } else if (listOf("camera", "gallery", "photo", "music", "video", "player", "youtube", "netflix", "spotify", "radio").any { p.contains(it) }) {
                    2
                } else if (p.contains("game")) {
                    3
                } else if (listOf("office", "docs", "drive", "calendar", "note", "mail", "pdf", "sheet", "word").any { p.contains(it) }) {
                    4
                } else {
                    5
                }
            }
        }
    }

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(i, 0)
        val items = ArrayList<AppItem>()
        val seen = HashSet<String>()
        for (r in list) {
            val pkg = r.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            val label = r.loadLabel(packageManager).toString()
            val t = try {
                packageManager.getPackageInfo(pkg, 0).firstInstallTime
            } catch (e: Exception) {
                0L
            }
            items.add(AppItem(label, pkg, r, t, groupOf(r, pkg)))
        }
        all = items.sortedBy { it.label.lowercase() }
        dockPkgs = computeDock()

        val installed = all.map { it.pkg }.toSet()
        val hidden = getList("hidden")
        val order = getList("order")
            .filter { installed.contains(it) && !dockPkgs.contains(it) && !hidden.contains(it) }
            .distinct()
            .toMutableList()
        for (a in all) {
            if (!order.contains(a.pkg) && !dockPkgs.contains(a.pkg) && !hidden.contains(a.pkg)) {
                order.add(a.pkg)
            }
        }
        putList("order", order)
    }

    private fun computeDock(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        )
        val res = ArrayList<String>()
        for (it in intents) {
            val r = packageManager.queryIntentActivities(it, 0).firstOrNull() ?: continue
            val p = r.activityInfo.packageName
            if (p != packageName && all.any { a -> a.pkg == p } && !res.contains(p)) res.add(p)
        }
        return res
    }

    // ------------------------------------------------------------ الشاشة الرئيسية

    private fun rebuildHome() {
        for (a in jiggles) a.cancel()
        jiggles.clear()
        pagesBox.removeAllViews()

        val items = getList("order").mapNotNull { p -> all.firstOrNull { it.pkg == p } }
        val cols = 4
        val rowH = dp(94)
        val avail = screenH - statusH - dp(8) - dp(190)
        val ww = (screenW - dp(32) - dp(14)) / 2
        val rows0 = ((avail - ww - dp(14)) / rowH).coerceAtLeast(2)
        val rows = (avail / rowH).coerceIn(3, 6)

        var idx = 0
        var pi = 0
        do {
            val rh = if (pi == 0) rows0 else rows
            val take = minOf(items.size - idx, rh * cols)
            val sub = items.subList(idx, idx + take)
            pagesBox.addView(makePage(sub, idx, pi == 0, rh, rowH, ww), lp(screenW, MATCH))
            idx += take
            pi++
        } while (idx < items.size)

        pagesBox.addView(buildLibrary(), lp(screenW, MATCH))
        pageCount = pi + 1
        if (curPage > pageCount - 1) curPage = pageCount - 1
        buildDots()
        updateDate()
        updateStats()
        pager.post { pager.scrollTo(curPage * screenW, 0) }
    }

    private fun makePage(
        sub: List<AppItem>,
        startIdx: Int,
        widgets: Boolean,
        rows: Int,
        rowH: Int,
        ww: Int
    ): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(16), statusH + dp(8), dp(16), 0)

        if (widgets) {
            val wl = lp(MATCH, ww)
            wl.bottomMargin = dp(14)
            page.addView(buildWidgets(ww), wl)
        }

        val cols = 4
        var k = 0
        for (r in 0 until rows) {
            if (k >= sub.size) break
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until cols) {
                if (k < sub.size) {
                    row.addView(cell(sub[k], true, HOME), lpw(0, rowH, 1f))
                } else {
                    row.addView(View(this), lpw(0, rowH, 1f))
                }
                k++
            }
            page.addView(row, lp(MATCH, rowH))
        }

        page.setOnLongClickListener {
            if (!editMode) showHomeMenu(it)
            true
        }
        page.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> editMode
                DragEvent.ACTION_DROP -> {
                    val p = e.localState as? String
                    if (p != null) {
                        moveTo(p, startIdx + sub.size)
                        handler.post { rebuildHome() }
                    }
                    true
                }
                else -> true
            }
        }
        return page
    }

    private fun cell(a: AppItem, label: Boolean, where: Int): View {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = if (label) Gravity.CENTER_HORIZONTAL else Gravity.CENTER

        val isz = dp(58)
        val box = FrameLayout(this)
        val iv = ImageView(this)
        iv.setImageBitmap(IconMaker.get(this, a.info, a.pkg))
        box.addView(iv, FrameLayout.LayoutParams(isz, isz, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        val n = NotifService.counts[a.pkg] ?: 0
        if (n > 0 && !editMode) {
            val b = TextView(this)
            b.text = if (n > 99) "99+" else n.toString()
            b.textSize = 11f
            b.setTextColor(Color.WHITE)
            b.typeface = Typeface.DEFAULT_BOLD
            b.gravity = Gravity.CENTER
            b.minWidth = dp(20)
            b.setPadding(dp(5), 0, dp(5), 0)
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#FF3B30"))
            bg.cornerRadius = dp(10).toFloat()
            b.background = bg
            box.addView(b, FrameLayout.LayoutParams(WRAP, dp(20), Gravity.TOP or Gravity.END))
        }

        if (editMode && where == HOME) {
            val m = TextView(this)
            m.text = "−"
            m.textSize = 16f
            m.typeface = Typeface.DEFAULT_BOLD
            m.setTextColor(Color.WHITE)
            m.gravity = Gravity.CENTER
            m.background = circle(Color.argb(230, 90, 90, 95))
            m.setOnClickListener { hideFromHome(a.pkg) }
            box.addView(m, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START))
        }
        v.addView(box, lp(isz + dp(14), isz + dp(10)))

        if (label) {
            val t = TextView(this)
            t.text = a.label
            t.setTextColor(Color.WHITE)
            t.textSize = 11f
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            t.gravity = Gravity.CENTER
            t.setShadowLayer(4f, 0f, 1f, Color.argb(170, 0, 0, 0))
            t.setPadding(dp(2), dp(3), dp(2), 0)
            v.addView(t, lp(MATCH, WRAP))
        }

        v.setOnClickListener {
            if (!editMode) launch(a.pkg, box)
        }
        v.setOnLongClickListener {
            if (editMode && where == HOME) {
                startDrag(v, a.pkg)
            } else {
                showAppMenu(v, a.pkg, where)
            }
            true
        }

        if (editMode && where == HOME) {
            val an = ObjectAnimator.ofFloat(v, View.ROTATION, -2f, 2f)
            an.duration = (100 + (a.pkg.hashCode() and 31)).toLong()
            an.repeatCount = ValueAnimator.INFINITE
            an.repeatMode = ValueAnimator.REVERSE
            an.start()
            jiggles.add(an)

            v.setOnDragListener { dv, e ->
                when (e.action) {
                    DragEvent.ACTION_DRAG_STARTED -> editMode
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        dv.scaleX = 1.12f
                        dv.scaleY = 1.12f
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        dv.scaleX = 1f
                        dv.scaleY = 1f
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        dv.scaleX = 1f
                        dv.scaleY = 1f
                        val p = e.localState as? String
                        if (p != null && p != a.pkg) {
                            moveBefore(p, a.pkg)
                            handler.post { rebuildHome() }
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        dv.alpha = 1f
                        true
                    }
                    else -> true
                }
            }
        }
        return v
    }

    private fun startDrag(v: View, pkg: String) {
        val data = ClipData.newPlainText("pkg", pkg)
        v.startDragAndDrop(data, View.DragShadowBuilder(v), pkg, 0)
        v.alpha = 0.25f
    }

    private fun edgeListener(dir: Int) = View.OnDragListener { _, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                edgeDir = dir
                handler.removeCallbacks(edgeRun)
                handler.postDelayed(edgeRun, 500)
                true
            }
            DragEvent.ACTION_DRAG_EXITED,
            DragEvent.ACTION_DRAG_ENDED,
            DragEvent.ACTION_DROP -> {
                handler.removeCallbacks(edgeRun)
                true
            }
            else -> true
        }
    }

    private fun buildDock() {
        dockView.removeAllViews()
        for (p in dockPkgs) {
            val a = all.firstOrNull { it.pkg == p } ?: continue
            dockView.addView(cell(a, false, DOCK), lpw(0, MATCH, 1f))
        }
    }

    private fun buildDots() {
        dotsRow.removeAllViews()
        for (i in 0 until pageCount) {
            val d = View(this)
            d.background = circle(if (i == curPage) Color.WHITE else Color.argb(110, 255, 255, 255))
            val l = lp(dp(7), dp(7))
            l.marginStart = dp(4)
            l.marginEnd = dp(4)
            dotsRow.addView(d, l)
        }
    }

    private fun onPagerScroll(x: Int) {
        val libStart = (pageCount - 1) * screenW
        libFrac = (1f - (libStart - x).toFloat() / screenW).coerceIn(0f, 1f)
        val hide = libFrac > 0.98f
        dockView.alpha = 1f - libFrac
        pill.alpha = 1f - libFrac
        val vis = if (hide) View.GONE else View.VISIBLE
        if (dockView.visibility != vis) {
            dockView.visibility = vis
            pill.visibility = vis
        }
        applyBlur()
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        val overlay = sheet.visibility == View.VISIBLE || cc.visibility == View.VISIBLE
        val r = if (overlay) 70 else (libFrac * 50f).toInt()
        if (r == lastBlur) return
        lastBlur = r
        try {
            window.setBackgroundBlurRadius(r)
        } catch (e: Throwable) {
        }
    }

    // ------------------------------------------------------------ الودجتس

    private fun widgetBg(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.argb(120, 28, 30, 38))
        g.cornerRadius = dp(24).toFloat()
        g.setStroke(dp(1), Color.argb(40, 255, 255, 255))
        return g
    }

    private fun buildWidgets(w: Int): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        // ودجت التاريخ
        val d = LinearLayout(this)
        d.orientation = LinearLayout.VERTICAL
        d.background = widgetBg()
        d.setPadding(dp(16), dp(14), dp(16), dp(14))
        val day = TextView(this)
        day.textSize = 14f
        day.typeface = Typeface.DEFAULT_BOLD
        day.setTextColor(Color.parseColor("#FF453A"))
        val num = TextView(this)
        num.textSize = 52f
        num.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        num.setTextColor(Color.WHITE)
        val mon = TextView(this)
        mon.textSize = 14f
        mon.setTextColor(Color.argb(200, 255, 255, 255))
        d.addView(day, lp(WRAP, WRAP))
        d.addView(num, lp(WRAP, WRAP))
        d.addView(mon, lp(WRAP, WRAP))
        d.setOnClickListener {
            try {
                startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            } catch (e: Exception) {
                toast("لا يوجد تطبيق تقويم")
            }
        }
        dateDay = day
        dateNum = num
        dateMonth = mon
        row.addView(d, lp(w, w))

        // ودجت الحالة
        val s = LinearLayout(this)
        s.orientation = LinearLayout.VERTICAL
        s.gravity = Gravity.CENTER_HORIZONTAL
        s.background = widgetBg()
        s.setPadding(dp(12), dp(12), dp(12), dp(10))
        val rf = FrameLayout(this)
        val rv = RingView(this)
        rf.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))
        val rt = TextView(this)
        rt.textSize = 15f
        rt.typeface = Typeface.DEFAULT_BOLD
        rt.setTextColor(Color.WHITE)
        rt.gravity = Gravity.CENTER
        rf.addView(rt, FrameLayout.LayoutParams(MATCH, MATCH))
        s.addView(rf, lp(dp(70), dp(70)))
        val bl = TextView(this)
        bl.text = "البطارية"
        bl.textSize = 11f
        bl.setTextColor(Color.argb(190, 255, 255, 255))
        bl.setPadding(0, dp(4), 0, dp(4))
        s.addView(bl, lp(WRAP, WRAP))
        val rm = TextView(this)
        rm.textSize = 12f
        rm.setTextColor(Color.WHITE)
        val dk = TextView(this)
        dk.textSize = 12f
        dk.setTextColor(Color.WHITE)
        s.addView(rm, lp(WRAP, WRAP))
        s.addView(dk, lp(WRAP, WRAP))
        ring = rv
        ringText = rt
        ramText = rm
        diskText = dk
        val sl = lp(w, w)
        sl.marginStart = dp(14)
        row.addView(s, sl)

        return row
    }

    private fun updateDate() {
        val now = Date()
        dateDay?.text = SimpleDateFormat("EEEE", Locale("ar")).format(now)
        dateNum?.text = SimpleDateFormat("d", Locale.ENGLISH).format(now)
        dateMonth?.text = SimpleDateFormat("MMMM", Locale("ar")).format(now)
    }

    private fun updateStats() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val rv = ring
        if (rv != null) {
            rv.progress = bat
            rv.ringColor = if (bat <= 20) Color.parseColor("#FF453A") else Color.parseColor("#30D158")
            rv.invalidate()
        }
        ringText?.text = "$bat%"

        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val ram = if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else 0
        ramText?.text = "الذاكرة $ram%"

        val sf = StatFs(Environment.getDataDirectory().path)
        val total = sf.blockCountLong * sf.blockSizeLong
        val free = sf.availableBlocksLong * sf.blockSizeLong
        val disk = if (total > 0) (100 - free * 100 / total).toInt() else 0
        diskText?.text = "التخزين $disk%"
    }

    // ------------------------------------------------------------ مكتبة التطبيقات

    private fun buildLibrary(): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setBackgroundColor(Color.argb(50, 0, 0, 0))
        page.setPadding(dp(16), statusH + dp(8), dp(16), dp(40))

        val title = TextView(this)
        title.text = "مكتبة التطبيقات"
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.WHITE)
        page.addView(title, lp(WRAP, WRAP))

        val bar = TextView(this)
        bar.text = "⌕  بحث"
        bar.textSize = 15f
        bar.setTextColor(Color.argb(200, 255, 255, 255))
        bar.setPadding(dp(16), dp(11), dp(16), dp(11))
        bar.background = glass(22, 50)
        bar.setOnClickListener { openSpotlight() }
        val barLp = lp(MATCH, WRAP)
        barLp.topMargin = dp(10)
        barLp.bottomMargin = dp(14)
        page.addView(bar, barLp)

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val groups = ArrayList<Pair<String, List<AppItem>>>()
        groups.add(Pair(groupNames[0], all.sortedByDescending { it.installTime }.take(4)))
        for (g in 1..5) {
            val l = all.filter { it.group == g }
            if (l.isNotEmpty()) groups.add(Pair(groupNames[g], l))
        }

        val tile = (screenW - dp(32) - dp(16)) / 2
        var i = 0
        while (i < groups.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0..1) {
                if (i + j < groups.size) {
                    val gl = lp(tile, WRAP)
                    if (j == 1) gl.marginStart = dp(16)
                    row.addView(groupTile(groups[i + j].first, groups[i + j].second, tile), gl)
                }
            }
            val rl = lp(MATCH, WRAP)
            rl.bottomMargin = dp(12)
            col.addView(row, rl)
            i += 2
        }
        scroll.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        page.addView(scroll, lpw(MATCH, 0, 1f))
        return page
    }

    private fun iconLp(s: Int, m: Int): GridLayout.LayoutParams {
        val p = GridLayout.LayoutParams()
        p.width = s
        p.height = s
        p.setMargins(m, m, m, m)
        return p
    }

    private fun groupTile(name: String, list: List<AppItem>, size: Int): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL

        val grid = GridLayout(this)
        grid.columnCount = 2
        grid.background = glass(26, 50)
        grid.setPadding(dp(8), dp(8), dp(8), dp(8))
        val m = dp(5)
        val isz = (size - dp(16) - m * 4) / 2
        val show = if (list.size > 4) list.take(3) else list.take(4)
        for (a in show) {
            val iv = ImageView(this)
            iv.setImageBitmap(IconMaker.get(this, a.info, a.pkg))
            iv.setOnClickListener { launch(a.pkg, iv) }
            iv.setOnLongClickListener {
                showAppMenu(iv, a.pkg, LIB)
                true
            }
            grid.addView(iv, iconLp(isz, m))
        }
        if (list.size > 4) {
            val more = TextView(this)
            more.text = "+" + (list.size - 3)
            more.textSize = 18f
            more.typeface = Typeface.DEFAULT_BOLD
            more.setTextColor(Color.WHITE)
            more.gravity = Gravity.CENTER
            more.background = glass(14, 70)
            grid.addView(more, iconLp(isz, m))
        }
        grid.setOnClickListener { openSheet(name, list, false) }
        wrap.addView(grid, lp(size, size))

        val t = TextView(this)
        t.text = name
        t.textSize = 12f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(5), 0, 0)
        wrap.addView(t, lp(MATCH, WRAP))
        return wrap
    }

    // ------------------------------------------------------------ البحث (Spotlight) وقوائم التطبيقات

    private inner class SheetAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            cell(shown[position], true, SHEET)
    }

    private fun buildSheet() {
        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setBackgroundColor(Color.argb(170, 10, 12, 18))
        sheet.setPadding(dp(16), statusH + dp(12), dp(16), 0)
        sheet.isClickable = true
        sheet.visibility = View.GONE

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        sheetSearch = EditText(this)
        sheetSearch.hint = "بحث"
        sheetSearch.setHintTextColor(Color.argb(150, 255, 255, 255))
        sheetSearch.setTextColor(Color.WHITE)
        sheetSearch.setSingleLine()
        sheetSearch.imeOptions = EditorInfo.IME_ACTION_GO
        sheetSearch.background = glass(22, 50)
        sheetSearch.setPadding(dp(18), dp(11), dp(18), dp(11))
        sheetSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterSheet(s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        sheetSearch.setOnEditorActionListener { _, _, _ ->
            val q = sheetSearch.text.toString().trim()
            if (shown.isNotEmpty()) {
                launch(shown[0].pkg, null)
            } else if (q.isNotEmpty()) {
                webSearch(q)
            }
            true
        }
        head.addView(sheetSearch, lpw(0, WRAP, 1f))

        val cancel = TextView(this)
        cancel.text = "إلغاء"
        cancel.textSize = 16f
        cancel.setTextColor(Color.WHITE)
        cancel.setPadding(dp(14), dp(8), dp(4), dp(8))
        cancel.setOnClickListener { closeSheet() }
        head.addView(cancel, lp(WRAP, WRAP))
        sheet.addView(head, lp(MATCH, WRAP))

        sheetTitle = TextView(this)
        sheetTitle.textSize = 22f
        sheetTitle.typeface = Typeface.DEFAULT_BOLD
        sheetTitle.setTextColor(Color.WHITE)
        sheetTitle.setPadding(dp(4), dp(14), 0, dp(4))
        sheet.addView(sheetTitle, lp(MATCH, WRAP))

        webRow = TextView(this)
        webRow.textSize = 15f
        webRow.setTextColor(Color.WHITE)
        webRow.setPadding(dp(14), dp(12), dp(14), dp(12))
        webRow.background = glass(16, 40)
        webRow.visibility = View.GONE
        webRow.setOnClickListener { webSearch(sheetSearch.text.toString().trim()) }
        val wl = lp(MATCH, WRAP)
        wl.topMargin = dp(10)
        sheet.addView(webRow, wl)

        sheetAdapter = SheetAdapter()
        val grid = GridView(this)
        grid.numColumns = 4
        grid.verticalSpacing = dp(8)
        grid.setSelector(android.R.color.transparent)
        grid.adapter = sheetAdapter
        val gl = lpw(MATCH, 0, 1f)
        gl.topMargin = dp(10)
        sheet.addView(grid, gl)
    }

    private fun filterSheet(q: String) {
        shown.clear()
        shown.addAll(sheetSource.filter { it.label.contains(q.trim(), true) })
        sheetAdapter.notifyDataSetChanged()
        if (q.isBlank()) {
            webRow.visibility = View.GONE
        } else {
            webRow.visibility = View.VISIBLE
            webRow.text = "بحث في الويب عن «" + q.trim() + "»"
        }
    }

    private fun webSearch(q: String) {
        if (q.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        } catch (e: Exception) {
            toast("لا يوجد متصفح")
        }
    }

    private fun openSpotlight() {
        openSheet("", all, true)
    }

    private fun openSheet(title: String, src: List<AppItem>, focus: Boolean) {
        sheetSource = src
        sheetTitle.text = title
        sheetTitle.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        sheetSearch.setText("")
        filterSheet("")
        sheet.alpha = 0f
        sheet.visibility = View.VISIBLE
        sheet.animate().alpha(1f).setDuration(180).start()
        applyBlur()
        if (focus) {
            sheetSearch.requestFocus()
            sheetSearch.postDelayed({
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(sheetSearch, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }

    private fun closeSheet() {
        if (sheet.visibility != View.VISIBLE) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(sheetSearch.windowToken, 0)
        sheet.animate().alpha(0f).setDuration(160).withEndAction {
            sheet.visibility = View.GONE
            sheet.alpha = 1f
            applyBlur()
        }.start()
    }

    // ------------------------------------------------------------ مركز التحكم

    private fun ccTile(label: String, glyph: String, action: () -> Unit): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val c = FrameLayout(this)
        c.background = circle(Color.argb(70, 255, 255, 255))
        val g = TextView(this)
        g.text = glyph
        g.textSize = 24f
        g.gravity = Gravity.CENTER
        c.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))
        c.setOnClickListener { action() }
        col.addView(c, lp(dp(60), dp(60)))
        val t = TextView(this)
        t.text = label
        t.textSize = 11f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.setPadding(0, dp(4), 0, 0)
        col.addView(t, lp(MATCH, WRAP))
        return col
    }

    private fun ccRow(vararg tiles: View): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.background = glass(28, 50)
        card.setPadding(dp(10), dp(14), dp(10), dp(10))
        for (t in tiles) card.addView(t, lpw(0, WRAP, 1f))
        return card
    }

    private fun ccSlider(icon: String, bar: SeekBar): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = glass(28, 50)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val i = TextView(this)
        i.text = icon
        i.textSize = 20f
        card.addView(i, lp(WRAP, WRAP))
        bar.progressTintList = ColorStateList.valueOf(Color.WHITE)
        bar.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        card.addView(bar, lpw(0, WRAP, 1f))
        return card
    }

    private fun go(i: Intent) {
        try {
            closeCC()
            startActivity(i)
        } catch (e: Exception) {
            toast("غير متاح على هذا الجهاز")
        }
    }

    private fun buildCC() {
        cc = FrameLayout(this)
        cc.setBackgroundColor(Color.argb(90, 0, 0, 0))
        cc.visibility = View.GONE
        cc.setOnClickListener { closeCC() }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.isClickable = true

        val wifi = ccTile("الإنترنت", "📶") {
            if (Build.VERSION.SDK_INT >= 29) {
                go(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            } else {
                go(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }
        val bt = ccTile("بلوتوث", "🔷") { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        val data = ccTile("البيانات", "📡") { go(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }
        val plane = ccTile("الطيران", "✈️") { go(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
        box.addView(ccRow(wifi, bt, data, plane), lp(MATCH, WRAP))

        val torch = ccTile("الكشاف", "🔦") { toggleTorch() }
        torchCol = torch
        val calc = ccTile("الحاسبة", "🧮") {
            go(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
        }
        val cam = ccTile("الكاميرا", "📷") { go(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
        val clock = ccTile("المنبه", "⏱") { go(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        val r2 = lp(MATCH, WRAP)
        r2.topMargin = dp(12)
        box.addView(ccRow(torch, calc, cam, clock), r2)

        brightBar = SeekBar(this)
        brightBar.max = 255
        brightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setBrightness(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val r3 = lp(MATCH, WRAP)
        r3.topMargin = dp(12)
        box.addView(ccSlider("☀", brightBar), r3)

        volBar = SeekBar(this)
        volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val r4 = lp(MATCH, WRAP)
        r4.topMargin = dp(12)
        box.addView(ccSlider("🔊", volBar), r4)

        val bl = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
        bl.setMargins(dp(16), statusH + dp(16), dp(16), 0)
        cc.addView(box, bl)
    }

    private fun paintTorch() {
        torchCol?.getChildAt(0)?.background =
            circle(if (torchOn) Color.WHITE else Color.argb(70, 255, 255, 255))
    }

    private fun toggleTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            paintTorch()
        } catch (e: Exception) {
            toast("تعذّر تشغيل الكشاف")
        }
    }

    private fun setBrightness(p: Int) {
        if (!Settings.System.canWrite(this)) {
            toast("اسمح للانشر بتعديل إعدادات النظام ثم أعد المحاولة")
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        Settings.System.putInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, p.coerceIn(1, 255))
    }

    private fun openCC() {
        if (cc.visibility == View.VISIBLE) return
        try {
            brightBar.progress = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) {
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volBar.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volBar.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        paintTorch()
        cc.alpha = 0f
        cc.visibility = View.VISIBLE
        cc.animate().alpha(1f).setDuration(180).start()
        applyBlur()
    }

    private fun closeCC() {
        if (cc.visibility != View.VISIBLE) return
        cc.animate().alpha(0f).setDuration(160).withEndAction {
            cc.visibility = View.GONE
            cc.alpha = 1f
            applyBlur()
        }.start()
    }

    // ------------------------------------------------------------ الإيماءات

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downT = ev.eventTime
            }
            MotionEvent.ACTION_UP -> handleGesture(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleGesture(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        val dt = ev.eventTime - downT

        if (cc.visibility == View.VISIBLE) {
            if (dy < -dp(60) && abs(dy) > abs(dx)) closeCC()
            return
        }
        if (sheet.visibility == View.VISIBLE) return
        if (editMode) return

        if (dy > dp(90) && abs(dy) > abs(dx) * 1.5f) {
            val topBand = downY < statusH + dp(90)
            if (topBand) {
                if (downX > screenW * 0.6f) openCC() else expandNotifications()
            } else {
                openSpotlight()
            }
            return
        }

        if (abs(dx) < dp(12) && abs(dy) < dp(12) && dt < 220) {
            val isDouble = ev.eventTime - lastTapT < 300 &&
                abs(ev.x - lastTapX) < dp(40) && abs(ev.y - lastTapY) < dp(40)
            if (isDouble) {
                lastTapT = 0
                lockScreen()
            } else {
                lastTapT = ev.eventTime
                lastTapX = ev.x
                lastTapY = ev.y
            }
        }
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(sb)
        } catch (e: Throwable) {
            val s = LockService.instance
            if (s != null) {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            } else {
                toast("فعّل خدمة القفل من القائمة (ضغطة مطولة على الشاشة)")
            }
        }
    }

    private fun lockScreen() {
        val s = LockService.instance
        if (s != null && Build.VERSION.SDK_INT >= 28) {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            toast("فعّل القفل بالنقر المزدوج من القائمة (ضغطة مطولة على الشاشة)")
        }
    }

    // ------------------------------------------------------------ القوائم والتعديل

    private fun showHomeMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "تعديل الشاشة الرئيسية")
        m.menu.add(0, 2, 0, "تغيير الخلفية")
        m.menu.add(0, 3, 0, "تفعيل شارات الإشعارات")
        m.menu.add(0, 4, 0, "تفعيل القفل بالنقر المزدوج")
        m.menu.add(0, 5, 0, "السماح بتعديل السطوع")
        m.menu.add(0, 6, 0, "إعدادات النظام")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> setEdit(true)
                2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "الخلفية"))
                3 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                4 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                5 -> startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                6 -> startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            true
        }
        m.show()
    }

    private fun showAppMenu(anchor: View, pkg: String, where: Int) {
        val isHidden = getList("hidden").contains(pkg)
        val m = PopupMenu(this, anchor)
        if (where == HOME) {
            m.menu.add(0, 1, 0, "تعديل الشاشة الرئيسية")
            m.menu.add(0, 2, 0, "إزالة من الشاشة الرئيسية")
        } else if (isHidden && where != DOCK) {
            m.menu.add(0, 3, 0, "إضافة إلى الشاشة الرئيسية")
        }
        m.menu.add(0, 4, 0, "معلومات التطبيق")
        m.menu.add(0, 5, 0, "حذف التطبيق")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> setEdit(true)
                2 -> hideFromHome(pkg)
                3 -> unhide(pkg)
                4 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                )
                5 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            }
            true
        }
        m.show()
    }

    private fun setEdit(on: Boolean) {
        if (editMode == on) return
        editMode = on
        val vis = if (on) View.VISIBLE else View.GONE
        doneBtn.visibility = vis
        edgeL.visibility = vis
        edgeR.visibility = vis
        if (!on) handler.removeCallbacks(edgeRun)
        rebuildHome()
    }

    private fun moveTo(pkg: String, index: Int) {
        val l = getList("order")
        val old = l.indexOf(pkg)
        if (old < 0) return
        l.removeAt(old)
        var i = index
        if (old < index) i -= 1
        l.add(i.coerceIn(0, l.size), pkg)
        putList("order", l)
    }

    private fun moveBefore(pkg: String, target: String) {
        val idx = getList("order").indexOf(target)
        if (idx >= 0) moveTo(pkg, idx)
    }

    private fun hideFromHome(pkg: String) {
        val h = getList("hidden")
        if (!h.contains(pkg)) h.add(pkg)
        putList("hidden", h)
        val o = getList("order")
        o.remove(pkg)
        putList("order", o)
        rebuildHome()
    }

    private fun unhide(pkg: String) {
        val h = getList("hidden")
        h.remove(pkg)
        putList("hidden", h)
        val o = getList("order")
        if (!o.contains(pkg)) o.add(pkg)
        putList("order", o)
        rebuildHome()
        toast("تمت الإضافة إلى الشاشة الرئيسية")
    }

    private fun launch(pkg: String, from: View?) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            if (from != null) {
                val o = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
                startActivity(i, o.toBundle())
            } else {
                startActivity(i)
            }
        } catch (e: Exception) {
            toast("تعذّر فتح التطبيق")
        }
        if (sheet.visibility == View.VISIBLE) closeSheet()
    }
}
