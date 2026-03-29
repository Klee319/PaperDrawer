package com.example.paperdrawers.infrastructure.hopper

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.cache.DrawerLocationRegistry
import com.example.paperdrawers.infrastructure.display.DisplayManager
import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Hopper
import org.bukkit.block.data.type.Hopper as HopperData
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * ホッパーとドロワー間のアイテム転送を定期的に処理するタスク。
 *
 * 2つの方向を処理:
 * 1. ドロワー → ホッパー（下方向の吸出し）
 * 2. ホッパー → ドロワー（ホッパーがドロワーに向いている場合の搬入）
 *
 * Why: バニラのホッパーはドロワーのPDCベースストレージと連携できないため、
 * 全てのホッパー⇔ドロワー間のアイテム転送をこのタスクで直接操作する。
 * InventoryMoveItemEvent 方式は Paper のホッパー最適化と競合してアイテム消失を
 * 引き起こすため、ホッパーインベントリの直接操作方式を採用している。
 *
 * DrawerLocationRegistry を使用して既知のドロワー位置のみを走査する。
 *
 * 実行間隔: 設定可能（デフォルト8 tick = バニラホッパーと同じ速度）
 * 実行スレッド: メインスレッド
 */
class HopperPullTask(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val plugin: Plugin,
    private val logger: Logger
) : Runnable {

    companion object {
        /** ホッパーがドロワーに向いているかチェックする方向（上＋水平4方向） */
        private val PUSH_CHECK_FACES = arrayOf(
            BlockFace.UP,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
        )
    }

    override fun run() {
        for (location in DrawerLocationRegistry.getAllLocations()) {
            val world = location.world ?: continue
            if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) continue

            try {
                val drawer = repository.findByLocation(location) ?: continue
                tryPullToHopper(drawer)
                tryPushFromHoppers(drawer)
            } catch (e: Exception) {
                logger.fine("Failed to process hopper task at $location: ${e.message}")
            }
        }
    }

    // ========================================
    // ドロワー → ホッパー（下方向の吸出し）
    // ========================================

    /**
     * ドロワーの下にホッパーがあれば、アイテムを1つ移送する。
     */
    private fun tryPullToHopper(drawer: DrawerBlock) {
        if (drawer.type == DrawerType.VOID) return
        if (drawer.isEmpty()) return

        val belowBlock = drawer.location.clone().subtract(0.0, 1.0, 0.0).block
        if (belowBlock.type != Material.HOPPER) return
        if (belowBlock.isBlockPowered || belowBlock.isBlockIndirectlyPowered) return

        val hopper = belowBlock.state as? Hopper ?: return
        val hopperInventory = hopper.inventory

        val slot = drawer.slots.firstOrNull { !it.isEmpty() } ?: return
        val testItem = slot.createItemStack(1) ?: return

        val hasFreeSpace = hopperInventory.contents.any { content ->
            content == null || (content.isSimilar(testItem) && content.amount < content.maxStackSize)
        }
        if (!hasFreeSpace) return

        val (extractedItem, updatedDrawer) = drawer.extractItemStack(slot.index, 1)
        if (extractedItem == null) return

        val leftover = hopperInventory.addItem(extractedItem)
        if (leftover.isNotEmpty()) {
            val leftoverItem = leftover.values.first()
            val (reInsertResult, reInsertedDrawer) = updatedDrawer.insertItemStack(slot.index, leftoverItem)
            if (!reInsertResult.success) {
                val world = drawer.location.world
                if (world != null) {
                    val dropLoc = drawer.location.clone().add(0.5, 1.0, 0.5)
                    world.dropItemNaturally(dropLoc, leftoverItem)
                }
                logger.warning("[ITEM RECOVERY] Dropped ${leftoverItem.type} x${leftoverItem.amount} at ${drawer.getLocationKey()} (re-insert failed)")
                repository.save(updatedDrawer)
            } else {
                repository.save(reInsertedDrawer)
            }
            return
        }

        repository.save(updatedDrawer)
        displayManager.updateSlotDisplay(updatedDrawer, slot.index)
    }

    // ========================================
    // ホッパー → ドロワー（搬入）
    // ========================================

    /**
     * ドロワーに向いているホッパーからアイテムを1つ搬入する。
     *
     * Why: InventoryMoveItemEvent のキャンセル＋手動操作方式は Paper のホッパー最適化と
     * 競合してアイテム消失を引き起こす。ホッパーインベントリの直接操作で安全に転送する。
     */
    private fun tryPushFromHoppers(drawer: DrawerBlock) {
        // 仕分けドロワーへの搬入は SortingDrawerPullTask が設定済みスロットのみに
        // フィルタリングして行うため、ここではスキップする
        if (drawer.isSorting) return
        for (face in PUSH_CHECK_FACES) {
            val adjacentBlock = drawer.location.block.getRelative(face)
            if (adjacentBlock.type != Material.HOPPER) continue

            // ホッパーがドロワーの方向を向いているかチェック
            val hopperData = adjacentBlock.blockData as? HopperData ?: continue
            val hopperOutputFace = hopperData.facing
            if (adjacentBlock.getRelative(hopperOutputFace).location != drawer.location.block.location) continue

            // レッドストーン動力で無効化
            if (adjacentBlock.isBlockPowered || adjacentBlock.isBlockIndirectlyPowered) continue

            val hopper = adjacentBlock.state as? Hopper ?: continue
            val hopperInventory = hopper.inventory

            if (tryInsertFromInventory(drawer, hopperInventory)) {
                return
            }
        }
    }

    /**
     * ホッパーインベントリからドロワーにアイテムを1つ搬入する。
     *
     * @return 搬入成功した場合 true
     */
    private fun tryInsertFromInventory(drawer: DrawerBlock, inventory: Inventory): Boolean {
        // Void drawer: 最初のアイテムを消滅
        if (drawer.type == DrawerType.VOID) {
            for (i in 0 until inventory.size) {
                val item = inventory.getItem(i) ?: continue
                if (item.type.isAir) continue

                if (item.amount <= 1) {
                    inventory.setItem(i, null)
                } else {
                    inventory.setItem(i, item.clone().apply { amount = item.amount - 1 })
                }
                return true
            }
            return false
        }

        // 通常ドロワー: 受け入れ可能なアイテムを1つ搬入
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (item.type.isAir) continue

            val singleItem = item.clone().apply { amount = 1 }

            for (slot in drawer.slots) {
                if (!slot.canAcceptItemStack(singleItem)) continue

                val (result, updatedDrawer) = drawer.insertItemStack(slot.index, singleItem)
                if (result.success) {
                    repository.save(updatedDrawer)

                    // ホッパーから1個減らす
                    if (item.amount <= 1) {
                        inventory.setItem(i, null)
                    } else {
                        inventory.setItem(i, item.clone().apply { amount = item.amount - 1 })
                    }

                    displayManager.updateSlotDisplay(updatedDrawer, slot.index)
                    return true
                }
            }
        }
        return false
    }
}
