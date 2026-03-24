package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.display.DisplayManager
import org.bukkit.block.Barrel
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import java.util.logging.Logger

/**
 * ホッパーによるドロワーへのアイテム搬入を処理するイベントリスナー。
 *
 * ホッパーがドロワー（バレル）にアイテムをプッシュする場合に、
 * バニラのインベントリ操作をキャンセルし、ドロワーの挿入ロジックを使用する。
 *
 * Why: ドロワーはバレルのインベントリではなくPDCにアイテムを保存するため、
 * バニラのホッパー動作ではアイテムがPDCに反映されない。
 * イベントをインターセプトしてドロワーの挿入ロジックに委譲する必要がある。
 */
class HopperInteractionListener(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val logger: Logger
) : Listener {

    /**
     * ホッパーからドロワーへのアイテム移動を処理する。
     *
     * destination がドロワーバレルの場合:
     * - バニラの移動をキャンセル
     * - ドロワーの insertItemStack でアイテムを挿入
     * - 成功した場合、ソース（ホッパー）からアイテムを減らす
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        // Why: destination が BARREL 以外の場合はドロワーではないため早期リターン
        val destination = event.destination
        if (destination.type != InventoryType.BARREL) return

        val destinationHolder = destination.holder
        if (destinationHolder !is Barrel) return

        val location = destinationHolder.location
        val drawer = repository.findByLocation(location) ?: return

        // Why: バニラの移動をキャンセルしないと、バレルのインベントリにアイテムが入ってしまう
        event.isCancelled = true

        // Why: ホッパーは1tickあたり1アイテムずつ移動するため、amount=1で挿入する
        val itemToInsert = event.item.clone().apply { amount = 1 }

        // Void drawer: consume the item (destroy it)
        if (drawer.type == DrawerType.VOID) {
            removeOneItemFromSource(event)
            return
        }

        // Why: 最初に受け入れ可能なスロットを探して挿入する
        for (slot in drawer.slots) {
            if (!slot.canAcceptItemStack(itemToInsert)) continue

            val (result, updatedDrawer) = drawer.insertItemStack(slot.index, itemToInsert)
            if (result.success) {
                // ドロワーを先に保存（保存失敗時のアイテム消失を防ぐ）
                repository.save(updatedDrawer)
                removeOneItemFromSource(event)
                displayManager.updateSlotDisplay(updatedDrawer, slot.index)
                return
            }
        }

        // Why: 受け入れ可能なスロットが見つからない場合、アイテムはホッパーに残る
    }

    /**
     * ソースインベントリ（ホッパー）からアイテムを1つ減らす。
     *
     * Why: event.isCancelled=true によりバニラの移動が無効化されるため、
     * 手動でソースからアイテムを減らす必要がある。
     */
    private fun removeOneItemFromSource(event: InventoryMoveItemEvent) {
        val source = event.source
        val movedItem = event.item

        for (i in 0 until source.size) {
            val slotItem = source.getItem(i) ?: continue
            if (slotItem.isSimilar(movedItem)) {
                if (slotItem.amount <= 1) {
                    source.setItem(i, null)
                } else {
                    val updatedItem = slotItem.clone().apply { amount = slotItem.amount - 1 }
                    source.setItem(i, updatedItem)
                }
                return
            }
        }
    }
}
