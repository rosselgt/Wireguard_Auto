package com.example.wgautotoggle

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

object UiStyle {
    fun pill(context: Context, colorRes: Int, radiusDp: Float): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(ContextCompat.getColor(context, colorRes))
        }
    }

    fun outline(context: Context, strokeColorRes: Int, radiusDp: Float): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setStroke((1.2f * density).toInt(), ContextCompat.getColor(context, strokeColorRes))
            setColor(Color.TRANSPARENT)
        }
    }
}