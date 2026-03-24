package com.example.paperdrawers.domain.repository

import com.example.paperdrawers.domain.model.DrawerBlock
import org.bukkit.Chunk
import org.bukkit.Location

/**
 * ドロワーブロックの永続化を担当するリポジトリインターフェース。
 *
 * このインターフェースはドメイン層に属し、具体的な永続化実装から
 * ドメインロジックを分離するために使用される（依存性逆転の原則）。
 *
 * 実装クラスはインフラストラクチャ層に配置し、
 * ファイルベース、データベース、インメモリなど任意の永続化方式を選択可能。
 */
interface DrawerRepository {

    /**
     * ドロワーブロックを保存する。
     *
     * 同じ位置に既存のドロワーが存在する場合は上書きする。
     *
     * @param drawer 保存するドロワーブロック
     * @throws DrawerPersistenceException 永続化に失敗した場合
     */
    fun save(drawer: DrawerBlock)

    /**
     * 指定された位置のドロワーブロックを取得する。
     *
     * @param location 検索する位置
     * @return ドロワーブロック、存在しない場合はnull
     * @throws DrawerPersistenceException 取得に失敗した場合
     */
    fun findByLocation(location: Location): DrawerBlock?

    /**
     * 指定されたチャンク内のすべてのドロワーブロックを取得する。
     *
     * チャンクロード時のドロワー復元や、チャンクアンロード時の
     * メモリ解放処理に使用される。
     *
     * @param chunk 検索するチャンク
     * @return チャンク内のドロワーブロックのリスト（存在しない場合は空リスト）
     * @throws DrawerPersistenceException 取得に失敗した場合
     */
    fun findByChunk(chunk: Chunk): List<DrawerBlock>

    /**
     * 指定された位置のドロワーブロックを削除する。
     *
     * 指定位置にドロワーが存在しない場合は何もしない。
     *
     * @param location 削除するドロワーの位置
     * @throws DrawerPersistenceException 削除に失敗した場合
     */
    fun delete(location: Location)

    /**
     * 指定された位置にドロワーブロックが存在するかどうかを確認する。
     *
     * @param location 確認する位置
     * @return ドロワーが存在する場合true
     * @throws DrawerPersistenceException 確認に失敗した場合
     */
    fun exists(location: Location): Boolean
}

/**
 * ドロワーの永続化処理で発生する例外。
 *
 * @property message エラーメッセージ
 * @property cause 原因となった例外
 */
class DrawerPersistenceException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
