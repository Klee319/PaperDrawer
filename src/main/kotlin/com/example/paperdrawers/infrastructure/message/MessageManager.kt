package com.example.paperdrawers.infrastructure.message

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * messages.yml からメッセージを読み込んで管理するクラス。
 *
 * Why: messages セクションを独立した messages.yml に分離することで、
 * 設定ファイルの可読性と保守性を向上させる。
 * 設定にない場合はデフォルトのメッセージ（日本語）を返す。
 *
 * @property plugin プラグインインスタンス
 * @property logger ログ出力用のロガー
 * @property messagesConfig messages.yml から読み込んだ設定
 */
class MessageManager(
    private val plugin: Plugin,
    private val logger: Logger,
    private val messagesConfig: YamlConfiguration?
) {

    /**
     * ドロワーキーでロックした時のメッセージを取得する。
     *
     * @param materialName ロックしたマテリアル名
     * @return ローカライズされたメッセージ
     */
    fun getDrawerKeyLocked(materialName: String): String {
        val template = messagesConfig?.getString("messages.drawer-key.locked", "ドロワーを「%s」にロックしました！")
            ?: "ドロワーを「%s」にロックしました！"
        return template.replace("%s", materialName)
    }

    /**
     * ドロワーキーでアンロックした時のメッセージを取得する。
     *
     * @return ローカライズされたメッセージ
     */
    fun getDrawerKeyUnlocked(): String {
        return messagesConfig?.getString("messages.drawer-key.unlocked", "ドロワーのロックを解除しました！")
            ?: "ドロワーのロックを解除しました！"
    }

    /**
     * 空のスロットをロックしようとした時のメッセージを取得する。
     *
     * @return ローカライズされたメッセージ
     */
    fun getCannotLockEmpty(): String {
        return messagesConfig?.getString("messages.drawer-key.cannot-lock-empty", "空のスロットはロックできません。先にアイテムを入れてください！")
            ?: "空のスロットはロックできません。先にアイテムを入れてください！"
    }

    /**
     * ドロワー設置失敗時のメッセージを取得する。
     *
     * @return ローカライズされたメッセージ
     */
    fun getPlacementFailed(): String {
        return messagesConfig?.getString("messages.error.placement-failed", "ドロワーの設置に失敗しました。もう一度お試しください。")
            ?: "ドロワーの設置に失敗しました。もう一度お試しください。"
    }

    /**
     * 権限エラーメッセージを取得する。
     *
     * @return ローカライズされたメッセージ
     */
    fun getNoPermission(): String {
        return messagesConfig?.getString("messages.error.no-permission", "権限がありません")
            ?: "権限がありません"
    }

    /**
     * 無効なアイテムエラーメッセージを取得する。
     *
     * @return ローカライズされたメッセージ
     */
    fun getInvalidItem(): String {
        return messagesConfig?.getString("messages.error.invalid-item", "このアイテムは格納できません")
            ?: "このアイテムは格納できません"
    }

    companion object {
        @Volatile
        private var instance: MessageManager? = null

        /**
         * MessageManager を初期化する。
         *
         * @param plugin プラグインインスタンス
         * @param logger ロガー
         * @param messagesConfig messages.yml から読み込んだ YamlConfiguration（null許容）
         */
        fun initialize(plugin: Plugin, logger: Logger, messagesConfig: YamlConfiguration? = null) {
            instance = MessageManager(plugin, logger, messagesConfig)
            logger.fine("MessageManager initialized")
        }

        /**
         * 初期化済みかどうかを確認する。
         */
        fun isInitialized(): Boolean = instance != null

        /**
         * MessageManager インスタンスを取得する。
         *
         * @return MessageManager インスタンス
         * @throws IllegalStateException 初期化されていない場合
         */
        fun getInstance(): MessageManager {
            return instance ?: throw IllegalStateException("MessageManager is not initialized. Call initialize() first.")
        }
    }
}
