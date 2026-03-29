package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.hopper.SortingDrawerPullTask
import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import org.bukkit.block.Barrel
import org.bukkit.block.BlockFace
import org.bukkit.block.Hopper
import org.bukkit.block.Jukebox
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * ホッパーとドロワー間のインタラクションを処理するリスナー。
 *
 * 2つの役割:
 * 1. ドロワーが関与するバニラ転送をキャンセル（PDC ストレージ保護）
 * 2. ホッパーにアイテムが到着した際に隣接する仕分けドロワーの吸い取りをトリガー
 *
 * Why: 仕分けドロワーの吸い取りをイベント駆動にすることで、
 * ホッパーの流通速度に追従して即座にアイテムを吸収できる。
 * ポーリング（SortingDrawerPullTask）はフォールバックとして併用。
 */
class HopperInteractionListener(
    private val repository: DrawerRepository,
    private val sortingPullTask: SortingDrawerPullTask,
    private val plugin: Plugin,
    private val logger: Logger
) : Listener {

    companion object {
        private val ADJACENT_FACES = arrayOf(
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
        )
    }

    /**
     * ホッパーのアイテム移動をインターセプトする。
     *
     * 1. ドロワーが source/destination の場合 → バニラ転送をキャンセル
     * 2. ホッパーにアイテムが到着した場合 → 隣接仕分けドロワーの即時吸い取り
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        // === ドロワーのバニラインベントリ保護 ===
        // source がドロワー → 吸出し防止
        val sourceLocation = when (event.source.type) {
            InventoryType.BARREL -> (event.source.holder as? Barrel)?.location
            InventoryType.JUKEBOX -> (event.source.holder as? Jukebox)?.location
            else -> null
        }
        if (sourceLocation != null && repository.findByLocation(sourceLocation) != null) {
            event.isCancelled = true
            return
        }

        // destination がドロワー → 流入防止
        val destLocation = when (event.destination.type) {
            InventoryType.BARREL -> (event.destination.holder as? Barrel)?.location
            InventoryType.JUKEBOX -> (event.destination.holder as? Jukebox)?.location
            else -> null
        }
        if (destLocation != null && repository.findByLocation(destLocation) != null) {
            event.isCancelled = true
            return
        }

        // === 仕分けドロワーのイベント駆動吸い取り ===
        // destination がホッパーの場合、そのホッパーに隣接する仕分けドロワーを即時処理
        // Why: InventoryMoveItemEvent は転送前に発火するため、次tick で処理する
        // バニラ転送はキャンセルしない（通常のホッパー動作に影響しない）
        if (event.destination.type == InventoryType.HOPPER) {
            val destHopper = event.destination.holder as? Hopper ?: return
            triggerAdjacentSortingDrawers(destHopper)
        }
    }

    /**
     * ホッパーに隣接する仕分けドロワーの吸い取りを次tick でトリガーする。
     *
     * Why: InventoryMoveItemEvent は転送前に発火するため、
     * 次tick（転送完了後）に処理することで確実にアイテムを吸収できる。
     * キャンセルや手動操作は行わないため Paper の最適化と競合しない。
     */
    private fun triggerAdjacentSortingDrawers(hopper: Hopper) {
        val hopperBlock = hopper.block

        for (face in ADJACENT_FACES) {
            val adjacent = hopperBlock.getRelative(face)
            if (!DrawerItemFactory.isDrawerMaterial(adjacent.type)) continue

            val drawer = repository.findByLocation(adjacent.location) ?: continue
            if (!drawer.isSorting) continue

            // 次tick で仕分けドロワーの全数吸い取りを実行
            val drawerLocation = adjacent.location.clone()
            plugin.server.scheduler.runTask(plugin, Runnable {
                sortingPullTask.processDrawerAt(drawerLocation)
            })
            // 複数の仕分けドロワーが隣接している可能性があるため break しない
        }
    }
}
