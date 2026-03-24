package com.example.paperdrawers

import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.cache.DrawerCache
import com.example.paperdrawers.infrastructure.config.DrawerCapacityConfig
import com.example.paperdrawers.infrastructure.config.DrawerDisplayConfig
import com.example.paperdrawers.infrastructure.display.DisplayManager
import com.example.paperdrawers.infrastructure.display.DrawerDisplayManager
import com.example.paperdrawers.infrastructure.display.StubDisplayManager
import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import com.example.paperdrawers.infrastructure.item.DrawerKeyFactory
import com.example.paperdrawers.infrastructure.message.MessageManager
import com.example.paperdrawers.infrastructure.persistence.AsyncDrawerRepository
import com.example.paperdrawers.infrastructure.persistence.CustomBlockDataDrawerRepository
import com.example.paperdrawers.infrastructure.persistence.DrawerDataKeys
import com.example.paperdrawers.infrastructure.platform.PlatformDetector
import com.example.paperdrawers.infrastructure.recipe.RecipeManager
import com.example.paperdrawers.presentation.listener.ChunkLoadListener
import com.example.paperdrawers.presentation.listener.PlayerJoinListener
import com.example.paperdrawers.presentation.listener.DrawerBreakListener
import com.example.paperdrawers.presentation.listener.DrawerCraftListener
import com.example.paperdrawers.presentation.listener.DrawerInteractionListener
import com.example.paperdrawers.presentation.listener.DrawerPlaceListener
import com.example.paperdrawers.presentation.listener.DrawerProtectionListener
import com.example.paperdrawers.presentation.listener.HopperInteractionListener
import com.example.paperdrawers.presentation.listener.ItemFrameInteractionListener
import com.example.paperdrawers.infrastructure.hopper.HopperPullTask
import com.jeff_media.customblockdata.CustomBlockData
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * PaperDrawers プラグインのメインクラス。
 *
 * Storage Drawers 風のアイテム収納ブロックを Paper サーバーに追加する。
 * プラグインの初期化、依存性の注入、リソースの管理を担当する。
 *
 * Why: JavaPlugin を継承することで Bukkit/Paper のプラグインライフサイクルに統合される。
 * コンポーネントは依存性注入パターンで管理し、テスト容易性と保守性を確保する。
 */
class PaperDrawersPlugin : JavaPlugin() {

    // ========================================
    // Core Components (Lazy Initialized)
    // ========================================

    /**
     * ドロワーデータの永続化を担当するリポジトリ。
     *
     * Why: Lazy initialization により、プラグインが有効化されるまでインスタンスが作成されない。
     * これにより起動順序の問題を回避し、依存コンポーネントの初期化を保証する。
     */
    lateinit var drawerRepository: DrawerRepository
        private set

    /**
     * ドロワーの視覚的表示を管理するマネージャー。
     */
    lateinit var displayManager: DisplayManager
        private set

    /**
     * プラットフォーム検出器（Bedrock/Java 判定）。
     */
    lateinit var platformDetector: PlatformDetector
        private set

    /**
     * ドロワーキャッシュ（パフォーマンス最適化用）。
     *
     * Why: 頻繁にアクセスされるドロワーをメモリにキャッシュすることで、
     * PDC からの読み取りを削減し、パフォーマンスを向上させる。
     */
    private var drawerCache: DrawerCache? = null

    /**
     * 非同期リポジトリ（オプション）。
     *
     * Why: 保存処理をバックグラウンドで実行することで、
     * メインスレッドのブロッキングを防ぎ、サーバーのパフォーマンスを向上させる。
     */
    private var asyncRepository: AsyncDrawerRepository? = null

    /**
     * キャッシュクリーンアップタスク。
     */
    private var cacheCleanupTask: BukkitTask? = null

    /**
     * メトリクスログタスク。
     */
    private var metricsLogTask: BukkitTask? = null

    /**
     * ホッパー吸出しタスク。
     */
    private var hopperPullTask: BukkitTask? = null

    /**
     * クラフトレシピマネージャー。
     *
     * Why: config.ymlから読み込んだレシピを管理し、
     * プラグイン無効化時にクリーンアップするために参照を保持する。
     */
    private var recipeManager: RecipeManager? = null

