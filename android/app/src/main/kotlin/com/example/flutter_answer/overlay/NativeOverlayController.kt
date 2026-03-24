package com.example.flutter_answer.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 原生悬浮窗的唯一状态源。
 *
 * 设计上把拖动、吸边、收起/展开都放在 Android 主线程完成，
 * 避免再经由 Flutter/MethodChannel 做高频往返，减少卡顿与位置回写竞态。
 */
object NativeOverlayController {

    private const val modeCollapsed = "collapsed"
    private const val modeExpanded = "expanded"
    private const val collapsedWidthDp = 72
    private const val collapsedHeightDp = 72
    private const val expandedWidthDp = 288
    private const val expandedHeightDp = 272
    private const val edgeMarginDp = 12f

    private const val colorTeal = 0xFF0C655F.toInt()
    private const val colorAmber = 0xFFE4A646.toInt()
    private const val colorGreen = 0xFF2E7D32.toInt()
    private const val colorRed = 0xFFC62828.toInt()
    private const val colorInk = 0xFF1F2937.toInt()
    private const val colorSubtle = 0xFF6B7280.toInt()
    private const val colorPanel = 0xF7FFFFFF.toInt()
    private const val colorOutline = 0x1F0F172A

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null

    private var overlayRoot: FrameLayout? = null
    private var collapsedBubble: FrameLayout? = null
    private var expandedPanel: LinearLayout? = null
    private var bubbleFaceView: TextView? = null
    private var bubbleIndicatorView: View? = null
    private var accessibilityValueView: TextView? = null
    private var runStateValueView: TextView? = null
    private var latestLogValueView: TextView? = null
    private var startActionView: TextView? = null
    private var stopActionView: TextView? = null

    private var layoutParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null

    private var isActive = false
    private var currentMode = modeExpanded
    private var positionXPx = 0f
    private var positionYPx = 0f

