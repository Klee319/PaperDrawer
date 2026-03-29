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
        val isSortingRecipe = recipeManager.isSortingRecipe(recipe.key)

        // === 仕分けドロワーレシピ: 入力 Tier に応じた結果を動的に設定 ===
        // Why: 全ティアの仕分けレシピは同一素材構成のため1つのレシピで全 Tier をカバーする。
        // Tier の検証は不要（どの Tier のドロワーでも仕分けドロワーに変換可能）。
        if (isSortingRecipe) {
            handleSortingRecipeResult(event, matrix)
            return
        }

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
        val resultItem = event.inventory.result
        if (resultItem != null) {
            val resultDrawerType = DrawerItemFactory.getDrawerType(resultItem)
            if (resultDrawerType != null) {
                for (item in matrix) {
                    if (item == null) continue
                    val inputType = DrawerItemFactory.getDrawerType(item) ?: continue

                    val expectedTier = resultDrawerType.tier - 1
                    if (inputType.tier != expectedTier) {
                        logger.fine("DrawerCraftListener: Tier mismatch - input=${inputType.tier}, expected=$expectedTier, result=${resultDrawerType.tier}")
                        event.inventory.result = null
                        return
                    }
                }
            }
        }
    }

    /**
     * 仕分けドロワーレシピの結果を入力ドロワーの Tier に応じて動的に設定する。
     *
     * Why: 統合仕分けレシピは1つのレシピで全 Tier をカバーする。
     * 入力ドロワーの Tier を読み取り、同じ Tier の仕分けドロワーを結果として設定する。
     * 元ドロワーに中身がある場合はそれも引き継ぐ。
     */
    private fun handleSortingRecipeResult(event: PrepareItemCraftEvent, matrix: Array<out ItemStack?>) {
        val drawerItem = matrix.filterNotNull().firstOrNull { DrawerItemFactory.isDrawerItem(it) }
        if (drawerItem == null) {
            event.inventory.result = null
            return
        }

        val inputType = DrawerItemFactory.getDrawerType(drawerItem)
        if (inputType == null) {
            event.inventory.result = null
            return
        }

        // 入力ドロワーの Tier に応じた仕分けドロワーを作成
        val result = DrawerItemFactory.convertToSortingDrawer(drawerItem)
        if (result != null) {
            event.inventory.result = result
        } else {
            event.inventory.result = null
        }
    }
}
