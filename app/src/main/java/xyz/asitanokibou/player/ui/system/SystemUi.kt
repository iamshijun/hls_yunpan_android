package xyz.asitanokibou.player.ui.system

import android.app.Activity
import android.content.Context

const val DOUBLE_TAP_SEEK_MS = 10_000L

fun readBrightness(context: Context): Float {
    val window = (context as? Activity)?.window ?: return 0.5f
    return if (window.attributes.screenBrightness >= 0f) {
        window.attributes.screenBrightness
    } else {
        0.5f
    }
}

fun setBrightness(context: Context, value: Float) {
    val window = (context as? Activity)?.window ?: return
    val attrs = window.attributes
    attrs.screenBrightness = value.coerceIn(0.01f, 1f)
    window.attributes = attrs
}
