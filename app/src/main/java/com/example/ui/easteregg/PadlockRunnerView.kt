package com.example.ui.easteregg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.R
import kotlin.random.Random

/**
 * Vista de juego interactivo estilo Chrome Dino para el Easter Egg de TagLock.
 * El personaje principal es un Candado ("Padlock") que corre y salta para evitar
 * distracciones digitales (notificaciones, smartphones tentadores y alertas sociales).
 */
class PadlockRunnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        READY,
        PLAYING,
        GAME_OVER
    }

    sealed class ObstacleType(val widthDp: Float, val heightDp: Float) {
        object Phone : ObstacleType(28f, 50f)
        object NotificationBubble : ObstacleType(36f, 32f)
        object DoubleAlert : ObstacleType(38f, 54f)
    }

    data class Obstacle(
        var x: Float,
        val type: ObstacleType,
        val width: Float,
        val height: Float
    )

    private var gameState = State.READY
    private var score = 0
    private var highScore = 0

    // Densidad para cálculos en dp
    private val density = resources.displayMetrics.density

    // Parámetros físicos del Candado
    private var padlockX = 0f
    private var padlockY = 0f
    private var padlockVelocityY = 0f
    private val padlockWidth = 36f * density
    private val padlockHeight = 44f * density
    private val gravity = 1.1f * density
    private val jumpVelocity = -16f * density
    private var groundY = 0f

    // Velocidad del juego
    private val baseSpeed = 4.8f * density
    private var currentSpeed = baseSpeed
    private val maxSpeed = 12f * density

    // Obstáculos y terreno
    private val obstacles = mutableListOf<Obstacle>()
    private var nextObstacleDistance = 0f
    private val groundDashes = mutableListOf<Float>()

    // Animación de correr
    private var runAnimFrame = 0
    private var runAnimTimer = 0L

    // Tiempo y loop
    private var lastFrameTime = 0L

    // Pinturas
    private val padlockBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nfc_blue_primary)
        style = Paint.Style.FILL
    }

    private val padlockShacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val padlockKeyholePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val padlockFeetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val groundDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val obstaclePhonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.FILL
    }

    private val obstacleScreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.FILL
    }

    private val obstacleBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
    }

    private val obstacleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val textSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    // Callbacks
    var onScoreChanged: ((Int, Int) -> Unit)? = null
    var onGameOver: ((Int) -> Unit)? = null
    var onGameStarted: (() -> Unit)? = null

    init {
        // Inicializar marcas de terreno decorativas
        for (i in 0 until 12) {
            groundDashes.add(i * 40f * density)
        }
    }

    fun setHighScore(savedHighScore: Int) {
        this.highScore = savedHighScore
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        groundY = h * 0.78f
        padlockX = w * 0.18f
        padlockY = groundY - padlockHeight
        resetGame(startImmediately = false)
    }

    fun resetGame(startImmediately: Boolean = false) {
        obstacles.clear()
        score = 0
        currentSpeed = baseSpeed
        padlockVelocityY = 0f
        padlockY = groundY - padlockHeight
        nextObstacleDistance = 300f * density
        gameState = if (startImmediately) State.PLAYING else State.READY
        lastFrameTime = System.currentTimeMillis()
        onScoreChanged?.invoke(score, highScore)
        if (startImmediately) onGameStarted?.invoke()
        invalidate()
    }

    private fun jump() {
        if (gameState == State.READY) {
            gameState = State.PLAYING
            padlockVelocityY = jumpVelocity
            lastFrameTime = System.currentTimeMillis()
            onGameStarted?.invoke()
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            invalidate()
            return
        }

        if (gameState == State.GAME_OVER) {
            resetGame(startImmediately = true)
            padlockVelocityY = jumpVelocity
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            invalidate()
            return
        }

        if (gameState == State.PLAYING) {
            val isOnGround = padlockY >= groundY - padlockHeight - 1f
            if (isOnGround) {
                padlockVelocityY = jumpVelocity
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            jump()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.currentTimeMillis()
        val dt = if (lastFrameTime == 0L) 16L else (now - lastFrameTime).coerceIn(1, 50)
        lastFrameTime = now

        if (gameState == State.PLAYING) {
            updateGame(dt)
        }

        drawGround(canvas)
        drawObstacles(canvas)
        drawPadlock(canvas)
        drawOverlays(canvas)

        if (gameState == State.PLAYING) {
            postInvalidateOnAnimation()
        }
    }

    private fun updateGame(dtMs: Long) {
        val dtSec = dtMs / 16.66f

        // Físicas del salto
        padlockY += padlockVelocityY * dtSec
        padlockVelocityY += gravity * dtSec

        if (padlockY >= groundY - padlockHeight) {
            padlockY = groundY - padlockHeight
            padlockVelocityY = 0f
        }

        // Animación de correr
        runAnimTimer += dtMs
        if (runAnimTimer >= 80) {
            runAnimTimer = 0
            runAnimFrame = (runAnimFrame + 1) % 4
        }

        // Mover terreno
        for (i in groundDashes.indices) {
            groundDashes[i] -= currentSpeed * dtSec
            if (groundDashes[i] < -20f * density) {
                groundDashes[i] = width + 20f * density
            }
        }

        // Incrementar puntuación
        score++
        if (score > highScore) {
            highScore = score
        }
        onScoreChanged?.invoke(score, highScore)

        // Aumentar velocidad progresivamente
        currentSpeed = (baseSpeed + (score / 150f) * 0.35f * density).coerceAtMost(maxSpeed)

        // Mover obstáculos existentes
        val iterator = obstacles.iterator()
        while (iterator.hasNext()) {
            val obs = iterator.next()
            obs.x -= currentSpeed * dtSec

            // Verificar colisión
            if (checkCollision(obs)) {
                gameState = State.GAME_OVER
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onGameOver?.invoke(score)
                break
            }

            if (obs.x + obs.width < -50f * density) {
                iterator.remove()
            }
        }

        // Generar nuevos obstáculos
        nextObstacleDistance -= currentSpeed * dtSec
        if (nextObstacleDistance <= 0) {
            spawnObstacle()
            val minGap = 200f * density
            val maxGap = 420f * density
            nextObstacleDistance = minGap + Random.nextFloat() * (maxGap - minGap)
        }
    }

    private fun spawnObstacle() {
        val types = listOf(
            ObstacleType.Phone,
            ObstacleType.NotificationBubble,
            ObstacleType.DoubleAlert
        )
        val type = types.random()
        val w = type.widthDp * density
        val h = type.heightDp * density
        val obs = Obstacle(
            x = width.toFloat() + 20f * density,
            type = type,
            width = w,
            height = h
        )
        obstacles.add(obs)
    }

    private fun checkCollision(obs: Obstacle): Boolean {
        val padding = 6f * density

        val pLeft = padlockX + padding
        val pRight = padlockX + padlockWidth - padding
        val pTop = padlockY + padding
        val pBottom = padlockY + padlockHeight

        val obsBottom = groundY
        val obsTop = groundY - obs.height
        val obsLeft = obs.x + padding
        val obsRight = obs.x + obs.width - padding

        return (pRight >= obsLeft && pLeft <= obsRight && pBottom >= obsTop && pTop <= obsBottom)
    }

    private fun drawGround(canvas: Canvas) {
        // Línea principal de suelo
        canvas.drawLine(0f, groundY, width.toFloat(), groundY, groundPaint)

        // Rayas dinámicas de velocidad
        for (dashX in groundDashes) {
            val dashY = groundY + 8f * density
            canvas.drawLine(dashX, dashY, dashX + 12f * density, dashY, groundDashPaint)
        }
    }

    private fun drawObstacles(canvas: Canvas) {
        for (obs in obstacles) {
            val top = groundY - obs.height
            val left = obs.x
            val right = left + obs.width
            val bottom = groundY

            when (obs.type) {
                ObstacleType.Phone -> {
                    // Cuerpo del smartphone distractor
                    val rect = RectF(left, top, right, bottom)
                    canvas.drawRoundRect(rect, 6f * density, 6f * density, obstaclePhonePaint)

                    // Pantalla interna
                    val screenRect = RectF(left + 2f * density, top + 5f * density, right - 2f * density, bottom - 5f * density)
                    canvas.drawRoundRect(screenRect, 3f * density, 3f * density, obstacleScreenPaint)

                    // Notificación roja superior
                    canvas.drawCircle(right - 3f * density, top + 2f * density, 7f * density, obstacleBadgePaint)
                    canvas.drawText("!", right - 3f * density, top + 6f * density, obstacleIconPaint)
                }
                ObstacleType.NotificationBubble -> {
                    // Burbuja flotante de red social / notificación
                    val rect = RectF(left, top, right, bottom)
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, obstacleBadgePaint)

                    // Cola de bocadillo de chat
                    val tail = Path().apply {
                        moveTo(left + 6f * density, bottom)
                        lineTo(left + 12f * density, bottom)
                        lineTo(left + 4f * density, bottom + 5f * density)
                        close()
                    }
                    canvas.drawPath(tail, obstacleBadgePaint)

                    // Icono de campana / mensaje
                    canvas.drawText("🔔", left + obs.width / 2f, top + obs.height * 0.65f, obstacleIconPaint)
                }
                ObstacleType.DoubleAlert -> {
                    // Doble notificación apilada
                    val rect1 = RectF(left, top, right, top + obs.height * 0.48f)
                    canvas.drawRoundRect(rect1, 6f * density, 6f * density, obstaclePhonePaint)
                    canvas.drawCircle(right - 2f * density, top + 2f * density, 5f * density, obstacleBadgePaint)

                    val rect2 = RectF(left + 4f * density, top + obs.height * 0.52f, right - 4f * density, bottom)
                    canvas.drawRoundRect(rect2, 6f * density, 6f * density, obstacleBadgePaint)
                    canvas.drawText("💬", left + obs.width / 2f, bottom - 6f * density, obstacleIconPaint)
                }
            }
        }
    }

    private fun drawPadlock(canvas: Canvas) {
        val bodyHeight = padlockHeight * 0.62f
        val shackleHeight = padlockHeight * 0.38f
        val bodyTop = padlockY + shackleHeight
        val bodyBottom = padlockY + padlockHeight

        // Arco / Gancho metálico superior (Shackle)
        val shackleLeft = padlockX + padlockWidth * 0.22f
        val shackleRight = padlockX + padlockWidth * 0.78f
        val shackleTop = padlockY + 2f * density

        val shacklePath = Path().apply {
            moveTo(shackleLeft, bodyTop + 4f * density)
            lineTo(shackleLeft, shackleTop + 10f * density)
            quadTo(
                padlockX + padlockWidth * 0.5f,
                shackleTop - 3f * density,
                shackleRight,
                shackleTop + 10f * density
            )
            lineTo(shackleRight, bodyTop + 4f * density)
        }
        canvas.drawPath(shacklePath, padlockShacklePaint)

        // Cuerpo rectangular redondeado del candado
        val bodyRect = RectF(padlockX, bodyTop, padlockX + padlockWidth, bodyBottom)
        canvas.drawRoundRect(bodyRect, 8f * density, 8f * density, padlockBodyPaint)

        // Ojo de la cerradura (Círculo + ranura vertical)
        val centerX = padlockX + padlockWidth / 2f
        val centerY = bodyTop + bodyHeight * 0.42f
        canvas.drawCircle(centerX, centerY, 3.8f * density, padlockKeyholePaint)

        val slotPath = Path().apply {
            moveTo(centerX - 1.8f * density, centerY)
            lineTo(centerX + 1.8f * density, centerY)
            lineTo(centerX + 2.4f * density, centerY + 7.5f * density)
            lineTo(centerX - 2.4f * density, centerY + 7.5f * density)
            close()
        }
        canvas.drawPath(slotPath, padlockKeyholePaint)

        // Patitas animadas corriendo si está en el suelo
        val isOnGround = padlockY >= groundY - padlockHeight - 1f
        val legLength = 7f * density

        if (isOnGround && gameState == State.PLAYING) {
            val leftLegOffset = if (runAnimFrame % 2 == 0) -4f * density else 4f * density
            val rightLegOffset = if (runAnimFrame % 2 == 0) 4f * density else -4f * density

            // Pata izquierda
            canvas.drawLine(
                padlockX + padlockWidth * 0.3f,
                bodyBottom,
                padlockX + padlockWidth * 0.3f + leftLegOffset,
                bodyBottom + legLength,
                padlockFeetPaint
            )

            // Pata derecha
            canvas.drawLine(
                padlockX + padlockWidth * 0.7f,
                bodyBottom,
                padlockX + padlockWidth * 0.7f + rightLegOffset,
                bodyBottom + legLength,
                padlockFeetPaint
            )
        } else if (!isOnGround) {
            // Saltando: patas recogidas hacia atrás
            canvas.drawLine(
                padlockX + padlockWidth * 0.3f,
                bodyBottom,
                padlockX + padlockWidth * 0.15f,
                bodyBottom + 4f * density,
                padlockFeetPaint
            )
            canvas.drawLine(
                padlockX + padlockWidth * 0.7f,
                bodyBottom,
                padlockX + padlockWidth * 0.55f,
                bodyBottom + 4f * density,
                padlockFeetPaint
            )
        }
    }

    private fun drawOverlays(canvas: Canvas) {
        val centerX = width / 2f

        when (gameState) {
            State.READY -> {
                canvas.drawText("🏃 Toca para saltar y comenzar", centerX, groundY * 0.45f, textPaint)
                canvas.drawText("Salta por encima de las alertas y distracciones", centerX, groundY * 0.45f + 20f * density, textSubPaint)
            }
            State.GAME_OVER -> {
                canvas.drawText("💥 ¡Caíste en la distracción!", centerX, groundY * 0.36f, textPaint)
                canvas.drawText("Puntuación: $score   |   Récord: $highScore", centerX, groundY * 0.36f + 22f * density, textPaint)
                canvas.drawText("Toca la pantalla para intentarlo de nuevo", centerX, groundY * 0.36f + 44f * density, textSubPaint)
            }
            State.PLAYING -> {
                // Durante el juego, el puntaje se actualiza en el HUD superior
            }
        }
    }
}