    // ========================================
    // Plugin Lifecycle Methods
    // ========================================

    /**
     * プラグインが有効化された時に呼び出される。
     *
     * 以下の初期化処理を実行:
     * 1. 設定ファイルのロード
     * 2. DrawerDataKeys の初期化
     * 3. CustomBlockData ライブラリの登録
     * 4. コアコンポーネントの初期化
     * 5. イベントリスナーの登録
     */
    override fun onEnable() {
        logger.info("Initializing PaperDrawers plugin...")

        try {
            // Step 1: Load configuration
            saveDefaultConfig()
            loadConfiguration()

            // Step 2: Initialize DrawerDataKeys with plugin instance
            initializeDataKeys()

            // Step 3: Register CustomBlockData library
            registerCustomBlockData()

            // Step 4: Initialize core components
            initializeComponents()

            // Step 5: Register event listeners (commands are registered in PaperDrawersBootstrap)
            registerEventListeners()

            // Step 6: Restore drawer displays after a short delay (wait for worlds to load)
            scheduleDisplayRestoration()

            // Step 7: Start background tasks
            startBackgroundTasks()

            logger.info("PaperDrawers plugin enabled successfully!")
            logPluginInfo()
        } catch (e: Exception) {
            logger.severe("Failed to enable PaperDrawers plugin: ${e.message}")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
        }
    }

    /**
     * プラグインが無効化された時に呼び出される。
     *
     * 以下のクリーンアップ処理を実行:
     * 1. バックグラウンドタスクの停止
     * 2. 保留中の保存のフラッシュ
     * 3. 表示の削除
     * 4. リソースの解放
     */
    override fun onDisable() {
        logger.info("Disabling PaperDrawers plugin...")

        try {
            // Step 1: Unregister craft recipes
            recipeManager?.unregisterAll()
            recipeManager = null

            // Step 2: Stop background tasks
            stopBackgroundTasks()

            // Step 3: Flush pending saves if async repository is enabled
            asyncRepository?.let { asyncRepo ->
                logger.fine("Flushing pending drawer saves...")
                asyncRepo.flushPendingSaves()
            }

            // Step 4: Clean up display entities
            if (::displayManager.isInitialized) {
                displayManager.cleanup()
            }

            // Step 5: Clear cache
            drawerCache?.let { cache ->
                val stats = cache.getStats()
                logger.fine("Final cache stats: $stats")
                cache.invalidateAll()
            }

            logger.info("PaperDrawers plugin disabled successfully!")
        } catch (e: Exception) {
            logger.severe("Error during plugin disable: ${e.message}")
            e.printStackTrace()
        }
    }

    // ========================================
    // Initialization Methods
    // ========================================

    /**
     * 設定ファイルをロードする。
     *
     * デフォルト設定を config.yml として保存し、必要に応じてユーザー設定を読み込む。
     */
    private fun loadConfiguration() {
        // Reload config from disk
        reloadConfig()

        // Log loaded configuration
        logger.fine("Configuration loaded from config.yml")
    }

    /**
     * DrawerDataKeys および DrawerKeyFactory を初期化する。
     *
     * Why: NamespacedKey の生成にはプラグインインスタンスが必要なため、
     * プラグインの onEnable で初期化する必要がある。
     */
    private fun initializeDataKeys() {
        if (!DrawerDataKeys.isInitialized()) {
            DrawerDataKeys.initialize(this)
            logger.fine("DrawerDataKeys initialized")
        }

        if (!DrawerKeyFactory.isInitialized()) {
            DrawerKeyFactory.initialize(this)
            logger.fine("DrawerKeyFactory initialized")
        }

        if (!MessageManager.isInitialized()) {
            MessageManager.initialize(this, logger)
            logger.fine("MessageManager initialized")
        }
    }

    /**
     * CustomBlockData ライブラリを登録する。
     *
     * Why: CustomBlockData はブロックの PDC にデータを保存するために必要。
     * プラグイン登録により、チャンクロード/アンロード時のデータ保持が有効になる。
     */
    private fun registerCustomBlockData() {
        CustomBlockData.registerListener(this)
        logger.fine("CustomBlockData listener registered")
    }

