package com.example.wgautotoggle

import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    var excluded: Boolean
)