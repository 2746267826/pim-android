package com.pim.locationtest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var originalState: TextView
    private lateinit var originalReason: TextView
    private lateinit var proposedState: TextView
    private lateinit var proposedReason: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var metrics: TextView
    private lateinit var feedback: TextView
    private lateinit var timeline: ModeTimelineView
    private lateinit var candidateTimeline: ModeTimelineView
    private lateinit var startButton: Button
    private lateinit var replayButtons: List<Button>
    private var replayJob: Runnable? = null

    private val refresh = object : Runnable {
        override fun run() {
            render()
            uiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildScreen())
        render()
        uiHandler.post(refresh)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(refresh)
        replayJob?.let(uiHandler::removeCallbacks)
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            clipToPadding = false
            setPadding(
                dp(16),
                systemBarHeight("status_bar_height") + dp(14),
                dp(16),
                systemBarHeight("navigation_bar_height") + dp(24)
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        content.addView(text("高速定位算法对照", 24f, COLOR_TEXT, bold = true))
        content.addView(text("同一组定位样本并行运行原算法与候选算法", 14f, COLOR_MUTED).apply {
            setPadding(0, dp(4), 0, dp(6))
        })
        sourceLabel = text("等待开始", 13f, COLOR_MUTED)
        content.addView(sourceLabel)

        content.addView(sectionTitle("判定结果"))
        val comparisonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        originalState = text("普通策略", 18f, COLOR_TEXT, bold = true)
        originalReason = text("≥8 km/h 持续 10 秒进入；低于 7 km/h 或无速度持续 60 秒退出", 12f, COLOR_MUTED)
        proposedState = text("未激活", 18f, COLOR_TEXT, bold = true)
        proposedReason = text("等待高速速度证据", 12f, COLOR_MUTED)
        comparisonRow.addView(
            panel("原算法", "GPS 速度 + 原有计时", originalState, originalReason, COLOR_ORIGINAL),
            weightedParams(1f, end = 6)
        )
        comparisonRow.addView(
            panel("候选算法", "速度 + 连续位移佐证", proposedState, proposedReason, COLOR_PROPOSED),
            weightedParams(1f, start = 6)
        )
        content.addView(comparisonRow)

        content.addView(sectionTitle("当前样本"))
        metrics = text("尚无定位样本", 14f, COLOR_TEXT)
        content.addView(card(metrics))

        content.addView(sectionTitle("最近状态变化"))
        val timelineColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        timelineColumn.addView(text("原算法", 12f, COLOR_MUTED))
        timeline = ModeTimelineView(this, original = true)
        timelineColumn.addView(timeline, LinearLayout.LayoutParams(-1, dp(27)))
        timelineColumn.addView(text("候选算法", 12f, COLOR_MUTED).apply { setPadding(0, dp(6), 0, 0) })
        candidateTimeline = ModeTimelineView(this, original = false)
        timelineColumn.addView(candidateTimeline, LinearLayout.LayoutParams(-1, dp(27)))
        timelineColumn.addView(text("灰色 未激活　琥珀色 验证中　绿/红 已激活", 11f, COLOR_MUTED))
        content.addView(card(timelineColumn))

        content.addView(sectionTitle("实测"))
        feedback = text("回放只生成模拟点，不访问定位、不联网。实时测试会持续采样，锁屏也可运行。", 13f, COLOR_MUTED)
        content.addView(feedback.apply { setPadding(0, 0, 0, dp(8)) })
        startButton = button("开始实时采样", COLOR_PROPOSED) { onLiveToggle() }
        content.addView(startButton, matchParams(bottom = 8))

        val firstReplayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stationaryReplay = button("静止速度毛刺", COLOR_SECONDARY) {
            runReplay("回放：静止速度毛刺", stationaryOutliers())
        }
        val movingReplay = button("真实高速后停车", COLOR_SECONDARY) {
            runReplay("回放：高速移动后停车", movingThenStop())
        }
        firstReplayRow.addView(stationaryReplay, weightedParams(1f, end = 4))
        firstReplayRow.addView(movingReplay, weightedParams(1f, start = 4))
        content.addView(firstReplayRow, matchParams(bottom = 8))

        val secondReplayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cleanReplay = button("持续高速", COLOR_SECONDARY) {
            runReplay("回放：持续高速移动", movingFast())
        }
        val exportButton = button("导出 CSV", COLOR_DARK) { exportCsv() }
        secondReplayRow.addView(cleanReplay, weightedParams(1f, end = 4))
        secondReplayRow.addView(exportButton, weightedParams(1f, start = 4))
        content.addView(secondReplayRow, matchParams(bottom = 8))

        val resetButton = button("清除本次数据", COLOR_OUTLINE) { resetRun() }
        content.addView(resetButton, matchParams())

        content.addView(sectionTitle("判定参数"))
        content.addView(card(text(
            "原算法：≥8 km/h 连续 10 秒进入；<7 km/h 或无速度连续 60 秒退出。\n" +
                "候选算法：水平精度 <30 m；速度精度 ≤2 m/s，或用位置推算速度；≥4 个点、至少 8 秒、位移不少于 max(20 m, 2×水平精度)、方向一致度 ≥0.55；持续 10 秒进入。\n" +
                "候选退出：20 秒静止点簇、可信低速持续 10 秒，或 25 秒没有位移佐证。参数用于对照实测，尚未接入正式采集。",
            12f,
            COLOR_TEXT
        )))
        content.addView(text("位置坐标仅在内存中用于位移判定；CSV 不含坐标，应用不向业务服务上传样本。系统定位提供方可能使用网络辅助定位。", 11f, COLOR_MUTED).apply {
            setPadding(dp(2), dp(12), dp(2), 0)
        })

        replayButtons = listOf(stationaryReplay, movingReplay, cleanReplay)
        return scroll
    }

    private fun panel(
        title: String,
        subtitle: String,
        state: TextView,
        reason: TextView,
        accent: Int
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_SURFACE, 10, accent)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(text(title, 15f, COLOR_TEXT, bold = true))
        addView(text(subtitle, 11f, COLOR_MUTED).apply { setPadding(0, dp(3), 0, dp(12)) })
        addView(state)
        addView(reason.apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun card(child: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_SURFACE, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(child)
    }

    private fun sectionTitle(value: String): TextView = text(value, 16f, COLOR_TEXT, bold = true).apply {
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START
            includeFontPadding = true
        }

    private fun button(label: String, backgroundColor: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(if (backgroundColor == COLOR_OUTLINE) COLOR_TEXT else COLOR_ON_ACCENT)
        background = rounded(backgroundColor, 9)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun render() {
        if (!::originalState.isInitialized) return
        val snapshot = DemoRuntime.snapshot()
        sourceLabel.text = buildString {
            append(snapshot.source)
            append(" · ")
            append(if (snapshot.running) "采样运行中" else "未采样")
            append(" · ${snapshot.sampleCount} 点")
            snapshot.issue?.let { append(" · $it") }
        }

        originalState.text = when (snapshot.legacyMode) {
            LegacyMode.Inactive -> "普通策略"
            LegacyMode.Accumulating -> "进入累计中"
            LegacyMode.Active -> "高速密集档"
        }
        originalState.setTextColor(if (snapshot.legacyMode == LegacyMode.Active) COLOR_ORIGINAL else COLOR_TEXT)
        originalReason.text = when (snapshot.legacyMode) {
            LegacyMode.Inactive -> "未达到原高速阈值"
            LegacyMode.Accumulating -> "GPS 速度 ≥8 km/h，等待 10 秒确认"
            LegacyMode.Active -> "GPS 速度高于退出阈值，维持 2.5 秒采样档"
        }

        proposedState.text = when (snapshot.proposedMode) {
            ProposedMode.Inactive -> "未激活"
            ProposedMode.Verifying -> "验证中"
            ProposedMode.Active -> "高速密集档"
        }
        proposedState.setTextColor(when (snapshot.proposedMode) {
            ProposedMode.Inactive -> COLOR_TEXT
            ProposedMode.Verifying -> COLOR_VERIFY
            ProposedMode.Active -> COLOR_PROPOSED
        })
        proposedReason.text = snapshot.proposedReason

        val latest = snapshot.latest
        metrics.text = if (latest == null) {
            snapshot.issue ?: "尚无定位样本"
        } else {
            val fix = latest.sample
            val speedText = fix.speedMetersPerSecond?.let { "%.1f km/h".format(java.util.Locale.US, it * 3.6f) } ?: "无"
            val speedAccuracyText = fix.speedAccuracyMetersPerSecond?.let { "±%.1f m/s".format(java.util.Locale.US, it) } ?: "无"
            val horizontalText = fix.horizontalAccuracyMeters?.let { "±%.0f m".format(java.util.Locale.US, it) } ?: "无"
            val derivedText = latest.derivedSpeedMetersPerSecond?.let { "%.1f km/h".format(java.util.Locale.US, it * 3.6) } ?: "计算中"
            val update = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            "GPS 速度　$speedText\n速度精度　$speedAccuracyText\n水平精度　$horizontalText\n\n" +
                "候选位移　%.1f m\n位置推算　%s\n佐证点数　%d\n最近回调　%s".format(
                    java.util.Locale.getDefault(), latest.displacementMeters, derivedText, latest.evidenceFixCount, update
                )
        }
        startButton.text = if (snapshot.running) "停止实时采样" else "开始实时采样"
        startButton.setBackgroundColor(if (snapshot.running) COLOR_STOP else COLOR_PROPOSED)
        replayButtons.forEach { it.isEnabled = !snapshot.running }
        timeline.records = snapshot.records
        timeline.invalidate()
        candidateTimeline.records = snapshot.records
        candidateTimeline.invalidate()

        val latestRow = snapshot.latest
        feedback.text = when {
            snapshot.running -> "实时定位前台服务运行中。可以锁屏测试；采样保持在本机。"
            latestRow != null && snapshot.source.startsWith("回放") -> "回放完成或正在播放。观察两条状态轨迹与候选算法给出的退出原因。"
            snapshot.issue != null -> snapshot.issue
            else -> "回放只生成模拟点，不访问定位、不联网。实时测试会持续采样，锁屏也可运行。"
        }
    }

    private fun onLiveToggle() {
        val snapshot = DemoRuntime.snapshot()
        if (snapshot.running) {
            stopService(Intent(this, LocationTestService::class.java))
            DemoRuntime.setRunning(false)
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        replayJob?.let(uiHandler::removeCallbacks)
        DemoRuntime.begin("实时定位", isRunning = true)
        val intent = Intent(this, LocationTestService::class.java).setAction(LocationTestService.ACTION_START)
        try {
            startForegroundService(intent)
        } catch (error: Exception) {
            DemoRuntime.setRunning(false)
            DemoRuntime.setIssue(error.message ?: "无法启动定位服务")
        }
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            onLiveToggle()
        } else {
            DemoRuntime.setIssue("需要精确定位权限才能进行实时对照")
            render()
        }
    }

    private fun runReplay(name: String, samples: List<LocationSample>) {
        stopService(Intent(this, LocationTestService::class.java))
        replayJob?.let(uiHandler::removeCallbacks)
        DemoRuntime.begin(name, isRunning = false)
        feedback.text = "正在回放 ${samples.size} 个模拟定位点…"
        var index = 0
        val job = object : Runnable {
            override fun run() {
                if (index >= samples.size) {
                    replayJob = null
                    render()
                    return
                }
                DemoRuntime.accept(samples[index++])
                render()
                uiHandler.postDelayed(this, REPLAY_INTERVAL_MILLIS)
            }
        }
        replayJob = job
        uiHandler.post(job)
    }

    private fun stationaryOutliers(): List<LocationSample> {
        val start = SystemClock.elapsedRealtime()
        return (0 until 24).map { index ->
            sample(start + index * SIMULATED_FIX_INTERVAL_MILLIS, 0.0, 3.0f, 0.4f)
        }
    }

    private fun movingFast(): List<LocationSample> {
        val start = SystemClock.elapsedRealtime()
        return (0 until 20).map { index ->
            sample(start + index * SIMULATED_FIX_INTERVAL_MILLIS, index * 7.4, 2.9f, 0.4f)
        }
    }

    private fun movingThenStop(): List<LocationSample> {
        val start = SystemClock.elapsedRealtime()
        val movingCount = 16
        val travel = (0 until movingCount).map { index ->
            sample(start + index * SIMULATED_FIX_INTERVAL_MILLIS, index * 7.4, 2.9f, 0.4f)
        }
        val stoppedAtMeters = (movingCount - 1) * 7.4
        val stopped = (0 until 16).map { index ->
            sample(
                start + (movingCount + index) * SIMULATED_FIX_INTERVAL_MILLIS,
                stoppedAtMeters,
                3.0f,
                0.4f
            )
        }
        return travel + stopped
    }

    private fun sample(at: Long, eastMeters: Double, speed: Float, speedAccuracy: Float?): LocationSample =
        LocationSample(
            elapsedRealtimeMillis = at,
            latitude = DEMO_LATITUDE,
            longitude = DEMO_LONGITUDE + eastMeters / METERS_PER_DEGREE_LONGITUDE,
            horizontalAccuracyMeters = 5f,
            speedMetersPerSecond = speed,
            speedAccuracyMetersPerSecond = speedAccuracy
        )

    private fun resetRun() {
        stopService(Intent(this, LocationTestService::class.java))
        replayJob?.let(uiHandler::removeCallbacks)
        replayJob = null
        DemoRuntime.begin("已清除", isRunning = false)
        render()
    }

    private fun exportCsv() {
        val csv = DemoRuntime.csv()
        if (DemoRuntime.snapshot().sampleCount == 0) {
            Toast.makeText(this, "当前没有可导出的定位样本", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "定位高速判定对照")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(send, "分享对照数据（不含坐标）"))
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(2), it) }
        }

    private fun matchParams(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        if (bottom > 0) bottomMargin = dp(bottom)
    }

    private fun weightedParams(weight: Float, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, -2, weight).apply {
            if (start > 0) marginStart = dp(start)
            if (end > 0) marginEnd = dp(end)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun systemBarHeight(name: String): Int {
        val resource = resources.getIdentifier(name, "dimen", "android")
        return if (resource == 0) 0 else resources.getDimensionPixelSize(resource)
    }

    private class ModeTimelineView(
        context: android.content.Context,
        private val original: Boolean
    ) : View(context) {
        var records: List<ComparisonRecord> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val density = resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rows = records.takeLast(MAX_TIMELINE_RECORDS)
            val left = 1f * density
            val right = width - left
            val top = 8f * density
            drawLane(canvas, left, right, top, 16f * density, rows)
        }

        private fun drawLane(
            canvas: Canvas,
            left: Float,
            right: Float,
            top: Float,
            height: Float,
            rows: List<ComparisonRecord>
        ) {
            paint.color = COLOR_TRACK
            rect.set(left, top, right, top + height)
            canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
            if (rows.isEmpty()) return
            val itemWidth = (right - left) / rows.size
            rows.forEachIndexed { index, row ->
                val color = if (original) {
                    if (row.originalMode == LegacyMode.Active) COLOR_ORIGINAL else COLOR_TRACK
                } else {
                    when (row.proposedMode) {
                        ProposedMode.Inactive -> COLOR_TRACK
                        ProposedMode.Verifying -> COLOR_VERIFY
                        ProposedMode.Active -> COLOR_PROPOSED
                    }
                }
                if (color == COLOR_TRACK) return@forEachIndexed
                paint.color = color
                val x = left + index * itemWidth
                rect.set(x, top, (x + itemWidth + 1f).coerceAtMost(right), top + height)
                canvas.drawRect(rect, paint)
            }
        }

        companion object {
            private const val MAX_TIMELINE_RECORDS = 180
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 5102
        private const val REPLAY_INTERVAL_MILLIS = 240L
        private const val SIMULATED_FIX_INTERVAL_MILLIS = 2_500L
        private const val DEMO_LATITUDE = 31.2304
        private const val DEMO_LONGITUDE = 121.4737
        private const val METERS_PER_DEGREE_LONGITUDE = 95_300.0
        private const val COLOR_BACKGROUND = 0xFFF1F4F3.toInt()
        private const val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT = 0xFF202C32.toInt()
        private const val COLOR_MUTED = 0xFF627179.toInt()
        private const val COLOR_ORIGINAL = 0xFFC45A45.toInt()
        private const val COLOR_PROPOSED = 0xFF197A65.toInt()
        private const val COLOR_VERIFY = 0xFFB87818.toInt()
        private const val COLOR_SECONDARY = 0xFF345C68.toInt()
        private const val COLOR_DARK = 0xFF36434A.toInt()
        private const val COLOR_STOP = 0xFF9C4135.toInt()
        private const val COLOR_OUTLINE = 0xFFE3E9E7.toInt()
        private const val COLOR_ON_ACCENT = 0xFFFFFFFF.toInt()
        private const val COLOR_TRACK = 0xFFE3E9E7.toInt()
    }
}