    /**
     * コアコンポーネントを初期化する。
     *
     * Why: 依存性注入パターンにより、各コンポーネントは必要な依存関係を
     * コンストラクタで受け取る。これによりテスト容易性が向上する。
     */
    private fun initializeComponents() {
        // Initialize platform detector first (no dependencies)
        platformDetector = PlatformDetector(logger)
        logger.fine("PlatformDetector initialized (Floodgate available: ${platformDetector.isFloodgateAvailable()})")

        // Initialize DrawerCapacityConfig from config.yml
        DrawerCapacityConfig.initialize(config.getConfigurationSection("drawer-capacity"))
        logger.fine("DrawerCapacityConfig initialized")

        // Initialize DrawerDisplayConfig from config.yml
        val drawerDisplayConfig = DrawerDisplayConfig(
            configSection = config.getConfigurationSection("drawer-display"),
            logger = logger
        )
        DrawerItemFactory.initialize(drawerDisplayConfig)
        logger.fine("DrawerDisplayConfig initialized and injected into DrawerItemFactory")

        // Initialize base drawer repository
        val baseRepository = CustomBlockDataDrawerRepository(this, logger)
        logger.fine("Base DrawerRepository initialized (CustomBlockData implementation)")

        // Initialize cache and async repository based on configuration
        drawerRepository = initializeRepositoryWithOptimizations(baseRepository)

        // Initialize display manager with full implementation
        // Use config to determine if display system should be enabled
        val enableDisplay = config.getBoolean("display.enabled", true)
        val isBedrockServerMode = platformDetector.isFloodgateAvailable()
        displayManager = if (enableDisplay) {
            DrawerDisplayManager(this, logger, isBedrockServerMode).also {
                logger.fine("DisplayManager initialized (DrawerDisplayManager with ItemFrame, bedrockMode=$isBedrockServerMode)")
            }
        } else {
            StubDisplayManager(logger).also {
                logger.fine("DisplayManager initialized (Stub - display disabled in config)")
            }
        }

        // Initialize RecipeManager and register craft recipes
        recipeManager = RecipeManager(this, logger).also { manager ->
            manager.registerRecipes(config.getConfigurationSection("recipes"))
        }
        logger.fine("RecipeManager initialized")
    }

    /**
     * キャッシュと非同期保存の最適化を適用したリポジトリを初期化する。
     *
     * Why: 設定に基づいてキャッシュと非同期保存を有効/無効にすることで、
     * サーバーの特性に合わせた最適化を可能にする。
     *
     * @param baseRepository ベースとなるリポジトリ
     * @return 最適化が適用されたリポジトリ
     */
    private fun initializeRepositoryWithOptimizations(baseRepository: DrawerRepository): DrawerRepository {
        val cacheEnabled = config.getBoolean("performance.cache.enabled", true)
        val asyncSaveEnabled = config.getBoolean("performance.async-save.enabled", true)

        // If both optimizations are disabled, return base repository
        if (!cacheEnabled && !asyncSaveEnabled) {
            logger.fine("Performance optimizations disabled")
            return baseRepository
        }

        // Initialize cache if enabled
        if (cacheEnabled) {
            val maxSize = config.getInt("performance.cache.max-size", DrawerCache.DEFAULT_MAX_SIZE)
            val expireAfterMs = config.getLong("performance.cache.expire-after-ms", DrawerCache.DEFAULT_EXPIRE_AFTER_ACCESS_MS)

            drawerCache = DrawerCache(
                maxSize = maxSize,
                expireAfterAccessMs = expireAfterMs,
                logger = logger
            )

            logger.fine("DrawerCache initialized (maxSize=$maxSize, expireMs=$expireAfterMs)")
        }

        // If async save is enabled and cache is available, wrap with AsyncDrawerRepository
        if (asyncSaveEnabled && drawerCache != null) {
            val intervalTicks = config.getLong("performance.async-save.interval-ticks", DEFAULT_ASYNC_SAVE_INTERVAL_TICKS)

            asyncRepository = AsyncDrawerRepository(
                delegate = baseRepository,
                plugin = this,
                cache = drawerCache!!,
                logger = logger
            ).also {
                it.startBackgroundSaveTask(intervalTicks)
            }

            logger.fine("AsyncDrawerRepository initialized (intervalTicks=$intervalTicks)")
            return asyncRepository!!
        }

        // If only cache is enabled, use CachingDrawerRepository (simple cache wrapper)
        if (cacheEnabled && drawerCache != null) {
            logger.fine("Cache-only mode enabled (async save disabled)")
            return CachingDrawerRepositoryWrapper(baseRepository, drawerCache!!, logger)
        }

        return baseRepository
    }

