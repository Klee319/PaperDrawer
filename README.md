# PaperDrawers

Storage Drawers Mod を Paper プラグインとして再現したプロジェクトです。
Geyser 経由の Bedrock Edition プレイヤーにも対応しています。

## 機能

### ドロワーティア

全7段階のティアがあり、上位ティアほど大容量です。容量は `config.yml` でカスタマイズ可能。

| ティア | 名前 | デフォルト容量 | クラフト素材 |
|--------|------|---------------|-------------|
| Tier 1 | 基本のドロワー | 32 スタック | 木材 + チェスト |
| Tier 2 | 銅のドロワー | 64 スタック | 銅インゴット + Tier 1 |
| Tier 3 | 鉄のドロワー | 128 スタック | 鉄インゴット + Tier 2 |
| Tier 4 | 金のドロワー | 256 スタック | 金インゴット + Tier 3 |
| Tier 5 | ダイヤのドロワー | 512 スタック | ダイヤモンド + Tier 4 |
| Tier 6 | ネザライトのドロワー | 1024 スタック | ネザライトインゴット + Tier 5 |
| Tier 7 | クリエイティブドロワー | 2048 スタック | デフォルト無効 |

### ドロワーキー

スロットを特定のアイテムタイプにロックする特殊アイテムです。
ロックされたスロットは空になっても指定アイテムのみ受け付けます。

- **クラフト**: 金塊 + 棒 = ドロワーキー 3個
- **コマンド**: `/drawer key [amount]`

### 操作方法

| 操作 | アクション |
|------|----------|
| 右クリック（正面） | 手持ちアイテムを1つ挿入 |
| Shift + 右クリック | 手持ちスタック全体を挿入 |
| ダブル右クリック | インベントリ内の同種アイテムを全て挿入 |
| 空手で右クリック | インベントリからドロワー内のアイテムを1つ挿入 |
| 左クリック（正面中央） | 1アイテム取り出し |
| Shift + 左クリック | 1スタック取り出し |
| ドロワーキー + 右クリック | スロットをロック/アンロック |
| 左クリック（正面の縁） | ドロワーを破壊 |
| 左クリック（正面以外） | ドロワーを破壊 |

### シュルカーボックス式アイテム保持

破壊したドロワーはアイテム内に中身を保持します。再設置時に内容が復元されます。

### Geyser/Bedrock 対応

- Java Edition: ItemDisplay エンティティで高品質な表示
- Bedrock Edition: ArmorStand によるフォールバック表示
- Floodgate API で自動的にプレイヤーを判別

### 保護機能

- 爆発（TNT、クリーパー等）からドロワーを保護
- ピストンによるドロワーの移動を防止

## 動作要件

- **Minecraft**: 1.21.x
- **サーバー**: Paper 1.21.1+
- **Java**: 21+
- **オプション**: Floodgate (Bedrock プレイヤー対応)

## インストール

1. Releases から最新の JAR をダウンロード
2. `plugins/` フォルダに配置
3. サーバーを再起動

## コマンド

エイリアス: `/drawer`, `/drawers`, `/pd`

| コマンド | 説明 | 権限 |
|---------|------|------|
| `/drawer give <player> <type> [amount]` | ドロワーアイテムを付与 | `paperdrawers.give` |
| `/drawer key [amount]` | ドロワーキーを取得 | `paperdrawers.give` |
| `/drawer reload` | 設定をリロード | `paperdrawers.admin` |
| `/drawer info` | プラグイン情報を表示 | `paperdrawers.use` |
| `/drawer debug` | デバッグモードを切り替え | `paperdrawers.admin` |

### ドロワータイプの指定

```
/drawer give Steve tier1       # Tier 1 ドロワー
/drawer give Steve basic       # Tier 1 ドロワー (別名)
/drawer give Steve copper      # Tier 2 ドロワー
/drawer give Steve diamond 64  # Tier 5 ドロワー 64個
```

有効なタイプ名: `tier1`〜`tier7`, `basic`, `copper`, `iron`, `gold`, `diamond`, `netherite`, `creative`

## 権限

| 権限 | 説明 | デフォルト |
|------|------|-----------|
| `paperdrawers.use` | ドロワーの使用と基本コマンド | 全員 |
| `paperdrawers.give` | ドロワーアイテム/キーの付与 | OP |
| `paperdrawers.admin` | 管理コマンド (reload, debug) | OP |

## 設定 (config.yml)

初回起動時に自動生成されます。`/drawer reload` でサーバー再起動なしに反映可能。

