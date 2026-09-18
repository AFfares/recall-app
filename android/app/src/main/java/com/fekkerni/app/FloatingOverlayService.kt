package com.fekkerni.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class FloatingOverlayService : Service() {
  private lateinit var windowManager: WindowManager
  private lateinit var handleView: OverlayView
  private lateinit var layoutParams: WindowManager.LayoutParams
  private val overlayWidthPx by lazy { dp(20f) }
  private val overlayHeightPx by lazy { dp(176f) }

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
      stopSelf()
      return
    }

    createNotificationChannel()
    startForeground(NOTIFICATION_ID, notification())
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    handleView = OverlayView(this)
    layoutParams = baseLayoutParams()
    handleView.prepareInitialLayout(layoutParams)
    windowManager.addView(handleView, layoutParams)
  }

  override fun onDestroy() {
    if (::handleView.isInitialized) windowManager.removeView(handleView)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun baseLayoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
    overlayWidthPx,
    overlayHeightPx,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else WindowManager.LayoutParams.TYPE_PHONE,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    android.graphics.PixelFormat.TRANSLUCENT
  ).apply { gravity = Gravity.TOP or Gravity.START }

  private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("Recall")
    .setContentText("Floating handle is active")
    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
    .setOngoing(true)
    .build()

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Recall floating handle", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

  private inner class OverlayView(context: Context) : View(context) {
    private val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val handler = Handler(Looper.getMainLooper())
    private val screenWidth = resources.displayMetrics.widthPixels.toFloat()
    private val screenHeight = resources.displayMetrics.heightPixels.toFloat()
    private val edgeWindowSize = overlayWidthPx
    private val edgeWindowHeight = overlayHeightPx
    private val barLength = dp(140f).toFloat()
    private val barThickness = dp(12f).toFloat()
    private val ballDiameter = dp(46f).toFloat()
    private val ringSize = dp(38f).toFloat()
    private val minY = dp(90f).toFloat()
    private val edgeInset = barThickness / 2f - dp(2f)
    private var rightSide = prefs.getBoolean(KEY_RIGHT, true)
    private var centerX = if (rightSide) screenWidth - edgeInset else edgeInset
    private var centerY = prefs.getFloat(KEY_Y, screenHeight / 2f).coerceIn(minY, screenHeight - minY)
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressTriggered = false
    private var morph = 0f
    private var wave = 0f
    private var previousX = centerX
    private var previousY = centerY
    private var lastMoveTime = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var trailAlpha = 0f
    private var trailLength = 0f
    private var trailAngle = 0f
    private var gazeX = 0f
    private var expanded = false
    private var waveAnimator: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var trailAnimator: ValueAnimator? = null
    private var gazeAnimator: ValueAnimator? = null

    private val longPress = Runnable {
      if (!dragging) {
        longPressTriggered = true
        dragging = true
        previousX = centerX
        previousY = centerY
        lastMoveTime = System.currentTimeMillis()
        velocityX = 0f
        velocityY = 0f
        animateGazeTo(gazeTargetFor(centerX))
        expandToFullScreen()
        animateMorph(1f)
        startWaves()
        invalidate()
      }
    }

    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    fun prepareInitialLayout(params: WindowManager.LayoutParams) {
      params.x = if (rightSide) screenWidth.toInt() - edgeWindowSize else 0
      params.y = (centerY - edgeWindowHeight / 2f).toInt()
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val x = if (expanded) centerX else if (rightSide) width - edgeInset else edgeInset
      val y = if (expanded) centerY else centerY - this@FloatingOverlayService.layoutParams.y
      val diameter = barThickness + (ballDiameter - barThickness) * morph
      val length = barLength + (ballDiameter - barLength) * morph
      val radius = barThickness / 2f + (ballDiameter / 2f - barThickness / 2f) * morph

      if (morph > 0f && waveAnimator != null) {
        drawRing(canvas, x, y, wave, 0f)
        drawRing(canvas, x, y, wave, 0.5f)
      }
      drawTrail(canvas, x, y)
      paint.color = HANDLE_COLOR
      paint.alpha = if (morph > 0.5f) PUZZLE_ALPHA else HANDLE_ALPHA
      paint.setShadowLayer(4f + morph * 4f, 0f, 1f, Color.argb(80, 59, 63, 143))
      drawHandle(canvas, x, y, diameter, length, radius)
      paint.clearShadowLayer()
      if (morph > 0.01f) drawEyes(canvas, x, y, diameter, length)
      if (morph > 0.01f) postInvalidateOnAnimation()
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, radius: Float) {
      val left = x - width / 2f
      val right = x + width / 2f
      val top = y - height / 2f
      val bottom = y + height / 2f
      val path = Path()
      val puzzleProgress = morph
      if (puzzleProgress < 0.02f) {
        val edgeRadius = radius * puzzleProgress
        val innerRadius = radius
        if (rightSide) {
          path.moveTo(left + innerRadius, top)
          path.lineTo(right - edgeRadius, top)
          path.quadTo(right, top, right, top + edgeRadius)
          path.lineTo(right, bottom - edgeRadius)
          path.quadTo(right, bottom, right - edgeRadius, bottom)
          path.lineTo(left + innerRadius, bottom)
          path.quadTo(left, bottom, left, bottom - innerRadius)
          path.lineTo(left, top + innerRadius)
          path.quadTo(left, top, left + innerRadius, top)
        } else {
          path.moveTo(left, top)
          path.lineTo(right - innerRadius, top)
          path.quadTo(right, top, right, top + innerRadius)
          path.lineTo(right, bottom - innerRadius)
          path.quadTo(right, bottom, right - innerRadius, bottom)
          path.lineTo(left + edgeRadius, bottom)
          path.quadTo(left, bottom, left, bottom - edgeRadius)
          path.lineTo(left, top + edgeRadius)
          path.quadTo(left, top, left + edgeRadius, top)
        }
        path.close()
        canvas.drawPath(path, paint)
        return
      }

      val scaleX = width / 46f
      val scaleY = height / 46f
      val canvasLeft = x - width / 2f
      val canvasTop = y - height / 2f
      val bodyLeft = canvasLeft
      val bodyRight = canvasLeft + 46f * scaleX
      val bodyTop = canvasTop + 10f * scaleY
      val bodyBottom = bodyTop + 46f * scaleY
      val corner = 10f * min(scaleX, scaleY)
      val bumpLeft = canvasLeft + 12f * scaleX
      val bumpRight = canvasLeft + 34f * scaleX
      val bumpCenter = canvasLeft + 23f * scaleX
      val bumpTop = bodyTop - 11f * scaleY
      val biteRadius = 9f * min(scaleX, scaleY)
      val biteCenterY = bodyTop + 26f * scaleY

      // One continuous outline: structured rounded body, one top bump, and one circular bite.
      path.moveTo(bodyLeft + corner, bodyTop)
      path.lineTo(bumpLeft, bodyTop)
      path.cubicTo(bumpLeft, bumpTop + 6f * scaleY, bumpCenter - 6.1f * scaleX, bumpTop, bumpCenter, bumpTop)
      path.cubicTo(bumpCenter + 6.1f * scaleX, bumpTop, bumpRight, bumpTop + 6f * scaleY, bumpRight, bodyTop)
      path.lineTo(bodyRight - corner, bodyTop)
      path.quadTo(bodyRight, bodyTop, bodyRight, bodyTop + corner)
      path.lineTo(bodyRight, bodyBottom - corner)
      path.quadTo(bodyRight, bodyBottom, bodyRight - corner, bodyBottom)
      path.lineTo(bodyLeft + corner, bodyBottom)
      path.quadTo(bodyLeft, bodyBottom, bodyLeft, bodyBottom - corner)
      path.lineTo(bodyLeft, biteCenterY + biteRadius)
      path.arcTo(bodyLeft - biteRadius, biteCenterY - biteRadius, bodyLeft + biteRadius, biteCenterY + biteRadius, 90f, -180f, false)
      path.lineTo(bodyLeft, bodyTop + corner)
      path.quadTo(bodyLeft, bodyTop, bodyLeft + corner, bodyTop)
      path.close()
      canvas.drawPath(path, paint)
    }

    private fun drawEyes(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
      val eyeAlpha = (PUZZLE_ALPHA * min(1f, morph * 1.8f)).toInt()
      paint.clearShadowLayer()
      paint.color = EYE_COLOR
      paint.alpha = eyeAlpha
      val scaleX = width / 46f
      val scaleY = height / 46f
      val canvasLeft = x - width / 2f
      val baseEyeY = y - height / 2f + 11f * scaleY + 25.3f * scaleY
      val eyeOffsetY = (sin(System.currentTimeMillis() / 700.0) * 4.0f).toFloat()
      val animatedEyeY = baseEyeY + eyeOffsetY
      val eyeWidth = dp(4f).toFloat()
      val eyeHeight = dp(9f).toFloat()
      val gazeOffset = gazeX * dp(7f).toFloat()
      val leftEyeCenter = canvasLeft + 13.8f * scaleX + gazeOffset
      val rightEyeCenter = canvasLeft + 28.5f * scaleX + gazeOffset
      paint.alpha = 255
      canvas.drawRoundRect(leftEyeCenter - eyeWidth / 2f, animatedEyeY - eyeHeight / 2f, leftEyeCenter + eyeWidth / 2f, animatedEyeY + eyeHeight / 2f, eyeWidth / 2f, eyeWidth / 2f, paint)
      canvas.drawRoundRect(rightEyeCenter - eyeWidth / 2f, animatedEyeY - eyeHeight / 2f, rightEyeCenter + eyeWidth / 2f, animatedEyeY + eyeHeight / 2f, eyeWidth / 2f, eyeWidth / 2f, paint)
    }

    private fun drawTrail(canvas: Canvas, x: Float, y: Float) {
      if (trailAlpha <= 0.01f || morph <= 0.2f) return
      val trailPaint = paint
      trailPaint.clearShadowLayer()
      trailPaint.color = TRAIL_COLOR
      trailPaint.alpha = (trailAlpha * 90f).toInt().coerceIn(0, 90)
      canvas.save()
      canvas.rotate(trailAngle, x, y)
      canvas.drawRoundRect(x - trailLength, y - ballDiameter * 0.18f, x - ballDiameter * 0.2f, y + ballDiameter * 0.18f, ballDiameter * 0.18f, ballDiameter * 0.18f, trailPaint)
      canvas.restore()
    }

    private fun drawRing(canvas: Canvas, x: Float, y: Float, phase: Float, offset: Float) {
      val progress = (phase + offset) % 1f
      ringPaint.color = Color.argb((42 * (1f - progress)).toInt(), 217, 231, 254)
      canvas.drawCircle(x, y, ringSize * (0.9f + progress * 1.3f), ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.rawX; downY = event.rawY; longPressTriggered = false
          trailAnimator?.cancel()
          handler.postDelayed(longPress, LONG_PRESS_MS)
          return true
        }
        MotionEvent.ACTION_MOVE -> {
          if (!longPressTriggered && hypot(event.rawX - downX, event.rawY - downY) > TOUCH_SLOP) handler.removeCallbacks(longPress)
          if (dragging) {
            val now = event.eventTime
            val elapsedMs = max(1L, now - lastMoveTime)
            val deltaX = event.rawX - previousX
            val deltaY = event.rawY - previousY
            velocityX = deltaX * 1000f / elapsedMs
            velocityY = deltaY * 1000f / elapsedMs
            val speed = hypot(velocityX, velocityY)
            centerX = event.rawX
            centerY = event.rawY
            previousX = centerX
            previousY = centerY
            lastMoveTime = now

            gazeAnimator?.cancel()
            val gazeTarget = gazeTargetFor(centerX)
            gazeX += (gazeTarget - gazeX) * GAZE_SMOOTHING
            trailAngle = Math.toDegrees(atan2(velocityY.toDouble(), velocityX.toDouble())).toFloat()
            trailLength = (speed * TRAIL_LENGTH_FACTOR).coerceIn(0f, ballDiameter * 1.35f)
            trailAlpha = ((speed - TRAIL_START_SPEED) / TRAIL_FULL_SPEED).coerceIn(0f, 1f)
            if (speed > TRAIL_START_SPEED) {
              stopWaves()
              trailAnimator?.cancel()
            } else {
              startWaves()
              animateTrailTo(0f)
            }
            invalidate()
          }
          return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          handler.removeCallbacks(longPress)
          if (dragging) finishDrag() else {
            invalidate()
          }
          return true
        }
      }
      return true
    }

    private fun finishDrag() {
      dragging = false
      stopWaves()
      animateTrailTo(0f)
      animateGazeTo(0f)
      rightSide = centerX >= screenWidth / 2f
      val targetX = if (rightSide) screenWidth - edgeInset else edgeInset
      val targetY = centerY.coerceIn(minY, screenHeight - minY)
      val startX = centerX; val startY = centerY
      ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 360
        addUpdateListener { animator ->
          val t = animator.animatedValue as Float
          centerX = startX + (targetX - startX) * t
          centerY = startY + (targetY - startY) * t
          invalidate()
        }
        doOnEnd { persistAndCollapse() }
        start()
      }
    }

    private fun persistAndCollapse() {
      prefs.edit().putBoolean(KEY_RIGHT, rightSide).putFloat(KEY_Y, centerY).apply()
      stopWaves()
      trailAnimator?.cancel()
      trailAlpha = 0f
      gazeAnimator?.cancel()
      gazeX = 0f
      animateMorph(0f) { collapseToEdge() }
    }

    private fun expandToFullScreen() {
      expanded = true
      this@FloatingOverlayService.layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
      this@FloatingOverlayService.layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
      this@FloatingOverlayService.layoutParams.x = 0
      this@FloatingOverlayService.layoutParams.y = 0
      windowManager.updateViewLayout(this, this@FloatingOverlayService.layoutParams)
    }

    private fun collapseToEdge() {
      this@FloatingOverlayService.layoutParams.width = edgeWindowSize
      this@FloatingOverlayService.layoutParams.height = edgeWindowHeight
      this@FloatingOverlayService.layoutParams.x = if (rightSide) screenWidth.toInt() - edgeWindowSize else 0
      this@FloatingOverlayService.layoutParams.y = (centerY - edgeWindowHeight / 2f).toInt()
      windowManager.updateViewLayout(this, this@FloatingOverlayService.layoutParams)
      expanded = false
      invalidate()
    }

    private fun animateMorph(target: Float, onEnd: (() -> Unit)? = null) {
      morphAnimator?.cancel()
      morphAnimator = ValueAnimator.ofFloat(morph, target).apply {
        duration = 220
        addUpdateListener { morph = it.animatedValue as Float; invalidate() }
        doOnEnd { onEnd?.invoke() }
        start()
      }
    }

    private fun startWaves() {
      if (waveAnimator != null) return
      waveAnimator?.cancel()
      waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { wave = it.animatedValue as Float; invalidate() }; start()
      }
    }

    private fun stopWaves() { waveAnimator?.cancel(); waveAnimator = null }

    private fun animateTrailTo(target: Float) {
      trailAnimator?.cancel()
      val start = trailAlpha
      trailAnimator = ValueAnimator.ofFloat(start, target).apply {
        duration = 180
        addUpdateListener {
          trailAlpha = it.animatedValue as Float
          trailLength *= 0.86f
          invalidate()
        }
        start()
      }
    }

    private fun animateGazeTo(target: Float) {
      gazeAnimator?.cancel()
      gazeAnimator = ValueAnimator.ofFloat(gazeX, target).apply {
        duration = 180
        addUpdateListener {
          gazeX = it.animatedValue as Float
          invalidate()
        }
        start()
      }
    }

    private fun gazeTargetFor(positionX: Float): Float {
      val distanceToLeft = positionX
      val distanceToRight = screenWidth - positionX
      val halfScreen = screenWidth / 2f
      return ((positionX - halfScreen) / halfScreen).coerceIn(-1f, 1f)
    }
  }

  private fun ValueAnimator.doOnEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = action()
  })

  companion object {
    private const val PREFS = "recall_overlay"
    private const val KEY_RIGHT = "right_side"
    private const val KEY_Y = "center_y"
    private const val CHANNEL_ID = "recall_overlay"
    private const val NOTIFICATION_ID = 4917
    private const val LONG_PRESS_MS = 300L
    private const val TOUCH_SLOP = 12f
    private const val HANDLE_COLOR = 0xFFD9E7FE.toInt()
    private const val ACTIVE_COLOR = 0xFFD9E7FE.toInt()
    private const val HANDLE_ALPHA = 148
    private const val PUZZLE_ALPHA = 148
    private const val EYE_COLOR = 0xFF2B2F4A.toInt()
    private const val PUPIL_COLOR = 0xFF161A3A.toInt()
    private const val TRAIL_COLOR = 0x6696A0FA.toInt()
    private const val TRAIL_START_SPEED = 55f
    private const val TRAIL_FULL_SPEED = 1200f
    private const val TRAIL_LENGTH_FACTOR = 0.035f
    private const val GAZE_SMOOTHING = 0.24f
    private const val MAX_GAZE_OFFSET = 0.10f
  }
}