    /**
     * イベントリスナーを登録する。
     *
     * Why: ドロワーのインタラクション（配置、破壊、クリックなど）を
     * 処理するためにイベントリスナーが必要。
     */
    private fun registerEventListeners() {
        // DrawerInteractionListener を先に作成（ItemFrameInteractionListenerで使用するため）
        val drawerInteractionListener = DrawerInteractionListener(drawerRepository, displayManager, logger)

        // Register event listeners
        server.pluginManager.registerEvents(
            DrawerPlaceListener(drawerRepository, displayManager, logger),
            this
        )
        server.pluginManager.registerEvents(
            drawerInteractionListener,
            this
        )
        server.pluginManager.registerEvents(
            DrawerBreakListener(drawerRepository, displayManager, logger),
            this
        )
        server.pluginManager.registerEvents(
            DrawerProtectionListener(drawerRepository, logger),
            this
        )
        server.pluginManager.registerEvents(
            ChunkLoadListener(drawerRepository, displayManager, logger),
            this
        )

        // DrawerCraftListener を登録（ドロワー素材の Tier バリデーション）
        // Why: MaterialChoice(BARREL) で登録されたドロワー素材が正しい Tier か検証する
        val currentRecipeManager = recipeManager
        if (currentRecipeManager != null) {
            server.pluginManager.registerEvents(
                DrawerCraftListener(currentRecipeManager, logger),
                this
            )
        }

        // ItemFrameInteractionListener を登録（ItemFrameへのクリックをドロワーに転送）
        val itemFrameInteractionListener = ItemFrameInteractionListener(
            this, drawerRepository, displayManager, drawerInteractionListener, logger
        )
        server.pluginManager.registerEvents(itemFrameInteractionListener, this)

        // 循環参照を設定（onPlayerAnimation での誤検出防止のため）
        drawerInteractionListener.setItemFrameInteractionListener(itemFrameInteractionListener)

        // PlayerJoinListener は DrawerDisplayManager を必要とするため、キャストして登録
        if (displayManager is DrawerDisplayManager) {
            server.pluginManager.registerEvents(
                PlayerJoinListener(displayManager as DrawerDisplayManager, logger),
                this
            )
        }

        // HopperInteractionListener を登録（ホッパーからドロワーへのアイテム搬入）
        server.pluginManager.registerEvents(
            HopperInteractionListener(drawerRepository, displayManager, logger),
            this
        )

        logger.fine("Event listeners registered successfully")
    }

    /**
     * バックグラウンドタスクを開始する。
     *
     * Why: キャッシュクリーンアップとメトリクスログを定期的に実行するために必要。
     */
    private fun startBackgroundTasks() {
        // Start cache cleanup task if cache is enabled
        drawerCache?.let { cache ->
            cacheCleanupTask = server.scheduler.runTaskTimerAsynchronously(
                this,
                Runnable {
                    val removedCount = cache.cleanup()
                    if (removedCount > 0) {
                        logger.fine("Cache cleanup removed $removedCount expired entries")
                    }
                },
                CACHE_CLEANUP_INTERVAL_TICKS,
                CACHE_CLEANUP_INTERVAL_TICKS
            )
            logger.fine("Cache cleanup task started")
        }

        // Start metrics log task if enabled
        val metricsIntervalMinutes = config.getInt("performance.metrics-log-interval-minutes", DEFAULT_METRICS_LOG_INTERVAL_MINUTES)
        if (metricsIntervalMinutes > 0) {
            val intervalTicks = metricsIntervalMinutes * 60L * 20L // minutes to ticks

            metricsLogTask = server.scheduler.runTaskTimerAsynchronously(
                this,
                Runnable { logPerformanceMetrics() },
                intervalTicks,
                intervalTicks
            )
            logger.fine("Metrics log task started (interval: ${metricsIntervalMinutes}min)")
        }

        // Start hopper pull task (every 8 ticks = vanilla hopper speed)
        val hopperEnabled = config.getBoolean("hopper.enabled", true)
        if (hopperEnabled) {
            val hopperInterval = config.getLong("hopper.pull-interval-ticks", DEFAULT_HOPPER_PULL_INTERVAL_TICKS)
            hopperPullTask = server.scheduler.runTaskTimer(
                this,
                HopperPullTask(drawerRepository, displayManager, this, logger),
                hopperInterval,
                hopperInterval
            )
            logger.fine("Hopper pull task started (interval: ${hopperInterval} ticks)")
        }
    }

