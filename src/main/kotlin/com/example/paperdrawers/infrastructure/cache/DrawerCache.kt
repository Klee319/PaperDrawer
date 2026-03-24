package com.example.paperdrawers.infrastructure.cache

import com.example.paperdrawers.domain.model.DrawerBlock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * 頻繁にアクセスされるドロワーのインメモリキャッシュ。
 *
 * Why: データベース/PDC への読み取りアクセスを削減し、ホットなドロワーへのアクセスを高速化する。
 * ConcurrentHashMap を使用してスレッドセーフ性を確保し、複数スレッドからの同時アクセスに対応する。
 *
 * @property maxSize キャッシュの最大サイズ（エントリ数）
 * @property expireAfterAccessMs 最終アクセスからの有効期限（ミリ秒）
 * @property logger ログ出力用のロガー
 */
class DrawerCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val expireAfterAccessMs: Long = DEFAULT_EXPIRE_AFTER_ACCESS_MS,
    private val logger: Logger? = null
) {
    /**
     * キャッシュエントリ。
     *
     * @property drawer キャッシュされたドロワーブロック
     * @property lastAccessTime 最終アクセス時刻（エポックミリ秒）
     */
    data class CacheEntry(
        val drawer: DrawerBlock,
        @Volatile var lastAccessTime: Long = System.currentTimeMillis()
    ) {
        /**
         * アクセス時刻を更新する。
         */
        fun touch() {
            lastAccessTime = System.currentTimeMillis()
        }
    }

    private val cache: ConcurrentHashMap<String, CacheEntry> = ConcurrentHashMap()
    private val hitCount: AtomicLong = AtomicLong(0)
    private val missCount: AtomicLong = AtomicLong(0)

    /**
     * 指定されたロケーションキーに対応するドロワーを取得する。
     *
     * キャッシュにエントリが存在し、有効期限内であればドロワーを返す。
     * アクセス時にエントリのタイムスタンプを更新する（LRU相当の動作）。
     *
     * @param locationKey ロケーションキー（"world:x:y:z" 形式）
     * @return キャッシュされたドロワーブロック、存在しないか期限切れの場合はnull
     */
    fun get(locationKey: String): DrawerBlock? {
        val entry = cache[locationKey]

        if (entry == null) {
            missCount.incrementAndGet()
            return null
        }

        // Check if entry is expired
        val now = System.currentTimeMillis()
        if (now - entry.lastAccessTime > expireAfterAccessMs) {
            cache.remove(locationKey)
            missCount.incrementAndGet()
            logger?.fine("Cache entry expired for key: $locationKey")
            return null
        }

        // Update access time and return
        entry.touch()
        hitCount.incrementAndGet()
        return entry.drawer
    }

    /**
     * ドロワーをキャッシュに追加する。
     *
     * キャッシュサイズが上限に達している場合は、最も古いエントリを削除してから追加する。
     *
     * @param drawer キャッシュするドロワーブロック
     */
    fun put(drawer: DrawerBlock) {
        val locationKey = drawer.getLocationKey()

        // Evict old entries if cache is full
        if (cache.size >= maxSize && !cache.containsKey(locationKey)) {
            evictOldestEntry()
        }

        cache[locationKey] = CacheEntry(drawer)
        logger?.fine("Cached drawer at key: $locationKey")
    }

    /**
     * 指定されたロケーションキーのエントリを無効化（削除）する。
     *
     * @param locationKey 無効化するロケーションキー
     */
    fun invalidate(locationKey: String) {
        val removed = cache.remove(locationKey)
        if (removed != null) {
            logger?.fine("Invalidated cache entry for key: $locationKey")
        }
    }

    /**
     * すべてのキャッシュエントリを無効化（クリア）する。
     */
    fun invalidateAll() {
        val size = cache.size
        cache.clear()
        logger?.info("Invalidated all $size cache entries")
    }

    /**
     * 期限切れのエントリを削除するクリーンアップ処理。
     *
     * Why: 定期的に呼び出すことでメモリ使用量を抑制し、
     * 古いデータがキャッシュに残り続けることを防ぐ。
     *
     * @return 削除されたエントリ数
     */
    fun cleanup(): Int {
        val now = System.currentTimeMillis()
        var removedCount = 0

        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastAccessTime > expireAfterAccessMs) {
                iterator.remove()
                removedCount++
            }
        }

        if (removedCount > 0) {
            logger?.fine("Cleaned up $removedCount expired cache entries")
        }

        return removedCount
    }

    /**
     * キャッシュの統計情報を取得する。
     *
     * @return キャッシュ統計情報
     */
    fun getStats(): CacheStats {
        val hits = hitCount.get()
        val misses = missCount.get()
        val total = hits + misses
        val hitRate = if (total > 0) hits.toDouble() / total else 0.0

        return CacheStats(
            size = cache.size,
            hits = hits,
            misses = misses,
            hitRate = hitRate,
            maxSize = maxSize,
            expireAfterAccessMs = expireAfterAccessMs
        )
    }

    /**
     * 統計情報をリセットする。
     *
     * Why: 定期的なメトリクス収集や、設定変更後のリセットに使用。
     */
    fun resetStats() {
        hitCount.set(0)
        missCount.set(0)
        logger?.fine("Cache stats reset")
    }

    /**
     * 現在のキャッシュサイズを取得する。
     *
     * @return キャッシュ内のエントリ数
     */
    fun size(): Int = cache.size

    /**
     * キャッシュが空かどうかを判定する。
     *
     * @return キャッシュが空の場合true
     */
    fun isEmpty(): Boolean = cache.isEmpty()

    /**
     * 最も古いエントリを削除する（LRU eviction）。
     *
     * Why: キャッシュサイズが上限に達した際に、最も長くアクセスされていないエントリを
     * 削除することで、頻繁にアクセスされるエントリを優先的に保持する。
     */
    private fun evictOldestEntry() {
        var oldestKey: String? = null
        var oldestTime = Long.MAX_VALUE

        // Find the oldest entry
        for ((key, entry) in cache) {
            if (entry.lastAccessTime < oldestTime) {
                oldestTime = entry.lastAccessTime
                oldestKey = key
            }
        }

        // Remove the oldest entry
        oldestKey?.let {
            cache.remove(it)
            logger?.fine("Evicted oldest cache entry: $it")
        }
    }

    companion object {
        /** デフォルトの最大キャッシュサイズ */
        const val DEFAULT_MAX_SIZE: Int = 1000

        /** デフォルトの有効期限（1分 = 60000ミリ秒） */
        const val DEFAULT_EXPIRE_AFTER_ACCESS_MS: Long = 60000L
    }
}

/**
 * キャッシュの統計情報。
 *
 * @property size 現在のキャッシュサイズ（エントリ数）
 * @property hits キャッシュヒット数
 * @property misses キャッシュミス数
 * @property hitRate ヒット率（0.0〜1.0）
 * @property maxSize 設定された最大サイズ
 * @property expireAfterAccessMs 設定された有効期限（ミリ秒）
 */
data class CacheStats(
    val size: Int,
    val hits: Long,
    val misses: Long,
    val hitRate: Double,
    val maxSize: Int,
    val expireAfterAccessMs: Long
) {
    /**
     * 統計情報を人間が読みやすい形式で出力する。
     */
    override fun toString(): String {
        val hitRatePercent = String.format("%.2f", hitRate * 100)
        return "CacheStats(size=$size/$maxSize, hits=$hits, misses=$misses, hitRate=$hitRatePercent%, expire=${expireAfterAccessMs}ms)"
    }
}
