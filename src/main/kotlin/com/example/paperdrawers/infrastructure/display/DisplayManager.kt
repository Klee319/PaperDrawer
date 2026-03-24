package com.example.paperdrawers.infrastructure.display

import com.example.paperdrawers.domain.model.DrawerBlock
import org.bukkit.entity.Player

/**
 * ドロワーの視覚的表示を管理するインターフェース。
 *
 * Why: ドロワーの視覚的表現（アイテムディスプレイ、テキストディスプレイなど）を
 * ドメインロジックから分離する。これにより、異なる表示方式への切り替えや
 * テスト時のモック化が容易になる。
 *
 * 実装クラスはアイテムディスプレイエンティティやパーティクルなどを使用して
 * ドロワーの内容を視覚的に表現する。
 */
interface DisplayManager {

    /**
     * 指定されたドロワーの表示を作成する。
     *
     * ドロワーの各スロットに対応するアイテムディスプレイを生成し、
     * 適切な位置に配置する。
     *
     * @param drawer 表示を作成するドロワー
     */
    fun createDisplay(drawer: DrawerBlock)

    /**
     * 指定されたドロワーの表示を更新する。
     *
     * スロットの内容が変更された場合に呼び出し、
     * 表示されているアイテムやカウントを更新する。
     *
     * @param drawer 更新するドロワー
     */
    fun updateDisplay(drawer: DrawerBlock)

    /**
     * 指定されたドロワーの特定スロットの表示を更新する。
     *
     * Why: 全スロットを更新するよりも効率的。
     * 単一スロットの変更時に使用する。
     *
     * @param drawer 更新するドロワー
     * @param slotIndex 更新するスロットのインデックス
     */
    fun updateSlotDisplay(drawer: DrawerBlock, slotIndex: Int)

    /**
     * 指定されたドロワーの表示を削除する。
     *
     * ドロワーが破壊された場合や、チャンクがアンロードされた場合に呼び出す。
     *
     * @param drawer 表示を削除するドロワー
     */
    fun removeDisplay(drawer: DrawerBlock)

    /**
     * 特定のプレイヤーに対してドロワーの表示を表示する。
     *
     * Why: プレイヤーがドロワーの近くに来た時に表示を送信するために使用。
     * パケットベースの表示実装で使用される。
     *
     * @param drawer 表示するドロワー
     * @param player 表示先のプレイヤー
     */
    fun showDisplayToPlayer(drawer: DrawerBlock, player: Player)

    /**
     * 特定のプレイヤーに対してドロワーの表示を非表示にする。
     *
     * Why: プレイヤーがドロワーから離れた時に表示を削除するために使用。
     *
     * @param drawer 非表示にするドロワー
     * @param player 非表示にするプレイヤー
     */
    fun hideDisplayFromPlayer(drawer: DrawerBlock, player: Player)

    /**
     * すべての表示をクリーンアップする。
     *
     * プラグインの無効化時に呼び出し、すべてのディスプレイエンティティを削除する。
     */
    fun cleanup()

    /**
     * 指定されたドロワーの表示が存在するかどうかを確認する。
     *
     * @param drawer 確認するドロワー
     * @return 表示が存在する場合 true
     */
    fun hasDisplay(drawer: DrawerBlock): Boolean
}

/**
 * DisplayManager のスタブ実装。
 *
 * Why: 本格的な DisplayManager 実装が完成するまでの一時的な実装。
 * すべてのメソッドはログ出力のみを行い、実際の表示処理は行わない。
 *
 * Note: この実装はプレースホルダーであり、将来的に ItemDisplayDrawerDisplay などの
 * 具体的な実装に置き換える必要がある。
 *
 * @property logger ログ出力用のロガー
 */
class StubDisplayManager(
    private val logger: java.util.logging.Logger
) : DisplayManager {

    override fun createDisplay(drawer: DrawerBlock) {
        logger.fine("StubDisplayManager: createDisplay called for drawer ${drawer.id}")
    }

    override fun updateDisplay(drawer: DrawerBlock) {
        logger.fine("StubDisplayManager: updateDisplay called for drawer ${drawer.id}")
    }

    override fun updateSlotDisplay(drawer: DrawerBlock, slotIndex: Int) {
        logger.fine("StubDisplayManager: updateSlotDisplay called for drawer ${drawer.id}, slot $slotIndex")
    }

    override fun removeDisplay(drawer: DrawerBlock) {
        logger.fine("StubDisplayManager: removeDisplay called for drawer ${drawer.id}")
    }

    override fun showDisplayToPlayer(drawer: DrawerBlock, player: Player) {
        logger.fine("StubDisplayManager: showDisplayToPlayer called for drawer ${drawer.id}, player ${player.name}")
    }

    override fun hideDisplayFromPlayer(drawer: DrawerBlock, player: Player) {
        logger.fine("StubDisplayManager: hideDisplayFromPlayer called for drawer ${drawer.id}, player ${player.name}")
    }

    override fun cleanup() {
        logger.info("StubDisplayManager: cleanup called")
    }

    override fun hasDisplay(drawer: DrawerBlock): Boolean {
        return false
    }
}