    /**
     * バックグラウンドタスクを停止する。
     */
    private fun stopBackgroundTasks() {
        cacheCleanupTask?.cancel()
        cacheCleanupTask = null

        metricsLogTask?.cancel()
        metricsLogTask = null

        hopperPullTask?.cancel()
        hopperPullTask = null

        logger.fine("Background tasks stopped")
    }

    /**
     * サーバー起動時にドロワーの表示を復元する。
     *
     * Why: サーバー再起動後、displayEntities マップは空になるが、
     * ワールド内のドロワーは PDC に保存されている。
     * この処理で既存のドロワーの表示を再作成する。
     */
    private fun scheduleDisplayRestoration() {
        // 少し遅延させてからワールドが完全に読み込まれるのを待つ
        server.scheduler.runTaskLater(this, Runnable {
            restoreAllDrawerDisplays()
        }, DISPLAY_RESTORATION_DELAY_TICKS)
    }

    /**
     * すべてのロードされたチャンク内のドロワー表示を復元する。
     */
    private fun restoreAllDrawerDisplays() {
        logger.fine("Restoring drawer displays...")

        // まず孤立したエンティティをクリーンアップ
        if (displayManager is DrawerDisplayManager) {
            (displayManager as DrawerDisplayManager).cleanupOrphanedDisplayEntities()
        }

        var totalDrawers = 0
        var totalDisplaysCreated = 0

        for (world in server.worlds) {
            for (chunk in world.loadedChunks) {
                try {
                    val drawers = drawerRepository.findByChunk(chunk)
                    for (drawer in drawers) {
                        totalDrawers++
                        if (!displayManager.hasDisplay(drawer)) {
                            displayManager.createDisplay(drawer)
                            totalDisplaysCreated++
                        }
                    }
                } catch (e: Exception) {
                    logger.warning("Failed to restore displays in chunk [${chunk.x}, ${chunk.z}]: ${e.message}")
                }
            }
        }

        logger.fine("Restored $totalDisplaysCreated displays for $totalDrawers drawers")
    }

    /**
     * パフォーマンスメトリクスをログに出力する。
     */
    private fun logPerformanceMetrics() {
        drawerCache?.let { cache ->
            val cacheStats = cache.getStats()
            logger.fine("[Metrics] Cache: $cacheStats")
        }

        asyncRepository?.let { asyncRepo ->
            val asyncStats = asyncRepo.getStats()
            logger.fine("[Metrics] AsyncRepo: pending=${asyncStats.pendingSaveCount}, processed=${asyncStats.processedSaveCount}")
        }
    }

    /**
     * プラグイン情報をログに出力する。
     */
    private fun logPluginInfo() {
        logger.fine("=================================")
        logger.fine("PaperDrawers v${description.version}")
        logger.fine("Minecraft: ${server.minecraftVersion}")
        logger.fine("Platform: ${server.name} ${server.version}")
        logger.fine("Floodgate: ${if (platformDetector.isFloodgateAvailable()) "Available" else "Not found"}")
        logger.fine("Cache: ${if (drawerCache != null) "Enabled" else "Disabled"}")
        logger.fine("AsyncSave: ${if (asyncRepository != null) "Enabled" else "Disabled"}")
        logger.fine("=================================")
    }

    // ========================================
    // Public API Methods
    // ========================================

