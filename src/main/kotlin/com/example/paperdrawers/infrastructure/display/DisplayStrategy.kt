package com.example.paperdrawers.infrastructure.display

import com.example.paperdrawers.domain.model.DrawerBlock
import org.bukkit.entity.Player
import java.util.UUID

/**
 * ドロワーの視覚的表示のための戦略インターフェース。
 *
 * Why: 将来的な拡張性のために Strategy パターンを採用。
 * 現在は ItemFrame を使用した実装が Java Edition と Bedrock Edition
 * の両方で動作する。
 *
 * @see ItemFrameStrategy ItemFrame を使用した実装（JE/BE 両対応）
 */
interface DisplayStrategy {

    /**
     * ドロワーの単一スロットに対する表示エンティティを作成する。
     *
     * スロットが空の場合は null を返し、エンティティは作成されない。
     * スロットにアイテムがある場合は、適切な位置にディスプレイエンティティを
     * 生成し、その UUID を返す。
     *
     * Why: スロット単位で管理することで、効率的な更新と削除が可能になる。
     * UUID を返すことで、後から特定のエンティティを更新・削除できる。
     *
     * @param drawer 表示対象のドロワーブロック
     * @param slotIndex 作成するスロットのインデックス（0から始まる）
     * @return 作成されたエンティティの UUID、スロットが空の場合は null
     * @throws IndexOutOfBoundsException スロットインデックスが範囲外の場合
     */
    fun createSlotDisplay(drawer: DrawerBlock, slotIndex: Int): UUID?

    /**
     * 既存の表示エンティティを更新する。
     *
     * スロットの内容が変更された場合（アイテム追加/削除など）に呼び出す。
     * エンティティが存在しない場合は何も行わない。
     *
     * Why: 全エンティティを再作成するよりも効率的。
     * アイテムのスタック数変更時など、頻繁に呼び出される可能性がある。
     *
     * @param drawer 更新対象のドロワーブロック
     * @param slotIndex 更新するスロットのインデックス
     * @param entityId 更新対象のエンティティ UUID
     */
    fun updateSlotDisplay(drawer: DrawerBlock, slotIndex: Int, entityId: UUID)

    /**
     * 表示エンティティを削除する。
     *
     * 指定された UUID のエンティティをワールドから削除する。
     * エンティティが既に存在しない場合は何も行わない。
     *
     * Why: ドロワーの破壊時やスロットが空になった時にエンティティを
     * クリーンアップする必要がある。
     *
     * @param entityId 削除対象のエンティティ UUID
     */
    fun removeDisplay(entityId: UUID)

    /**
     * この戦略が指定されたプレイヤーをサポートするかどうかを判定する。
     *
     * Why: Java Edition と Bedrock Edition で異なる戦略を使用するため、
     * プレイヤーのプラットフォームに応じて適切な戦略を選択する必要がある。
     *
     * @param player 判定対象のプレイヤー
     * @return このプレイヤーに対してこの戦略が使用可能な場合 true
     */
    fun supportsPlayer(player: Player): Boolean

    /**
     * ログ出力用の戦略名を取得する。
     *
     * Why: デバッグやログ出力時に、どの戦略が使用されているかを
     * 識別するために使用する。
     *
     * @return 戦略の識別名（例: "ItemFrameStrategy"）
     */
    fun getStrategyName(): String
}
