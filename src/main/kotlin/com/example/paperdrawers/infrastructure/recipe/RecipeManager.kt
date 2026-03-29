package com.example.paperdrawers.infrastructure.recipe

import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.infrastructure.item.DrawerItemFactory
import com.example.paperdrawers.infrastructure.item.DrawerKeyFactory
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * ドロワーのクラフトレシピを管理するクラス。
 *
 * Why: config.ymlからレシピ定義を読み込み、Bukkit のレシピシステムに登録することで、
 * サーバー管理者がレシピをカスタマイズ可能にする。
 * 特殊素材プレフィックス PAPER_DRAWERS_TIER_X により、ドロワーアイテム同士の
 * アップグレードレシピも定義できる。
 *
 * @property plugin プラグインインスタンス（NamespacedKey 生成に必要）
 * @property logger ログ出力用ロガー
 */
class RecipeManager(
    private val plugin: Plugin,
    private val logger: Logger
) {
    /**
     * ドロワー素材の要件情報。
     *
     * Why: PrepareItemCraftEvent で使用するために、
     * どのレシピのどのスロット文字がどのTierのドロワーを要求するかを記録する。
     * ExactChoice では UUID 付きドロワーがマッチしないため、
     * MaterialChoice + イベントバリデーションで代替する。
     *
     * @property recipeKey レシピの NamespacedKey
     * @property ingredientChar レシピシェイプ内の素材文字
     * @property requiredTier 要求されるドロワーTier
     */
    data class DrawerIngredientRequirement(
        val recipeKey: NamespacedKey,
        val ingredientChar: Char,
        val requiredTier: Int
    )

    /** 登録済みレシピキーのリスト（クリーンアップ用） */
    private val registeredKeys: List<NamespacedKey>
        get() = _registeredKeys.toList()
    private val _registeredKeys = mutableListOf<NamespacedKey>()

    /** ドロワー素材の要件リスト（DrawerCraftListener で参照） */
    private val _drawerIngredientRequirements = mutableListOf<DrawerIngredientRequirement>()
    val drawerIngredientRequirements: List<DrawerIngredientRequirement>
        get() = _drawerIngredientRequirements.toList()

    /** 仕分けドロワーレシピキーのセット（DrawerCraftListener で参照） */
    private val _sortingRecipeKeys = mutableSetOf<NamespacedKey>()
    val sortingRecipeKeys: Set<NamespacedKey>
        get() = _sortingRecipeKeys.toSet()

    companion object {
        /** ドロワーアイテムを参照する特殊素材プレフィックス */
        private const val DRAWER_INGREDIENT_PREFIX = "PAPER_DRAWERS_TIER_"

        /** Bukkit Tag を参照する素材プレフィックス */
        private const val TAG_PREFIX = "TAG_"

        /** レシピシェイプの行数 */
        private const val SHAPE_ROW_COUNT = 3

        /** 有効なTier範囲 */
        private val VALID_TIER_RANGE = 1..7
    }

    /**
     * config.ymlのrecipesセクションからすべてのレシピを登録する。
     *
     * Why: 設定ファイルから動的にレシピを読み込むことで、
     * サーバー管理者がコードを変更せずにレシピをカスタマイズできる。
     *
     * @param configSection recipesセクションの ConfigurationSection（null の場合は警告を出力）
     */
    fun registerRecipes(configSection: ConfigurationSection?) {
        if (configSection == null) {
            logger.warning("Recipe configuration section 'recipes' not found in config.yml. No recipes registered.")
            return
        }

        // Register drawer key recipe
        try {
            registerDrawerKeyRecipe(configSection)
        } catch (e: Exception) {
            logger.warning("Failed to register drawer key recipe: ${e.message}")
        }

        var registeredCount = 0
        var skippedCount = 0

        for (tier in VALID_TIER_RANGE) {
            val tierKey = "tier-$tier"
            val tierSection = configSection.getConfigurationSection(tierKey)

            if (tierSection == null) {
                logger.fine("No recipe configuration found for $tierKey, skipping.")
                skippedCount++
                continue
            }

            if (!tierSection.getBoolean("enabled", true)) {
                logger.fine("Recipe for $tierKey is disabled, skipping.")
                skippedCount++
                continue
            }

            try {
                registerTierRecipe(tier, tierSection)
                registeredCount++
            } catch (e: Exception) {
                logger.warning("Failed to register recipe for $tierKey: ${e.message}")
                skippedCount++
            }
        }

        // Register void drawer recipe
        try {
            registerVoidRecipe(configSection)
        } catch (e: Exception) {
            logger.warning("Failed to register void drawer recipe: ${e.message}")
        }

        // Register single sorting drawer recipe
        // Why: 全ティアの仕分けレシピは同一素材構成（COMPARATOR + BARREL + HOPPER）のため、
        // Bukkit は最初にマッチしたレシピのみ選択する。1つのレシピを登録し、
        // DrawerCraftListener で入力ドロワーの Tier に応じて結果を動的に決定する。
        try {
            val sortingSection = configSection.getConfigurationSection("sorting")
                ?: configSection.getConfigurationSection("sorting-tier-1")
            if (sortingSection != null && sortingSection.getBoolean("enabled", true)) {
                registerUnifiedSortingRecipe(sortingSection)
            }
        } catch (e: Exception) {
            logger.warning("Failed to register sorting drawer recipe: ${e.message}")
        }

        logger.fine("Recipes registered: $registeredCount, skipped: $skippedCount")
    }

    /**
     * 登録済みのすべてのレシピを解除する。
     *
     * Why: プラグイン無効化時にレシピをクリーンアップし、
     * 他のプラグインや再ロード時の競合を防ぐ。
     */
    fun unregisterAll() {
        for (key in _registeredKeys) {
            try {
                plugin.server.removeRecipe(key)
                logger.fine("Unregistered recipe: ${key.key}")
            } catch (e: Exception) {
                logger.warning("Failed to unregister recipe ${key.key}: ${e.message}")
            }
        }

        val count = _registeredKeys.size
        _registeredKeys.clear()
        _drawerIngredientRequirements.clear()
        _sortingRecipeKeys.clear()
        logger.fine("Unregistered $count recipes")
    }

    /**
     * ドロワーキーのクラフトレシピを登録する。
     *
     * Why: ドロワーキーもconfig.ymlで設定可能にすることで、
     * サーバー管理者がレシピの有効/無効や素材をカスタマイズできる。
     *
     * @param configSection recipesセクションの ConfigurationSection
     */
    private fun registerDrawerKeyRecipe(configSection: ConfigurationSection) {
        val keySection = configSection.getConfigurationSection("drawer-key") ?: return

        if (!keySection.getBoolean("enabled", true)) {
            return
        }

        val resultAmount = keySection.getInt("result-amount", 3)
        val resultItem = DrawerKeyFactory.createDrawerKey(resultAmount)

        val key = NamespacedKey(plugin, "drawer_key")
        val recipe = ShapedRecipe(key, resultItem)

        // シェイプの読み込みと検証
        val shapeList = keySection.getStringList("shape")
        if (shapeList.size != SHAPE_ROW_COUNT) {
            throw IllegalArgumentException(
                "Drawer key recipe shape must have exactly $SHAPE_ROW_COUNT rows, but has ${shapeList.size}"
            )
        }
        recipe.shape(*shapeList.toTypedArray())

        // 素材マッピングの読み込み
        val ingredientsSection = keySection.getConfigurationSection("ingredients")
            ?: throw IllegalArgumentException("No 'ingredients' section found for drawer-key recipe")

        for (charKey in ingredientsSection.getKeys(false)) {
            if (charKey.length != 1) {
                logger.warning("Invalid ingredient key '$charKey' in drawer-key recipe, skipping.")
                continue
            }

            val ingredientChar = charKey[0]
            val materialString = ingredientsSection.getString(charKey)
                ?: throw IllegalStateException("Ingredient value for '$charKey' is null in drawer-key recipe")

            val recipeChoice = resolveIngredient(materialString)
                ?: throw IllegalStateException("Failed to resolve ingredient '$materialString' for drawer-key recipe")

            recipe.setIngredient(ingredientChar, recipeChoice)
        }

        plugin.server.addRecipe(recipe)
        _registeredKeys.add(key)
        logger.fine("Registered drawer key recipe (key: ${key.key})")
    }

    /**
     * 特定Tierのレシピを登録する。
     *
     * Why: 各Tierのレシピを個別に処理することで、
     * エラーが発生しても他のTierのレシピ登録に影響しない。
     *
     * @param tier Tierレベル（1-7）
     * @param section このTierのレシピ設定セクション
     * @throws IllegalArgumentException シェイプが不正な場合
     * @throws IllegalStateException 素材の解決に失敗した場合
     */
    private fun registerTierRecipe(tier: Int, section: ConfigurationSection) {
        val drawerType = DrawerType.fromTier(tier)
        val resultItem = DrawerItemFactory.createDrawerItem(drawerType)

        val key = NamespacedKey(plugin, "drawer_tier_$tier")
        val recipe = ShapedRecipe(key, resultItem)

        // シェイプの読み込みと検証
        val shapeList = section.getStringList("shape")
        if (shapeList.size != SHAPE_ROW_COUNT) {
            throw IllegalArgumentException(
                "Recipe shape must have exactly $SHAPE_ROW_COUNT rows, but has ${shapeList.size}"
            )
        }
        recipe.shape(*shapeList.toTypedArray())

        // 素材マッピングの読み込み
        val ingredientsSection = section.getConfigurationSection("ingredients")
            ?: throw IllegalArgumentException("No 'ingredients' section found for tier-$tier")

        for (charKey in ingredientsSection.getKeys(false)) {
            if (charKey.length != 1) {
                logger.warning("Invalid ingredient key '$charKey' in tier-$tier recipe (must be single character), skipping.")
                continue
            }

            val ingredientChar = charKey[0]
            val materialString = ingredientsSection.getString(charKey)
                ?: throw IllegalStateException("Ingredient value for '$charKey' is null in tier-$tier")

            val recipeChoice = resolveIngredient(materialString)
                ?: throw IllegalStateException(
                    "Failed to resolve ingredient '$materialString' for key '$charKey' in tier-$tier"
                )

            recipe.setIngredient(ingredientChar, recipeChoice)

            // ドロワー素材参照の場合、要件を記録する
            // Why: PrepareItemCraftEvent でバリデーションするために必要
            val trimmedMaterial = materialString.trim()
            if (trimmedMaterial.startsWith(DRAWER_INGREDIENT_PREFIX)) {
                val requiredTier = trimmedMaterial.removePrefix(DRAWER_INGREDIENT_PREFIX).toIntOrNull()
                if (requiredTier != null) {
                    _drawerIngredientRequirements.add(
                        DrawerIngredientRequirement(
                            recipeKey = key,
                            ingredientChar = ingredientChar,
                            requiredTier = requiredTier
                        )
                    )
                }
            }
        }

        plugin.server.addRecipe(recipe)
        _registeredKeys.add(key)
        logger.fine("Registered recipe for tier-$tier (key: ${key.key})")
    }

    /**
     * ボイドドロワーのクラフトレシピを登録する。
     */
    private fun registerVoidRecipe(configSection: ConfigurationSection) {
        val voidSection = configSection.getConfigurationSection("void") ?: return

        if (!voidSection.getBoolean("enabled", true)) {
            return
        }

        val resultItem = DrawerItemFactory.createDrawerItem(DrawerType.VOID)
        val key = NamespacedKey(plugin, "drawer_void")
        val recipe = ShapedRecipe(key, resultItem)

        val shapeList = voidSection.getStringList("shape")
        if (shapeList.size != SHAPE_ROW_COUNT) {
            throw IllegalArgumentException(
                "Void drawer recipe shape must have exactly $SHAPE_ROW_COUNT rows, but has ${shapeList.size}"
            )
        }
        recipe.shape(*shapeList.toTypedArray())

        val ingredientsSection = voidSection.getConfigurationSection("ingredients")
            ?: throw IllegalArgumentException("No 'ingredients' section found for void drawer recipe")

        for (charKey in ingredientsSection.getKeys(false)) {
            if (charKey.length != 1) {
                logger.warning("Invalid ingredient key '$charKey' in void recipe, skipping.")
                continue
            }

            val ingredientChar = charKey[0]
            val materialString = ingredientsSection.getString(charKey)
                ?: throw IllegalStateException("Ingredient value for '$charKey' is null in void recipe")

            val recipeChoice = resolveIngredient(materialString)
                ?: throw IllegalStateException("Failed to resolve ingredient '$materialString' for void recipe")

            recipe.setIngredient(ingredientChar, recipeChoice)
        }

        plugin.server.addRecipe(recipe)
        _registeredKeys.add(key)
        logger.fine("Registered void drawer recipe (key: ${key.key})")
    }

    /**
     * 統合仕分けドロワーレシピを1つ登録する。
     *
     * Why: 全ティアの仕分けレシピは同一素材構成（COMPARATOR + BARREL + HOPPER）のため、
     * Bukkit は最初にマッチしたレシピしか選択できない。
     * 1つのレシピを登録し、DrawerCraftListener で入力ドロワーの Tier に応じて
     * 結果を動的に決定することで全ティアに対応する。
     *
     * @param section 仕分けレシピの設定セクション（sorting または sorting-tier-1）
     */
    private fun registerUnifiedSortingRecipe(section: ConfigurationSection) {
        // Tier 1 をデフォルト結果として登録（実際の結果は DrawerCraftListener が動的に設定）
        val resultItem = DrawerItemFactory.createDrawerItem(DrawerType.fromTier(1), 1, isSorting = true)

        val key = NamespacedKey(plugin, "drawer_sorting")
        val recipe = ShapedRecipe(key, resultItem)

        val shapeList = section.getStringList("shape")
        if (shapeList.size != SHAPE_ROW_COUNT) {
            throw IllegalArgumentException(
                "Sorting recipe shape must have exactly $SHAPE_ROW_COUNT rows, but has ${shapeList.size}"
            )
        }
        recipe.shape(*shapeList.toTypedArray())

        val ingredientsSection = section.getConfigurationSection("ingredients")
            ?: throw IllegalArgumentException("No 'ingredients' section found for sorting recipe")

        for (charKey in ingredientsSection.getKeys(false)) {
            if (charKey.length != 1) continue

            val ingredientChar = charKey[0]
            val materialString = ingredientsSection.getString(charKey)
                ?: throw IllegalStateException("Ingredient value for '$charKey' is null in sorting recipe")

            val recipeChoice = resolveIngredient(materialString)
                ?: throw IllegalStateException("Failed to resolve ingredient '$materialString' for sorting recipe")

            recipe.setIngredient(ingredientChar, recipeChoice)
        }

        plugin.server.addRecipe(recipe)
        _registeredKeys.add(key)
        _sortingRecipeKeys.add(key)
        logger.fine("Registered unified sorting drawer recipe (key: ${key.key})")
    }

    /**
     * 指定されたレシピキーが仕分けドロワーレシピかどうかを判定する。
     *
     * @param key レシピの NamespacedKey
     * @return 仕分けレシピの場合 true
     */
    fun isSortingRecipe(key: NamespacedKey): Boolean {
        return key in _sortingRecipeKeys
    }

    /**
     * 素材文字列をRecipeChoiceに解決する。
     *
     * Why: PAPER_DRAWERS_TIER_X プレフィックスを持つ素材はドロワーアイテムへの参照として解決し、
     * TAG_X プレフィックスを持つ素材は Bukkit Tag として解決し、
     * 通常の素材名はBukkitのMaterial列挙型から解決する。
     *
     * @param materialString 素材文字列（Material名、PAPER_DRAWERS_TIER_X、または TAG_X）
     * @return 解決された RecipeChoice、解決不可能な場合はnull
     */
    private fun resolveIngredient(materialString: String): RecipeChoice? {
        val trimmed = materialString.trim()

        // ドロワーアイテム参照の場合
        if (trimmed.startsWith(DRAWER_INGREDIENT_PREFIX)) {
            val tierString = trimmed.removePrefix(DRAWER_INGREDIENT_PREFIX)
            val tierNum = tierString.toIntOrNull()

            if (tierNum == null || tierNum !in VALID_TIER_RANGE) {
                logger.warning("Invalid drawer tier reference: '$trimmed'. Expected format: PAPER_DRAWERS_TIER_1 to PAPER_DRAWERS_TIER_7")
                return null
            }

            return try {
                DrawerType.fromTier(tierNum)
                RecipeChoice.MaterialChoice(Material.BARREL)
            } catch (e: IllegalArgumentException) {
                logger.warning("Failed to resolve drawer tier $tierNum: ${e.message}")
                null
            }
        }

        // Bukkit Tag 参照の場合（例: TAG_PLANKS）
        if (trimmed.startsWith(TAG_PREFIX)) {
            val tagName = trimmed.removePrefix(TAG_PREFIX).uppercase()
            val tag = resolveTag(tagName)
            if (tag != null) {
                return RecipeChoice.MaterialChoice(tag)
            }
            logger.warning("Unknown tag: '$trimmed'. Supported tags: PLANKS, LOGS, WOODEN_SLABS")
            return null
        }

        // 通常のMaterial素材の場合
        return try {
            val material = Material.valueOf(trimmed)
            RecipeChoice.MaterialChoice(material)
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown material: '$trimmed'. Check config.yml for valid Bukkit Material names.")
            null
        }
    }

    /**
     * タグ名から Bukkit Tag を解決する。
     *
     * Why: TAG_PLANKS のようなプレフィックス付き素材文字列をサポートすることで、
     * 全種類の木材等をまとめて指定でき、新しい木材が追加された際も自動的に対応できる。
     *
     * @param tagName タグ名（例: "PLANKS"）
     * @return 対応する Tag<Material>、見つからない場合はnull
     */
    private fun resolveTag(tagName: String): Tag<Material>? {
        return when (tagName) {
            "PLANKS" -> Tag.PLANKS
            "LOGS" -> Tag.LOGS
            "WOODEN_SLABS" -> Tag.WOODEN_SLABS
            "WOOL" -> Tag.WOOL
            else -> null
        }
    }
}
