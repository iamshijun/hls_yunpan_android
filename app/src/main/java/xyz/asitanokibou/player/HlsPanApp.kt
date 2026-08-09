package xyz.asitanokibou.player

import android.app.Application
import xyz.asitanokibou.player.di.AppContainer

class HlsPanApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
