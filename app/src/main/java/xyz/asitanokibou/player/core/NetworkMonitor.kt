package xyz.asitanokibou.player.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前默认网络是否"计费网络"(metered):移动数据、计费 WiFi、热点共享、漫游都算。
 * 无网络或无法判定时一律按非计费处理 —— 取流失败由播放/下载自己报错,闸门不背这个锅。
 */
interface NetworkMonitor {
    val isMetered: StateFlow<Boolean>
}

/** ConnectivityManager 实现:跟随系统默认网络变化更新 [isMetered] */
class ConnectivityNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val _isMetered = MutableStateFlow(readActiveNetworkMetered())
    override val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isMetered.value =
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        // 切换网络时新旧默认网络的回调会交错到达,统一以系统当前的默认网络为准重新判定,
        // 否则 onLost(旧网络) 会把刚切过去的移动数据误改回"非计费"。
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()
    }

    init {
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
    }

    private fun refresh() {
        _isMetered.value = readActiveNetworkMetered()
    }

    private fun readActiveNetworkMetered(): Boolean =
        runCatching { connectivityManager?.isActiveNetworkMetered ?: false }.getOrDefault(false)
}
