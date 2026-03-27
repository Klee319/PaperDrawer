package com.example.paperdrawers.infrastructure.hopper

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.cache.DrawerLocationRegistry
import com.example.paperdrawers.infrastructure.display.DisplayManager
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Hopper
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * 仕分けドロワーが隣接するホッパーからアイテムを吸い取る定期タスク。
 *
 * Why: InventoryMoveItemEvent ベースの横取り方式は、Paper のホッパー最適化と
 * 競合してアイテム消失を引き起こす。ホッパーインベントリを直接操作する
 * ポーリング方式の方が安全で確実。
 *
 * DrawerLocationRegistry を使用して仕分けドロワーの位置のみを走査するため、
 * 全チャンク走査と異なりサーバー負荷は最小限。
 *
 * 実行間隔: 1 tick（ホッパーの搬送速度（8tick）より高速にすることで取りこぼしを防ぐ）
 * 実行スレッド: メインスレッド
 */
class SortingDrawerPullTask(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val plugin: Plugin,
    private val logger: Logger
) : Runnable {

    companion object {
        private val ADJACENT_FACES = arrayOf(
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
        )
    }

    override fun run() {
        for (location in DrawerLocationRegistry.getSortingLocations()) {
            val world = location.world ?: continue
            if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) continue

            try {
                val drawer = repository.findByLocation(location) ?: continue
                if (drawer.type == DrawerType.VOID) continue

                tryPullFromAdjacentHoppers(drawer)
            } catch (e: Exception) {
                logger.fine("Failed to process sorting pull at $location: ${e.message}")
            }
        }
    }

    /**
     * 仕分けドロワーの隣接ホッパーからアイテムを1つ吸い取る。
     *
     * 1サイクルにつき1アイテムのみ移送し、サーバー負荷を抑える。
     * ホッパーインベントリを直接操作するため、
     * InventoryMoveItemEvent との競合がない。
     */
    private fun tryPullFromAdjacentHoppers(drawer: DrawerBlock) {
        val hasSpace = drawer.slots.any { slot ->
            (!slot.isEmpty() || slot.isLocked) && slot.itemCount < slot.maxCapacity
        }
        if (!hasSpace) return

        for (face in ADJACENT_FACES) {
            val adjacentBlock = drawer.location.block.getRelative(face)
            if (adjacentBlock.type != Material.HOPPER) continue

            // レッドストーン動力を受けたホッパーは無効化
            if (adjacentBlock.isBlockPowered || adjacentBlock.isBlockIndirectlyPowered) continue

            val hopper = adjacentBlock.state as? Hopper ?: continue

            if (tryPullFromInventory(drawer, hopper.inventory)) {
                return
            }
        }
    }

    /**
     * ホッパーインベントリからドロワーに受け入れ可能なアイテムを1つ吸い取る。
     *
     * 仕分けドロワーは「設定済みスロット」（ロック済み or アイテム入り）のみ
     * アイテムを受け入れる。これにより仕分け機能を維持する。
     *
     * @return アイテムを吸い取った場合 true
     */
    private fun tryPullFromInventory(drawer: DrawerBlock, inventory: Inventory): Boolean {
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (item.type.isAir) continue

            for (slot in drawer.slots) {
                val isConfiguredSlot = !slot.isEmpty() || slot.isLocked
                if (!isConfiguredSlot) continue

                val singleItem = item.clone().apply { amount = 1 }
                if (!slot.canAcceptItemStack(singleItem)) continue

                val (result, updatedDrawer) = drawer.insertItemStack(slot.index, singleItem)
                if (result.success) {
                    repository.save(updatedDrawer)

                    // ホッパーから1個減らす（直接操作、イベント不要）
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
