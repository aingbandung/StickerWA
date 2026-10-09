package com.firdaus.stickerai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * View untuk memotong (crop) foto: geser kotak untuk memindah, tarik sudut/sisi untuk
 * mengubah ukuran. Mendukung mode bebas dan rasio terkunci (mis. 1:1), serta putar 90°.
 */
class CropView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val crop = RectF()
    private var aspect = 0f // 0 = bebas, selain itu lebar/tinggi

    private val density = resources.displayMetrics.density
    private val handleRadius = 11f * density
    private val touchSlop = 28f * density
    private val minSizeBase = 56f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 211, 102)
        style = Paint.Style.FILL
    }

    private var dragLeft = false
    private var dragRight = false
    private var dragTop = false
    private var dragBottom = false
    private var dragMove = false
    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(b: Bitmap) {
        bitmap = b
        layoutImage()
        resetCrop()
        invalidate()
    }

    fun currentBitmap(): Bitmap? = bitmap

    fun setAspect(ratio: Float) {
        aspect = ratio
        resetCrop()
        invalidate()
    }

    fun rotate90() {
        val b = bitmap ?: return
        val m = Matrix().apply { postRotate(90f) }
        bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        layoutImage()
        resetCrop()
        invalidate()
    }

    fun getCroppedBitmap(): Bitmap? {
        val b = bitmap ?: return null
        if (imageRect.width() <= 0f) return null
        val scale = b.width / imageRect.width()
        val l = ((crop.left - imageRect.left) * scale).roundToInt().coerceIn(0, b.width - 1)
        val t = ((crop.top - imageRect.top) * scale).roundToInt().coerceIn(0, b.height - 1)
        val w = (crop.width() * scale).roundToInt().coerceIn(1, b.width - l)
        val h = (crop.height() * scale).roundToInt().coerceIn(1, b.height - t)
        return Bitmap.createBitmap(b, l, t, w, h)
    }

    // ------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
        resetCrop()
    }

    private fun layoutImage() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = 16f * density
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        val scale = min(availW / b.width, availH / b.height)
        val w = b.width * scale
        val h = b.height * scale
        imageRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun minSz(): Float = min(minSizeBase, min(imageRect.width(), imageRect.height()) / 2f)

    private fun resetCrop() {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return
        var w = imageRect.width() * 0.9f
        var h = imageRect.height() * 0.9f
        if (aspect > 0f) {
            if (w / h > aspect) w = h * aspect else h = w / aspect
        }
        val cx = imageRect.centerX()
        val cy = imageRect.centerY()
        crop.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, imageRect, bitmapPaint)

        // redupkan area di luar kotak crop
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, crop.top, dimPaint)
        canvas.drawRect(imageRect.left, crop.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, crop.top, crop.left, crop.bottom, dimPaint)
        canvas.drawRect(crop.right, crop.top, imageRect.right, crop.bottom, dimPaint)

        // garis bantu 3x3
        val tw = crop.width() / 3f
        val th = crop.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(crop.left + tw * i, crop.top, crop.left + tw * i, crop.bottom, gridPaint)
            canvas.drawLine(crop.left, crop.top + th * i, crop.right, crop.top + th * i, gridPaint)
        }
        canvas.drawRect(crop, borderPaint)

        // pegangan sudut
        canvas.drawCircle(crop.left, crop.top, handleRadius, handlePaint)
        canvas.drawCircle(crop.right, crop.top, handleRadius, handlePaint)
        canvas.drawCircle(crop.left, crop.bottom, handleRadius, handlePaint)
        canvas.drawCircle(crop.right, crop.bottom, handleRadius, handlePaint)
        if (aspect == 0f) {
            val r = handleRadius * 0.7f
            canvas.drawCircle(crop.centerX(), crop.top, r, handlePaint)
            canvas.drawCircle(crop.centerX(), crop.bottom, r, handlePaint)
            canvas.drawCircle(crop.left, crop.centerY(), r, handlePaint)
            canvas.drawCircle(crop.right, crop.centerY(), r, handlePaint)
        }
    }

    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                pickHandle(event.x, event.y)
                return dragLeft || dragRight || dragTop || dragBottom || dragMove
            }
            MotionEvent.ACTION_MOVE -> {
                handleDrag(event.x, event.y)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clearDrag()
                return true
            }
        }
        return true
    }

    private fun clearDrag() {
        dragLeft = false
        dragRight = false
        dragTop = false
        dragBottom = false
        dragMove = false
    }

    private fun pickHandle(x: Float, y: Float) {
        clearDrag()
        val nearL = abs(x - crop.left) <= touchSlop
        val nearR = abs(x - crop.right) <= touchSlop
        val nearT = abs(y - crop.top) <= touchSlop
        val nearB = abs(y - crop.bottom) <= touchSlop
        val inX = x >= crop.left - touchSlop && x <= crop.right + touchSlop
        val inY = y >= crop.top - touchSlop && y <= crop.bottom + touchSlop

        var hx = when {
            nearL && nearR -> if (abs(x - crop.left) <= abs(x - crop.right)) -1 else 1
            nearL -> -1
            nearR -> 1
            else -> 0
        }
        var hy = when {
            nearT && nearB -> if (abs(y - crop.top) <= abs(y - crop.bottom)) -1 else 1
            nearT -> -1
            nearB -> 1
            else -> 0
        }

        if (aspect > 0f) {
            // rasio terkunci: hanya sudut yang bisa ditarik
            if (hx != 0 && hy != 0) {
                dragLeft = hx < 0
                dragRight = hx > 0
                dragTop = hy < 0
                dragBottom = hy > 0
            } else if (crop.contains(x, y)) {
                dragMove = true
            }
        } else {
            if (hx != 0 && hy == 0 && !inY) hx = 0
            if (hy != 0 && hx == 0 && !inX) hy = 0
            dragLeft = hx < 0
            dragRight = hx > 0
            dragTop = hy < 0
            dragBottom = hy > 0
            if (hx == 0 && hy == 0 && crop.contains(x, y)) dragMove = true
        }
    }

    private fun handleDrag(x: Float, y: Float) {
        val cx = min(max(x, imageRect.left), imageRect.right)
        val cy = min(max(y, imageRect.top), imageRect.bottom)
        when {
            dragMove -> {
                var dx = x - lastX
                var dy = y - lastY
                dx = max(dx, imageRect.left - crop.left)
                dx = min(dx, imageRect.right - crop.right)
                dy = max(dy, imageRect.top - crop.top)
                dy = min(dy, imageRect.bottom - crop.bottom)
                crop.offset(dx, dy)
            }
            aspect > 0f -> resizeLocked(cx, cy)
            else -> {
                val m = minSz()
                if (dragLeft) crop.left = min(cx, crop.right - m)
                if (dragRight) crop.right = max(cx, crop.left + m)
                if (dragTop) crop.top = min(cy, crop.bottom - m)
                if (dragBottom) crop.bottom = max(cy, crop.top + m)
            }
        }
    }

    private fun resizeLocked(x: Float, y: Float) {
        if (!(dragLeft || dragRight) || !(dragTop || dragBottom)) return
        val ax = if (dragLeft) crop.right else crop.left
        val ay = if (dragTop) crop.bottom else crop.top
        val maxW = if (dragLeft) ax - imageRect.left else imageRect.right - ax
        val maxH = if (dragTop) ay - imageRect.top else imageRect.bottom - ay
        val dx = max(0f, if (dragLeft) ax - x else x - ax)
        val dy = max(0f, if (dragTop) ay - y else y - ay)

        var w = max(dx, dy * aspect)
        w = max(w, minSz() * max(1f, aspect))
        w = min(w, min(maxW, maxH * aspect))
        val h = w / aspect

        val left = if (dragLeft) ax - w else ax
        val top = if (dragTop) ay - h else ay
        crop.set(left, top, left + w, top + h)
    }
}
