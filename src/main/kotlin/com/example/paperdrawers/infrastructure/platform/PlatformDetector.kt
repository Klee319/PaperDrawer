package com.example.paperdrawers.infrastructure.platform

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.logging.Level
import java.util.logging.Logger

/**
 * プラットフォーム検出クラス。
 *
 * Why: Bedrock Edition プレイヤー（Geyser/Floodgate 経由）と Java Edition プレイヤーを
 * 区別する必要がある。Bedrock プレイヤーは一部の機能（アイテムディスプレイの表示など）で
 * 異なる挙動を必要とする場合がある。
 *
 * Floodgate API はオプショナルな依存関係として扱い、インストールされていない場合は
 * 全プレイヤーを Java Edition として扱う。
 *
 * @property logger ログ出力用のロガー
 */
class PlatformDetector(
    private val logger: Logger
) {

    /**
     * Floodgate がサーバーにインストールされているかどうか。
     *
     * Why: 起動時に一度だけチェックし、結果をキャッシュすることでパフォーマンスを最適化。
     */
    private val floodgateAvailable: Boolean = checkFloodgateAvailability()

    /**
     * Floodgate API インスタンス（利用可能な場合のみ）。
     *
     * Why: リフレクションを避け、コンパイル時に依存関係が解決されるようにするため、
     * 利用可能な場合のみ Floodgate API を取得する。
     */
    private val floodgateApi: Any? = if (floodgateAvailable) getFloodgateApiInstance() else null

    /**
     * Floodgate がサーバーで利用可能かどうかを確認する。
     *
     * @return Floodgate が利用可能な場合 true
     */
    fun isFloodgateAvailable(): Boolean = floodgateAvailable

    /**
     * 指定されたプレイヤーが Bedrock Edition プレイヤーかどうかを判定する。
     *
     * Why: Bedrock プレイヤーは Geyser/Floodgate を通じて接続しており、
     * 一部の機能で異なる処理が必要になる場合がある。
     *
     * Floodgate がインストールされていない場合は、常に false を返す
     * （全プレイヤーを Java Edition として扱う）。
     *
     * @param player 判定対象のプレイヤー
     * @return Bedrock Edition プレイヤーの場合 true
     */
    fun isBedrockPlayer(player: Player): Boolean {
        if (!floodgateAvailable || floodgateApi == null) {
            return false
        }

        return try {
            // Floodgate API を使用して Bedrock プレイヤーかどうかを判定
            // org.geysermc.floodgate.api.FloodgateApi.isFloodgatePlayer(UUID)
            val isFloodgatePlayerMethod = floodgateApi.javaClass.getMethod(
                "isFloodgatePlayer",
                java.util.UUID::class.java
            )
            isFloodgatePlayerMethod.invoke(floodgateApi, player.uniqueId) as Boolean
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "Failed to check if player ${player.name} is a Bedrock player",
                e
            )
            false
        }
    }

    /**
     * プレイヤーのプラットフォーム種別を取得する。
     *
     * @param player 判定対象のプレイヤー
     * @return プラットフォーム種別
     */
    fun getPlayerPlatform(player: Player): PlayerPlatform {
        return if (isBedrockPlayer(player)) {
            PlayerPlatform.BEDROCK
        } else {
            PlayerPlatform.JAVA
        }
    }

    /**
     * Floodgate プラグインの存在を確認する。
     *
     * @return Floodgate がインストールされている場合 true
     */
    private fun checkFloodgateAvailability(): Boolean {
        return try {
            // Floodgate プラグインの存在を確認
            val floodgatePlugin = Bukkit.getPluginManager().getPlugin("floodgate")
            if (floodgatePlugin != null && floodgatePlugin.isEnabled) {
                logger.info("Floodgate detected. Bedrock player detection enabled.")
                true
            } else {
                logger.info("Floodgate not found. All players will be treated as Java Edition.")
                false
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error checking for Floodgate", e)
            false
        }
    }

    /**
     * Floodgate API インスタンスを取得する。
     *
     * @return Floodgate API インスタンス、または取得に失敗した場合は null
     */
    private fun getFloodgateApiInstance(): Any? {
        return try {
            // Floodgate API クラスを動的にロード
            val floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            val getInstanceMethod = floodgateApiClass.getMethod("getInstance")
            getInstanceMethod.invoke(null)
        } catch (e: ClassNotFoundException) {
            logger.fine("Floodgate API class not found")
            null
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to get Floodgate API instance", e)
            null
        }
    }

    companion object {
        /**
         * プレイヤーの UUID プレフィックスで Bedrock プレイヤーを簡易判定する。
         *
         * Why: Floodgate がインストールされていない環境でも、Geyser のデフォルト設定では
         * Bedrock プレイヤーの UUID が特定のプレフィックスで始まる。
         * ただし、これは Geyser の設定に依存するため、Floodgate API の使用を推奨。
         *
         * Note: この方法は Floodgate が利用できない場合のフォールバックとして使用可能だが、
         * 信頼性は Floodgate API より低い。
         *
         * @param player 判定対象のプレイヤー
         * @return Bedrock プレイヤーの可能性がある場合 true
         */
        @Suppress("unused")
        fun isProbablyBedrockPlayer(player: Player): Boolean {
            // Geyser のデフォルト UUID プレフィックス
            // Bedrock UUID は通常 00000000-0000-0000-0009-... の形式
            val uuid = player.uniqueId.toString()
            return uuid.startsWith("00000000-0000-0000-0009-") ||
                   uuid.startsWith("00000000-0000-0000-000a-")
        }
    }
}

/**
 * プレイヤーのプラットフォーム種別。
 */
enum class PlayerPlatform {
    /**
     * Java Edition プレイヤー。
     */
    JAVA,

    /**
     * Bedrock Edition プレイヤー（Geyser/Floodgate 経由）。
     */
    BEDROCK
}
