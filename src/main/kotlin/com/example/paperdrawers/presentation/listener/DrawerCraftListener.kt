package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import com.example.paperdrawers.infrastructure.recipe.RecipeManager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import java.util.logging.Logger

/**
 * ドロワーのクラフトレシピにおける素材バリデーションを行うリスナー。
 *
 * Why: RecipeChoice.ExactChoice は isSimilar() を内部で使用するが、
 * 一度設置・破壊されたドロワーアイテムには UUID が PDC に付与されるため、
 * クリーンな DrawerItem とマッチしない。
 * この問題を回避するため、レシピには MaterialChoice(BARREL) を使用し、
 * このリスナーで実際のドロワー Tier を PDC タグから検証する。
 *
 * また、仕分けドロワーレシピの場合は元ドロワーの中身を引き継いだ
 * 仕分けドロワーアイテムを結果として設定する。
 *
 * @property recipeManager ドロワー素材の要件情報を持つ RecipeManager
 * @property logger ログ出力用ロガー
 */
class DrawerCraftListener(
    private val recipeManager: RecipeManager,
    private val logger: Logger
) : Listener {

    /**
     * クラフト準備イベントを処理し、ドロワー素材の Tier を検証する。
     *
     * @param event クラフト準備イベント
     */
    @EventHandler
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        val recipe = event.recipe as? ShapedRecipe ?: return

        val matrix = event.inventory.matrix
        val shape = recipe.shape

        // === Phase 1: レシピキーベースの Tier バリデーション ===
        val requirements = recipeManager.drawerIngredientRequirements
            .filter { it.recipeKey == recipe.key }

        for (req in requirements) {
            for (row in shape.indices) {
                for (col in shape[row].indices) {
                    if (shape[row][col] != req.ingredientChar) continue

                    val matrixIndex = row * 3 + col
                    val itemInSlot = matrix.getOrNull(matrixIndex)

                    if (itemInSlot == null || itemInSlot.type.isAir) continue

                    val drawerType = DrawerItemFactory.getDrawerType(itemInSlot)
                    if (drawerType == null || drawerType.tier != req.requiredTier) {
                        event.inventory.result = null
                        return
                    }
                }
            }
        }

        // === Phase 2: 汎用ドロワー Tier バリデーション（Phase 1 の安全ネット） ===
        // レシピキーの不一致等で Phase 1 がスキップされても、
        // クラフトグリッド内のドロワーの Tier を結果アイテムに対して検証する
        val resultItem = event.inventory.result
        if (resultItem != null) {
            val resultDrawerType = DrawerItemFactory.getDrawerType(resultItem)
            if (resultDrawerType != null) {
                val isSortingResult = DrawerItemFactory.isSortingDrawer(resultItem)

                for (item in matrix) {
                    if (item == null) continue
                    val inputType = DrawerItemFactory.getDrawerType(item) ?: continue

                    // 仕分けレシピ: 同じ Tier が必要
                    // アップグレードレシピ: 1つ下の Tier が必要
                    val expectedTier = if (isSortingResult) resultDrawerType.tier else resultDrawerType.tier - 1

                    if (inputType.tier != expectedTier) {
                        logger.fine("DrawerCraftListener: Tier mismatch - input=${inputType.tier}, expected=$expectedTier, result=${resultDrawerType.tier}")
                        event.inventory.result = null
                        return
                    }
                }
            }
        }

        // === Phase 3: 仕分けドロワーレシピの中身引き継ぎ ===
        if (recipeManager.isSortingRecipe(recipe.key)) {
            handleSortingRecipeResult(event, matrix)
        }
    }

    /**
     * 仕分けドロワーレシピの結果アイテムに元ドロワーの中身を引き継ぐ。
     *
     * Why: 仕分けドロワーへの変換時に格納アイテムを失わないようにするため、
     * クラフトグリッド内のドロワーアイテムから中身を読み取り、
     * 仕分けドロワーアイテムにコピーする。
     */
    private fun handleSortingRecipeResult(event: PrepareItemCraftEvent, matrix: Array<out ItemStack?>) {
        val drawerItem = matrix.filterNotNull().firstOrNull { DrawerItemFactory.isDrawerItem(it) }
            ?: return

        val result = DrawerItemFactory.convertToSortingDrawer(drawerItem)
        if (result != null) {
            event.inventory.result = result
        }
    }
}