    /**
     * プラグインの設定をリロードする。
     *
     * Why: 管理者がサーバーを再起動せずに設定を変更できるようにする。
     */
    fun reloadPluginConfig() {
        reloadConfig()
        loadConfiguration()

        // Reload capacity config
        DrawerCapacityConfig.initialize(config.getConfigurationSection("drawer-capacity"))

        // Reload display config
        val displayConfig = DrawerDisplayConfig(
            config.getConfigurationSection("drawer-display"),
            logger
        )
        DrawerItemFactory.initialize(displayConfig)

        // Reload recipes
        recipeManager?.unregisterAll()
        recipeManager?.registerRecipes(config.getConfigurationSection("recipes"))

        logger.info("Configuration reloaded")
    }

    /**
     * キャッシュ統計情報を取得する。
     *
     * @return キャッシュ統計情報、キャッシュが無効の場合はnull
     */
    fun getCacheStats() = drawerCache?.getStats()

    /**
     * 非同期リポジトリ統計情報を取得する。
     *
     * @return 非同期リポジトリ統計情報、非同期保存が無効の場合はnull
     */
    fun getAsyncRepositoryStats() = asyncRepository?.getStats()

    companion object {
        /** デフォルトの非同期保存インターバル（tick） */
        private const val DEFAULT_ASYNC_SAVE_INTERVAL_TICKS: Long = 100L

        /** キャッシュクリーンアップインターバル（tick）- 30秒 */
        private const val CACHE_CLEANUP_INTERVAL_TICKS: Long = 600L

        /** デフォルトのメトリクスログインターバル（分） */
        private const val DEFAULT_METRICS_LOG_INTERVAL_MINUTES: Int = 5

        /** 表示復元までの遅延（tick）- 3秒（ワールドが完全に読み込まれるのを待つ） */
        private const val DISPLAY_RESTORATION_DELAY_TICKS: Long = 60L

        /** デフォルトのホッパー吸出し間隔（tick）- バニラホッパー速度と同じ8tick */
        private const val DEFAULT_HOPPER_PULL_INTERVAL_TICKS: Long = 8L

        /**
         * プラグインインスタンスを取得する。
         *
         * Why: 他のクラスからプラグインインスタンスにアクセスするための
         * ユーティリティメソッド。ただし、可能な限り依存性注入を使用することを推奨。
         *
         * @return PaperDrawersPlugin インスタンス
         * @throws IllegalStateException プラグインがロードされていない場合
         */
        fun getInstance(): PaperDrawersPlugin {
            return getPlugin(PaperDrawersPlugin::class.java)
        }
    }
}

/**
 * キャッシュのみを使用するシンプルなリポジトリラッパー。
 *
 * Why: 非同期保存が無効でキャッシュのみ有効な場合に使用する。
 * 同期的な保存を行いながら、読み取りはキャッシュから行う。
 *
 * @property delegate 実際の永続化を行うリポジトリ
 * @property cache ドロワーキャッシュ
 * @property logger ログ出力用のロガー
 */
private class CachingDrawerRepositoryWrapper(
    private val delegate: DrawerRepository,
    private val cache: DrawerCache,
    private val logger: java.util.logging.Logger
) : DrawerRepository {

    override fun save(drawer: com.example.paperdrawers.domain.model.DrawerBlock) {
        delegate.save(drawer)
        cache.put(drawer)
    }

    override fun findByLocation(location: org.bukkit.Location): com.example.paperdrawers.domain.model.DrawerBlock? {
        val key = locationToKey(location)
        val cached = cache.get(key)
        if (cached != null) {
            return cached
        }

        val drawer = delegate.findByLocation(location)
        if (drawer != null) {
            cache.put(drawer)
        }
        return drawer
    }

    override fun findByChunk(chunk: org.bukkit.Chunk): List<com.example.paperdrawers.domain.model.DrawerBlock> {
        val drawers = delegate.findByChunk(chunk)
        drawers.forEach { cache.put(it) }
        return drawers
    }

    override fun delete(location: org.bukkit.Location) {
        val key = locationToKey(location)
        cache.invalidate(key)
        delegate.delete(location)
    }

    override fun exists(location: org.bukkit.Location): Boolean {
        val key = locationToKey(location)
        if (cache.get(key) != null) {
            return true
        }
        return delegate.exists(location)
    }

    private fun locationToKey(location: org.bukkit.Location): String {
        return "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
    }
}
