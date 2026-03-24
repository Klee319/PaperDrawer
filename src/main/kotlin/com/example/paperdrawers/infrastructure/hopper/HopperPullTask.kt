package com.example.paperdrawers.infrastructure.hopper

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.display.DisplayManager
import org.bukkit.Material
import org.bukkit.block.Hopper
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * ホッパーによるドロワーからのアイテム吸出しを定期的に処理するタスク。
 *
 * Why: バニラのホッパーは空のバレルインベントリからアイテムを吸い出せないため、
 * 定期タスクでドロワーの下にあるホッパーにアイテムを移送する必要がある。
 * ドロワーのアイテムはPDCに保存されており、バレルのインベントリは常に空であるため、
 * バニラのホッパー吸出し機構は機能しない。
 *
 * 実行間隔: 8 tick（バニラホッパーと同じ速度）
 * 実行スレッド: メインスレッド（Bukkit APIへのアクセスが必要なため）
 */
class HopperPullTask(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val plugin: Plugin,
    private val logger: Logger
) : Runnable {

    override fun run() {
        for (world in plugin.server.worlds) {
            for (chunk in world.loadedChunks) {
                try {
                    val drawers = repository.findByChunk(chunk)
                    for (drawer in drawers) {
                        tryPullToHopper(drawer)
                    }
                } catch (e: Exception) {
                    // Why: 個別のチャンクの失敗でタスク全体がキャンセルされることを防ぐ
                    logger.fine("Failed to process hopper pull for chunk [${chunk.x}, ${chunk.z}]: ${e.message}")
                }
            }
        }
    }

    /**
     * ドロワーの下にホッパーがあれば、アイテムを1つ移送する。
     *
     * Why: バニラのホッパーは上のブロックから1個ずつアイテムを吸い出す動作を模倣する。
     * レッドストーン信号で動力を受けているホッパーは無効化されているため、
     * バニラと同じ動作を再現する。
     */
    private fun tryPullToHopper(drawer: DrawerBlock) {
        // Void drawers have nothing to extract
        if (drawer.type == DrawerType.VOID) return
        if (drawer.isEmpty()) return

        val belowBlock = drawer.location.clone().subtract(0.0, 1.0, 0.0).block

        if (belowBlock.type != Material.HOPPER) return

        // Why: レッドストーン動力を受けたホッパーはバニラと同様に無効化する
        if (belowBlock.isBlockPowered || belowBlock.isBlockIndirectlyPowered) return

        val hopper = belowBlock.state as? Hopper ?: return
        val hopperInventory = hopper.inventory

        // Why: 最初の非空スロットからアイテムを取り出す（ラウンドロビンではない）
        val slot = drawer.slots.firstOrNull { !it.isEmpty() } ?: return

        val testItem = slot.createItemStack(1) ?: return

        // Why: ホッパーに空きがあるか確認してからアイテムを取り出す
        val hasFreeSpace = hopperInventory.contents.any { content ->
            content == null || (content.isSimilar(testItem) && content.amount < content.maxStackSize)
        }
        if (!hasFreeSpace) return

        val (extractedItem, updatedDrawer) = drawer.extractItemStack(slot.index, 1)
        if (extractedItem == null) return

        val leftover = hopperInventory.addItem(extractedItem)
        if (leftover.isNotEmpty()) {
            // Why: 空き確認後にアイテムが入らなかった場合、アイテムロスを防ぐためドロワーに戻す
            val leftoverItem = leftover.values.first()
            val (reInsertResult, reInsertedDrawer) = updatedDrawer.insertItemStack(slot.index, leftoverItem)
            if (!reInsertResult.success) {
                // Why: 再挿入が失敗した場合（競合等）、元の状態を保存してログに記録
                logger.warning("[ITEM LOSS] Failed to re-insert ${leftoverItem.type} x${leftoverItem.amount} into drawer at ${drawer.getLocationKey()}")
                repository.save(drawer) // 元の（抽出前の）状態を復元
            } else {
                repository.save(reInsertedDrawer)
            }
            return
        }

        repository.save(updatedDrawer)
        displayManager.updateSlotDisplay(updatedDrawer, slot.index)
    }
}
