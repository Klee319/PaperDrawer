package com.example.paperdrawers.infrastructure.persistence

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.cache.DrawerCache
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 非同期保存機能を持つ DrawerRepository ラッパー。
 *
 * Why: ドロワーの保存処理をバックグラウンドで実行することで、
 * メインスレッドのブロッキングを防ぎ、サーバーのパフォーマンスを向上させる。
 * Decorator パターンを使用して、既存のリポジトリに非同期保存機能を追加。
 *
 * @property delegate 実際の永続化を行うリポジトリ
 * @property plugin プラグインインスタンス
 * @property cache ドロワーキャッシュ
 * @property logger ログ出力用のロガー
 */
class AsyncDrawerRepository(
    private val delegate: DrawerRepository,
    private val plugin: Plugin,
    private val cache: DrawerCache,
    private val logger: Logger
) : DrawerRepository {

    /**
     * 保存待ちのドロワーのロケーションキーセット。
     *
     * Why: 同じドロワーが複数回キューに入ることを防ぎ、
     * 重複した保存処理を回避する。
     */
    private val pendingSaves: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 保存キュー。
     *
     * Why: LinkedBlockingQueue はスレッドセーフで、
     * バックグラウンドタスクがキューからドロワーを取り出して保存処理を行う。
     */
    private val saveQueue: BlockingQueue<DrawerBlock> = LinkedBlockingQueue()

    /**
     * バックグラウンド保存タスク。
     */
    @Volatile
    private var saveTask: BukkitTask? = null

    /**
     * シャットダウン中フラグ。
     */
    @Volatile
    private var shuttingDown: Boolean = false

    /**
     * 削除済みロケーションの追跡セット。
     *
     * Why: delete() 呼び出し後に、既にキューからpoll済みの保存リクエストが
     * runTask で実行されてデータが復活することを防ぐ。
     * 保存処理でこのセットをチェックし、削除済みのロケーションへの保存をスキップする。
     */
    private val deletedLocations: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 処理済み保存数（メトリクス用）。
     */
    @Volatile
    private var processedSaveCount: Long = 0

    /**
     * ドロワーブロックを保存する。
     *
     * キャッシュに即座に反映し、保存処理をバックグラウンドキューに追加する。
     * これにより、呼び出し側は保存完了を待たずに処理を継続できる。
     *
     * @param drawer 保存するドロワーブロック
     */
    override fun save(drawer: DrawerBlock) {
        // save が呼ばれた場合、削除済みマークを解除（再設置等）
        deletedLocations.remove(drawer.getLocationKey())

        // Update cache immediately for read consistency
        cache.put(drawer)

        // Queue for async save
        queueSave(drawer)
    }

    /**
     * 指定された位置のドロワーブロックを取得する。
     *
     * キャッシュを最初にチェックし、キャッシュヒットの場合は即座に返す。
     * キャッシュミスの場合はデリゲートから取得し、結果をキャッシュに追加する。
     *
     * @param location 検索する位置
     * @return ドロワーブロック、存在しない場合はnull
     */
    override fun findByLocation(location: Location): DrawerBlock? {
        val key = locationToKey(location)

        // Try cache first
        val cached = cache.get(key)
        if (cached != null) {
            return cached
        }

        // Cache miss - load from delegate
        val drawer = delegate.findByLocation(location)
        if (drawer != null) {
            cache.put(drawer)
        }

        return drawer
    }

    /**
     * 指定されたチャンク内のすべてのドロワーブロックを取得する。
     *
     * デリゲートから取得し、結果をキャッシュに追加する。
     *
     * @param chunk 検索するチャンク
     * @return チャンク内のドロワーブロックのリスト
     */
    override fun findByChunk(chunk: Chunk): List<DrawerBlock> {
        val drawers = delegate.findByChunk(chunk)

        // キャッシュに新しいデータがある場合はそちらを優先する
        // Why: 非同期保存がまだフラッシュされていない場合、PDCのデータは古い可能性がある
        // キャッシュには最新のsave()結果が即座に反映されているため、キャッシュを優先する
        return drawers.map { drawer ->
            val key = locationToKey(drawer.location)
            val cached = cache.get(key)
            if (cached != null) {
                cached
            } else {
                cache.put(drawer)
                drawer
            }
        }
    }

    /**
     * 指定された位置のドロワーブロックを削除する。
     *
     * キャッシュと保存キューから削除し、デリゲートにも即座に削除を依頼する。
     * 削除は即座に行う必要があるため、非同期ではない。
     *
     * @param location 削除するドロワーの位置
     */
    override fun delete(location: Location) {
        val key = locationToKey(location)

        // 削除済みとしてマーク（非同期保存による復活を防止）
        deletedLocations.add(key)

        // Remove from cache
        cache.invalidate(key)

        // Remove from pending saves
        pendingSaves.remove(key)

        // Remove from queue (best effort - may not be perfectly synchronized)
        saveQueue.removeIf { it.getLocationKey() == key }

        // Delegate delete immediately (not async)
        delegate.delete(location)
    }

    /**
     * 指定された位置にドロワーブロックが存在するかどうかを確認する。
     *
     * キャッシュを最初にチェックし、キャッシュヒットの場合は即座にtrueを返す。
     *
     * @param location 確認する位置
     * @return ドロワーが存在する場合true
     */
    override fun exists(location: Location): Boolean {
        val key = locationToKey(location)

        // Check cache first
        if (cache.get(key) != null) {
            return true
        }

        // Fallback to delegate
        return delegate.exists(location)
    }

    /**
     * バックグラウンド保存タスクを開始する。
     *
     * 指定されたインターバルごとに保存キューを処理するタスクをスケジュールする。
     * 既にタスクが実行中の場合は何もしない。
     *
     * @param intervalTicks 処理間隔（tick単位、20tick = 1秒）
     */
    fun startBackgroundSaveTask(intervalTicks: Long) {
        if (saveTask != null) {
            logger.warning("Background save task is already running")
            return
        }

        shuttingDown = false

        saveTask = plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable { processSaveQueue() },
            intervalTicks,  // Initial delay
            intervalTicks   // Period
        )

        logger.info("Started background save task with interval: ${intervalTicks}ticks")
    }

    /**
     * バックグラウンド保存タスクを停止する。
     */
    fun stopBackgroundSaveTask() {
        saveTask?.cancel()
        saveTask = null
        logger.fine("Stopped background save task")
    }

    /**
     * 保留中のすべての保存を強制的に処理する。
     *
     * プラグイン無効化時に呼び出し、すべてのデータが確実に保存されるようにする。
     * この処理はメインスレッドで同期的に実行される。
     */
    fun flushPendingSaves() {
        shuttingDown = true
        stopBackgroundSaveTask()

        val pendingCount = saveQueue.size
        if (pendingCount > 0) {
            logger.info("Flushing $pendingCount pending saves...")

            var savedCount = 0
            var errorCount = 0

            while (!saveQueue.isEmpty()) {
                val drawer = saveQueue.poll() ?: break

                // 削除済みロケーションへの保存をスキップ
                if (deletedLocations.contains(drawer.getLocationKey())) {
                    pendingSaves.remove(drawer.getLocationKey())
                    continue
                }

                try {
                    // Execute on main thread for thread safety with Bukkit API
                    if (plugin.server.isPrimaryThread) {
                        delegate.save(drawer)
                    } else {
                        // Schedule sync task and wait (blocking)
                        plugin.server.scheduler.runTask(plugin, Runnable {
                            delegate.save(drawer)
                        })
                    }
                    savedCount++
                    pendingSaves.remove(drawer.getLocationKey())
                } catch (e: Exception) {
                    errorCount++
                    logger.log(Level.SEVERE, "Failed to flush save for drawer at ${drawer.getLocationKey()}", e)
                }
            }

            if (errorCount > 0) {
                logger.warning("Flush completed: $savedCount saved, $errorCount errors")
            } else {
                logger.info("Flush completed: $savedCount drawers saved successfully")
            }
        }

        pendingSaves.clear()
        deletedLocations.clear()
    }

    /**
     * 現在の統計情報を取得する。
     *
     * @return 非同期リポジトリの統計情報
     */
    fun getStats(): AsyncRepositoryStats {
        return AsyncRepositoryStats(
            pendingSaveCount = pendingSaves.size,
            queueSize = saveQueue.size,
            processedSaveCount = processedSaveCount,
            cacheStats = cache.getStats(),
            isBackgroundTaskRunning = saveTask != null
        )
    }

    /**
     * ドロワーを保存キューに追加する。
     *
     * 同じロケーションのドロワーが既にキューにある場合は、
     * 新しいバージョンで置き換える。
     *
     * @param drawer 保存するドロワー
     */
    private fun queueSave(drawer: DrawerBlock) {
        if (shuttingDown) {
            // During shutdown, save directly
            try {
                delegate.save(drawer)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to save drawer during shutdown: ${drawer.getLocationKey()}", e)
            }
            return
        }

        val locationKey = drawer.getLocationKey()

        // If already pending, remove old entry from queue
        if (pendingSaves.contains(locationKey)) {
            saveQueue.removeIf { it.getLocationKey() == locationKey }
        }

        // Add to pending set and queue
        pendingSaves.add(locationKey)
        saveQueue.offer(drawer)

        logger.fine("Queued save for drawer at $locationKey (queue size: ${saveQueue.size})")
    }

    /**
     * 保存キューを処理する。
     *
     * バックグラウンドスレッドから定期的に呼び出され、
     * キュー内のドロワーをバッチで保存する。
     */
    private fun processSaveQueue() {
        if (saveQueue.isEmpty()) {
            return
        }

        val batchSize = minOf(saveQueue.size, MAX_BATCH_SIZE)
        var savedCount = 0
        var errorCount = 0

        repeat(batchSize) {
            val drawer = saveQueue.poll() ?: return@repeat

            try {
                // Save must be done on main thread for Bukkit API
                plugin.server.scheduler.runTask(plugin, Runnable {
                    try {
                        // 削除済みロケーションへの保存をスキップ（delete後の復活を防止）
                        if (deletedLocations.contains(drawer.getLocationKey())) {
                            logger.fine("Skipping save for deleted drawer at ${drawer.getLocationKey()}")
                            return@Runnable
                        }
                        delegate.save(drawer)
                        processedSaveCount++
                    } catch (e: Exception) {
                        logger.log(Level.WARNING, "Failed to save drawer at ${drawer.getLocationKey()}", e)
                    } finally {
                        pendingSaves.remove(drawer.getLocationKey())
                    }
                })
                savedCount++
            } catch (e: Exception) {
                errorCount++
                logger.log(Level.WARNING, "Failed to schedule save for drawer at ${drawer.getLocationKey()}", e)
                pendingSaves.remove(drawer.getLocationKey())
            }
        }

        if (savedCount > 0) {
            logger.fine("Processed $savedCount saves from queue (errors: $errorCount, remaining: ${saveQueue.size})")
        }

        // 定期的にdeletedLocationsをクリーンアップ（キューが空の場合、保留中の保存もない）
        if (saveQueue.isEmpty() && pendingSaves.isEmpty()) {
            deletedLocations.clear()
        }
    }

    /**
     * Location からキャッシュキーを生成する。
     *
     * @param location 位置
     * @return ロケーションキー
     */
    private fun locationToKey(location: Location): String {
        return "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
    }

    companion object {
        /** バッチ処理の最大サイズ */
        private const val MAX_BATCH_SIZE: Int = 50
    }
}

/**
 * 非同期リポジトリの統計情報。
 *
 * @property pendingSaveCount 保留中の保存数
 * @property queueSize 保存キューのサイズ
 * @property processedSaveCount 処理済み保存数
 * @property cacheStats キャッシュ統計情報
 * @property isBackgroundTaskRunning バックグラウンドタスクが実行中かどうか
 */
data class AsyncRepositoryStats(
    val pendingSaveCount: Int,
    val queueSize: Int,
    val processedSaveCount: Long,
    val cacheStats: com.example.paperdrawers.infrastructure.cache.CacheStats,
    val isBackgroundTaskRunning: Boolean
) {
    override fun toString(): String {
        return buildString {
            append("AsyncRepositoryStats(\n")
            append("  pendingSaves=$pendingSaveCount, ")
            append("queueSize=$queueSize, ")
            append("processed=$processedSaveCount, ")
            append("backgroundTask=${if (isBackgroundTaskRunning) "running" else "stopped"}\n")
            append("  cache=$cacheStats\n")
            append(")")
        }
    }
}
