package com.example.paperdrawers.application.usecase

import com.example.paperdrawers.domain.repository.DrawerPersistenceException
import com.example.paperdrawers.domain.repository.DrawerRepository
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * アイテム挿入リクエスト。
 *
 * @property location ドロワーの位置
 * @property slotIndex 挿入先のスロットインデックス
 * @property material 挿入するアイテムのマテリアル
 * @property amount 挿入する数量
 * @property playerUuid 操作を行うプレイヤーのUUID
 */
data class InsertItemRequest(
    val location: Location,
    val slotIndex: Int,
    val material: Material,
    val amount: Int,
    val playerUuid: UUID
)

/**
 * アイテム挿入レスポンス。
 *
 * @property success 挿入が成功したかどうか（少なくとも1つ挿入された場合true）
 * @property insertedCount 実際に挿入されたアイテム数
 * @property remainingCount 挿入できなかったアイテム数
 * @property errorMessage エラーメッセージ（エラー時のみ）
 */
data class InsertItemResponse(
    val success: Boolean,
    val insertedCount: Int,
    val remainingCount: Int,
    val errorMessage: String? = null
) {
    companion object {
        /**
         * 成功レスポンスを作成する。
         */
        fun success(insertedCount: Int, remainingCount: Int): InsertItemResponse {
            return InsertItemResponse(
                success = true,
                insertedCount = insertedCount,
                remainingCount = remainingCount
            )
        }

        /**
         * 失敗レスポンスを作成する。
         */
        fun failure(requestedAmount: Int, errorMessage: String): InsertItemResponse {
            return InsertItemResponse(
                success = false,
                insertedCount = 0,
                remainingCount = requestedAmount,
                errorMessage = errorMessage
            )
        }
    }
}

/**
 * ドロワーにアイテムを挿入するユースケース。
 *
 * プレイヤーがドロワーのスロットにアイテムを追加する際の
 * ビジネスロジックを処理する。
 *
 * @property repository ドロワーリポジトリ
 * @property logger ロガー
 */
class InsertItemUseCase(
    private val repository: DrawerRepository,
    private val logger: Logger = Logger.getLogger(InsertItemUseCase::class.java.name)
) {
    /**
     * アイテム挿入を実行する。
     *
     * @param request 挿入リクエスト
     * @return 挿入結果のレスポンス
     */
    fun execute(request: InsertItemRequest): InsertItemResponse {
        logger.fine {
            "InsertItem requested: location=${request.location.toSimpleString()}, " +
            "slot=${request.slotIndex}, material=${request.material}, " +
            "amount=${request.amount}, player=${request.playerUuid}"
        }

        // バリデーション
        if (request.amount <= 0) {
            return InsertItemResponse.failure(
                requestedAmount = 0,
                errorMessage = "Amount must be positive"
            )
        }

        // ドロワーの取得
        val drawer = try {
            repository.findByLocation(request.location)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to retrieve drawer at ${request.location.toSimpleString()}", e)
            return InsertItemResponse.failure(
                requestedAmount = request.amount,
                errorMessage = "Failed to retrieve drawer: ${e.message}"
            )
        }

        if (drawer == null) {
            logger.fine { "No drawer found at ${request.location.toSimpleString()}" }
            return InsertItemResponse.failure(
                requestedAmount = request.amount,
                errorMessage = "No drawer found at the specified location"
            )
        }

        // スロットインデックスの検証
        if (request.slotIndex < 0 || request.slotIndex >= drawer.slots.size) {
            return InsertItemResponse.failure(
                requestedAmount = request.amount,
                errorMessage = "Invalid slot index: ${request.slotIndex}. Drawer has ${drawer.slots.size} slots"
            )
        }

        // アイテムの挿入を試行
        val (insertResult, updatedDrawer) = try {
            drawer.insertItem(request.slotIndex, request.material, request.amount)
        } catch (e: IllegalArgumentException) {
            logger.fine { "Insert validation failed: ${e.message}" }
            return InsertItemResponse.failure(
                requestedAmount = request.amount,
                errorMessage = e.message ?: "Insert validation failed"
            )
        }

        // 挿入が完全に失敗した場合
        if (!insertResult.success) {
            logger.fine {
                "Insert failed: material=${request.material} cannot be accepted " +
                "by slot ${request.slotIndex} at ${request.location.toSimpleString()}"
            }
            return InsertItemResponse.failure(
                requestedAmount = request.amount,
                errorMessage = "Slot cannot accept material ${request.material}"
            )
        }

        // ドロワーの更新を永続化
        try {
            repository.save(updatedDrawer)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to save drawer after insert", e)
            return InsertItemResponse.failure(
                requestedAmount = request.amount,
                errorMessage = "Failed to save drawer: ${e.message}"
            )
        }

        logger.info {
            "Item inserted: ${insertResult.insertedCount}x ${request.material} " +
            "to slot ${request.slotIndex} at ${request.location.toSimpleString()} " +
            "by player ${request.playerUuid}"
        }

        return InsertItemResponse.success(
            insertedCount = insertResult.insertedCount,
            remainingCount = insertResult.remainingCount
        )
    }

    /**
     * Locationをシンプルな文字列に変換する拡張関数。
     */
    private fun Location.toSimpleString(): String {
        return "${world?.name}:$blockX,$blockY,$blockZ"
    }
}
