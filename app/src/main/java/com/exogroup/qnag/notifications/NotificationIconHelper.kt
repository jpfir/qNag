package com.exogroup.qnag.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates colored large-icon bitmaps for qNag notifications.
 *
 * Android's small status-bar icon must be monochrome (system requirement), but the large icon
 * (circular accent shown on the left of the compact notification row) is rendered in full color
 * by the notification shade. Using setLargeIcon() alongside setColor() gives consistent
 * colored-accent behavior across OEMs that may not respect setColor() in compact view.
 *
 * Cache avoids recreating bitmaps every poll cycle.
 */
object NotificationIconHelper {

    private val cache = ConcurrentHashMap<NotificationVisualState, Bitmap>()

    fun largeIcon(state: NotificationVisualState): Bitmap =
        cache.getOrPut(state) { makeIcon(state) }

    private fun makeIcon(state: NotificationVisualState, sizePx: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx / 2f

        // Colored background circle
        paint.style = Paint.Style.FILL
        paint.color = visualStateColor(state)
        canvas.drawCircle(cx, cy, r, paint)

        // White symbol: ✓ for OK, ! for degraded/critical, ? for unknown
        val symbol = when (state) {
            NotificationVisualState.OK            -> "✓"  // ✓
            NotificationVisualState.UNKNOWN       -> "?"
            else                                  -> "!"
        }
        paint.color = android.graphics.Color.WHITE
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = sizePx * 0.58f
        paint.textAlign = Paint.Align.CENTER
        val yPos = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(symbol, cx, yPos, paint)

        return bmp
    }
}
