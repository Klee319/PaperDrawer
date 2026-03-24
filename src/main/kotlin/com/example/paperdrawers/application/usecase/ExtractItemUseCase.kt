package com.example.paperdrawers.application.usecase

import com.example.paperdrawers.domain.repository.DrawerPersistenceException
import com.example.paperdrawers.domain.repository.DrawerRepository
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * アイテム取り出しリクエスト。
 *
 * @property location ドロワーの位置
 * @property slotIndex 取り出し元のスロットインデックス
 * @property amount 取り出す数量
 * @property playerUuid 操作を行うプレイヤーのUUID
 */
data class ExtractItemRequest(
    val location: Location,
    val slotIndex: Int,
    val amount: Int,
    val playerUuid: UUID
)

/**
 * アイテム取り出しレスポンス。
 *
 * @property success 取り出しが成功したかどうか（少なくとも1つ取り出された場合true）
 * @property extractedMaterial 取り出されたアイテムのマテリアル（成功時のみ）
 * @property extractedCount 実際に取り出されたアイテム数
 * @property errorMessage エラーメッセージ（エラー時のみ）
 */
data class ExtractItemResponse(
    val success: Boolean,
    val extractedMaterial: Material?,
    val extractedCount: Int,
    val errorMessage: String? = null
) {
    companion object {
        /**
         * 成功レスポンスを作成する。
         */
        fun success(material: Material, extractedCount: Int): ExtractItemResponse {
            return ExtractItemResponse(
                success = true,
                extractedMaterial = material,
                extractedCount = extractedCount
            )
        }

        /**
         * 空スロットからの取り出し結果（失敗ではない）。
         */
        fun empty(): ExtractItemResponse {
            return ExtractItemResponse(
                success = false,
                extractedMaterial = null,
                extractedCount = 0
            )
        }

        /**
         * 失敗レスポンスを作成する。
         */
        fun failure(errorMessage: String): ExtractItemResponse {
            return ExtractItemResponse(
                success = false,
                extractedMaterial = null,
                extractedCount = 0,
                errorMessage = errorMessage
            )
        }
    }
}

/**
 * ドロワーからアイテムを取り出すユースケース。
 *
 * プレイヤーがドロワーのスロットからアイテムを取り出す際の
 * ビジネスロジックを処理する。
 *
 * @property repository ドロワーリポジトリ
 * @property logger ロガー
 */
class ExtractItemUseCase(
    private val repository: DrawerRepository,
    private val logger: Logger = Logger.getLogger(ExtractItemUseCase::class.java.name)
) {
    /**
     * アイテム取り出しを実行する。
     *
     * @param request 取り出しリクエスト
     * @return 取り出し結果のレスポンス
     */
    fun execute(request: ExtractItemRequest): ExtractItemResponse {
        logger.fine {
            "ExtractItem requested: location=${request.location.toSimpleString()}, " +
            "slot=${request.slotIndex}, amount=${request.amount}, " +
            "player=${request.playerUuid}"
        }

        // バリデーション
        if (request.amount <= 0) {
            return ExtractItemResponse.failure("Amount must be positive")
        }

        // ドロワーの取得
        val drawer = try {
            repository.findByLocation(request.location)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to retrieve drawer at ${request.location.toSimpleString()}", e)
            return ExtractItemResponse.failure("Failed to retrieve drawer: ${e.message}")
        }

        if (drawer == null) {
            logger.fine { "No drawer found at ${request.location.toSimpleString()}" }
            return ExtractItemResponse.failure("No drawer found at the specified location")
        }

        // スロットインデックスの検証
        if (request.slotIndex < 0 || request.slotIndex >= drawer.slots.size) {
            return ExtractItemResponse.failure(
                "Invalid slot index: ${request.slotIndex}. Drawer has ${drawer.slots.size} slots"
            )
        }

        // アイテムの取り出しを試行
        val (extractedMaterial, extractedCount, updatedDrawer) = try {
            drawer.extractItem(request.slotIndex, request.amount)
        } catch (e: IllegalArgumentException) {
            logger.fine { "Extract validation failed: ${e.message}" }
            return ExtractItemResponse.failure(e.message ?: "Extract validation failed")
        }

        // スロットが空だった場合
        if (extractedMaterial == null || extractedCount == 0) {
            logger.fine {
                "Extract from empty slot: slot ${request.slotIndex} " +
                "at ${request.location.toSimpleString()}"
            }
            return ExtractItemResponse.empty()
        }

        // ドロワーの更新を永続化
        try {
            repository.save(updatedDrawer)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to save drawer after extract", e)
            return ExtractItemResponse.failure("Failed to save drawer: ${e.message}")
        }

        logger.info {
            "Item extracted: ${extractedCount}x $extractedMaterial " +
            "from slot ${request.slotIndex} at ${request.location.toSimpleString()} " +
            "by player ${request.playerUuid}"
        }

        return ExtractItemResponse.success(
            material = extractedMaterial,
            extractedCount = extractedCount
        )
    }

    /**
     * Locationをシンプルな文字列に変換する拡張関数。
     */
    private fun Location.toSimpleString(): String {
        return "${world?.name}:$blockX,$blockY,$blockZ"
    }
}
