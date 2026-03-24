package com.example.paperdrawers.domain.model

import com.example.paperdrawers.infrastructure.config.DrawerCapacityConfig
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * ドロワーブロックを表す集約ルートエンティティ。
 *
 * ドロワーブロックはワールド内の特定位置に配置され、複数のスロットを持つ。
 * 各スロットはアイテムの格納・取り出しを行うことができる。
 *
 * @property id ドロワーの一意識別子
 * @property location ワールド内の位置（ワールド名、x、y、z座標）
 * @property type ドロワーの種類
 * @property facing ドロワーの向き（プレイヤーが操作する面の方向）
 * @property slots スロットのリスト
 * @property ownerId 所有者のUUID（nullの場合は所有者なし）
 * @property createdAt 作成時刻（エポックミリ秒）
 */
data class DrawerBlock(
    val id: UUID,
    val location: Location,
    val type: DrawerType,
    val facing: BlockFace,
    val slots: List<DrawerSlot>,
    val ownerId: UUID? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(slots.size == type.getSlotCount()) {
            "Slot count (${slots.size}) must match drawer type slot count (${type.getSlotCount()})"
        }

        // 各スロットのインデックスが正しいことを検証
        slots.forEachIndexed { index, slot ->
            require(slot.index == index) {
                "Slot at position $index has incorrect index ${slot.index}"
            }
        }

        // facingは水平方向のみ許可（NORTH, SOUTH, EAST, WEST）
        require(facing in VALID_FACING_DIRECTIONS) {
            "facing must be a horizontal direction (NORTH, SOUTH, EAST, WEST), but was $facing"
        }
    }

    /**
     * 指定されたスロットにアイテムを挿入する。
     *
     * Note: この関数はマテリアルのみを保存し、ItemMeta は保持しない。
     * エンチャントやNBTタグを保持したい場合は insertItemStack() を使用すること。
     *
     * @param slotIndex 挿入先のスロットインデックス
     * @param material 挿入するマテリアル
     * @param amount 挿入する数量
     * @return 挿入結果と更新されたドロワーブロックのペア
     * @throws IndexOutOfBoundsException スロットインデックスが範囲外の場合
     */
    fun insertItem(slotIndex: Int, material: Material, amount: Int): Pair<DetailedInsertResult, DrawerBlock> {
        validateSlotIndex(slotIndex)
        require(amount > 0) { "amount must be positive, but was $amount" }

        // Void drawer: consume all items without storing
        if (type == DrawerType.VOID) {
            return Pair(
                DetailedInsertResult.success(material, slotIndex, amount, amount),
                this
            )
        }

        val slot = slots[slotIndex]

        // スロットがアイテムを受け入れられない場合
        if (!slot.canAccept(material)) {
            return Pair(
                DetailedInsertResult.failure(material, slotIndex, amount),
                this
            )
        }

        val (insertedCount, updatedSlot) = slot.addItems(material, amount)
        val updatedSlots = slots.toMutableList().apply {
            set(slotIndex, updatedSlot)
        }

        val updatedDrawer = copy(slots = updatedSlots)
        val result = DetailedInsertResult.success(material, slotIndex, insertedCount, amount)

        return Pair(result, updatedDrawer)
    }

    /**
     * 指定されたスロットに ItemStack を挿入する。
     *
     * ItemStack のメタデータ（エンチャント、名前、説明、NBTタグ等）を
     * テンプレートとして保存し、取り出し時に復元する。
     *
     * @param slotIndex 挿入先のスロットインデックス
     * @param itemStack 挿入するアイテム
     * @return 挿入結果と更新されたドロワーブロックのペア
     * @throws IndexOutOfBoundsException スロットインデックスが範囲外の場合
     */
    fun insertItemStack(slotIndex: Int, itemStack: ItemStack): Pair<DetailedInsertResult, DrawerBlock> {
        validateSlotIndex(slotIndex)
        require(itemStack.amount > 0) { "itemStack amount must be positive, but was ${itemStack.amount}" }

        // Void drawer: consume all items without storing
        if (type == DrawerType.VOID) {
            return Pair(
                DetailedInsertResult.success(itemStack.type, slotIndex, itemStack.amount, itemStack.amount),
                this
            )
        }

        val slot = slots[slotIndex]

        // スロットがアイテムを受け入れられない場合
        if (!slot.canAcceptItemStack(itemStack)) {
            return Pair(
                DetailedInsertResult.failure(itemStack.type, slotIndex, itemStack.amount),
                this
            )
        }

        val (insertedCount, updatedSlot) = slot.addItemStack(itemStack)
        val updatedSlots = slots.toMutableList().apply {
            set(slotIndex, updatedSlot)
        }

        val updatedDrawer = copy(slots = updatedSlots)
        val result = DetailedInsertResult.success(itemStack.type, slotIndex, insertedCount, itemStack.amount)

        return Pair(result, updatedDrawer)
    }

    /**
     * 指定されたスロットからアイテムを取り出す。
     *
     * Note: この関数は Material を返すため、ItemMeta は復元されない。
     * エンチャントやNBTタグを復元したい場合は extractItemStack() を使用すること。
     *
     * @param slotIndex 取り出し元のスロットインデックス
     * @param amount 取り出そうとする数量
     * @return 取り出したマテリアルと数量のペア、および更新されたドロワーブロック
     * @throws IndexOutOfBoundsException スロットインデックスが範囲外の場合
     */
    fun extractItem(slotIndex: Int, amount: Int): Triple<Material?, Int, DrawerBlock> {
        validateSlotIndex(slotIndex)
        require(amount > 0) { "amount must be positive, but was $amount" }

        val slot = slots[slotIndex]

        // スロットが空の場合
        if (slot.isEmpty()) {
            return Triple(null, 0, this)
        }

        val materialToExtract = slot.storedMaterial
        val (extractedCount, updatedSlot) = slot.removeItems(amount)
        val updatedSlots = slots.toMutableList().apply {
            set(slotIndex, updatedSlot)
        }

        val updatedDrawer = copy(slots = updatedSlots)

        return Triple(materialToExtract, extractedCount, updatedDrawer)
    }

    /**
     * 指定されたスロットから ItemStack を取り出す。
     *
     * 格納されていた ItemStack のメタデータ（エンチャント、名前、説明、NBTタグ等）を
     * 復元した完全な ItemStack を返す。
     *
     * @param slotIndex 取り出し元のスロットインデックス
     * @param amount 取り出そうとする数量
     * @return 取り出した ItemStack（エンチャント等を含む）と更新されたドロワーブロックのペア
     * @throws IndexOutOfBoundsException スロットインデックスが範囲外の場合
     */
    fun extractItemStack(slotIndex: Int, amount: Int): Pair<ItemStack?, DrawerBlock> {
        validateSlotIndex(slotIndex)
        require(amount > 0) { "amount must be positive, but was $amount" }

        val slot = slots[slotIndex]

        // スロットが空の場合
        if (slot.isEmpty()) {
            return Pair(null, this)
        }

        val (extractedCount, updatedSlot) = slot.removeItems(amount)
        val updatedSlots = slots.toMutableList().apply {
            set(slotIndex, updatedSlot)
        }

        val updatedDrawer = copy(slots = updatedSlots)

        // テンプレートから ItemStack を作成（extractedCount が 0 の場合も slot の情報を使用）
        val extractedItemStack = if (extractedCount > 0) {
            // 取り出し前のスロット状態からテンプレートを取得して ItemStack を作成
            slot.createItemStack(extractedCount)
        } else {
            null
        }

        return Pair(extractedItemStack, updatedDrawer)
    }

    /**
     * 指定されたインデックスのスロットを取得する。
     *
     * @param index スロットインデックス
     * @return スロット
     * @throws IndexOutOfBoundsException インデックスが範囲外の場合
     */
    fun getSlot(index: Int): DrawerSlot {
        validateSlotIndex(index)
        return slots[index]
    }

    /**
     * すべてのスロットに格納されているアイテムの合計数を取得する。
     *
     * @return 合計アイテム数
     */
    fun getTotalItemCount(): Int = slots.sumOf { it.itemCount }

    /**
     * 指定されたスロットをロックする。
     *
     * @param slotIndex ロックするスロットのインデックス
     * @param material ロックするマテリアル
     * @return 更新されたドロワーブロック
     */
    fun lockSlot(slotIndex: Int, material: Material): DrawerBlock {
        validateSlotIndex(slotIndex)
        val updatedSlot = slots[slotIndex].lock(material)
        val updatedSlots = slots.toMutableList().apply {
            set(slotIndex, updatedSlot)
        }
        return copy(slots = updatedSlots)
    }

    /**
     * 指定されたスロットのロックを解除する。
     *
     * @param slotIndex ロック解除するスロットのインデックス
     * @return 更新されたドロワーブロック
     */
    fun unlockSlot(slotIndex: Int): DrawerBlock {
        validateSlotIndex(slotIndex)
        val updatedSlot = slots[slotIndex].unlock()
        val updatedSlots = slots.toMutableList().apply {
            set(slotIndex, updatedSlot)
        }
        return copy(slots = updatedSlots)
    }

    /**
     * ドロワーが完全に空かどうかを判定する。
     *
     * @return すべてのスロットが空の場合true
     */
    fun isEmpty(): Boolean = slots.all { it.isEmpty() }

    /**
     * ドロワーがいずれかのスロットにアイテムを持っているかどうかを判定する。
     *
     * @return いずれかのスロットにアイテムがある場合true
     */
    fun hasItems(): Boolean = slots.any { !it.isEmpty() }

    /**
     * 位置情報からシンプルなロケーションキーを生成する。
     * チャンクベースの検索や永続化に使用。
     */
    fun getLocationKey(): String {
        return "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
    }

    private fun validateSlotIndex(index: Int) {
        if (index < 0 || index >= slots.size) {
            throw IndexOutOfBoundsException(
                "Slot index $index is out of bounds for drawer with ${slots.size} slots"
            )
        }
    }

    companion object {
        /** 有効な向き（水平方向のみ） */
        private val VALID_FACING_DIRECTIONS = setOf(
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
        )

        /**
         * 新しいドロワーブロックを作成する。
         *
         * @param location 配置位置
         * @param type ドロワーの種類
         * @param facing ドロワーの向き
         * @param ownerId 所有者のUUID（オプション）
         * @return 新しいドロワーブロック
         */
        fun create(
            location: Location,
            type: DrawerType,
            facing: BlockFace,
            ownerId: UUID? = null
        ): DrawerBlock {
            val slots = (0 until type.getSlotCount()).map { index ->
                DrawerSlot.create(index, DrawerCapacityConfig.getCapacity(type))
            }

            return DrawerBlock(
                id = UUID.randomUUID(),
                location = location,
                type = type,
                facing = facing,
                slots = slots,
                ownerId = ownerId,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
