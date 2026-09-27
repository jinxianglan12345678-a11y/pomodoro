package com.hanvon.clear6.pomodoro

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 墨水屏高对比度极简柔软纯黑大猫视图（内置 4 款柔和三次贝塞尔曲线剪影）
 * - 款式 0: ① 软糯揣手猫团（闭眼打盹团子）
 * - 款式 1: ② 慵懒大眼趴趴猫（液态贴地 + 灵动圆眼）
 * - 款式 2: ③ 蜷缩熟睡黑猫丸（禅意鹅卵石圆弧）
 * - 款式 3: ④ 桌沿垂尾摇摆猫（尾巴垂在横线下方如钟摆晃动）
 */
class RestingCatEInkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val prefs = context.getSharedPreferences("clear6_pomodoro_prefs", Context.MODE_PRIVATE)
    private var catStyleIndex: Int = prefs.getInt("cat_style_index", 1).coerceIn(0, 3)
    private var tailFrame: Int = 0 // 0, 1, 2
    private var restRemainingText: String = "05"
    private var restUnitLabel: String = "分钟休息剩余 · 放下书本远眺"
    private var isWaggingActive: Boolean = false

    private val styleNames = arrayOf(
        "① 半眯眼趴趴黑猫",
        "② 圆瞳灵动趴趴黑猫",
        "③ 熟睡闭眼趴趴黑猫",
        "④ 高翘摇尾趴趴黑猫"
    )

    private val wagHandler = Handler(Looper.getMainLooper())
    private val wagRunnable = object : Runnable {
        override fun run() {
            if (!isWaggingActive || visibility != VISIBLE) return
            tailFrame = (tailFrame + 1) % 3
            invalidate()
            wagHandler.postDelayed(this, 4000L)
        }
    }

    private val fillBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val fillWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val strokeBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val strokeHaloWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val strokeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setOnClickListener {
            catStyleIndex = (catStyleIndex + 1) % 4
            prefs.edit().putInt("cat_style_index", catStyleIndex).apply()
            tailFrame = (tailFrame + 1) % 3
            invalidate()
        }
    }

    fun updateRestCountdown(timeText: String, subtitle: String) {
        restRemainingText = timeText
        restUnitLabel = subtitle
        invalidate()
    }

    fun setWagging(enabled: Boolean) {
        if (isWaggingActive == enabled) return
        isWaggingActive = enabled
        wagHandler.removeCallbacks(wagRunnable)
        if (enabled) {
            catStyleIndex = (catStyleIndex + (1..3).random()) % 4
            invalidate()
            wagHandler.postDelayed(wagRunnable, 4000L)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wagHandler.removeCallbacks(wagRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 10f || h <= 10f) return

        val scale = min(w / 640f, h / 420f)
        val offsetX = (w - 640f * scale) * 0.5f
        val offsetY = (h - 380f * scale) * 0.5f + 16f * scale

        fun sx(x: Float) = offsetX + x * scale
        fun sy(y: Float) = offsetY + y * scale

        // 顶部白底黑框胶囊倒计时横幅（在微信读书密集文字上方透明霸屏时依然 100% 清晰可读）
        val bannerText = restRemainingText + "  " + restUnitLabel
        subtitlePaint.textSize = 24f * scale
        val textW = subtitlePaint.measureText(bannerText)
        val bannerRect = RectF(
            (w - textW) * 0.5f - 24f * scale,
            offsetY - 34f * scale,
            (w + textW) * 0.5f + 24f * scale,
            offsetY + 14f * scale
        )
        canvas.drawRoundRect(bannerRect, 10f * scale, 10f * scale, fillWhitePaint)
        strokeBlackPaint.strokeWidth = 3f * scale
        canvas.drawRoundRect(bannerRect, 10f * scale, 10f * scale, strokeBlackPaint)
        canvas.drawText(bannerText, w * 0.5f, offsetY - 2f * scale, subtitlePaint)

        // 1. 绘制黑猫主体与圆润臀尾一体实心剪影（背部→臀部→环绕粗尾单笔贯通，消除臀部凹陷与断层）
        val bodyAndTailPath = Path().apply {
            moveTo(sx(126f), sy(295f))
            cubicTo(sx(86f), sy(295f), sx(46f), sy(287f), sx(46f), sy(264f))
            cubicTo(sx(46f), sy(244f), sx(62f), sy(228f), sx(84f), sy(220f))
            cubicTo(sx(50f), sy(194f), sx(48f), sy(142f), sx(82f), sy(108f))
            cubicTo(sx(88f), sy(78f), sx(98f), sy(44f), sx(106f), sy(32f))
            cubicTo(sx(116f), sy(44f), sx(134f), sy(66f), sx(148f), sy(84f))
            quadTo(sx(192f), sy(76f), sx(232f), sy(82f))
            cubicTo(sx(248f), sy(62f), sx(266f), sy(40f), sx(278f), sy(28f))
            cubicTo(sx(286f), sy(42f), sx(296f), sy(74f), sx(302f), sy(98f))
            cubicTo(sx(324f), sy(100f), sx(356f), sy(94f), sx(394f), sy(84f))
            cubicTo(sx(458f), sy(68f), sx(534f), sy(78f), sx(570f), sy(132f))

            if (catStyleIndex == 3) {
                cubicTo(sx(590f), sy(162f), sx(594f), sy(218f), sx(574f), sy(264f))
                cubicTo(sx(558f), sy(290f), sx(526f), sy(295f), sx(480f), sy(295f))
                lineTo(sx(126f), sy(295f))
            } else {
                cubicTo(sx(590f), sy(162f), sx(596f), sy(192f), sx(593f), sy(224f))
                cubicTo(sx(589f), sy(266f), sx(554f), sy(336f), sx(492f), sy(336f))
                when (tailFrame) {
                    1 -> {
                        cubicTo(sx(462f), sy(336f), sx(430f), sy(344f), sx(398f), sy(346f))
                        cubicTo(sx(366f), sy(348f), sx(368f), sy(307f), sx(400f), sy(305f))
                        cubicTo(sx(414f), sy(304f), sx(426f), sy(299f), sx(438f), sy(295f))
                    }
                    2 -> {
                        cubicTo(sx(462f), sy(336f), sx(428f), sy(331f), sx(396f), sy(324f))
                        cubicTo(sx(372f), sy(319f), sx(366f), sy(304f), sx(372f), sy(295f))
                    }
                    else -> {
                        lineTo(sx(400f), sy(336f))
                        cubicTo(sx(368f), sy(336f), sx(368f), sy(295f), sx(400f), sy(295f))
                    }
                }
                lineTo(sx(126f), sy(295f))
            }
            close()
        }

        if (catStyleIndex == 3) {
            val highTailPath = Path().apply {
                moveTo(sx(562f), sy(252f))
                when (tailFrame) {
                    0 -> cubicTo(sx(604f), sy(228f), sx(614f), sy(152f), sx(586f), sy(64f))
                    1 -> cubicTo(sx(612f), sy(234f), sx(632f), sy(164f), sx(612f), sy(78f))
                    else -> cubicTo(sx(594f), sy(220f), sx(588f), sy(144f), sx(554f), sy(62f))
                }
            }
            strokeHaloWhitePaint.strokeWidth = 52f * scale
            canvas.drawPath(highTailPath, strokeHaloWhitePaint)
            strokeHaloWhitePaint.strokeWidth = 12f * scale
            canvas.drawPath(bodyAndTailPath, strokeHaloWhitePaint)

            strokeBlackPaint.strokeWidth = 40f * scale
            canvas.drawPath(highTailPath, strokeBlackPaint)
        } else {
            strokeHaloWhitePaint.strokeWidth = 12f * scale
            canvas.drawPath(bodyAndTailPath, strokeHaloWhitePaint)
        }
        canvas.drawPath(bodyAndTailPath, fillBlackPaint)

        // 2. 双耳内侧、前爪、后腿与圆润臀尾透明镂空分界线
        strokeWhitePaint.strokeWidth = 2.2f * scale
        val earLines = Path().apply {
            moveTo(sx(92f), sy(98f))
            cubicTo(sx(98f), sy(74f), sx(103f), sy(52f), sx(107f), sy(43f))
            cubicTo(sx(115f), sy(53f), sx(128f), sy(70f), sx(140f), sy(85f))
            moveTo(sx(240f), sy(83f))
            cubicTo(sx(253f), sy(67f), sx(267f), sy(49f), sx(275f), sy(39f))
            cubicTo(sx(281f), sy(50f), sx(288f), sy(73f), sx(293f), sy(95f))
        }
        canvas.drawPath(earLines, strokeWhitePaint)

        strokeWhitePaint.strokeWidth = 6f * scale
        val whiteCreases = Path().apply {
            // 前爪分界弧线
            moveTo(sx(94f), sy(290f))
            cubicTo(sx(92f), sy(266f), sx(114f), sy(248f), sx(162f), sy(233f))
            // 后腿与后爪留白弧线
            moveTo(sx(476f), sy(154f))
            cubicTo(sx(440f), sy(176f), sx(424f), sy(212f), sx(434f), sy(252f))
            if (catStyleIndex != 3 && tailFrame == 2) {
                cubicTo(sx(404f), sy(254f), sx(393f), sy(268f), sx(396f), sy(284f))
            } else {
                cubicTo(sx(404f), sy(254f), sx(392f), sy(272f), sx(398f), sy(295f))
            }
            // 圆润臀部下沿与环绕尾巴之间的平滑等宽留白弧线
            if (catStyleIndex == 3) {
                moveTo(sx(548f), sy(228f))
                cubicTo(sx(560f), sy(242f), sx(562f), sy(260f), sx(554f), sy(274f))
            } else {
                when (tailFrame) {
                    1 -> {
                        moveTo(sx(426f), sy(297f))
                        cubicTo(sx(440f), sy(295f), sx(454f), sy(295f), sx(468f), sy(295f))
                        cubicTo(sx(508f), sy(295f), sx(538f), sy(272f), sx(548f), sy(230f))
                    }
                    2 -> {
                        moveTo(sx(372f), sy(295f))
                        cubicTo(sx(378f), sy(282f), sx(396f), sy(283f), sx(414f), sy(290f))
                        cubicTo(sx(428f), sy(295f), sx(448f), sy(295f), sx(468f), sy(295f))
                        cubicTo(sx(508f), sy(295f), sx(538f), sy(272f), sx(548f), sy(230f))
                    }
                    else -> {
                        moveTo(sx(396f), sy(295f))
                        lineTo(sx(468f), sy(295f))
                        cubicTo(sx(508f), sy(295f), sx(538f), sy(272f), sx(548f), sy(230f))
                    }
                }
            }
            // 左右各两根白胡须
            moveTo(sx(40f), sy(174f))
            quadTo(sx(72f), sy(175f), sx(104f), sy(181f))
            moveTo(sx(48f), sy(205f))
            quadTo(sx(76f), sy(195f), sx(102f), sy(188f))
            moveTo(sx(288f), sy(164f))
            quadTo(sx(326f), sy(151f), sx(366f), sy(139f))
            moveTo(sx(290f), sy(177f))
            quadTo(sx(328f), sy(180f), sx(364f), sy(185f))
        }
        canvas.drawPath(whiteCreases, strokeWhitePaint)

        // 3. 灵魂五官
        if (catStyleIndex == 2) {
            val sleepEyes = Path().apply {
                moveTo(sx(122f), sy(154f))
                quadTo(sx(152f), sy(178f), sx(182f), sy(154f))
                moveTo(sx(212f), sy(154f))
                quadTo(sx(242f), sy(178f), sx(272f), sy(154f))
            }
            strokeWhitePaint.strokeWidth = 7f * scale
            canvas.drawPath(sleepEyes, strokeWhitePaint)
        } else {
            canvas.drawCircle(sx(152f), sy(148f), 35f * scale, fillWhitePaint)
            canvas.drawCircle(sx(240f), sy(148f), 35f * scale, fillWhitePaint)

            if (catStyleIndex == 1) {
                val shift = (tailFrame - 1) * 3.5f
                canvas.drawCircle(sx(152f + shift), sy(150f), 15f * scale, fillBlackPaint)
                canvas.drawCircle(sx(240f + shift), sy(150f), 15f * scale, fillBlackPaint)
            } else {
                val crescents = Path().apply {
                    moveTo(sx(132f), sy(150f))
                    cubicTo(sx(140f), sy(166f), sx(164f), sy(166f), sx(172f), sy(150f))
                    moveTo(sx(220f), sy(150f))
                    cubicTo(sx(228f), sy(166f), sx(252f), sy(166f), sx(260f), sy(150f))
                }
                strokeBlackPaint.strokeWidth = 10f * scale
                canvas.drawPath(crescents, strokeBlackPaint)
            }
        }
    }
}