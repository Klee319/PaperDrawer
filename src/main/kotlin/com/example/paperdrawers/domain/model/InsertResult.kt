package com.example.paperdrawers.domain.model

import org.bukkit.Material

/**
 * アイテム挿入操作の結果を表す値クラス。
 *
 * 挿入操作の成功/失敗状態と、実際に挿入されたアイテム数を保持する。
 *
 * @property success 挿入操作が成功したかどうか（少なくとも1つのアイテムが挿入された場合true）
 * @property insertedCount 実際に挿入されたアイテム数
 * @property remainingCount 挿入できなかったアイテム数
 * @property material 挿入しようとしたマテリアル
 * @property slotIndex 挿入先のスロットインデックス
 */
@JvmInline
value class InsertResult private constructor(
    private val packed: Long
) {
    /**
     * 挿入されたアイテム数を取得する。
     */
    val insertedCount: Int
        get() = (packed shr 32).toInt()

    /**
     * 挿入できなかったアイテム数を取得する。
     */
    val remainingCount: Int
        get() = packed.toInt()

    /**
     * 挿入操作が成功したかどうか（少なくとも1つのアイテムが挿入された場合true）。
     */
    val success: Boolean
        get() = insertedCount > 0

    /**
     * すべてのアイテムが挿入されたかどうか。
     */
    val isComplete: Boolean
        get() = remainingCount == 0

    companion object {
        /**
         * InsertResultを作成する。
         *
         * @param insertedCount 挿入されたアイテム数
         * @param remainingCount 挿入できなかったアイテム数
         * @return InsertResult
         */
        fun of(insertedCount: Int, remainingCount: Int): InsertResult {
            require(insertedCount >= 0) { "insertedCount must be non-negative" }
            require(remainingCount >= 0) { "remainingCount must be non-negative" }
            val packed = (insertedCount.toLong() shl 32) or (remainingCount.toLong() and 0xFFFFFFFFL)
            return InsertResult(packed)
        }

        /**
         * 成功結果を作成する。
         *
         * @param insertedCount 挿入されたアイテム数
         * @param requestedCount 要求されたアイテム数
         * @return InsertResult
         */
        fun success(insertedCount: Int, requestedCount: Int): InsertResult {
            return of(insertedCount, requestedCount - insertedCount)
        }

        /**
         * 失敗結果を作成する（アイテムが1つも挿入されなかった場合）。
         *
         * @param requestedCount 要求されたアイテム数
         * @return InsertResult
         */
        fun failure(requestedCount: Int): InsertResult {
            return of(0, requestedCount)
        }
    }
}

/**
 * アイテム挿入操作の詳細な結果を表すデータクラス。
 *
 * InsertResultの軽量版に対し、こちらはマテリアルやスロット情報を含む完全版。
 *
 * @property material 挿入しようとしたマテリアル
 * @property slotIndex 挿入先のスロットインデックス
 * @property insertedCount 実際に挿入されたアイテム数
 * @property remainingCount 挿入できなかったアイテム数
 */
data class DetailedInsertResult(
    val material: Material,
    val slotIndex: Int,
    val insertedCount: Int,
    val remainingCount: Int
) {
    /**
     * 挿入操作が成功したかどうか（少なくとも1つのアイテムが挿入された場合true）。
     */
    val success: Boolean
        get() = insertedCount > 0

    /**
     * すべてのアイテムが挿入されたかどうか。
     */
    val isComplete: Boolean
        get() = remainingCount == 0

    /**
     * 軽量なInsertResultに変換する。
     */
    fun toInsertResult(): InsertResult = InsertResult.of(insertedCount, remainingCount)

    companion object {
        /**
         * 成功結果を作成する。
         */
        fun success(
            material: Material,
            slotIndex: Int,
            insertedCount: Int,
            requestedCount: Int
        ): DetailedInsertResult {
            return DetailedInsertResult(
                material = material,
                slotIndex = slotIndex,
                insertedCount = insertedCount,
                remainingCount = requestedCount - insertedCount
            )
        }

        /**
         * 失敗結果を作成する。
         */
        fun failure(
            material: Material,
            slotIndex: Int,
            requestedCount: Int
        ): DetailedInsertResult {
            return DetailedInsertResult(
                material = material,
                slotIndex = slotIndex,
                insertedCount = 0,
                remainingCount = requestedCount
            )
        }
    }
}