    private var serviceStatus: Map<String, Any?> = emptyMap()
    private var agentConfig: Map<String, Any?> = emptyMap()
    private var agentRunStatus: Map<String, Any?> = emptyMap()
    private var diagnosticHint: Map<String, Any?> = emptyMap()
    private var lastPayload = "{}"
    private var logs: List<String> = emptyList()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        windowManager = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (positionXPx == 0f && positionYPx == 0f) {
            positionXPx = dpToPx(edgeMarginDp)
            positionYPx = dpToPx(120f)
        }
    }

    fun isActive(): Boolean = isActive

    fun snapshot(): Map<String, Any?> {
        val size = sizeForMode(currentMode)
        return mapOf(
            "mode" to currentMode,
            "position" to mapOf(
                "x" to pxToDp(positionXPx),
                "y" to pxToDp(positionYPx),
            ),
            "width" to pxToDp(size.width.toFloat()),
            "height" to pxToDp(size.height.toFloat()),
            "active" to isActive,
        )
    }

    fun showOverlay(mode: String, logicalX: Double, logicalY: Double) {
        runOnMain {
            val context = appContext ?: return@runOnMain
            val manager = windowManager ?: return@runOnMain
            ensureOverlayView(context)
            snapAnimator?.cancel()

            currentMode = normalizeMode(mode)
            positionXPx = dpToPx(logicalX.toFloat())
            positionYPx = dpToPx(logicalY.toFloat())
            clampPositionInPlace(sizeForMode(currentMode))
            updateLayout(manager)
            renderOverlayUi()

            val wasActive = isActive
            isActive = true
            if (!wasActive) {
                OverlayEventBus.send(mapOf("event" to "overlay_shown"))
            }
            notifyOverlayStateChanged()
        }
    }

    fun hideOverlay(notify: Boolean) {
        runOnMain {
            snapAnimator?.cancel()
            val manager = windowManager
            val root = overlayRoot
            if (manager != null && root != null && root.isAttachedToWindow) {
                try {
                    manager.removeView(root)
                } catch (_: IllegalArgumentException) {
                    // 视图可能已经被系统移除，这里直接吞掉避免二次清理崩溃。
                }
            }
            if (isActive && notify) {
                OverlayEventBus.send(mapOf("event" to "overlay_closed"))
            }
            isActive = false
            notifyOverlayStateChanged()
        }
    }

    fun moveOverlay(logicalX: Double, logicalY: Double, notify: Boolean = true) {
        runOnMain {
            positionXPx = dpToPx(logicalX.toFloat())
            positionYPx = dpToPx(logicalY.toFloat())
            clampPositionInPlace(sizeForMode(currentMode))
            updateLayout(windowManager)
            if (notify) {
                notifyOverlayStateChanged()
            }
        }
    }

    fun handleFlutterMessage(payload: Map<String, Any?>) {
        runOnMain {
            when (payload["type"]?.toString()) {
                "snapshot" -> {
                    serviceStatus = normalizeMap(payload["serviceStatus"])
                    agentConfig = normalizeMap(payload["agentConfig"])
                    agentRunStatus = normalizeMap(payload["agentRunStatus"])
                    diagnosticHint = normalizeMap(payload["diagnosticHint"])
                    lastPayload = payload["lastPayload"]?.toString() ?: lastPayload
                    logs = normalizeStringList(payload["logs"])
                    val overlayState = normalizeMap(payload["overlayState"])
                    applyOverlayStatePayload(overlayState)
                }

                "service_status" -> {
                    serviceStatus = normalizeMap(payload["payload"])
                }

                "agent_config" -> {
                    agentConfig = normalizeMap(payload["payload"])
                }

                "agent_status" -> {
                    agentRunStatus = normalizeMap(payload["payload"])
                }

                "diagnostic_hint" -> {
                    diagnosticHint = normalizeMap(payload["payload"])
                }

                "overlay_state" -> {
                    applyOverlayStatePayload(normalizeMap(payload["payload"]))
                }

                "payload" -> {
                    lastPayload = payload["payload"]?.toString() ?: lastPayload
                }

                "log" -> {
                    val line = payload["line"]?.toString().orEmpty()
                    if (line.isNotBlank()) {
                        logs = listOf(line) + logs.take(19)
                    }
                }
            }
            renderOverlayUi()
        }
    }

    private fun applyOverlayStatePayload(payload: Map<String, Any?>) {
        val mode = payload["mode"]?.toString()
        if (!mode.isNullOrBlank()) {
            currentMode = normalizeMode(mode)
        }
        val position = normalizeMap(payload["position"])
        val x = readDouble(position["x"])
        val y = readDouble(position["y"])
        if (x != null && y != null) {
            positionXPx = dpToPx(x.toFloat())
            positionYPx = dpToPx(y.toFloat())
            clampPositionInPlace(sizeForMode(currentMode))
            if (overlayRoot?.isAttachedToWindow == true) {
                updateLayout(windowManager)
            }
        }
        val active = payload["active"]
        if (active is Boolean && !active) {
            isActive = false
        }
    }

    private fun notifyOverlayStateChanged() {
        OverlayEventBus.send(
            mapOf(
                "source" to "native_overlay",
                "type" to "overlay_state",
                "payload" to snapshot(),
            ),
        )
    }

    private fun emitOverlayCommand(action: String, extra: Map<String, Any?> = emptyMap()) {
        val event = linkedMapOf<String, Any?>(
            "source" to "overlay",
            "type" to "command",
            "action" to action,
        )
        event.putAll(extra)
        OverlayEventBus.send(event)
    }

    private fun ensureOverlayView(context: Context) {
        if (overlayRoot != null) {
            return
        }
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        val bubble = buildCollapsedBubble(context)
        val panel = buildExpandedPanel(context)
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            bubble,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        overlayRoot = root
        collapsedBubble = bubble
        expandedPanel = panel
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCollapsedBubble(context: Context): FrameLayout {
        val container = FrameLayout(context)
        val bubbleFace = TextView(context).apply {
            gravity = Gravity.CENTER
            text = "AI"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            background = bubbleDrawable(colorTeal)
            elevation = dpToPx(6f)
        }
        val indicator = View(context).apply {
            background = circleDrawable(colorRed, Color.WHITE, 2f)
        }

        container.addView(
            bubbleFace,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        container.addView(
            indicator,
            FrameLayout.LayoutParams(dpToPx(12f).roundToInt(), dpToPx(12f).roundToInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(2f).roundToInt()
                marginEnd = dpToPx(2f).roundToInt()
            },
        )
        container.setPadding(dpToPx(8f).roundToInt(), dpToPx(8f).roundToInt(), dpToPx(8f).roundToInt(), dpToPx(8f).roundToInt())
        container.setOnTouchListener(
            DragTouchListener(
                allowSnap = true,
                onTap = { toggleMode(modeExpanded) },
            ),
        )

        bubbleFaceView = bubbleFace
        bubbleIndicatorView = indicator
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildExpandedPanel(context: Context): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12f).roundToInt(), dpToPx(12f).roundToInt(), dpToPx(12f).roundToInt(), dpToPx(12f).roundToInt())
            background = panelDrawable()
            elevation = dpToPx(14f)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(DragTouchListener(allowSnap = false, onTap = null))
        }
        val title = TextView(context).apply {
            text = "AI答题"
            setTextColor(colorInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val collapseButton = buildHeaderActionView(context, "收起") {
            toggleMode(modeCollapsed)
        }
        val closeButton = buildHeaderActionView(context, "关闭") {
            hideOverlay(notify = true)
            appContext?.let(NativeOverlayService::stop)
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(collapseButton)
        header.addView(space(context, 8f))
        header.addView(closeButton)

        panel.addView(header)
        panel.addView(space(context, 12f))

        // 展开态保持紧凑，但给状态区预留稳定高度，避免长日志把底部操作按钮挤出可点击区域。
        val statusCard = buildSectionCard(context)
        statusCard.addView(buildSectionTitle(context, "当前状态"))
        statusCard.addView(space(context, 8f))
        accessibilityValueView = addInfoLine(context, statusCard, "使用条件")
        runStateValueView = addInfoLine(context, statusCard, "运行状态")
        statusCard.addView(buildSectionCaption(context, "最近日志"))
        statusCard.addView(space(context, 4f))
        val latestLogView = buildBodyTextView(context).apply {
            // 固定两行高度，让短日志和长日志都占用同一块区域，减少按钮位置跳动。
            minLines = 2
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        statusCard.addView(latestLogView)
        latestLogValueView = latestLogView
        panel.addView(statusCard)
        panel.addView(space(context, 12f))
        panel.addView(
            View(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val startAction = buildActionView(context, "开始", filled = true) {
            emitOverlayCommand("start_agent")
        }
        val stopAction = buildActionView(context, "停止", filled = false) {
            emitOverlayCommand("stop_agent")
        }
        actionRow.addView(startAction, weightedLayoutParams())
        actionRow.addView(space(context, 8f))
        actionRow.addView(stopAction, weightedLayoutParams())
        startActionView = startAction
        stopActionView = stopAction
        panel.addView(actionRow)

        return panel
    }

    private fun buildSectionCard(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14f).roundToInt(), dpToPx(14f).roundToInt(), dpToPx(14f).roundToInt(), dpToPx(14f).roundToInt())
            background = roundedDrawable(18f, Color.WHITE, borderColor = colorOutline)
        }
    }

    private fun buildSectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(colorInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun buildSectionCaption(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(colorSubtle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun buildBodyTextView(context: Context): TextView {
        return TextView(context).apply {
            setTextColor(colorInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dpToPx(2f), 1f)
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun addInfoLine(context: Context, parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            setTextColor(colorSubtle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val valueView = TextView(context).apply {
            setTextColor(colorInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
        if (parent.childCount < 5) {
            parent.addView(space(context, 8f))
        }
        return valueView
    }

    private fun weightedLayoutParams() = LinearLayout.LayoutParams(0, dpToPx(40f).roundToInt(), 1f)

    private fun buildHeaderActionView(
        context: Context,
        label: String,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(colorSubtle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedDrawable(12f, Color.WHITE, borderColor = colorOutline)
            setPadding(dpToPx(10f).roundToInt(), 0, dpToPx(10f).roundToInt(), 0)
            minHeight = dpToPx(32f).roundToInt()
            setOnClickListener { onClick() }
        }
    }

    private fun buildActionView(
        context: Context,
        label: String,
        filled: Boolean,
        onClick: () -> Unit,
    ): TextView {
        val backgroundColor = if (filled) colorTeal else Color.WHITE
        val textColor = if (filled) Color.WHITE else colorInk
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedDrawable(14f, backgroundColor, borderColor = if (filled) colorTeal else colorOutline)
            setPadding(dpToPx(12f).roundToInt(), 0, dpToPx(12f).roundToInt(), 0)
            minHeight = dpToPx(40f).roundToInt()
            setOnClickListener { onClick() }
        }
    }

    private fun renderOverlayUi() {
        val bubble = collapsedBubble ?: return
        val panel = expandedPanel ?: return
        bubble.visibility = if (currentMode == modeCollapsed) View.VISIBLE else View.GONE
        panel.visibility = if (currentMode == modeExpanded) View.VISIBLE else View.GONE

        val running = agentRunStatus["running"] == true
        val accessibilityEnabled = serviceStatus["enabled"] == true
        val hasActiveWindow = serviceStatus["hasActiveWindow"] == true

        bubbleFaceView?.background = bubbleDrawable(if (running) colorAmber else colorTeal)
        bubbleIndicatorView?.background = circleDrawable(
            if (accessibilityEnabled) colorGreen else colorRed,
            Color.WHITE,
            2f,
        )

        accessibilityValueView?.text = when {
            accessibilityEnabled && hasActiveWindow -> "已就绪"
            accessibilityEnabled -> "等待页面"
            else -> "待授权"
        }
        runStateValueView?.text = runStatusLabel(agentRunStatus["status"]?.toString())
        val latestLog = logs.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
        val statusMessage = agentRunStatus["message"]?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: diagnosticHint["summary"]?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
        latestLogValueView?.text = readableText(
            latestLog ?: statusMessage,
            if (accessibilityEnabled && hasActiveWindow) "可以开始执行自动答题。"
            else "请先完成权限和页面准备。",
        )

        startActionView?.alpha = if (running) 0.45f else 1f
        startActionView?.isEnabled = !running
        stopActionView?.alpha = if (running) 1f else 0.45f
        stopActionView?.isEnabled = running
    }

    private fun updateLayout(manager: WindowManager?) {
        val root = overlayRoot ?: return
        val realManager = manager ?: windowManager ?: return
        val size = sizeForMode(currentMode)
        clampPositionInPlace(size)

        val params = layoutParams ?: buildLayoutParams(size).also { layoutParams = it }
        params.width = size.width
        params.height = size.height
        params.x = positionXPx.roundToInt()
        params.y = positionYPx.roundToInt()

        if (root.isAttachedToWindow) {
            realManager.updateViewLayout(root, params)
        } else {
            realManager.addView(root, params)
        }
    }

    private fun buildLayoutParams(size: OverlaySize): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            size.width,
            size.height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun toggleMode(nextMode: String) {
        runOnMain {
            val normalizedMode = normalizeMode(nextMode)
            if (normalizedMode == currentMode) {
                return@runOnMain
            }
            val currentSize = sizeForMode(currentMode)
            val nextSize = sizeForMode(normalizedMode)
            val screen = screenSizePx()
            val anchorX = positionXPx + (currentSize.width / 2f)
            val anchorY = positionYPx + (currentSize.height / 2f) - (nextSize.height / 2f)
            positionXPx = if (anchorX < screen.width / 2f) {
                edgeMarginPx()
            } else {
                screen.width - nextSize.width - edgeMarginPx()
            }
            positionYPx = anchorY
            currentMode = normalizedMode
            clampPositionInPlace(nextSize)
            updateLayout(windowManager)
            renderOverlayUi()
            notifyOverlayStateChanged()
        }
    }

    private fun clampPositionInPlace(size: OverlaySize) {
        val screen = screenSizePx()
        val minX = edgeMarginPx()
        val minY = edgeMarginPx()
        val maxX = max(minX, screen.width - size.width - edgeMarginPx())
        val maxY = max(minY, screen.height - size.height - edgeMarginPx())
        positionXPx = positionXPx.coerceIn(minX, maxX)
        positionYPx = positionYPx.coerceIn(minY, maxY)
    }

    private fun sizeForMode(mode: String): OverlaySize {
        return if (mode == modeCollapsed) {
            OverlaySize(dpToPx(collapsedWidthDp.toFloat()).roundToInt(), dpToPx(collapsedHeightDp.toFloat()).roundToInt())
        } else {
            OverlaySize(dpToPx(expandedWidthDp.toFloat()).roundToInt(), dpToPx(expandedHeightDp.toFloat()).roundToInt())
        }
    }

    private fun screenSizePx(): OverlaySize {
        val metrics = appContext?.resources?.displayMetrics
        return OverlaySize(metrics?.widthPixels ?: 1080, metrics?.heightPixels ?: 1920)
    }

    private fun edgeMarginPx(): Float = dpToPx(edgeMarginDp)

    private fun dpToPx(value: Float): Float {
        val density = appContext?.resources?.displayMetrics?.density ?: 1f
        return value * density
    }

    private fun pxToDp(value: Float): Double {
        val density = appContext?.resources?.displayMetrics?.density ?: 1f
        return (value / density).toDouble()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun readableText(value: String?, fallback: String): String {
        return value?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback
    }

    private fun normalizeMode(mode: String?): String {
        return if (mode == modeCollapsed) modeCollapsed else modeExpanded
    }

    private fun normalizeMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) {
            return emptyMap()
        }
        return value.entries.associate { (key, item) ->
            key.toString() to when (item) {
                is Map<*, *> -> normalizeMap(item)
                is List<*> -> item.map { listItem -> if (listItem is Map<*, *>) normalizeMap(listItem) else listItem }
                else -> item
            }
        }
    }

    private fun normalizeStringList(value: Any?): List<String> {
        if (value !is List<*>) {
            return emptyList()
        }
        return value.mapNotNull { item -> item?.toString()?.takeIf { it.isNotBlank() } }
    }

    private fun readDouble(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        }
    }

    private fun runStatusLabel(status: String?): String {
        return when (status) {
            "starting" -> "启动中"
            "thinking" -> "分析中"
            "acting" -> "执行中"
            "completed" -> "已完成"
            "failed" -> "失败"
            "stopping" -> "停止中"
            "stopped" -> "已停止"
            else -> "空闲"
        }
    }

    private fun bubbleDrawable(fillColor: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(fillColor, withAlpha(fillColor, 0.78f)),
        ).apply {
            shape = GradientDrawable.OVAL
        }
    }

    private fun panelDrawable(): GradientDrawable {
        return roundedDrawable(24f, colorPanel, borderColor = withAlpha(colorInk, 0.08f))
    }

    private fun circleDrawable(fillColor: Int, strokeColor: Int, strokeDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(dpToPx(strokeDp).roundToInt(), strokeColor)
        }
    }

    private fun roundedDrawable(cornerDp: Float, fillColor: Int, borderColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(cornerDp)
            setColor(fillColor)
            setStroke(dpToPx(1f).roundToInt(), borderColor)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val clamped = alpha.coerceIn(0f, 1f)
        val alphaInt = (clamped * 255).roundToInt()
        return Color.argb(alphaInt, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun space(context: Context, widthDp: Float): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(widthDp).roundToInt(), dpToPx(widthDp).roundToInt())
        }
    }

    private data class OverlaySize(val width: Int, val height: Int)

    /**
     * 统一处理拖动手势。
     *
     * 折叠态在拖动结束后做吸边动画，避免之前 Dart 定时器二次矫正带来的跳动；
     * 展开态只做实时跟手和边界裁剪，避免大面板在拖动结束时出现二次位移。
     */
    private class DragTouchListener(
        private val allowSnap: Boolean,
        private val onTap: (() -> Unit)?,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val context = v.context
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    NativeOverlayController.snapAnimator?.cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = NativeOverlayController.positionXPx
                    startY = NativeOverlayController.positionYPx
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragging && hypot(deltaX.toDouble(), deltaY.toDouble()) > ViewConfiguration.get(context).scaledTouchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        NativeOverlayController.positionXPx = startX + deltaX
                        NativeOverlayController.positionYPx = startY + deltaY
                        NativeOverlayController.clampPositionInPlace(
                            NativeOverlayController.sizeForMode(NativeOverlayController.currentMode),
                        )
                        NativeOverlayController.updateLayout(NativeOverlayController.windowManager)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        onTap?.invoke()
                    } else if (allowSnap) {
                        NativeOverlayController.animateSnapToEdge()
                    } else {
                        NativeOverlayController.notifyOverlayStateChanged()
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    private fun animateSnapToEdge() {
        val size = sizeForMode(currentMode)
        val screenWidth = screenSizePx().width.toFloat()
        val targetX = if (positionXPx + (size.width / 2f) < screenWidth / 2f) {
            edgeMarginPx()
        } else {
            screenWidth - size.width - edgeMarginPx()
        }
        val startX = positionXPx
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(startX, targetX).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                positionXPx = animator.animatedValue as Float
                clampPositionInPlace(size)
                updateLayout(windowManager)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        notifyOverlayStateChanged()
                    }
                },
            )
            start()
        }
    }
}
