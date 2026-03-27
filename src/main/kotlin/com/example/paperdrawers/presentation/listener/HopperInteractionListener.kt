package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.repository.DrawerRepository
import org.bukkit.block.Barrel
import org.bukkit.block.Jukebox
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import java.util.logging.Logger

/**
 * ホッパーとドロワー間のバニラインベントリ操作をブロックするリスナー。
 *
 * Why: ドロワーは PDC にアイテムを保存しており、バレル/ジュークボックスのバニラインベントリは
 * 常に空である。バニラのホッパー動作でアイテムがバニラインベントリに流入/流出すると
 * アイテムの消失や増殖が発生するため、全てのバニラ転送をキャンセルする。
 *
 * 実際のホッパー⇔ドロワー間のアイテム転送は HopperPullTask が
 * ホッパーインベントリを直接操作して行う（Paper のホッパー最適化と競合しない）。
 */
class HopperInteractionListener(
    private val repository: DrawerRepository,
    private val logger: Logger
) : Listener {

    /**
     * ドロワーが関与するバニラのインベントリ移動を全てキャンセルする。
     *
     * - destination がドロワー → ホッパーからバニラインベントリへの流入を防止
     * - source がドロワー → ホッパーによるバニラインベントリからの吸出しを防止
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        // source がドロワーの場合キャンセル（吸出し防止）
        val sourceLocation = when (event.source.type) {
            InventoryType.BARREL -> (event.source.holder as? Barrel)?.location
            InventoryType.JUKEBOX -> (event.source.holder as? Jukebox)?.location
            else -> null
        }
        if (sourceLocation != null && repository.findByLocation(sourceLocation) != null) {
            event.isCancelled = true
            return
        }

        // destination がドロワーの場合キャンセル（流入防止）
        val destLocation = when (event.destination.type) {
            InventoryType.BARREL -> (event.destination.holder as? Barrel)?.location
            InventoryType.JUKEBOX -> (event.destination.holder as? Jukebox)?.location
            else -> null
        }
        if (destLocation != null && repository.findByLocation(destLocation) != null) {
            event.isCancelled = true
        }
    }
}
