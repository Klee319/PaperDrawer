package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import java.util.logging.Logger

/**
 * ドロワーブロックを爆発およびピストンから保護するイベントリスナー。
 *
 * 爆発によるアイテム消失を防ぐため、爆発の影響範囲からドロワーブロックを除外する。
 * また、ピストンによるドロワーの移動を禁止し、PDCデータの破損を防止する。
 *
 * Why: ドロワーはPDCにアイテムデータを格納しているため、爆発やピストンによる
 * 破壊・移動が発生すると、通常の破壊処理（DrawerBreakListener）を経由せず
 * ブロックが消滅し、格納アイテムが失われる。シュルカーボックスの爆発耐性と
 * 同様の保護を提供する。
 *
 * @property repository ドロワーデータの永続化を担当するリポジトリ
 * @property logger ログ出力用のロガー
 */
class DrawerProtectionListener(
    private val repository: DrawerRepository,
    private val logger: Logger
) : Listener {

    /**
     * ブロック起因の爆発イベントを処理する（例: TNT、リスポーンアンカー）。
     *
     * 爆発の影響範囲にドロワーブロックが含まれている場合、
     * そのブロックを爆発リストから除外して破壊を防止する。
     *
     * Why: blockList().removeIf() はBukkit APIの標準的な方法で、
     * 爆発リストからブロックを除外することで破壊を防ぐ。
     * イベント自体はキャンセルしないため、他のブロックは通常通り爆発する。
     *
     * @param event ブロック爆発イベント
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val removedCount = event.blockList().removeIf { block ->
            DrawerItemFactory.isDrawerMaterial(block.type) && repository.findByLocation(block.location) != null
        }

        if (removedCount) {
            logger.fine("Protected drawer blocks from block explosion")
        }
    }

    /**
     * エンティティ起因の爆発イベントを処理する（例: クリーパー、ウィザー、火の玉）。
     *
     * 爆発の影響範囲にドロワーブロックが含まれている場合、
     * そのブロックを爆発リストから除外して破壊を防止する。
     *
     * Why: EntityExplodeEvent はクリーパーやTNTエンティティなど、
     * エンティティが原因の爆発を処理する。BlockExplodeEvent とは別イベントのため、
     * 両方をハンドリングする必要がある。
     *
     * @param event エンティティ爆発イベント
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val removedCount = event.blockList().removeIf { block ->
            DrawerItemFactory.isDrawerMaterial(block.type) && repository.findByLocation(block.location) != null
        }

        if (removedCount) {
            val entityType = event.entity.type
            logger.fine("Protected drawer blocks from entity explosion (${entityType})")
        }
    }

    /**
     * ピストン伸長イベントを処理する。
     *
     * ピストンが押し出すブロックの中にドロワーが含まれている場合、
     * イベントをキャンセルしてドロワーの移動を防止する。
     *
     * Why: ピストンによるブロック移動はPDCデータを保持しないため、
     * ドロワーが移動するとアイテムデータが失われる。
     * イベントをキャンセルすることで、ピストン自体の動作を無効化する。
     *
     * @param event ピストン伸長イベント
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val hasDrawer = event.blocks.any { block ->
            repository.findByLocation(block.location) != null
        }

        if (hasDrawer) {
            event.isCancelled = true
            logger.fine("Prevented drawer movement by piston extend")
        }
    }

    /**
     * ピストン収縮イベントを処理する。
     *
     * ピストンが引き戻すブロックの中にドロワーが含まれている場合、
     * イベントをキャンセルしてドロワーの移動を防止する。
     *
     * Why: 粘着ピストンの収縮時にもブロック移動が発生するため、
     * 伸長イベントと同様に保護する必要がある。
     *
     * @param event ピストン収縮イベント
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val hasDrawer = event.blocks.any { block ->
            repository.findByLocation(block.location) != null
        }

        if (hasDrawer) {
            event.isCancelled = true
            logger.fine("Prevented drawer movement by piston retract")
        }
    }
}
