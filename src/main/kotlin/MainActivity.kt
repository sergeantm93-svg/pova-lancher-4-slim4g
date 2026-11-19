package com.pova.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView

class MainActivity : Activity() {

    private class AppItem(val label: String, val info: ResolveInfo)

    private val accent = Color.parseColor("#FF6A3D")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var all: List<AppItem> = emptyList()
    private val shown = ArrayList<AppItem>()
    private var downY = 0f

    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var dock: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var batteryBar: ProgressBar
    private lateinit var adapter: AppAdapter

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun glass(radius: Int) = GradientDrawable().apply {
        setColor(Color.argb(46, 255, 255, 255))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(70, 255, 255, 255))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(110, 8, 10, 16))

        val home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        home.setPadding(dp(20), dp(36), dp(20), dp(20))
        home.isClickable = true
        home.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) downY = e.y
            if (e.action == MotionEvent.ACTION_UP && downY - e.y > dp(80)) openDrawer()
            false
        }

        val time = TextClock(this)
        time.format12Hour = "H:mm"
        time.format24Hour = "H:mm"
        time.textSize = 64f
        time.setTextColor(Color.WHITE)
        time.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        home.addView(time)

        val date = TextClock(this)
        date.format12Hour = "EEEE d MMMM"
        date.format24Hour = "EEEE d MMMM"
        date.textSize = 16f
        date.setTextColor(Color.argb(200, 255, 255, 255))
        home.addView(date)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(14), dp(18), dp(14))
        card.background = glass(24)
        batteryText = TextView(this)
        batteryText.setTextColor(Color.WHITE)
        batteryText.textSize = 15f
        batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        batteryBar.max = 100
        batteryBar.progressTintList = ColorStateList.valueOf(accent)
        card.addView(batteryText)
        card.addView(batteryBar, LinearLayout.LayoutParams(MATCH, dp(10)).apply { topMargin = dp(8) })
        home.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })

        home.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val hint = TextView(this)
        hint.text = "اسحب للأعلى لكل التطبيقات"
        hint.gravity = Gravity.CENTER
        hint.textSize = 12f
        hint.setTextColor(Color.argb(170, 255, 255, 255))
        hint.setPadding(0, dp(8), 0, dp(12))
        hint.setOnClickListener { openDrawer() }
        home.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.background = glass(28)
        dock.setPadding(dp(8), dp(8), dp(8), dp(8))
        home.addView(dock, LinearLayout.LayoutParams(MATCH, WRAP))

        drawer = LinearLayout(this)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.setBackgroundColor(Color.argb(240, 14, 16, 22))
        drawer.setPadding(dp(16), dp(40), dp(16), 0)
        drawer.visibility = View.GONE

        search = EditText(this)
        search.hint = "ابحث في التطبيقات"
        search.setHintTextColor(Color.argb(150, 255, 255, 255))
        search.setTextColor(Color.WHITE)
        search.setSingleLine()
        search.background = glass(24)
        search.setPadding(dp(18), dp(12), dp(18), dp(12))
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        drawer.addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val grid = GridView(this)
        grid.numColumns = 4
        grid.verticalSpacing = dp(8)
        adapter = AppAdapter()
        grid.adapter = adapter
        drawer.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        filter(search.text.toString())
        buildDock()
        updateBattery()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeDrawer()
    }

    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        all = packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppItem(it.loadLabel(packageManager).toString(), it) }
            .sortedBy { it.label.lowercase() }
    }

    private fun filter(q: String) {
        shown.clear()
        shown.addAll(all.filter { it.label.contains(q.trim(), ignoreCase = true) })
        adapter.notifyDataSetChanged()
    }

    private fun buildDock() {
        dock.removeAllViews()
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        )
        val seen = HashSet<String>()
        for (i in intents) {
            val r = packageManager.queryIntentActivities(i, 0).firstOrNull() ?: continue
            val pkg = r.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            dock.addView(
                cell(r.loadLabel(packageManager).toString(), r, false),
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
        }
    }

    private fun updateBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryText.text = "البطارية  $p%"
        batteryBar.progress = p
    }

    private fun cell(label: String, info: ResolveInfo, showLabel: Boolean): View {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER
        v.setPadding(dp(4), dp(8), dp(4), dp(8))
        val iv = ImageView(this)
        iv.setImageDrawable(info.loadIcon(packageManager))
        v.addView(iv, LinearLayout.LayoutParams(dp(52), dp(52)))
        if (showLabel) {
            val t = TextView(this)
            t.text = label
            t.setTextColor(Color.WHITE)
            t.textSize = 11f
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(4), 0, 0)
            v.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        v.setOnClickListener {
            packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
                ?.let { startActivity(it) }
        }
        return v
    }

    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE) return
        search.setText("")
        drawer.visibility = View.VISIBLE
        drawer.translationY = resources.displayMetrics.heightPixels.toFloat()
        drawer.animate().translationY(0f).setDuration(250).start()
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        drawer.animate()
            .translationY(resources.displayMetrics.heightPixels.toFloat())
            .setDuration(200)
            .withEndAction { drawer.visibility = View.GONE }
            .start()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            cell(shown[position].label, shown[position].info, true)
    }
}
