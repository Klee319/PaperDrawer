package com.example.paperdrawers.application.usecase

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerPersistenceException
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.config.DrawerCapacityConfig
import org.bukkit.Location
import org.bukkit.block.BlockFace
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ドロワー作成リクエスト。
 *
 * @property location ドロワーを配置する位置
 * @property type ドロワーの種類
 * @property facing ドロワーの向き（プレイヤーが操作する面の方向）
 * @property ownerUuid 所有者のUUID
 */
data class CreateDrawerRequest(
    val location: Location,
    val type: DrawerType,
    val facing: BlockFace,
    val ownerUuid: UUID
)

/**
 * ドロワー作成レスポンス。
 *
 * @property success 作成が成功したかどうか
 * @property drawer 作成されたドロワー（成功時のみ）
 * @property errorMessage エラーメッセージ（エラー時のみ）
 */
data class CreateDrawerResponse(
    val success: Boolean,
    val drawer: DrawerBlock?,
    val errorMessage: String? = null
) {
    companion object {
        /**
         * 成功レスポンスを作成する。
         */
        fun success(drawer: DrawerBlock): CreateDrawerResponse {
            return CreateDrawerResponse(
                success = true,
                drawer = drawer
            )
        }

        /**
         * 失敗レスポンスを作成する。
         */
        fun failure(errorMessage: String): CreateDrawerResponse {
            return CreateDrawerResponse(
                success = false,
                drawer = null,
                errorMessage = errorMessage
            )
        }
    }
}

/**
 * 新しいドロワーを作成するユースケース。
 *
 * プレイヤーがワールドにドロワーブロックを配置する際の
 * ビジネスロジックを処理する。
 *
 * @property repository ドロワーリポジトリ
 * @property logger ロガー
 */
class CreateDrawerUseCase(
    private val repository: DrawerRepository,
    private val logger: Logger = Logger.getLogger(CreateDrawerUseCase::class.java.name)
) {
    /** 有効な向き（水平方向のみ） */
    private val validFacingDirections = setOf(
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.EAST,
        BlockFace.WEST
    )

    /**
     * ドロワー作成を実行する。
     *
     * @param request 作成リクエスト
     * @return 作成結果のレスポンス
     */
    fun execute(request: CreateDrawerRequest): CreateDrawerResponse {
        logger.fine {
            "CreateDrawer requested: location=${request.location.toSimpleString()}, " +
            "type=${request.type}, facing=${request.facing}, " +
            "owner=${request.ownerUuid}"
        }

        // 向きのバリデーション
        if (request.facing !in validFacingDirections) {
            return CreateDrawerResponse.failure(
                "Invalid facing direction: ${request.facing}. Must be NORTH, SOUTH, EAST, or WEST"
            )
        }

        // ワールドのバリデーション
        if (request.location.world == null) {
            return CreateDrawerResponse.failure("Location world cannot be null")
        }

        // 既存ドロワーの存在確認
        val existingDrawerExists = try {
            repository.exists(request.location)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to check existing drawer at ${request.location.toSimpleString()}", e)
            return CreateDrawerResponse.failure("Failed to check existing drawer: ${e.message}")
        }

        if (existingDrawerExists) {
            logger.fine { "Drawer already exists at ${request.location.toSimpleString()}" }
            return CreateDrawerResponse.failure(
                "A drawer already exists at the specified location"
            )
        }

        // ドロワーの作成
        val drawer = try {
            DrawerBlock.create(
                location = request.location,
                type = request.type,
                facing = request.facing,
                ownerId = request.ownerUuid,
                capacityStacks = DrawerCapacityConfig.getCapacity(request.type)
            )
        } catch (e: IllegalArgumentException) {
            logger.fine { "Drawer creation validation failed: ${e.message}" }
            return CreateDrawerResponse.failure(e.message ?: "Drawer creation validation failed")
        }

        // ドロワーの永続化
        try {
            repository.save(drawer)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to save new drawer at ${request.location.toSimpleString()}", e)
            return CreateDrawerResponse.failure("Failed to save drawer: ${e.message}")
        }

        logger.info {
            "Drawer created: type=${request.type}, id=${drawer.id}, " +
            "location=${request.location.toSimpleString()}, " +
            "owner=${request.ownerUuid}"
        }

        return CreateDrawerResponse.success(drawer)
    }

    /**
     * Locationをシンプルな文字列に変換する拡張関数。
     */
    private fun Location.toSimpleString(): String {
        return "${world?.name}:$blockX,$blockY,$blockZ"
    }
}
