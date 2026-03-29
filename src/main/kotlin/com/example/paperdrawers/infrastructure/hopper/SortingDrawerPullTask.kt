package com.example.paperdrawers.infrastructure.hopper

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.cache.DrawerLocationRegistry
import com.example.paperdrawers.infrastructure.display.DisplayManager
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Hopper
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * 仕分けドロワーが隣接するホッパーからアイテムを吸い取るサービス。
 *
 * 2つのトリガーで動作:
 * 1. イベント駆動: ホッパーにアイテムが到着した際に HopperInteractionListener から即座に呼び出し
 * 2. ポーリング: 4tick間隔のフォールバック（プレイヤーが直接ホッパーにアイテムを入れた場合等）
 *
 * 1回の呼び出しで隣接ホッパー内の受け入れ可能なアイテムを全数吸い取る。
 * ホッパーインベントリを直接操作するため Paper のホッパー最適化と競合しない。
 */
class SortingDrawerPullTask(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val plugin: Plugin,
    private val logger: Logger
) : Runnable {

    companion object {
        /** 吸い込み対象の方向（下方向を除外: 下のホッパーは排出先） */
        private val ADJACENT_FACES = arrayOf(
            BlockFace.UP,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
        )
    }

    /**
     * ポーリングによるフォールバック処理。
     * プレイヤーが直接ホッパーにアイテムを入れた場合等、
     * InventoryMoveItemEvent が発火しないケースをカバーする。
     */
    override fun run() {
        for (location in DrawerLocationRegistry.getSortingLocations()) {
            val world = location.world ?: continue
            if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) continue

            try {
                processDrawerAt(location)
            } catch (e: Exception) {
                logger.fine("Failed to process sorting pull at $location: ${e.message}")
            }
        }
    }

    /**
     * 指定位置の仕分けドロワーの隣接ホッパーからアイテムを全数吸い取る。
     *
     * HopperInteractionListener からイベント駆動で呼び出される公開 API。
     * 1回の呼び出しで受け入れ可能なアイテムを可能な限り全て吸い取る。
     *
     * @param drawerLocation 仕分けドロワーの位置
     */
    fun processDrawerAt(drawerLocation: Location) {
        val drawer = repository.findByLocation(drawerLocation) ?: return
        if (!drawer.isSorting) return
        if (drawer.type == DrawerType.VOID) return

        val block = drawerLocation.block
        if (block.isBlockPowered || block.isBlockIndirectlyPowered) return

        pullAllFromAdjacentHoppers(drawer)
    }

    /**
     * 仕分けドロワーの隣接ホッパーから受け入れ可能なアイテムを全数吸い取る。
     */
    private fun pullAllFromAdjacentHoppers(drawer: DrawerBlock) {
        var currentDrawer = drawer

        for (face in ADJACENT_FACES) {
            val adjacentBlock = currentDrawer.location.block.getRelative(face)
            if (adjacentBlock.type != Material.HOPPER) continue
            if (adjacentBlock.isBlockPowered || adjacentBlock.isBlockIndirectlyPowered) continue

            val hopper = adjacentBlock.state as? Hopper ?: continue

            currentDrawer = pullAllFromInventory(currentDrawer, hopper.inventory)
        }
    }

    /**
     * ホッパーインベントリから受け入れ可能なアイテムを全数吸い取る。
     *
     * 1個ずつではなく、スタック全体を可能な限り一括で転送する。
     * ドロワーの容量上限を超えない範囲で最大量を移送する。
     *
     * @return 更新後の DrawerBlock
     */
    private fun pullAllFromInventory(drawer: DrawerBlock, inventory: Inventory): DrawerBlock {
        var currentDrawer = drawer
        var displayUpdated = false

        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (item.type.isAir) continue

            for (slot in currentDrawer.slots) {
                val isConfiguredSlot = !slot.isEmpty() || slot.isLocked
                if (!isConfiguredSlot) continue

                val singleItem = item.clone().apply { amount = 1 }
                if (!slot.canAcceptItemStack(singleItem)) continue

                // スタック全体を一括転送（容量上限まで）
                val transferItem = item.clone()
                val (result, updatedDrawer) = currentDrawer.insertItemStack(slot.index, transferItem)
                if (result.success) {
                    currentDrawer = updatedDrawer

                    // ホッパーから転送分を減らす
                    val transferred = result.insertedCount
                    val remaining = item.amount - transferred
                    if (remaining <= 0) {
                        inventory.setItem(i, null)
                    } else {
                        inventory.setItem(i, item.clone().apply { amount = remaining })
                    }

                    displayUpdated = true
                }
                break
            }
        }

        if (displayUpdated) {
            repository.save(currentDrawer)
            for (slot in currentDrawer.slots) {
                displayManager.updateSlotDisplay(currentDrawer, slot.index)
            }
        }

        return currentDrawer
    }
}
