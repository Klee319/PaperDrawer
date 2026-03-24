package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.infrastructure.display.DrawerDisplayManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.logging.Logger

/**
 * プレイヤー参加イベントを処理するリスナー。
 *
 * Bedrock Edition プレイヤーが参加した際に、既存の ArmorStand 表示を
 * そのプレイヤーに表示する処理を行う。
 *
 * @property displayManager ドロワーの視覚的表示を管理するマネージャー
 * @property logger ログ出力用のロガー
 */
class PlayerJoinListener(
    private val displayManager: DrawerDisplayManager,
    private val logger: Logger
) : Listener {

    /**
     * プレイヤー参加イベントを処理する。
     *
     * BE プレイヤーには既存の ArmorStand を表示する。
     * JE プレイヤーには特別な処理は不要（ArmorStand は非表示のまま）。
     *
     * @param event プレイヤー参加イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // 少し遅延させてから処理（プレイヤーのワールド読み込み完了を待つ）
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            org.bukkit.Bukkit.getPluginManager().getPlugin("PaperDrawers")!!,
            Runnable {
                displayManager.handlePlayerJoin(player)
                logger.fine("Processed display visibility for player ${player.name}")
            },
            20L // 1秒後
        )
    }
}
