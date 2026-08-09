package xyz.asitanokibou.player.proxy

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.cache.FsidStore
import java.util.concurrent.ConcurrentHashMap

class FsidDirectoryLoader(
    private val baidu: BaiduClient,
    private val fsidStore: FsidStore,
) {
    private val dirLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun loadDirectory(dirPath: String) {
        val lock = dirLocks.getOrPut(dirPath) { Mutex() }
        lock.withLock {
            Log.i(TAG, "开始加载目录 [$dirPath] 的文件列表和fsid...")
            val files = baidu.getFileListAll(dirPath)
            val map = HashMap<String, Long>(files.size)
            for (f in files) {
                if (f.path.isNotEmpty() && f.fsId != 0L) {
                    map[f.path] = f.fsId
                }
            }
            fsidStore.setMany(map)
            Log.i(TAG, "目录 [$dirPath] 加载完成，共 ${map.size} 个文件")
        }
    }

    companion object {
        private const val TAG = "FsidDirectoryLoader"
    }
}
