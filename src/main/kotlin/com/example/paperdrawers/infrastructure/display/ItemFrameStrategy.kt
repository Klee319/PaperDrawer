package com.example.paperdrawers.infrastructure.display

import com.example.paperdrawers.domain.model.DrawerBlock
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Rotation
import org.bukkit.block.BlockFace
import org.bukkit.entity.GlowItemFrame
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.joml.Vector2f
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ItemFrame を使用したドロワー表示戦略。
 *
 * Why: ItemFrame は Java Edition と Bedrock Edition (Geyser経由) の両方で
 * 正しく表示されるため、プラットフォームに依存しない表示方法として最適。
 * ArmorStand や ItemDisplay よりもシンプルで信頼性が高い。
 *
 * 特徴:
 * - アイテム表示専用のエンティティ
 * - Geyser/Bedrock で問題なく動作
 * - 額縁自体を非表示にしてアイテムのみ表示可能
 *
 * @property plugin プラグインインスタンス
 * @property logger ログ出力用のロガー
 */
class ItemFrameStrategy(
    private val plugin: Plugin,
    private val logger: Logger
) : DisplayStrategy {

    /** PDC キー: ドロワー ID */
    private val keyDrawerId: NamespacedKey = NamespacedKey(plugin, "display_drawer_id")

    /** PDC キー: スロットインデックス */
    private val keySlotIndex: NamespacedKey = NamespacedKey(plugin, "display_slot_index")

    /** PDC キー: ドロワーの位置（world:x:y:z形式） */
    private val keyDrawerLocation: NamespacedKey = NamespacedKey(plugin, "display_drawer_location")

    private companion object {
        /** ブロック面からの前方オフセット距離（樽の前面に表示） */
        const val FORWARD_OFFSET = 0.51

        /** ItemFrame のスケール */
        const val ITEM_FRAME_SCALE = 0.5f
    }

    override fun createSlotDisplay(drawer: DrawerBlock, slotIndex: Int): UUID? {
        if (slotIndex < 0 || slotIndex >= drawer.slots.size) {
            throw IndexOutOfBoundsException(
                "Slot index $slotIndex is out of bounds for drawer with ${drawer.slots.size} slots"
            )
        }

        val slot = drawer.getSlot(slotIndex)

        // スロットが空の場合はエンティティを作成しない
        if (slot.isEmpty() && slot.storedMaterial == null) {
            logger.fine("ItemFrameStrategy: Slot $slotIndex is empty, skipping display creation")
            return null
        }

        val material = slot.storedMaterial ?: return null
        val world = drawer.location.world ?: run {
            logger.warning("ItemFrameStrategy: Drawer location has no world")
            return null
        }

        // スロット位置を計算
        val displayLocation = calculateSlotDisplayLocation(drawer, slotIndex)

        return try {
            val itemFrame = world.spawn(displayLocation, GlowItemFrame::class.java) { frame ->
                configureItemFrame(frame, drawer, slotIndex, slot.storedItemTemplate ?: ItemStack(material, 1))
            }

            logger.fine(
                "ItemFrameStrategy: Created ItemFrame for drawer ${drawer.id}, " +
                "slot $slotIndex at ${displayLocation.toVector()}, material=$material"
            )

            itemFrame.uniqueId
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "ItemFrameStrategy: Failed to create ItemFrame for drawer ${drawer.id}, slot $slotIndex",
                e
            )
            null
        }
    }

    override fun updateSlotDisplay(drawer: DrawerBlock, slotIndex: Int, entityId: UUID) {
        val entity = findItemFrame(entityId) ?: run {
            logger.fine("ItemFrameStrategy: Entity $entityId not found for update")
            return
        }

        val slot = drawer.getSlot(slotIndex)

        // スロットが空で、かつロックされていない場合のみエンティティを削除
        // ロックされている場合はロックマテリアルを表示し続ける
        if (slot.isEmpty() && !slot.isLocked) {
            entity.remove()
            logger.fine("ItemFrameStrategy: Removed empty unlocked slot display for drawer ${drawer.id}, slot $slotIndex")
            return
        }

        // アイテムを更新
        // 優先順位: テンプレート > storedMaterial > lockedMaterial
        val itemStack = slot.storedItemTemplate?.clone()?.apply { amount = 1 }
            ?: slot.storedMaterial?.let { ItemStack(it, 1) }
            ?: slot.lockedMaterial?.let { ItemStack(it, 1) }
            ?: run {
                // 表示するマテリアルがない場合はエンティティを削除
                entity.remove()
                logger.fine("ItemFrameStrategy: Removed display with no material for drawer ${drawer.id}, slot $slotIndex")
                return
            }

        entity.setItem(itemStack, false)

        logger.fine(
            "ItemFrameStrategy: Updated ItemFrame for drawer ${drawer.id}, " +
            "slot $slotIndex with material ${itemStack.type}"
        )
    }

    override fun removeDisplay(entityId: UUID) {
        val entity = findItemFrame(entityId) ?: run {
            logger.fine("ItemFrameStrategy: Entity $entityId not found for removal")
            return
        }

        entity.remove()
        logger.fine("ItemFrameStrategy: Removed ItemFrame $entityId")
    }

    override fun supportsPlayer(player: Player): Boolean {
        // すべてのプレイヤーをサポート（JE/BE 両対応）
        return true
    }

    override fun getStrategyName(): String = "ItemFrameStrategy"

    /**
     * スロットの表示位置を計算する。
     */
    private fun calculateSlotDisplayLocation(drawer: DrawerBlock, slotIndex: Int): Location {
        val baseLocation = drawer.location.clone()
        val facing = drawer.facing
        val slotPositions = drawer.type.getSlotPositions()
        val slotPosition = slotPositions[slotIndex]

        // ブロックの中心座標を基準にする
        baseLocation.add(0.5, 0.0, 0.5)

        // 垂直方向のオフセット
        val verticalOffset = 1.0 - slotPosition.y

        // 水平方向のオフセット
        val horizontalOffset = calculateHorizontalOffset(facing, slotPosition)

        // 前方向のオフセット
        val forwardOffset = calculateForwardOffset(facing)

        val location = baseLocation.add(
            horizontalOffset.x + forwardOffset.x,
            verticalOffset,
            horizontalOffset.z + forwardOffset.z
        )

        // ItemFrame の向きを設定
        location.yaw = calculateYawFromFacing(facing)
        location.pitch = 0f

        return location
    }

    /**
     * 水平方向のオフセットを計算する。
     */
    private fun calculateHorizontalOffset(facing: BlockFace, slotPosition: Vector2f): Location {
        val normalizedX = slotPosition.x - 0.5f

        return when (facing) {
            BlockFace.NORTH -> Location(null, normalizedX.toDouble(), 0.0, 0.0)
            BlockFace.SOUTH -> Location(null, -normalizedX.toDouble(), 0.0, 0.0)
            BlockFace.EAST -> Location(null, 0.0, 0.0, normalizedX.toDouble())
            BlockFace.WEST -> Location(null, 0.0, 0.0, -normalizedX.toDouble())
            else -> Location(null, 0.0, 0.0, 0.0)
        }
    }

    /**
     * 前方向のオフセットを計算する。
     */
    private fun calculateForwardOffset(facing: BlockFace): Location {
        return when (facing) {
            BlockFace.NORTH -> Location(null, 0.0, 0.0, -FORWARD_OFFSET)
            BlockFace.SOUTH -> Location(null, 0.0, 0.0, FORWARD_OFFSET)
            BlockFace.EAST -> Location(null, FORWARD_OFFSET, 0.0, 0.0)
            BlockFace.WEST -> Location(null, -FORWARD_OFFSET, 0.0, 0.0)
            else -> Location(null, 0.0, 0.0, 0.0)
        }
    }

    /**
     * ItemFrame を設定する。
     */
    private fun configureItemFrame(
        itemFrame: ItemFrame,
        drawer: DrawerBlock,
        slotIndex: Int,
        itemStack: ItemStack
    ) {
        // 基本設定
        itemFrame.isVisible = false  // 額縁自体は非表示
        itemFrame.isFixed = true     // 固定（アイテムが取れない）
        // isInvulnerable = false にして EntityDamageByEntityEvent を発火させる
        // 保護は ItemFrameInteractionListener でイベントキャンセルによって行う
        itemFrame.isInvulnerable = false
        itemFrame.setGravity(false)

        // アイテムを設定
        val displayItem = itemStack.clone().apply { amount = 1 }
        itemFrame.setItem(displayItem, false)

        // 向きを設定
        itemFrame.setFacingDirection(drawer.facing, true)

        // 回転設定（アイテムを正位置で表示）
        itemFrame.rotation = Rotation.NONE

        // PDC に情報を保存
        val pdc = itemFrame.persistentDataContainer
        pdc.set(keyDrawerId, PersistentDataType.STRING, drawer.id.toString())
        pdc.set(keySlotIndex, PersistentDataType.INTEGER, slotIndex)
        // ドロワーの位置情報を保存（world:x:y:z形式）
        pdc.set(keyDrawerLocation, PersistentDataType.STRING, drawer.getLocationKey())

        // 非永続化（再起動時に消える、チャンクロード時に再生成）
        itemFrame.isPersistent = false
    }

    /**
     * BlockFace から Yaw 角度を計算する。
     */
    private fun calculateYawFromFacing(facing: BlockFace): Float {
        return when (facing) {
            BlockFace.SOUTH -> 0f
            BlockFace.WEST -> 90f
            BlockFace.NORTH -> 180f
            BlockFace.EAST -> -90f
            else -> 0f
        }
    }

    /**
     * UUID から ItemFrame を検索する。
     */
    private fun findItemFrame(entityId: UUID): ItemFrame? {
        val entity = Bukkit.getEntity(entityId)
        return entity as? ItemFrame
    }

    /**
     * ドロワーに関連するすべての ItemFrame を削除する。
     */
    fun removeAllDisplaysForDrawer(drawerId: UUID): Int {
        val drawerIdString = drawerId.toString()
        var removedCount = 0

        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                if (entity is ItemFrame) {
                    val storedDrawerId = entity.persistentDataContainer.get(
                        keyDrawerId,
                        PersistentDataType.STRING
                    )
                    if (storedDrawerId == drawerIdString) {
                        entity.remove()
                        removedCount++
                    }
                }
            }
        }

        if (removedCount > 0) {
            logger.fine("ItemFrameStrategy: Removed $removedCount ItemFrames for drawer $drawerId")
        }

        return removedCount
    }
}
