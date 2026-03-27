package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.cache.DrawerLocationRegistry
import com.example.paperdrawers.infrastructure.debug.MetricsCollector
import com.example.paperdrawers.infrastructure.display.DisplayManager
import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import com.example.paperdrawers.infrastructure.persistence.DrawerDataKeys
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ドロワーブロックの破壊を処理するイベントリスナー。
 *
 * ドロワーが破壊された際に、格納されているアイテムを保持したドロワーアイテムのドロップ、
 * ディスプレイエンティティの削除、リポジトリからのデータ削除を行う。
 * シュルカーボックスと同様に、ドロワー内のアイテムはドロップされたアイテム内に保持される。
 *
 * @property repository ドロワーデータの永続化を担当するリポジトリ
 * @property displayManager ドロワーの視覚的表示を管理するマネージャー
 * @property logger ログ出力用のロガー
 */
class DrawerBreakListener(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val logger: Logger
) : Listener {

    /**
     * ブロック破壊イベントを処理する。
     *
     * 以下の処理を実行:
     * 1. 破壊されたブロックがドロワーかを確認
     * 2. ドロワーアイテムを事前作成（例外時にはイベントをキャンセルしアイテムロスを防止）
     * 3. 通常のブロックドロップを防止
     * 4. 格納アイテムを保持したドロワーアイテムをドロップ（クリエイティブモード以外）
     * 5. ディスプレイエンティティを削除（ドロップ成功後に実行）
     * 6. リポジトリからデータを削除（最後に実行）
     *
     * Why: ディスプレイ削除をドロップ成功後に移動することで、
     * ドロップ処理で例外が発生した場合にイベントをキャンセルしても
     * ディスプレイが既に消えている問題を防止する。
     *
     * @param event ブロック破壊イベント
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val player = event.player

        // Step 1: Check if broken block is a drawer
        val drawer = repository.findByLocation(block.location)
        if (drawer == null) {
            // Not a drawer, ignore
            return
        }

        logger.fine("Player ${player.name} is breaking drawer ${drawer.id} at ${drawer.getLocationKey()}")

        // Phase 1: アイテム作成とドロップ前の準備（失敗時はイベントキャンセルで安全）
        val drawerItem: org.bukkit.inventory.ItemStack?
        try {
            drawerItem = if (player.gameMode != GameMode.CREATIVE) {
                val item = DrawerItemFactory.createDrawerItemWithContents(drawer.type, drawer.slots, drawer.isSorting)

                // H2: Add unique UUID to prevent stacking of drawers with contents or locked slots
                if (drawer.hasItems() || drawer.slots.any { it.isLocked }) {
                    item.editMeta { meta ->
                        val pdc = meta.persistentDataContainer
                        pdc.set(DrawerDataKeys.DRAWER_ID, PersistentDataType.STRING, UUID.randomUUID().toString())
                    }
                }

                item
            } else {
                null
            }

            event.isDropItems = false

            // ドロップ実行（不可逆操作）
            if (drawerItem != null) {
                val dropLocation = drawer.location.clone().add(0.5, 0.5, 0.5)
                val world = drawer.location.world
                    ?: throw IllegalStateException("Cannot drop drawer item: world is null for drawer ${drawer.id}")
                world.dropItemNaturally(dropLocation, drawerItem)
                logger.fine("Dropped drawer item of type ${drawer.type} with contents preserved")
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to prepare/drop drawer item: ${e.message}", e)
            // ドロップ前の失敗 → イベントキャンセルで安全にロールバック
            event.isCancelled = true
            return
        }

        // Phase 2: クリーンアップ（ドロップ成功後なのでイベントキャンセルしてはいけない）
        // Why: ドロップ済みの状態でイベントキャンセルするとブロック残存+ドロップアイテムで増殖する
        try {
            displayManager.removeDisplay(drawer)
            logger.fine("Display removed for drawer: ${drawer.id}")
        } catch (e: Exception) {
            // ディスプレイ削除失敗は致命的ではない（チャンクロード時に再生成される）
            logger.log(Level.WARNING, "Failed to remove display for drawer ${drawer.id}: ${e.message}", e)
        }

        try {
            repository.delete(block.location)
            DrawerLocationRegistry.unregister(block.location)
            logger.fine("Drawer deleted from repository: ${drawer.id}")
        } catch (e: Exception) {
            // リポジトリ削除失敗は孤立データが残るが、増殖よりは安全
            logger.log(Level.SEVERE, "Failed to delete drawer data for ${drawer.id}: ${e.message}", e)
        }

        MetricsCollector.incrementDrawersDestroyed()
        logger.fine("Drawer break completed successfully: ${drawer.id}")
    }
}
