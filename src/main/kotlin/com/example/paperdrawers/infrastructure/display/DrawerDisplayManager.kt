package com.example.paperdrawers.infrastructure.display

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.infrastructure.debug.MetricsCollector
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ドロワーの視覚的表示を管理するシンプルな実装クラス。
 *
 * ItemFrame を使用してアイテムを表示する。
 * Java Edition と Bedrock Edition (Geyser経由) の両方で動作する。
 *
 * @property plugin プラグインインスタンス
 * @property logger ログ出力用のロガー
 * @property isBedrockServerMode Geyser/Floodgateがインストールされているか（TextDisplay位置調整用）
 */
class DrawerDisplayManager(
    private val plugin: Plugin,
    private val logger: Logger,
    isBedrockServerMode: Boolean = false
) : DisplayManager {

    /**
     * ItemFrame を使用した表示戦略（JE/BE 両対応）。
     */
    private val itemFrameStrategy: ItemFrameStrategy = ItemFrameStrategy(plugin, logger)

    /**
     * テキスト表示ヘルパー（アイテム数表示用）。
     * Java版: 0.3ブロック前方、0.5ブロック下
     * 統合版: 0.4ブロック前方
     */
    private val textDisplayHelper: TextDisplayHelper = TextDisplayHelper(plugin, logger, isBedrockServerMode)

    /**
     * アイテム表示エンティティの追跡マップ。
     *
     * Key: ドロワーのロケーションキー
     * Value: スロットインデックス -> エンティティUUID のマップ
     */
    private val displayEntities: MutableMap<String, MutableMap<Int, UUID>> = ConcurrentHashMap()

    /**
     * テキスト表示エンティティの追跡マップ。
     */
    private val textDisplayEntities: MutableMap<String, MutableMap<Int, UUID>> = ConcurrentHashMap()

    override fun createDisplay(drawer: DrawerBlock) {
        val locationKey = drawer.getLocationKey()
        logger.fine("Creating display for drawer at $locationKey")

        // 既存の表示があれば削除
        if (hasDisplay(drawer)) {
            removeDisplay(drawer)
        }

        val world = drawer.location.world
        if (world == null) {
            logger.warning("Cannot create display: drawer world is null for location $locationKey")
            return
        }

        val itemEntities = ConcurrentHashMap<Int, UUID>()
        val textEntities = ConcurrentHashMap<Int, UUID>()

        // 各スロットの表示を作成
        for (slotIndex in drawer.slots.indices) {
            try {
                val slot = drawer.getSlot(slotIndex)

                // スロットが空でない場合のみ表示を作成
                if (!slot.isEmpty() || slot.storedMaterial != null) {
                    // ItemFrame でアイテム表示
                    val itemEntityId = itemFrameStrategy.createSlotDisplay(drawer, slotIndex)
                    if (itemEntityId != null) {
                        itemEntities[slotIndex] = itemEntityId
                        logger.fine("Created ItemFrame display for slot $slotIndex: $itemEntityId")
                    }

                    // テキスト表示（アイテム数）
                    if (slot.itemCount > 0) {
                        val textEntityId = textDisplayHelper.createCountDisplay(drawer, slotIndex)
                        if (textEntityId != null) {
                            textEntities[slotIndex] = textEntityId
                            logger.fine("Created text display for slot $slotIndex: $textEntityId")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.log(
                    Level.WARNING,
                    "Failed to create display for drawer $locationKey, slot $slotIndex",
                    e
                )
            }
        }

        // 追跡マップに登録
        if (itemEntities.isNotEmpty()) {
            displayEntities[locationKey] = itemEntities
        }
        if (textEntities.isNotEmpty()) {
            textDisplayEntities[locationKey] = textEntities
        }

        MetricsCollector.incrementDisplayCreations()

        logger.fine(
            "Created display for drawer at $locationKey: " +
            "${itemEntities.size} item displays, ${textEntities.size} text displays"
        )
    }

    override fun updateDisplay(drawer: DrawerBlock) {
        val locationKey = drawer.getLocationKey()
        logger.fine("Updating display for drawer at $locationKey")

        MetricsCollector.incrementDisplayUpdates()

        // シンプルな方法: 既存の表示を削除して再作成
        removeDisplay(drawer)
        createDisplay(drawer)
    }

    override fun updateSlotDisplay(drawer: DrawerBlock, slotIndex: Int) {
        val locationKey = drawer.getLocationKey()
        logger.fine("Updating slot $slotIndex display for drawer at $locationKey")

        MetricsCollector.incrementDisplayUpdates()

        if (slotIndex < 0 || slotIndex >= drawer.slots.size) {
            logger.warning("Invalid slot index $slotIndex for drawer at $locationKey")
            return
        }

        try {
            val slot = drawer.getSlot(slotIndex)
            val itemEntities = displayEntities[locationKey]
            val existingItemEntityId = itemEntities?.get(slotIndex)
            val textEntities = textDisplayEntities[locationKey]
            val existingTextEntityId = textEntities?.get(slotIndex)

            // スロットが空で、ロックされておらず、表示するマテリアルもない場合は表示を削除
            // ロックされている場合はロックマテリアルを表示し続ける
            if (slot.isEmpty() && !slot.isLocked && slot.storedMaterial == null) {
                // 既存のアイテム表示を削除
                if (existingItemEntityId != null) {
                    itemFrameStrategy.removeDisplay(existingItemEntityId)
                    itemEntities?.remove(slotIndex)
                }
                // 既存のテキスト表示を削除
                if (existingTextEntityId != null) {
                    textDisplayHelper.removeCountDisplay(existingTextEntityId)
                    textEntities?.remove(slotIndex)
                }
                cleanupEmptyMaps(locationKey)
                return
            }

            // アイテム表示の更新または作成
            if (existingItemEntityId != null) {
                // 既存のItemFrameを更新（削除せずにアイテムのみ更新）
                // Why: エンティティがワールドから消えている場合（チャンクアンロード等）、
                // 追跡マップから削除して再生成する
                val entityExists = Bukkit.getEntity(existingItemEntityId) != null
                if (entityExists) {
                    itemFrameStrategy.updateSlotDisplay(drawer, slotIndex, existingItemEntityId)
                } else {
                    // エンティティが消失 → 追跡マップから削除して再生成
                    itemEntities?.remove(slotIndex)
                    logger.fine("Item display entity $existingItemEntityId is gone, recreating for drawer $locationKey slot $slotIndex")
                    val newItemEntityId = itemFrameStrategy.createSlotDisplay(drawer, slotIndex)
                    if (newItemEntityId != null) {
                        val entities = displayEntities.getOrPut(locationKey) { ConcurrentHashMap() }
                        entities[slotIndex] = newItemEntityId
                    }
                }
            } else {
                // 新規作成
                val newItemEntityId = itemFrameStrategy.createSlotDisplay(drawer, slotIndex)
                if (newItemEntityId != null) {
                    val entities = displayEntities.getOrPut(locationKey) { ConcurrentHashMap() }
                    entities[slotIndex] = newItemEntityId
                }
            }

            // テキスト表示の更新または作成
            if (slot.itemCount > 0) {
                if (existingTextEntityId != null) {
                    // 既存のTextDisplayを更新
                    // Why: エンティティがワールドから消えている場合、再生成する
                    val textEntityExists = Bukkit.getEntity(existingTextEntityId) != null
                    if (textEntityExists) {
                        textDisplayHelper.updateCountDisplay(drawer, slotIndex, existingTextEntityId)
                    } else {
                        // テキストエンティティが消失 → 追跡マップから削除して再生成
                        textEntities?.remove(slotIndex)
                        logger.fine("Text display entity $existingTextEntityId is gone, recreating for drawer $locationKey slot $slotIndex")
                        val newTextEntityId = textDisplayHelper.createCountDisplay(drawer, slotIndex)
                        if (newTextEntityId != null) {
                            val texts = textDisplayEntities.getOrPut(locationKey) { ConcurrentHashMap() }
                            texts[slotIndex] = newTextEntityId
                        }
                    }
                } else {
                    // 新規作成
                    val newTextEntityId = textDisplayHelper.createCountDisplay(drawer, slotIndex)
                    if (newTextEntityId != null) {
                        val texts = textDisplayEntities.getOrPut(locationKey) { ConcurrentHashMap() }
                        texts[slotIndex] = newTextEntityId
                    }
                }
            } else {
                // アイテム数が0の場合はテキスト表示を削除
                if (existingTextEntityId != null) {
                    textDisplayHelper.removeCountDisplay(existingTextEntityId)
                    textEntities?.remove(slotIndex)
                }
            }

            cleanupEmptyMaps(locationKey)

        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "Failed to update slot display for drawer $locationKey, slot $slotIndex",
                e
            )
        }
    }

    override fun removeDisplay(drawer: DrawerBlock) {
        val locationKey = drawer.getLocationKey()
        logger.fine("Removing display for drawer at $locationKey")

        var removedCount = 0

        // アイテム表示エンティティを削除
        val itemEntities = displayEntities.remove(locationKey)
        if (itemEntities != null) {
            for ((_, entityId) in itemEntities) {
                try {
                    itemFrameStrategy.removeDisplay(entityId)
                    removedCount++
                } catch (e: Exception) {
                    logger.log(Level.FINE, "Failed to remove item display entity $entityId", e)
                }
            }
        }

        // テキスト表示エンティティを削除
        val textEntities = textDisplayEntities.remove(locationKey)
        if (textEntities != null) {
            for ((_, entityId) in textEntities) {
                try {
                    textDisplayHelper.removeCountDisplay(entityId)
                    removedCount++
                } catch (e: Exception) {
                    logger.log(Level.FINE, "Failed to remove text display entity $entityId", e)
                }
            }
        }

        if (removedCount > 0) {
            MetricsCollector.incrementDisplayRemovals()
        }

        logger.fine("Removed $removedCount display entities for drawer at $locationKey")
    }

    override fun showDisplayToPlayer(drawer: DrawerBlock, player: Player) {
        // ItemFrame は全プレイヤーに表示されるため、特別な処理は不要
        logger.fine("Showing display to player ${player.name} for drawer at ${drawer.getLocationKey()}")
    }

    override fun hideDisplayFromPlayer(drawer: DrawerBlock, player: Player) {
        // ItemFrame は全プレイヤーに表示されるため、特別な処理は不要
        logger.fine("Hiding display from player ${player.name} for drawer at ${drawer.getLocationKey()}")
    }

    override fun cleanup() {
        logger.info("Cleaning up all drawer displays")

        var totalRemoved = 0

        // すべてのアイテム表示エンティティを削除
        for ((_, entities) in displayEntities.toMap()) {
            for ((_, entityId) in entities) {
                try {
                    itemFrameStrategy.removeDisplay(entityId)
                    totalRemoved++
                } catch (e: Exception) {
                    logger.log(Level.FINE, "Failed to remove item display entity during cleanup", e)
                }
            }
        }

        // すべてのテキスト表示エンティティを削除
        for ((_, entities) in textDisplayEntities.toMap()) {
            for ((_, entityId) in entities) {
                try {
                    textDisplayHelper.removeCountDisplay(entityId)
                    totalRemoved++
                } catch (e: Exception) {
                    logger.log(Level.FINE, "Failed to remove text display entity during cleanup", e)
                }
            }
        }

        // 追跡マップをクリア
        displayEntities.clear()
        textDisplayEntities.clear()

        // 孤立したエンティティもクリーンアップ
        cleanupOrphanedEntities()

        logger.info("Cleaned up $totalRemoved drawer display entities")
    }

    override fun hasDisplay(drawer: DrawerBlock): Boolean {
        val locationKey = drawer.getLocationKey()

        // エンティティの実在確認を行い、存在しないものはマップからクリーンアップ
        val validItemDisplays = validateAndCleanupEntities(locationKey, displayEntities)
        val validTextDisplays = validateAndCleanupEntities(locationKey, textDisplayEntities)

        return validItemDisplays || validTextDisplays
    }

    /**
     * 指定されたロケーションのエンティティが実際に存在するか確認し、
     * 存在しないエンティティをマップからクリーンアップする。
     *
     * チャンクのアンロード時に非永続エンティティ（isPersistent = false）は
     * Minecraftによって削除されるが、追跡マップにはUUIDが残る場合がある。
     * このメソッドは実際のエンティティ存在を確認し、不整合を解消する。
     *
     * @param locationKey ドロワーのロケーションキー
     * @param entityMap エンティティ追跡マップ
     * @return 有効なエンティティが1つ以上存在する場合は true
     */
    private fun validateAndCleanupEntities(
        locationKey: String,
        entityMap: MutableMap<String, MutableMap<Int, UUID>>
    ): Boolean {
        val entities = entityMap[locationKey] ?: return false

        if (entities.isEmpty()) {
            entityMap.remove(locationKey)
            return false
        }

        val invalidSlots = mutableListOf<Int>()

        for ((slotIndex, entityId) in entities) {
            val entity = Bukkit.getEntity(entityId)
            if (entity == null || entity.isDead) {
                invalidSlots.add(slotIndex)
            }
        }

        // 無効なエンティティをマップから削除
        for (slotIndex in invalidSlots) {
            entities.remove(slotIndex)
        }

        // マップが空になった場合はエントリ自体を削除
        if (entities.isEmpty()) {
            entityMap.remove(locationKey)
            return false
        }

        return true
    }

    /**
     * 表示戦略を取得する。
     */
    fun getStrategy(): DisplayStrategy = itemFrameStrategy

    /**
     * 追跡マップが空の場合にエントリを削除する。
     */
    private fun cleanupEmptyMaps(locationKey: String) {
        val itemEntities = displayEntities[locationKey]
        if (itemEntities != null && itemEntities.isEmpty()) {
            displayEntities.remove(locationKey)
        }

        val textEntities = textDisplayEntities[locationKey]
        if (textEntities != null && textEntities.isEmpty()) {
            textDisplayEntities.remove(locationKey)
        }
    }

    /**
     * 孤立した表示エンティティをクリーンアップする（サーバー起動時用）。
     *
     * サーバー再起動後、古い ItemFrame/TextDisplay が残っている場合があるため、
     * プラグイン固有の PDC タグを持つエンティティを削除する。
     */
    fun cleanupOrphanedDisplayEntities() {
        cleanupOrphanedEntities()
    }

    /**
     * 孤立したエンティティをクリーンアップする。
     */
    private fun cleanupOrphanedEntities() {
        var orphanedCount = 0
        val drawerIdKey = NamespacedKey(plugin, "display_drawer_id")
        val textDrawerIdKey = NamespacedKey(plugin, "text_display_drawer_id")

        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                try {
                    // ItemFrame をチェック
                    if (entity is ItemFrame) {
                        val pdc = entity.persistentDataContainer
                        if (pdc.has(drawerIdKey, PersistentDataType.STRING)) {
                            entity.remove()
                            orphanedCount++
                        }
                    }

                    // TextDisplay をチェック
                    if (entity is TextDisplay) {
                        val pdc = entity.persistentDataContainer
                        if (pdc.has(textDrawerIdKey, PersistentDataType.STRING)) {
                            entity.remove()
                            orphanedCount++
                        }
                    }
                } catch (e: Exception) {
                    logger.log(Level.FINE, "Error checking entity for orphaned display cleanup", e)
                }
            }
        }

        if (orphanedCount > 0) {
            logger.info("Cleaned up $orphanedCount orphaned display entities")
        }
    }

    /**
     * 無効なエンティティを追跡マップから削除する。
     */
    fun cleanupInvalidEntities() {
        logger.fine("Cleaning up invalid entities from tracking maps")

        var cleanedCount = 0

        // アイテム表示エンティティをチェック
        for ((locationKey, entities) in displayEntities.toMap()) {
            val invalidSlots = mutableListOf<Int>()

            for ((slotIndex, entityId) in entities) {
                val entity = Bukkit.getEntity(entityId)
                if (entity == null || entity.isDead) {
                    invalidSlots.add(slotIndex)
                }
            }

            for (slotIndex in invalidSlots) {
                entities.remove(slotIndex)
                cleanedCount++
            }

            if (entities.isEmpty()) {
                displayEntities.remove(locationKey)
            }
        }

        // テキスト表示エンティティをチェック
        for ((locationKey, entities) in textDisplayEntities.toMap()) {
            val invalidSlots = mutableListOf<Int>()

            for ((slotIndex, entityId) in entities) {
                val entity = Bukkit.getEntity(entityId)
                if (entity == null || entity.isDead) {
                    invalidSlots.add(slotIndex)
                }
            }

            for (slotIndex in invalidSlots) {
                entities.remove(slotIndex)
                cleanedCount++
            }

            if (entities.isEmpty()) {
                textDisplayEntities.remove(locationKey)
            }
        }

        if (cleanedCount > 0) {
            logger.fine("Cleaned up $cleanedCount invalid entity references")
        }
    }

    /**
     * 統計情報を取得する。
     */
    fun getDisplayStatistics(): Map<String, Any> {
        return mapOf(
            "totalDrawers" to displayEntities.size,
            "totalItemDisplays" to displayEntities.values.sumOf { it.size },
            "totalTextDisplays" to textDisplayEntities.values.sumOf { it.size }
        )
    }

    /**
     * 新しく参加したプレイヤーの処理（ItemFrame は特別な処理不要）。
     */
    fun handlePlayerJoin(player: Player) {
        // ItemFrame は全プレイヤーに自動的に表示される
        logger.fine("Player ${player.name} joined - ItemFrame displays are automatically visible")
    }
}
