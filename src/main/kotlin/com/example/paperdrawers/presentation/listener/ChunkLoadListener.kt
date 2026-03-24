package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.repository.DrawerPersistenceException
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.display.DisplayManager
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.logging.Logger

/**
 * プレイヤーに対するチャンクのロード/アンロードイベントを処理するリスナー。
 *
 * Paper 1.21.84以降では、従来の ChunkLoadEvent / ChunkUnloadEvent は非推奨となり、
 * 代わりに PlayerChunkLoadEvent / PlayerChunkUnloadEvent を使用する必要がある。
 * これらのイベントはパケットレベルで発火し、プレイヤーがチャンクを受信した時点で通知される。
 *
 * 主な役割:
 * - チャンクがプレイヤーに送信された際に、そのチャンク内のドロワーの表示エンティティを
 *   プレイヤーに表示する
 * - チャンクがプレイヤーからアンロードされた際に、必要に応じて表示を非表示にする
 *
 * 注意: このイベントはクライアント/パケット関連の処理に使用することを意図しており、
 * サーバーサイドの状態変更には使用すべきではない。
 *
 * @property repository ドロワーデータの永続化を担当するリポジトリ
 * @property displayManager ドロワーの視覚的表示を管理するマネージャー
 * @property logger ログ出力用のロガー
 *
 * @see io.papermc.paper.event.packet.PlayerChunkLoadEvent
 * @see io.papermc.paper.event.packet.PlayerChunkUnloadEvent
 */
class ChunkLoadListener(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val logger: Logger
) : Listener {

    /**
     * プレイヤーがチャンクを受信した際に呼び出されるイベントハンドラー。
     *
     * 処理フロー:
     * 1. リポジトリからチャンク内のドロワーを検索
     * 2. ドロワーが存在しない場合は早期リターン（パフォーマンス最適化）
     * 3. 各ドロワーに対して表示が存在するか確認し、なければ作成
     * 4. プレイヤーに対して表示を送信
     *
     * @param event プレイヤーチャンクロードイベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerChunkLoad(event: PlayerChunkLoadEvent) {
        val chunk = event.chunk
        val player = event.player

        // Step 1: Find all drawers in the chunk
        val drawers = findDrawersInChunk(chunk)

        // Step 2: Early return if no drawers exist (performance optimization)
        if (drawers.isEmpty()) {
            return
        }

        logger.fine(
            "Player ${player.name} received chunk [${chunk.x}, ${chunk.z}] " +
            "containing ${drawers.size} drawer(s)"
        )

        // Step 3 & 4: Process each drawer
        drawers.forEach { drawer ->
            processDrawerForPlayer(drawer, player)
        }
    }

    /**
     * プレイヤーからチャンクがアンロードされた際に呼び出されるイベントハンドラー。
     *
     * 注意: このメソッドでは表示エンティティを削除しない。
     * 理由:
     * - 他のプレイヤーがまだそのチャンクを見ている可能性がある
     * - 表示エンティティはサーバーサイドで管理されており、
     *   プレイヤーへの表示/非表示はクライアントサイドで処理される
     *
     * 現在の実装では、ログ記録のみを行い、必要に応じて
     * プレイヤーからの表示非表示処理を行う。
     *
     * @param event プレイヤーチャンクアンロードイベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerChunkUnload(event: PlayerChunkUnloadEvent) {
        val chunk = event.chunk
        val player = event.player

        // Find drawers in chunk to determine if logging is needed
        val drawers = findDrawersInChunk(chunk)

        if (drawers.isEmpty()) {
            return
        }

        logger.fine(
            "Chunk [${chunk.x}, ${chunk.z}] unloaded from player ${player.name}, " +
            "containing ${drawers.size} drawer(s)"
        )

        // Optional: Hide displays from this specific player
        // Note: We don't remove the display entities - they may be visible to other players
        drawers.forEach { drawer ->
            hideDrawerFromPlayer(drawer, player)
        }
    }

    /**
     * 指定されたチャンク内のすべてのドロワーを検索する。
     *
     * リポジトリへのアクセス中に例外が発生した場合は、
     * エラーをログに記録し、空のリストを返す。
     *
     * @param chunk 検索対象のチャンク
     * @return チャンク内のドロワーのリスト（エラー時は空リスト）
     */
    private fun findDrawersInChunk(chunk: Chunk): List<DrawerBlock> {
        return try {
            repository.findByChunk(chunk)
        } catch (e: DrawerPersistenceException) {
            logger.warning(
                "Failed to find drawers in chunk [${chunk.x}, ${chunk.z}]: ${e.message}"
            )
            emptyList()
        } catch (e: Exception) {
            logger.severe(
                "Unexpected error while finding drawers in chunk [${chunk.x}, ${chunk.z}]: ${e.message}"
            )
            emptyList()
        }
    }

    /**
     * 指定されたドロワーの表示をプレイヤーに送信する。
     *
     * 表示が存在しない場合は先に作成してから表示を送信する。
     * エラーが発生した場合はログに記録し、処理を続行する。
     *
     * @param drawer 表示するドロワー
     * @param player 表示先のプレイヤー
     */
    private fun processDrawerForPlayer(drawer: DrawerBlock, player: Player) {
        try {
            // Ensure display exists (create if not)
            if (!displayManager.hasDisplay(drawer)) {
                logger.fine(
                    "Creating display for drawer ${drawer.id} at ${drawer.getLocationKey()}"
                )
                displayManager.createDisplay(drawer)
            }

            // Show display to this specific player
            displayManager.showDisplayToPlayer(drawer, player)
            logger.fine(
                "Showed drawer ${drawer.id} display to player ${player.name}"
            )
        } catch (e: Exception) {
            logger.warning(
                "Failed to process drawer ${drawer.id} for player ${player.name}: ${e.message}"
            )
        }
    }

    /**
     * 指定されたドロワーの表示をプレイヤーから非表示にする。
     *
     * 注意: 表示エンティティ自体は削除せず、
     * プレイヤーのクライアントからのみ非表示にする。
     *
     * @param drawer 非表示にするドロワー
     * @param player 非表示にするプレイヤー
     */
    private fun hideDrawerFromPlayer(drawer: DrawerBlock, player: Player) {
        try {
            displayManager.hideDisplayFromPlayer(drawer, player)
            logger.fine(
                "Hid drawer ${drawer.id} display from player ${player.name}"
            )
        } catch (e: Exception) {
            logger.warning(
                "Failed to hide drawer ${drawer.id} from player ${player.name}: ${e.message}"
            )
        }
    }
}
