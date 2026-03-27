package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerSlot
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.infrastructure.config.DrawerCapacityConfig
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.cache.DrawerLocationRegistry
import com.example.paperdrawers.infrastructure.debug.MetricsCollector
import com.example.paperdrawers.infrastructure.display.DisplayManager
import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import com.example.paperdrawers.infrastructure.message.MessageManager
import com.example.paperdrawers.infrastructure.persistence.DrawerDataKeys
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ドロワーブロックの設置を処理するイベントリスナー。
 *
 * プレイヤーがドロワーアイテム（特別なPDCタグを持つバレル）を設置した際に、
 * ドメインモデルの作成、リポジトリへの保存、ディスプレイエンティティの生成を行う。
 *
 * @property repository ドロワーデータの永続化を担当するリポジトリ
 * @property displayManager ドロワーの視覚的表示を管理するマネージャー
 * @property logger ログ出力用のロガー
 */
class DrawerPlaceListener(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val logger: Logger
) : Listener {

    companion object {
        /**
         * ドロワーとして認識されるブロックマテリアルのセット。
         *
         * Why: 設定可能にすることで、将来的にバレル以外のブロックを
         * ドロワーとして使用する拡張が可能になる。
         */
        val DRAWER_MATERIALS: Set<Material> = DrawerItemFactory.DRAWER_MATERIALS
    }

    /**
     * ブロック設置イベントを処理する。
     *
     * 以下の処理を実行:
     * 1. 設置されたブロックがドロワーマテリアルかを確認
     * 2. アイテムにドロワータグが付いているかを確認
     * 3. プレイヤーの向きから適切なBlockFaceを決定
     * 4. DrawerBlockを作成してリポジトリに保存
     * 5. ディスプレイエンティティを生成
     *
     * @param event ブロック設置イベント
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val block = event.block
        val player = event.player
        val itemInHand = event.itemInHand

        // Step 1: Check if placed block is a drawer material
        if (!isDrawerMaterial(block.type)) {
            return
        }

        // Step 2: Check if item has drawer tag
        val drawerType = getDrawerTypeFromItem(itemInHand)
        if (drawerType == null) {
            // Not a drawer item, ignore
            return
        }

        logger.fine("Player ${player.name} is placing a drawer of type $drawerType at ${block.location}")

        try {
            // Step 3: Determine drawer facing based on player direction
            val facing = getPlayerFacingDirection(player)

            // Step 3.5: Check if item is a sorting drawer
            val isSorting = DrawerItemFactory.isSortingDrawer(itemInHand)

            // Step 4: Create DrawerBlock
            val baseDrawer = DrawerBlock.create(
                location = block.location,
                type = drawerType,
                facing = facing,
                ownerId = player.uniqueId,
                isSorting = isSorting,
                capacityStacks = DrawerCapacityConfig.getCapacity(drawerType)
            )

            // Step 4.5: Restore stored contents from item PDC (shulker-box behavior)
            val storedSlots = DrawerItemFactory.getStoredSlots(itemInHand)
            val drawer = if (storedSlots != null && storedSlots.isNotEmpty()) {
                logger.fine("Restored ${storedSlots.size} slot(s) with contents for drawer: ${baseDrawer.id}")
                restoreDrawerContents(baseDrawer, storedSlots)
            } else {
                baseDrawer
            }

            // Step 4.6: ジュークボックス型（ボイドドロワー）のバニラ状態を初期化
            // Why: バニラのジュークボックスはディスク挿入・再生・コンパレータ出力等の
            // 機能を持つため、ドロワーとして使用する際はこれらを無効化する必要がある
            if (block.type == Material.JUKEBOX) {
                val jukebox = block.state as? org.bukkit.block.Jukebox
                if (jukebox != null) {
                    jukebox.setRecord(null)
                    jukebox.stopPlaying()
                    jukebox.update()
                }
            }

            repository.save(drawer)
            DrawerLocationRegistry.register(drawer.location, drawer.isSorting)
            logger.fine("Drawer saved to repository: ${drawer.id} at ${drawer.getLocationKey()}")

            // Step 5: Create display entities
            displayManager.createDisplay(drawer)
            logger.fine("Display created for drawer: ${drawer.id}")

            // Track metrics
            MetricsCollector.incrementDrawersCreated()

            // Notify player (optional)
            // player.sendMessage("Drawer placed successfully!")

        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to place drawer: ${e.message}", e)

            // Cancel the event to prevent placing invalid drawer
            event.isCancelled = true

            // Notify player about the error
            player.sendMessage(MessageManager.getInstance().getPlacementFailed())
        }
    }

    /**
     * 指定されたマテリアルがドロワーマテリアルかどうかを確認する。
     *
     * @param material 確認するマテリアル
     * @return ドロワーマテリアルの場合true
     */
    private fun isDrawerMaterial(material: Material): Boolean {
        return material in DRAWER_MATERIALS
    }

    /**
     * アイテムからドロワータイプを取得する。
     *
     * アイテムのPersistent Data Container (PDC)を確認し、
     * ドロワータイプのタグが存在すればそのタイプを返す。
     *
     * @param item 確認するアイテム
     * @return ドロワータイプ、ドロワーアイテムでない場合はnull
     */
    private fun getDrawerTypeFromItem(item: ItemStack): DrawerType? {
        val itemMeta = item.itemMeta ?: return null
        val pdc = itemMeta.persistentDataContainer

        // Check if item has drawer type tag
        val typeString = pdc.get(DrawerDataKeys.DRAWER_TYPE, PersistentDataType.STRING)
            ?: return null

        return try {
            // Parse drawer type from string (e.g., "SINGLE_TIER_1", "SINGLE_TIER_2", etc.)
            DrawerType.valueOf(typeString.uppercase())
        } catch (e: IllegalArgumentException) {
            // Try legacy type migration (DOUBLE_1X2, QUAD_2X2, etc. -> SINGLE_TIER_1)
            val legacyType = DrawerType.fromLegacyName(typeString)
            if (legacyType != null) {
                logger.info("Legacy drawer type '$typeString' will be migrated to '${legacyType.name}'")
                legacyType
            } else {
                logger.warning("Invalid drawer type in item PDC: $typeString")
                null
            }
        }
    }

    /**
     * アイテムPDCから読み取ったスロットデータをDrawerBlockに復元する。
     *
     * 新しく作成されたDrawerBlockのスロットに、保存されていたアイテムデータを
     * 反映した新しいDrawerBlockを返す。maxCapacityは新しいドロワーの設定を使用する。
     *
     * @param drawer 空のDrawerBlock
     * @param storedSlots PDCから読み取ったスロットデータ
     * @return コンテンツが復元されたDrawerBlock
     */
    private fun restoreDrawerContents(
        drawer: DrawerBlock,
        storedSlots: List<com.example.paperdrawers.infrastructure.item.StoredSlotData>
    ): DrawerBlock {
        val restoredSlots = drawer.slots.map { originalSlot ->
            val stored = storedSlots.find { it.index == originalSlot.index }
            if (stored != null) {
                if (stored.storedMaterial != null) {
                    // Slot has material: restore with clamped item count
                    val clampedCount = minOf(stored.itemCount, originalSlot.maxCapacity)
                    if (clampedCount < stored.itemCount) {
                        // キャパシティ超過分をドロップしてアイテムロスを防ぐ
                        val excessCount = stored.itemCount - clampedCount
                        dropExcessItems(drawer.location, stored, excessCount)
                        logger.warning(
                            "Slot ${originalSlot.index} item count clamped from ${stored.itemCount} " +
                            "to $clampedCount (maxCapacity=${originalSlot.maxCapacity}) for drawer: ${drawer.id}. " +
                            "Dropped $excessCount excess items."
                        )
                    }
                    // Guard: if isLocked but lockedMaterial is null, skip lock restoration
                    val validLock = stored.isLocked && stored.lockedMaterial != null
                    if (stored.isLocked && stored.lockedMaterial == null) {
                        logger.warning(
                            "Slot ${originalSlot.index} has isLocked=true but lockedMaterial=null for drawer: ${drawer.id}. " +
                            "Lock state will not be restored."
                        )
                    }
                    DrawerSlot(
                        index = originalSlot.index,
                        storedMaterial = stored.storedMaterial,
                        itemCount = clampedCount,
                        maxCapacity = originalSlot.maxCapacity,
                        isLocked = validLock,
                        lockedMaterial = if (validLock) stored.lockedMaterial else null,
                        storedItemTemplate = stored.storedItemTemplate
                    )
                } else {
                    // Locked empty slot: keep locked state but no material
                    // Guard: if lockedMaterial is null despite isLocked, skip lock restoration
                    val canRestoreLock = stored.isLocked && stored.lockedMaterial != null
                    if (stored.isLocked && stored.lockedMaterial == null) {
                        logger.warning(
                            "Slot ${originalSlot.index} has isLocked=true but lockedMaterial=null for drawer: ${drawer.id}. " +
                            "Lock state will not be restored."
                        )
                    }
                    DrawerSlot(
                        index = originalSlot.index,
                        storedMaterial = if (canRestoreLock) stored.lockedMaterial else null,
                        itemCount = 0,
                        maxCapacity = originalSlot.maxCapacity,
                        isLocked = canRestoreLock,
                        lockedMaterial = if (canRestoreLock) stored.lockedMaterial else null,
                        storedItemTemplate = null
                    )
                }
            } else {
                originalSlot
            }
        }

        return drawer.copy(slots = restoredSlots)
    }

    /**
     * キャパシティ超過分のアイテムをドロワー位置にドロップする。
     *
     * Why: ドロワーのティアが変更された場合等にキャパシティが減少し、
     * 保存されていたアイテム数が新しいキャパシティを超えることがある。
     * 超過分を消滅させずにワールドにドロップすることでアイテムロスを防ぐ。
     *
     * @param location ドロップ位置
     * @param stored スロットデータ
     * @param excessCount ドロップするアイテム数
     */
    private fun dropExcessItems(
        location: Location,
        stored: com.example.paperdrawers.infrastructure.item.StoredSlotData,
        excessCount: Int
    ) {
        val world = location.world ?: return
        val dropLocation = location.clone().add(0.5, 1.0, 0.5)
        val material = stored.storedMaterial ?: return

        // テンプレートがある場合はエンチャント等を保持してドロップ
        val baseItem = stored.storedItemTemplate?.clone() ?: ItemStack(material)
        val maxStackSize = material.maxStackSize

        var remaining = excessCount
        while (remaining > 0) {
            val dropAmount = minOf(remaining, maxStackSize)
            val dropItem = baseItem.clone().apply { amount = dropAmount }
            world.dropItemNaturally(dropLocation, dropItem)
            remaining -= dropAmount
        }
    }

    /**
     * プレイヤーの向いている方向から、ドロワーの正面方向を決定する。
     *
     * ドロワーの正面はプレイヤーの方を向くため、
     * プレイヤーの向きと反対方向をドロワーのfacingとして返す。
     *
     * Why: Storage Drawersと同様に、ドロワーはプレイヤーに向かって設置される。
     * これによりプレイヤーは設置後すぐにドロワーを操作できる。
     *
     * @param player プレイヤー
     * @return ドロワーの正面方向（水平方向のBlockFace）
     */
    private fun getPlayerFacingDirection(player: Player): BlockFace {
        // Get the direction the player is looking
        val yaw = player.location.yaw

        // Normalize yaw to 0-360 range
        val normalizedYaw = ((yaw % 360) + 360) % 360

        // Determine the opposite direction (drawer faces toward player)
        // Player looking North (yaw ~180) -> Drawer faces South
        // Player looking South (yaw ~0/360) -> Drawer faces North
        // Player looking East (yaw ~270) -> Drawer faces West
        // Player looking West (yaw ~90) -> Drawer faces East
        return when {
            normalizedYaw >= 315 || normalizedYaw < 45 -> BlockFace.NORTH   // Player facing South -> Drawer faces North
            normalizedYaw >= 45 && normalizedYaw < 135 -> BlockFace.EAST    // Player facing West -> Drawer faces East
            normalizedYaw >= 135 && normalizedYaw < 225 -> BlockFace.SOUTH  // Player facing North -> Drawer faces South
            else -> BlockFace.WEST                                           // Player facing East -> Drawer faces West
        }
    }
}
