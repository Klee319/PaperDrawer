package com.example.paperdrawers.domain.model

import org.joml.Vector2f

/**
 * ドロワーのTier（等級）を定義する列挙型。
 *
 * 各TierはSingle (1x1)レイアウトを持ち、Tierが上がるごとに容量が倍増する。
 *
 * @property slotCount このドロワータイプが持つスロット数（常に1）
 * @property baseCapacityStacks 各スロットの基本容量（スタック単位）
 * @property tier Tierレベル（1-7）
 * @property displayName 表示名
 */
enum class DrawerType(
    private val slotCount: Int,
    private val baseCapacityStacks: Int,
    val tier: Int,
    val displayName: String
) {
    /**
     * Tier 1: Basic Drawer
     * 32スタック容量
     */
    SINGLE_TIER_1(slotCount = 1, baseCapacityStacks = 32, tier = 1, displayName = "基本のドロワー"),

    /**
     * Tier 2: Copper Drawer
     * 64スタック容量
     */
    SINGLE_TIER_2(slotCount = 1, baseCapacityStacks = 64, tier = 2, displayName = "銅のドロワー"),

    /**
     * Tier 3: Iron Drawer
     * 128スタック容量
     */
    SINGLE_TIER_3(slotCount = 1, baseCapacityStacks = 128, tier = 3, displayName = "鉄のドロワー"),

    /**
     * Tier 4: Gold Drawer
     * 256スタック容量
     */
    SINGLE_TIER_4(slotCount = 1, baseCapacityStacks = 256, tier = 4, displayName = "金のドロワー"),

    /**
     * Tier 5: Diamond Drawer
     * 512スタック容量
     */
    SINGLE_TIER_5(slotCount = 1, baseCapacityStacks = 512, tier = 5, displayName = "ダイヤのドロワー"),

    /**
     * Tier 6: Netherite Drawer
     * 1024スタック容量
     */
    SINGLE_TIER_6(slotCount = 1, baseCapacityStacks = 1024, tier = 6, displayName = "ネザライトのドロワー"),

    /**
     * Tier 7: Creative Drawer
     * 2048スタック容量
     */
    SINGLE_TIER_7(slotCount = 1, baseCapacityStacks = 2048, tier = 7, displayName = "クリエイティブドロワー"),

    /**
     * Void Drawer (ゴミ箱)
     * 投入されたアイテムを消滅させる
     */
    VOID(slotCount = 1, baseCapacityStacks = 1, tier = 0, displayName = "ボイドドロワー");

    /**
     * このドロワータイプのスロット数を取得する。
     *
     * @return スロット数（常に1）
     */
    fun getSlotCount(): Int = slotCount

    /**
     * 各スロットの基本容量をスタック単位で取得する。
     *
     * @return 基本容量（スタック数）
     */
    fun getBaseCapacity(): Int = baseCapacityStacks

    /**
     * ブロック面上の各スロットの相対位置を取得する。
     *
     * 座標系は左上を原点(0,0)、右下を(1,1)とする正規化座標。
     * 1x1ドロワーは常に中央に1スロット。
     *
     * @return 各スロットの中心位置のリスト（スロットインデックス順）
     */
    fun getSlotPositions(): List<Vector2f> = listOf(Vector2f(0.5f, 0.5f))

    /**
     * クリック位置からスロットインデックスを取得する。
     *
     * 1x1ドロワーは常にスロット0を返す。
     *
     * @param relativeX ブロック面上のX座標（0.0〜1.0、左が0）
     * @param relativeY ブロック面上のY座標（0.0〜1.0、上が0）
     * @return スロットインデックス（常に0）
     */
    fun getSlotIndexFromClick(relativeX: Float, relativeY: Float): Int = 0

    companion object {
        /**
         * Tierレベルから対応するDrawerTypeを取得する。
         *
         * @param tier Tierレベル（1-7）
         * @return 対応するDrawerType
         * @throws IllegalArgumentException 無効なTierレベルの場合
         */
        fun fromTier(tier: Int): DrawerType {
            return entries.find { it.tier == tier }
                ?: throw IllegalArgumentException("Invalid tier: $tier. Valid range: 1-7")
        }

        /**
         * レガシータイプ名から対応するDrawerTypeを取得する（マイグレーション用）。
         *
         * @param legacyName 旧タイプ名（SINGLE_1X1, DOUBLE_1X2, QUAD_2X2）
         * @return 対応するDrawerType（SINGLE_TIER_1）
         */
        fun fromLegacyName(legacyName: String): DrawerType? {
            return when (legacyName) {
                "SINGLE_1X1" -> SINGLE_TIER_1
                "DOUBLE_1X2" -> SINGLE_TIER_1
                "QUAD_2X2" -> SINGLE_TIER_1
                else -> null
            }
        }

        /**
         * タイプ名またはエイリアスから対応するDrawerTypeを取得する。
         *
         * @param name タイプ名またはエイリアス
         * @return 対応するDrawerType、見つからない場合はnull
         */
        fun fromNameOrAlias(name: String): DrawerType? {
            val normalized = name.lowercase().trim()

            // Tier番号による検索
            if (normalized.startsWith("tier")) {
                val tierNum = normalized.removePrefix("tier").toIntOrNull()
                if (tierNum != null && tierNum in 1..7) {
                    return fromTier(tierNum)
                }
            }

            // 素材名による検索
            return when (normalized) {
                "basic", "1" -> SINGLE_TIER_1
                "copper", "2" -> SINGLE_TIER_2
                "iron", "3" -> SINGLE_TIER_3
                "gold", "4" -> SINGLE_TIER_4
                "diamond", "5" -> SINGLE_TIER_5
                "netherite", "6" -> SINGLE_TIER_6
                "creative", "7" -> SINGLE_TIER_7
                "void", "0" -> VOID
                else -> {
                    // enumの名前で直接検索
                    entries.find { it.name.equals(normalized, ignoreCase = true) }
                }
            }
        }

        /**
         * 有効なタイプ名のリストを取得する。
         *
         * @return タイプ名のリスト
         */
        fun getValidTypeNames(): List<String> {
            return listOf(
                "tier1", "tier2", "tier3", "tier4", "tier5", "tier6", "tier7",
                "basic", "copper", "iron", "gold", "diamond", "netherite", "creative",
                "void",
                "1", "2", "3", "4", "5", "6", "7"
            )
        }
    }
}
