package app.voyahmusic.shared.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal object MusicGlyphs {
    enum class Icon {
        NOTE, SEND, SETTINGS, BACK, PREVIOUS, NEXT, PLAY, PAUSE, SHUFFLE, REPEAT, REPEAT_ONE, MORE,
        CLOSE, SEARCH, FOLDER, FOLDER_ADD
    }

    internal class Glyph(private val icon: Icon, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            paint.setColor(color)
            paint.setStrokeWidth(1.8f)
            paint.setStrokeCap(Paint.Cap.ROUND)
            paint.setStrokeJoin(Paint.Join.ROUND)
        }

        override fun draw(canvas: Canvas) {
            val bounds = getBounds()
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            paint.setStyle(Paint.Style.STROKE)
            when (icon) {
                Icon.NOTE -> {
                    lines(canvas, 9f, 17f, 9f, 5.8f, 20f, 3f, 20f, 14.5f)
                    lines(canvas, 9f, 9f, 20f, 6.2f)
                    paint.setStyle(Paint.Style.FILL)
                    canvas.drawOval(3f, 15f, 10f, 21f, paint)
                    canvas.drawOval(14f, 12.2f, 21f, 18.2f, paint)
                }

                Icon.SEND -> {
                    lines(canvas, 12f, 15f, 12f, 3f, 7.5f, 7.5f)
                    lines(canvas, 12f, 3f, 16.5f, 7.5f)
                    lines(canvas, 4f, 13f, 4f, 20f, 20f, 20f, 20f, 13f)
                }

                Icon.SETTINGS -> {
                    canvas.drawCircle(12f, 12f, 6.5f, paint)
                    canvas.drawCircle(12f, 12f, 2.7f, paint)
                    for (i in 0..7) {
                        canvas.drawLine(12f, 3f, 12f, 5.4f, paint)
                        canvas.rotate(45f, 12f, 12f)
                    }
                }

                Icon.BACK -> {
                    lines(canvas, 20f, 12f, 4f, 12f, 10f, 6f)
                    lines(canvas, 4f, 12f, 10f, 18f)
                }

                Icon.PREVIOUS -> {
                    canvas.drawLine(6f, 5f, 6f, 19f, paint)
                    paint.setStyle(Paint.Style.FILL)
                    lines(canvas, 8f, 12f, 19f, 5f, 19f, 19f, 8f, 12f)
                }

                Icon.NEXT -> {
                    canvas.drawLine(18f, 5f, 18f, 19f, paint)
                    paint.setStyle(Paint.Style.FILL)
                    lines(canvas, 16f, 12f, 5f, 5f, 5f, 19f, 16f, 12f)
                }

                Icon.PLAY -> {
                    paint.setStyle(Paint.Style.FILL)
                    lines(canvas, 7f, 4f, 20f, 12f, 7f, 20f, 7f, 4f)
                }

                Icon.PAUSE -> {
                    paint.setStyle(Paint.Style.FILL)
                    canvas.drawRoundRect(6f, 5f, 10.2f, 19f, 0.8f, 0.8f, paint)
                    canvas.drawRoundRect(13.8f, 5f, 18f, 19f, 0.8f, 0.8f, paint)
                }

                Icon.SHUFFLE -> {
                    lines(canvas, 3f, 6f, 6f, 6f, 18f, 18f, 21f, 18f, 18f, 15f)
                    lines(canvas, 21f, 18f, 18f, 21f)
                    lines(canvas, 3f, 18f, 6f, 18f, 9.8f, 14.2f)
                    lines(canvas, 13.8f, 10.2f, 18f, 6f, 21f, 6f, 18f, 3f)
                    lines(canvas, 21f, 6f, 18f, 9f)
                }

                Icon.REPEAT, Icon.REPEAT_ONE -> {
                    lines(canvas, 4f, 11f, 4f, 6f, 20f, 6f, 17f, 3f)
                    lines(canvas, 20f, 6f, 17f, 9f)
                    lines(canvas, 20f, 13f, 20f, 18f, 4f, 18f, 7f, 21f)
                    lines(canvas, 4f, 18f, 7f, 15f)
                    if (icon == Icon.REPEAT_ONE) lines(canvas, 11f, 11f, 13f, 10f, 13f, 15f)
                }

                Icon.MORE -> {
                    paint.setStyle(Paint.Style.FILL)
                    var y = 5
                    while (y <= 19) {
                        canvas.drawCircle(12f, y.toFloat(), 1.65f, paint)
                        y += 7
                    }
                }

                Icon.CLOSE -> {
                    lines(canvas, 6f, 6f, 18f, 18f)
                    lines(canvas, 6f, 18f, 18f, 6f)
                }

                Icon.SEARCH -> {
                    canvas.drawCircle(10f, 10f, 5.5f, paint)
                    lines(canvas, 14.2f, 14.2f, 20f, 20f)
                }

                Icon.FOLDER, Icon.FOLDER_ADD -> {
                    lines(canvas, 3f, 5f, 10f, 5f, 12.4f, 8f, 21f, 8f, 21f, 20f, 3f, 20f, 3f, 5f)
                    if (icon == Icon.FOLDER_ADD) {
                        canvas.drawLine(12f, 10.5f, 12f, 17.5f, paint)
                        canvas.drawLine(8.5f, 14f, 15.5f, 14f, paint)
                    }
                }
            }
            canvas.restore()
        }

        fun lines(canvas: Canvas, vararg points: Float) {
            path.rewind()
            path.moveTo(points[0], points[1])
            var i = 2
            while (i < points.size) {
                path.lineTo(points[i], points[i + 1])
                i += 2
            }
            canvas.drawPath(path, paint)
        }


        override fun setAlpha(alpha: Int) {
            paint.setAlpha(alpha)
        }

        override fun setColorFilter(filter: ColorFilter?) {
            paint.setColorFilter(filter)
        }

        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }
    }
}
