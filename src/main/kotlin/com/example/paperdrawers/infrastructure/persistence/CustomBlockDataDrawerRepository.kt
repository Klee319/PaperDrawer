package com.example.paperdrawers.infrastructure.persistence

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerSlot
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerPersistenceException
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.config.DrawerCapacityConfig
import com.example.paperdrawers.infrastructure.debug.MetricsCollector
import com.jeff_media.customblockdata.CustomBlockData
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * CustomBlockData ライブラリを使用した DrawerRepository の実装。
 *
 * Why: CustomBlockData はブロック位置に基づいて PDC (Persistent Data Container) を提供し、
 * ブロックごとにカスタムデータを永続化できる。これにより外部データベースやファイルシステムに
 * 依存せず、Minecraft のネイティブな保存機構を活用できる。
 *
 * Note: このクラスはスレッドセーフではない。Bukkit のメインスレッドからのみ呼び出すこと。
 *
 * @property plugin プラグインインスタンス
 * @property logger ログ出力用のロガー
 */
class CustomBlockDataDrawerRepository(
    private val plugin: Plugin,
    private val logger: Logger = plugin.logger
) : DrawerRepository {

    /**
     * ドロワーブロックを保存する。
     *
     * PDC に以下のデータを保存:
     * - DRAWER_ID: UUID文字列
     * - DRAWER_TYPE: DrawerType名
     * - DRAWER_FACING: BlockFace名
     * - DRAWER_OWNER: 所有者UUID文字列（存在する場合）
     * - DRAWER_CREATED: 作成時刻（エポックミリ秒）
     * - 各スロットのデータ（マテリアル、数量、ロック状態）
     *
     * @param drawer 保存するドロワーブロック
     * @throws DrawerPersistenceException 永続化に失敗した場合
     */
    override fun save(drawer: DrawerBlock) {
        try {
            val block = drawer.location.block
            val blockData = CustomBlockData(block, plugin)
            val pdc = blockData

            // Core drawer data
            pdc.set(DrawerDataKeys.DRAWER_ID, PersistentDataType.STRING, drawer.id.toString())
            pdc.set(DrawerDataKeys.DRAWER_TYPE, PersistentDataType.STRING, drawer.type.name)
            pdc.set(DrawerDataKeys.DRAWER_FACING, PersistentDataType.STRING, drawer.facing.name)
            pdc.set(DrawerDataKeys.DRAWER_CREATED, PersistentDataType.LONG, drawer.createdAt)

            // Sorting flag
            if (drawer.isSorting) {
                pdc.set(DrawerDataKeys.DRAWER_SORTING, PersistentDataType.BYTE, 1.toByte())
            } else {
                pdc.remove(DrawerDataKeys.DRAWER_SORTING)
            }

            // Owner (optional)
            if (drawer.ownerId != null) {
                pdc.set(DrawerDataKeys.DRAWER_OWNER, PersistentDataType.STRING, drawer.ownerId.toString())
            } else {
                pdc.remove(DrawerDataKeys.DRAWER_OWNER)
            }

            // Slot data
            drawer.slots.forEachIndexed { index, slot ->
                saveSlotData(pdc, index, slot)
            }

            logger.fine("Saved drawer ${drawer.id} at ${drawer.getLocationKey()}")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to save drawer at ${drawer.location}", e)
            throw DrawerPersistenceException("Failed to save drawer at ${drawer.location}", e)
        }
    }

    /**
     * 指定された位置のドロワーブロックを取得する。
     *
     * @param location 検索する位置
     * @return ドロワーブロック、存在しない場合はnull
     * @throws DrawerPersistenceException 取得に失敗した場合
     */
    override fun findByLocation(location: Location): DrawerBlock? {
        try {
            val block = location.block
            val blockData = CustomBlockData(block, plugin)
            val pdc = blockData

            // Check if this block has drawer data
            val drawerIdStr = pdc.get(DrawerDataKeys.DRAWER_ID, PersistentDataType.STRING)

            if (drawerIdStr == null) {
                // Track cache miss (no drawer data at this location)
                MetricsCollector.incrementCacheMisses()
                return null
            }

            // Track cache hit (drawer data found)
            MetricsCollector.incrementCacheHits()
            return loadDrawerFromPdc(location, pdc, drawerIdStr)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to load drawer at $location", e)
            throw DrawerPersistenceException("Failed to load drawer at $location", e)
        }
    }

    /**
     * 指定されたチャンク内のすべてのドロワーブロックを取得する。
     *
     * Why: チャンクロード時のドロワー復元やチャンクアンロード時のメモリ解放に使用。
     * CustomBlockData.getBlocksWithCustomData() を使用して効率的に検索。
     *
     * @param chunk 検索するチャンク
     * @return チャンク内のドロワーブロックのリスト
     * @throws DrawerPersistenceException 取得に失敗した場合
     */
    override fun findByChunk(chunk: Chunk): List<DrawerBlock> {
        try {
            val drawers = mutableListOf<DrawerBlock>()
            val blocksWithData = CustomBlockData.getBlocksWithCustomData(plugin, chunk)

            for (block in blocksWithData) {
                val blockData = CustomBlockData(block, plugin)
                val pdc = blockData

                val drawerIdStr = pdc.get(DrawerDataKeys.DRAWER_ID, PersistentDataType.STRING)
                if (drawerIdStr != null) {
                    try {
                        val drawer = loadDrawerFromPdc(block.location, pdc, drawerIdStr)
                        if (drawer != null) {
                            drawers.add(drawer)
                        }
                    } catch (e: Exception) {
                        logger.log(Level.WARNING, "Failed to load drawer at ${block.location}", e)
                        // Continue loading other drawers
                    }
                }
            }

            logger.fine("Found ${drawers.size} drawers in chunk ${chunk.x}, ${chunk.z}")
            return drawers
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to load drawers from chunk ${chunk.x}, ${chunk.z}", e)
            throw DrawerPersistenceException("Failed to load drawers from chunk", e)
        }
    }

    /**
     * 指定された位置のドロワーブロックを削除する。
     *
     * @param location 削除するドロワーの位置
     * @throws DrawerPersistenceException 削除に失敗した場合
     */
    override fun delete(location: Location) {
        try {
            val block = location.block
            val blockData = CustomBlockData(block, plugin)

            // Check if drawer exists before clearing
            val drawerIdStr = blockData.get(DrawerDataKeys.DRAWER_ID, PersistentDataType.STRING)
            if (drawerIdStr != null) {
                blockData.clear()
                logger.fine("Deleted drawer $drawerIdStr at $location")
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to delete drawer at $location", e)
            throw DrawerPersistenceException("Failed to delete drawer at $location", e)
        }
    }

    /**
     * 指定された位置にドロワーブロックが存在するかどうかを確認する。
     *
     * @param location 確認する位置
     * @return ドロワーが存在する場合true
     * @throws DrawerPersistenceException 確認に失敗した場合
     */
    override fun exists(location: Location): Boolean {
        try {
            val block = location.block
            val blockData = CustomBlockData(block, plugin)
            return blockData.has(DrawerDataKeys.DRAWER_ID, PersistentDataType.STRING)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to check drawer existence at $location", e)
            throw DrawerPersistenceException("Failed to check drawer existence at $location", e)
        }
    }

    // ========================================
    // Private Helper Methods
    // ========================================

    /**
     * スロットデータを PDC に保存する。
     *
     * @param pdc 保存先の PersistentDataContainer
     * @param index スロットインデックス
     * @param slot 保存するスロット
     */
    private fun saveSlotData(pdc: CustomBlockData, index: Int, slot: DrawerSlot) {
        // Material (nullable)
        if (slot.storedMaterial != null) {
            pdc.set(
                DrawerDataKeys.slotMaterial(index),
                PersistentDataType.STRING,
                slot.storedMaterial.name
            )
        } else {
            pdc.remove(DrawerDataKeys.slotMaterial(index))
        }

        // Item count
        pdc.set(DrawerDataKeys.slotCount(index), PersistentDataType.INTEGER, slot.itemCount)

        // Lock state
        pdc.set(
            DrawerDataKeys.slotLocked(index),
            PersistentDataType.BYTE,
            if (slot.isLocked) 1.toByte() else 0.toByte()
        )

        // Locked material (nullable)
        if (slot.lockedMaterial != null) {
            pdc.set(
                DrawerDataKeys.slotLockedMaterial(index),
                PersistentDataType.STRING,
                slot.lockedMaterial.name
            )
        } else {
            pdc.remove(DrawerDataKeys.slotLockedMaterial(index))
        }

        // ItemStack template (nullable) - エンチャントやNBTタグを保持
        if (slot.storedItemTemplate != null) {
            val serializedBytes = serializeItemStack(slot.storedItemTemplate)
            if (serializedBytes != null) {
                pdc.set(
                    DrawerDataKeys.slotItemTemplate(index),
                    PersistentDataType.BYTE_ARRAY,
                    serializedBytes
                )
            }
        } else {
            pdc.remove(DrawerDataKeys.slotItemTemplate(index))
        }
    }

    /**
     * ItemStack をバイト配列にシリアライズする。
     *
     * Bukkit の ConfigurationSerializable インターフェースを使用して
     * ItemStack を YAML 形式でシリアライズし、バイト配列に変換する。
     *
     * @param itemStack シリアライズする ItemStack
     * @return シリアライズされたバイト配列、失敗した場合は null
     */
    private fun serializeItemStack(itemStack: ItemStack): ByteArray? {
        return try {
            val yaml = org.bukkit.configuration.file.YamlConfiguration()
            yaml.set("item", itemStack)
            yaml.saveToString().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            logger.warning("Failed to serialize ItemStack: ${e.message}")
            null
        }
    }

    /**
     * バイト配列から ItemStack をデシリアライズする。
     *
     * @param bytes デシリアライズするバイト配列
     * @return デシリアライズされた ItemStack、失敗した場合は null
     */
    private fun deserializeItemStack(bytes: ByteArray): ItemStack? {
        return try {
            val yamlString = String(bytes, Charsets.UTF_8)
            val yaml = org.bukkit.configuration.file.YamlConfiguration()
            yaml.loadFromString(yamlString)
            yaml.getItemStack("item")
        } catch (e: Exception) {
            logger.warning("Failed to deserialize ItemStack: ${e.message}")
            null
        }
    }

    /**
     * PDC からドロワーブロックを読み込む。
     *
     * @param location ブロックの位置
     * @param pdc 読み込み元の PersistentDataContainer
     * @param drawerIdStr ドロワーID文字列
     * @return 復元されたドロワーブロック、復元に失敗した場合はnull
     */
    private fun loadDrawerFromPdc(
        location: Location,
        pdc: CustomBlockData,
        drawerIdStr: String
    ): DrawerBlock? {
        // Parse drawer ID
        val drawerId = try {
            UUID.fromString(drawerIdStr)
        } catch (e: IllegalArgumentException) {
            logger.warning("Invalid drawer ID format: $drawerIdStr at $location")
            return null
        }

        // Load drawer type
        val typeStr = pdc.get(DrawerDataKeys.DRAWER_TYPE, PersistentDataType.STRING)
        if (typeStr == null) {
            logger.warning("Missing drawer type at $location")
            return null
        }

        // Why: 旧バージョンのレガシータイプ（SINGLE_1X1, DOUBLE_1X2, QUAD_2X2）を
        // 新しいTierベースのタイプにマイグレーションする
        val (drawerType, isLegacyMigration) = try {
            Pair(DrawerType.valueOf(typeStr), false)
        } catch (e: IllegalArgumentException) {
            val legacyType = DrawerType.fromLegacyName(typeStr)
            if (legacyType != null) {
                logger.info("Detected legacy drawer type '$typeStr' at $location, migrating to ${legacyType.name}")
                Pair(legacyType, true)
            } else {
                logger.warning("Invalid drawer type: $typeStr at $location")
                return null
            }
        }

        // Load facing direction
        val facingStr = pdc.get(DrawerDataKeys.DRAWER_FACING, PersistentDataType.STRING)
        if (facingStr == null) {
            logger.warning("Missing drawer facing at $location")
            return null
        }

        val facing = try {
            BlockFace.valueOf(facingStr)
        } catch (e: IllegalArgumentException) {
            logger.warning("Invalid facing direction: $facingStr at $location")
            return null
        }

        // Load owner (optional)
        val ownerIdStr = pdc.get(DrawerDataKeys.DRAWER_OWNER, PersistentDataType.STRING)
        val ownerId = ownerIdStr?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid owner ID format: $it at $location")
                null
            }
        }

        // Load created timestamp
        val createdAt = pdc.get(DrawerDataKeys.DRAWER_CREATED, PersistentDataType.LONG)
            ?: System.currentTimeMillis()

        // Load sorting flag
        val sortingByte = pdc.get(DrawerDataKeys.DRAWER_SORTING, PersistentDataType.BYTE) ?: 0
        val isSorting = sortingByte != 0.toByte()

        // Load slots
        val slots: List<DrawerSlot>

        if (isLegacyMigration) {
            // Why: レガシーマイグレーション時は旧スロット数分のデータを読み込み、
            // 1番目以降のスロットのアイテムをドロップしてから新タイプのスロットのみを使用する
            val legacySlotCount = getLegacySlotCount(typeStr)
            slots = loadSlotsForLegacyMigration(
                pdc = pdc,
                location = location,
                newDrawerType = drawerType,
                legacySlotCount = legacySlotCount,
                legacyTypeName = typeStr
            )
        } else {
            slots = loadSlots(pdc, drawerType)
        }

        val drawer = DrawerBlock(
            id = drawerId,
            location = location.clone(),
            type = drawerType,
            facing = facing,
            slots = slots,
            ownerId = ownerId,
            createdAt = createdAt,
            isSorting = isSorting
        )

        // Why: レガシーマイグレーション時はマイグレーション済みデータを再保存して
        // 次回以降の読み込みで再マイグレーションを防ぐ
        if (isLegacyMigration) {
            save(drawer)
            // 旧スロットデータをクリーンアップ
            cleanupLegacySlotData(pdc, legacySlotCount = getLegacySlotCount(typeStr), newSlotCount = drawerType.getSlotCount())
            logger.info("Legacy drawer migration completed at $location: $typeStr -> ${drawerType.name}")
        }

        return drawer
    }

    /**
     * レガシータイプ名からスロット数を取得する。
     *
     * @param legacyTypeName レガシータイプ名
     * @return スロット数
     */
    private fun getLegacySlotCount(legacyTypeName: String): Int {
        return when (legacyTypeName) {
            "SINGLE_1X1" -> 1
            "DOUBLE_1X2" -> 2
            "QUAD_2X2" -> 4
            else -> 1
        }
    }

    /**
     * レガシーマイグレーション用にスロットを読み込む。
     * 1番目以降のスロットにアイテムがある場合はドロップする。
     *
     * @param pdc 読み込み元の PersistentDataContainer
     * @param location ドロワーの位置
     * @param newDrawerType 新しいドロワータイプ
     * @param legacySlotCount 旧スロット数
     * @param legacyTypeName レガシータイプ名（ログ用）
     * @return 新タイプ用のスロットのリスト
     */
    private fun loadSlotsForLegacyMigration(
        pdc: CustomBlockData,
        location: Location,
        newDrawerType: DrawerType,
        legacySlotCount: Int,
        legacyTypeName: String
    ): List<DrawerSlot> {
        val baseCapacity = DrawerCapacityConfig.getCapacity(newDrawerType)

        // 最初のスロットを読み込み（これは維持される）
        val firstSlot = loadSlot(pdc, 0, baseCapacity)

        // 1番目以降のスロットを処理（アイテムがあればドロップ）
        for (slotIndex in 1 until legacySlotCount) {
            dropLegacySlotItems(pdc, location, slotIndex, legacyTypeName)
        }

        // 新タイプは単一スロットなので最初のスロットのみ返す
        return listOf(firstSlot)
    }

    /**
     * レガシースロットのアイテムをワールドにドロップする。
     *
     * @param pdc 読み込み元の PersistentDataContainer
     * @param location ドロップ位置
     * @param slotIndex スロットインデックス
     * @param legacyTypeName レガシータイプ名（ログ用）
     */
    private fun dropLegacySlotItems(
        pdc: CustomBlockData,
        location: Location,
        slotIndex: Int,
        legacyTypeName: String
    ) {
        val materialStr = pdc.get(DrawerDataKeys.slotMaterial(slotIndex), PersistentDataType.STRING)
        val itemCount = pdc.get(DrawerDataKeys.slotCount(slotIndex), PersistentDataType.INTEGER) ?: 0

        if (materialStr == null || itemCount <= 0) {
            return
        }

        val material = try {
            Material.valueOf(materialStr)
        } catch (e: IllegalArgumentException) {
            logger.warning("Legacy migration: Invalid material '$materialStr' in slot $slotIndex, items will be lost")
            return
        }

        // アイテムをワールドにドロップ
        val world = location.world
        if (world == null) {
            logger.warning("Legacy migration: World is null at $location, items from slot $slotIndex will be lost")
            return
        }

        val dropLocation = location.clone().add(0.5, 0.5, 0.5)
        val maxStackSize = material.maxStackSize

        var remaining = itemCount
        var droppedStacks = 0
        while (remaining > 0) {
            val dropAmount = minOf(remaining, maxStackSize)
            val itemStack = ItemStack(material, dropAmount)
            world.dropItemNaturally(dropLocation, itemStack)
            remaining -= dropAmount
            droppedStacks++
        }

        logger.info(
            "Legacy migration ($legacyTypeName): Dropped $itemCount x ${material.name} " +
            "($droppedStacks stacks) from slot $slotIndex at $location"
        )
    }

    /**
     * マイグレーション後に不要なレガシースロットデータをクリーンアップする。
     *
     * @param pdc PersistentDataContainer
     * @param legacySlotCount 旧スロット数
     * @param newSlotCount 新スロット数
     */
    private fun cleanupLegacySlotData(pdc: CustomBlockData, legacySlotCount: Int, newSlotCount: Int) {
        for (slotIndex in newSlotCount until legacySlotCount) {
            pdc.remove(DrawerDataKeys.slotMaterial(slotIndex))
            pdc.remove(DrawerDataKeys.slotCount(slotIndex))
            pdc.remove(DrawerDataKeys.slotLocked(slotIndex))
            pdc.remove(DrawerDataKeys.slotLockedMaterial(slotIndex))
        }
    }

    /**
     * PDC からスロットデータを読み込む。
     *
     * @param pdc 読み込み元の PersistentDataContainer
     * @param drawerType ドロワータイプ（スロット数と容量を決定）
     * @return 復元されたスロットのリスト
     */
    private fun loadSlots(pdc: CustomBlockData, drawerType: DrawerType): List<DrawerSlot> {
        val slotCount = drawerType.getSlotCount()
        val baseCapacity = DrawerCapacityConfig.getCapacity(drawerType)

        return (0 until slotCount).map { index ->
            loadSlot(pdc, index, baseCapacity)
        }
    }

    /**
     * PDC から単一のスロットを読み込む。
     *
     * @param pdc 読み込み元の PersistentDataContainer
     * @param index スロットインデックス
     * @param capacityInStacks スタック単位での容量
     * @return 復元されたスロット
     */
    private fun loadSlot(pdc: CustomBlockData, index: Int, capacityInStacks: Int): DrawerSlot {
        // Load material (nullable)
        val materialStr = pdc.get(DrawerDataKeys.slotMaterial(index), PersistentDataType.STRING)
        val storedMaterial = materialStr?.let {
            try {
                Material.valueOf(it)
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid material: $it for slot $index")
                null
            }
        }

        // Load item count
        val itemCount = pdc.get(DrawerDataKeys.slotCount(index), PersistentDataType.INTEGER) ?: 0

        // Load lock state
        val isLockedByte = pdc.get(DrawerDataKeys.slotLocked(index), PersistentDataType.BYTE) ?: 0
        val isLocked = isLockedByte != 0.toByte()

        // Load locked material (nullable)
        val lockedMaterialStr = pdc.get(
            DrawerDataKeys.slotLockedMaterial(index),
            PersistentDataType.STRING
        )
        val lockedMaterial = lockedMaterialStr?.let {
            try {
                Material.valueOf(it)
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid locked material: $it for slot $index")
                null
            }
        }

        // Load ItemStack template (nullable) - エンチャントやNBTタグを復元
        val templateBytes = pdc.get(DrawerDataKeys.slotItemTemplate(index), PersistentDataType.BYTE_ARRAY)
        val storedItemTemplate = templateBytes?.let { deserializeItemStack(it) }

        return DrawerSlot(
            index = index,
            storedMaterial = storedMaterial,
            itemCount = itemCount,
            maxCapacity = capacityInStacks * DrawerSlot.ITEMS_PER_STACK,
            isLocked = isLocked,
            lockedMaterial = lockedMaterial,
            storedItemTemplate = storedItemTemplate
        )
    }
}