```yaml
# 一般設定
general:
  drawer-material: BARREL     # ドロワーのベースブロック
  debug: false                # デバッグログ

# ティアごとの容量（スタック単位）
drawer-capacity:
  tier-1: 32
  tier-2: 64
  tier-3: 128
  tier-4: 256
  tier-5: 512
  tier-6: 1024
  tier-7: 2048

# 表示設定
display:
  enabled: true               # 表示システムの有効化
  update-interval: 5          # 更新間隔 (tick)
  max-distance: 32            # 最大表示距離
  show-count: true            # 数量表示
  count-format: compact       # compact (1K, 1M) / full (1000)

# Bedrock 互換性
bedrock:
  fallback-to-armor-stand: true

# パフォーマンス設定
performance:
  cache:
    enabled: true
    max-size: 1000
    expire-after-ms: 60000
  async-save:
    enabled: true
    interval-ticks: 100
  metrics-log-interval-minutes: 5

# メッセージ（日本語デフォルト、カスタマイズ可能）
messages:
  drawer-key:
    locked: "ドロワーを「%s」にロックしました！"
    unlocked: "ドロワーのロックを解除しました！"
    cannot-lock-empty: "空のスロットはロックできません。先にアイテムを入れてください！"

# クラフトレシピ（全て enabled/disabled 切り替え可能）
recipes:
  drawer-key:
    enabled: true
    result-amount: 3
    shape: [" G ", " S ", " S "]
    ingredients: { G: GOLD_NUGGET, S: STICK }
  tier-1:
    shape: ["PPP", "PCP", "PPP"]
    ingredients: { P: OAK_PLANKS, C: CHEST }
  tier-2:
    shape: [" I ", "IDI", " I "]
    ingredients: { I: COPPER_INGOT, D: PAPER_DRAWERS_TIER_1 }
  # ... tier-3 〜 tier-7 も同様のパターン

# ドロワーアイテム表示名・説明文（カラーコード&プレースホルダー対応）
drawer-display:
  tier-1:
    name: "&f基本のドロワー"
    lore:
      - "&7スロット数: &f{slots}"
      - "&7スロット容量: &f{capacity_per_slot} スタック"
      - "&7総容量: &f{total_capacity} スタック"
```

## ビルド

### 必要なツール

- JDK 21+
- Gradle 8.5+

### ビルド手順

```bash
cd paper-drawers
./gradlew build
# 出力: build/libs/paper-drawers-1.0.0-SNAPSHOT.jar
```

## アーキテクチャ

クリーンアーキテクチャ / DDD を採用しています。

```
src/main/kotlin/com/example/paperdrawers/
├── domain/                 # ドメイン層
│   ├── model/             # エンティティ、値オブジェクト
│   └── repository/        # リポジトリインターフェース
├── application/           # アプリケーション層
│   └── usecase/          # ユースケース
├── infrastructure/        # インフラストラクチャ層
│   ├── cache/            # キャッシュ
│   ├── config/           # 設定管理
│   ├── debug/            # メトリクス・デバッグ
│   ├── display/          # 表示システム (Strategy Pattern)
│   ├── item/             # アイテムファクトリ
│   ├── message/          # メッセージ管理
│   ├── persistence/      # データ永続化 (CustomBlockData)
│   ├── platform/         # プラットフォーム検出
│   └── recipe/           # クラフトレシピ管理
└── presentation/          # プレゼンテーション層
    └── listener/         # イベントリスナー
```

## 技術スタック

- **言語**: Kotlin 1.9.x
- **ビルド**: Gradle (Kotlin DSL)
- **API**: Paper 1.21.x (paperweight-userdev)
- **永続化**: [CustomBlockData](https://github.com/mfnalex/CustomBlockData)
- **Bedrock検出**: [Floodgate API](https://geysermc.org/wiki/floodgate/api/)

## 依存関係

| ライブラリ | 用途 | 種別 |
|-----------|------|------|
| Paper API | サーバー API | compileOnly |
| CustomBlockData | ブロックの PDC 永続化 | implementation |
| Floodgate API | Bedrock プレイヤー検出 | compileOnly (optional) |

## ライセンス

MIT License

## クレジット

- [Storage Drawers](https://www.curseforge.com/minecraft/mc-mods/storage-drawers) - オリジナル Mod
- [CustomBlockData](https://github.com/mfnalex/CustomBlockData) - ブロック PDC ライブラリ
- [GeyserMC](https://geysermc.org/) - Bedrock 互換性
