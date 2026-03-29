package com.example.paperdrawers

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit

/**
 * PaperDrawers プラグインのブートストラップクラス。
 *
 * Why: Paper プラグインでは YAML ベースのコマンド定義がサポートされていないため、
 * PluginBootstrap を使用してコマンドをプログラムで登録する必要がある。
 * Brigadier コマンドシステムを使用してコマンドを定義する。
 */
@Suppress("UnstableApiUsage")
class PaperDrawersBootstrap : PluginBootstrap {

    override fun bootstrap(context: BootstrapContext) {
        val lifecycleManager: LifecycleEventManager<BootstrapContext> = context.lifecycleManager

        // Register commands using Brigadier
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val commands: Commands = event.registrar()

            // Register /drawer command with subcommands
            commands.register(
                Commands.literal("drawer")
                    .then(
                        // /drawer give <player> <type> [amount]
                        Commands.literal("give")
                            .requires { source -> source.sender.hasPermission("paperdrawers.give") }
                            .then(
                                Commands.argument("player", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        Bukkit.getOnlinePlayers().forEach { player ->
                                            builder.suggest(player.name)
                                        }
                                        builder.buildFuture()
                                    }
                                    .then(
                                        Commands.argument("type", StringArgumentType.word())
                                            .suggests { _, builder ->
                                                // Tier番号による指定
                                                listOf("tier1", "tier2", "tier3", "tier4", "tier5", "tier6", "tier7").forEach {
                                                    builder.suggest(it)
                                                }
                                                // 素材名による指定
                                                listOf("basic", "copper", "iron", "gold", "diamond", "netherite", "creative", "void").forEach {
                                                    builder.suggest(it)
                                                }
                                                // 仕分けドロワー
                                                listOf("sorting-tier1", "sorting-tier2", "sorting-tier3", "sorting-tier4", "sorting-tier5", "sorting-tier6", "sorting-tier7").forEach {
                                                    builder.suggest(it)
                                                }
                                                // ドロワーキー
                                                builder.suggest("key")
                                                builder.buildFuture()
                                            }
                                            .executes { ctx ->
                                                val playerName = StringArgumentType.getString(ctx, "player")
                                                val type = StringArgumentType.getString(ctx, "type")
                                                executeGiveCommand(ctx.source, playerName, type, 1)
                                                1
                                            }
                                            .then(
                                                Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                                    .executes { ctx ->
                                                        val playerName = StringArgumentType.getString(ctx, "player")
                                                        val type = StringArgumentType.getString(ctx, "type")
                                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                                        executeGiveCommand(ctx.source, playerName, type, amount)
                                                        1
                                                    }
                                            )
                                    )
                            )
                    )
                    .then(
                        // /drawer reload
                        Commands.literal("reload")
                            .requires { source -> source.sender.hasPermission("paperdrawers.admin") }
                            .executes { ctx ->
                                executeReloadCommand(ctx.source)
                                1
                            }
                    )
                    .then(
                        // /drawer info
                        Commands.literal("info")
                            .requires { source -> source.sender.hasPermission("paperdrawers.use") }
                            .executes { ctx ->
                                executeInfoCommand(ctx.source)
                                1
                            }
                    )
                    .then(
                        // /drawer debug
                        Commands.literal("debug")
                            .requires { source -> source.sender.hasPermission("paperdrawers.admin") }
                            .executes { ctx ->
                                executeDebugCommand(ctx.source)
                                1
                            }
                    )
                    .build(),
                "PaperDrawers main command",
                listOf("drawers", "pd")
            )
        }
    }

    private fun executeGiveCommand(source: CommandSourceStack, playerName: String, type: String, amount: Int) {
        val plugin = getPlugin()
        if (plugin == null) {
            source.sender.sendMessage("§cPlugin not loaded yet")
            return
        }

        val player = Bukkit.getPlayer(playerName)
        if (player == null) {
            source.sender.sendMessage("§cPlayer '$playerName' not found")
            return
        }

        // ドロワーキーの場合
        if (type.equals("key", ignoreCase = true)) {
            val keyItem = com.example.paperdrawers.infrastructure.item.DrawerKeyFactory.createDrawerKey(amount)
            player.inventory.addItem(keyItem)
            source.sender.sendMessage("§aGave ${amount}x Drawer Key to ${player.name}")
            return
        }

        // 仕分けドロワーの場合: sorting-tierX 形式
        val isSorting = type.startsWith("sorting-", ignoreCase = true)
        val actualType = if (isSorting) type.removePrefix("sorting-").removePrefix("Sorting-") else type

        val drawerType = parseDrawerType(actualType)
        if (drawerType == null) {
            source.sender.sendMessage("§cInvalid drawer type: $type")
            source.sender.sendMessage("§7Valid types: tier1-tier7, basic, copper, iron, gold, diamond, netherite, creative, void, sorting-tier1-7, key")
            return
        }

        val factory = com.example.paperdrawers.infrastructure.item.DrawerItemFactory
        val item = factory.createDrawerItem(drawerType, amount, isSorting = isSorting)
        player.inventory.addItem(item)

        val prefix = if (isSorting) "Sorting " else ""
        source.sender.sendMessage("§aGave ${amount}x ${prefix}${drawerType.name} Drawer to ${player.name}")
    }

    private fun executeReloadCommand(source: CommandSourceStack) {
        val plugin = getPlugin()
        if (plugin == null) {
            source.sender.sendMessage("§cPlugin not loaded yet")
            return
        }

        plugin.reloadPluginConfig()
        source.sender.sendMessage("§aPaperDrawers configuration reloaded!")
    }

    private fun executeInfoCommand(source: CommandSourceStack) {
        val plugin = getPlugin()
        if (plugin == null) {
            source.sender.sendMessage("§cPlugin not loaded yet")
            return
        }

        source.sender.sendMessage("§6=== PaperDrawers Info ===")
        source.sender.sendMessage("§7Version: §f${plugin.description.version}")
        source.sender.sendMessage("§7Server: §f${Bukkit.getServer().minecraftVersion}")
        source.sender.sendMessage("§7Floodgate: §f${if (plugin.platformDetector.isFloodgateAvailable()) "Available" else "Not found"}")

        plugin.getCacheStats()?.let { stats ->
            source.sender.sendMessage("§7Cache: §f$stats")
        }

        plugin.getAsyncRepositoryStats()?.let { stats ->
            source.sender.sendMessage("§7AsyncRepo: §fpending=${stats.pendingSaveCount}")
        }
    }

    private fun executeDebugCommand(source: CommandSourceStack) {
        val plugin = getPlugin()
        if (plugin == null) {
            source.sender.sendMessage("§cPlugin not loaded yet")
            return
        }

        val config = plugin.config
        val currentDebug = config.getBoolean("general.debug", false)
        config.set("general.debug", !currentDebug)
        plugin.saveConfig()

        val newState = if (!currentDebug) "§aenabled" else "§cdisabled"
        source.sender.sendMessage("§6Debug mode $newState")
    }

    /**
     * ドロワータイプ文字列をDrawerType enumに変換する。
     *
     * Why: DrawerType.fromNameOrAlias()を使用して、Tier番号（tier1-tier7）と
     * 素材名エイリアス（basic, copper, iron, gold, diamond, netherite, creative）の
     * 両方に対応する。
     */
    private fun parseDrawerType(type: String): com.example.paperdrawers.domain.model.DrawerType? {
        return com.example.paperdrawers.domain.model.DrawerType.fromNameOrAlias(type)
    }

    private fun getPlugin(): PaperDrawersPlugin? {
        return try {
            Bukkit.getPluginManager().getPlugin("PaperDrawers") as? PaperDrawersPlugin
        } catch (e: Exception) {
            null
        }
    }
}
