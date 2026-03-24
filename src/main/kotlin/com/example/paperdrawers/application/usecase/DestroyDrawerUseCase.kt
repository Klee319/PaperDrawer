package com.example.paperdrawers.application.usecase

import com.example.paperdrawers.domain.repository.DrawerPersistenceException
import com.example.paperdrawers.domain.repository.DrawerRepository
import org.bukkit.Location
import org.bukkit.inventory.ItemStack
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ドロワー破壊リクエスト。
 *
 * @property location 破壊するドロワーの位置
 * @property dropItems アイテムをドロップするかどうか（デフォルトtrue）
 */
data class DestroyDrawerRequest(
    val location: Location,
    val dropItems: Boolean = true
)

/**
 * ドロワー破壊レスポンス。
 *
 * @property success 破壊が成功したかどうか
 * @property droppedItems ドロップされたアイテムのリスト
 * @property errorMessage エラーメッセージ（エラー時のみ）
 */
data class DestroyDrawerResponse(
    val success: Boolean,
    val droppedItems: List<ItemStack>,
    val errorMessage: String? = null
) {
    companion object {
        /**
         * 成功レスポンスを作成する。
         */
        fun success(droppedItems: List<ItemStack> = emptyList()): DestroyDrawerResponse {
            return DestroyDrawerResponse(
                success = true,
                droppedItems = droppedItems
            )
        }

        /**
         * 失敗レスポンスを作成する。
         */
        fun failure(errorMessage: String): DestroyDrawerResponse {
            return DestroyDrawerResponse(
                success = false,
                droppedItems = emptyList(),
                errorMessage = errorMessage
            )
        }
    }
}

/**
 * ドロワーを破壊するユースケース。
 *
 * ドロワーブロックが破壊された際のビジネスロジックを処理する。
 * オプションで格納されていたアイテムをドロップアイテムとして返す。
 *
 * @property repository ドロワーリポジトリ
 * @property logger ロガー
 */
class DestroyDrawerUseCase(
    private val repository: DrawerRepository,
    private val logger: Logger = Logger.getLogger(DestroyDrawerUseCase::class.java.name)
) {
    /**
     * ドロワー破壊を実行する。
     *
     * @param request 破壊リクエスト
     * @return 破壊結果のレスポンス
     */
    fun execute(request: DestroyDrawerRequest): DestroyDrawerResponse {
        logger.fine {
            "DestroyDrawer requested: location=${request.location.toSimpleString()}, " +
            "dropItems=${request.dropItems}"
        }

        // ドロワーの取得
        val drawer = try {
            repository.findByLocation(request.location)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to retrieve drawer at ${request.location.toSimpleString()}", e)
            return DestroyDrawerResponse.failure("Failed to retrieve drawer: ${e.message}")
        }

        if (drawer == null) {
            logger.fine { "No drawer found at ${request.location.toSimpleString()}" }
            return DestroyDrawerResponse.failure("No drawer found at the specified location")
        }

        // ドロップアイテムの生成
        val droppedItems = if (request.dropItems) {
            buildDroppedItemsList(drawer)
        } else {
            emptyList()
        }

        // ドロワーの削除
        try {
            repository.delete(request.location)
        } catch (e: DrawerPersistenceException) {
            logger.log(Level.WARNING, "Failed to delete drawer at ${request.location.toSimpleString()}", e)
            return DestroyDrawerResponse.failure("Failed to delete drawer: ${e.message}")
        }

        logger.info {
            "Drawer destroyed: id=${drawer.id}, " +
            "location=${request.location.toSimpleString()}, " +
            "droppedItems=${droppedItems.size} stacks"
        }

        return DestroyDrawerResponse.success(droppedItems)
    }

    /**
     * ドロワー内のアイテムからItemStackリストを構築する。
     *
     * 各スロットのアイテムを適切なスタックサイズに分割して返す。
     */
    private fun buildDroppedItemsList(drawer: com.example.paperdrawers.domain.model.DrawerBlock): List<ItemStack> {
        val items = mutableListOf<ItemStack>()

        for (slot in drawer.slots) {
            val material = slot.storedMaterial ?: continue
            var remainingCount = slot.itemCount

            if (remainingCount <= 0) continue

            // マテリアルの最大スタックサイズを取得
            val maxStackSize = material.maxStackSize

            // スタックに分割してItemStackを作成
            while (remainingCount > 0) {
                val stackSize = minOf(remainingCount, maxStackSize)
                items.add(ItemStack(material, stackSize))
                remainingCount -= stackSize
            }
        }

        return items
    }

    /**
     * Locationをシンプルな文字列に変換する拡張関数。
     */
    private fun Location.toSimpleString(): String {
        return "${world?.name}:$blockX,$blockY,$blockZ"
    }
}
