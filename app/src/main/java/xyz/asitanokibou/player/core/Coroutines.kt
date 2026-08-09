package xyz.asitanokibou.player.core

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * CoroutineExceptionHandler
 * */
fun appCeHandler(tag: String, onError: (Throwable) -> Unit = {}): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        Log.e(tag, "未捕获异常", t)
        onError(t)
    }
