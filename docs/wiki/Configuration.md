# 設定ファイル

PaperDrawers の全設定は `plugins/PaperDrawers/config.yml` に集約されています。サーバー起動時に自動生成され、`/drawer reload` コマンドで再読み込みが可能です。

---

## config.yml の全設定項目

### general（一般設定）

| キー | 型 | デフォルト値 | 説明 |
|------|-----|:----------:|------|
| `general.drawer-material` | String | `BARREL` | ドロワーブロックの外見となるブロック素材 |
| `general.debug` | Boolean | `false` | デバッグログの有効化。`/drawer debug` でも切り替え可能 |

---

### drawer-capacity（ドロワー容量設定）

各ティアの容量をスタック単位で設定します。1スタック = 64個（アイテムにより異なる）。

| キー | 型 | デフォルト値 | 個数換算 (x64) |
|------|-----|:----------:|:-------------:|
| `drawer-capacity.tier-1` | Integer | `32` | 2,048 |
| `drawer-capacity.tier-2` | Integer | `64` | 4,096 |
| `drawer-capacity.tier-3` | Integer | `128` | 8,192 |
| `drawer-capacity.tier-4` | Integer | `256` | 16,384 |
| `drawer-capacity.tier-5` | Integer | `512` | 32,768 |
| `drawer-capacity.tier-6` | Integer | `1024` | 65,536 |
| `drawer-capacity.tier-7` | Integer | `2048` | 131,072 |

**カスタマイズ例:**
```yaml
# Tier 1 の容量を倍にする
drawer-capacity:
  tier-1: 64
```

> **注意:** 容量を変更しても既に設置されているドロワーには反映されません。新しく設置するドロワーから適用されます。

---

### display（表示設定）

ドロワー上のアイテム表示に関する設定です。

| キー | 型 | デフォルト値 | 説明 |
|------|-----|:----------:|------|
| `display.enabled` | Boolean | `true` | ドロワー上のアイテムアイコン表示を有効化 |
| `display.update-interval` | Integer | `5` | 表示の更新間隔（tick単位、20 tick = 1秒） |
| `display.max-distance` | Integer | `32` | 表示が見える最大距離（ブロック単位） |
| `display.show-count` | Boolean | `true` | アイテム数の表示を有効化 |
| `display.count-format` | String | `compact` | 数値フォーマット。`compact` (1K, 1M) または `full` (1000, 1000000) |

**カスタマイズ例:**
```yaml
# アイテム数を完全な数字で表示し、表示距離を短くする
display:
  count-format: full
  max-distance: 16
```

---

### bedrock（統合版/Geyser 互換性設定）

| キー | 型 | デフォルト値 | 説明 |
|------|-----|:----------:|------|
| `bedrock.fallback-to-armor-stand` | Boolean | `true` | 統合版プレイヤー向けに ItemDisplay の代わりに ArmorStand を使用 |

> Floodgate プラグインが検出された場合、統合版プレイヤーには ArmorStand ベースの表示に自動的にフォールバックします。Java 版プレイヤーには影響しません。

---

### performance（パフォーマンス設定）

#### cache（キャッシュ設定）

| キー | 型 | デフォルト値 | 説明 |
|------|-----|:----------:|------|
| `performance.cache.enabled` | Boolean | `true` | ドロワーキャッシュの有効化（PDC 読み取り回数を削減） |
| `performance.cache.max-size` | Integer | `1000` | キャッシュに保持するドロワーの最大数 |
| `performance.cache.expire-after-ms` | Integer | `60000` | キャッシュの有効期限（ミリ秒、60000 = 1分） |

**カスタマイズ例:**
```yaml
# 大規模サーバー向け: キャッシュを拡大
performance:
  cache:
    max-size: 5000
    expire-after-ms: 120000  # 2分
```

#### async-save（非同期保存設定）

| キー | 型 | デフォルト値 | 説明 |
|------|-----|:----------:|------|
| `performance.async-save.enabled` | Boolean | `true` | 非同期保存の有効化（メインスレッドのブロッキングを削減） |
| `performance.async-save.interval-ticks` | Integer | `100` | 保存キューの処理間隔（tick 単位、100 = 5秒） |

#### metrics（メトリクスログ）

| キー | 型 | デフォルト値 | 説明 |
|------|-----|:----------:|------|
| `performance.metrics-log-interval-minutes` | Integer | `5` | メトリクスログの出力間隔（分単位、0 で無効化） |

---

### hopper（ホッパー連携設定）

| キー | 型 | デフォルト値 | 説明 |
|------|-----|:----------:|------|
| `hopper.enabled` | Boolean | `true` | ホッパー連携の有効/無効 |
| `hopper.pull-interval-ticks` | Integer | `4` | ホッパー処理の間隔（tick 単位）。8 でバニラと同じ速度 |

**カスタマイズ例:**
```yaml
# サーバー負荷を軽減するためにバニラと同じ速度にする
hopper:
  pull-interval-ticks: 8
```

---

### messages（メッセージ設定）

ゲーム内に表示されるメッセージをカスタマイズできます。`%s` はプレースホルダーです。

#### drawer-key（ドロワーキー関連）

| キー | デフォルト値 | プレースホルダー |
|------|------------|:---------------:|
| `messages.drawer-key.locked` | `ドロワーを「%s」にロックしました！` | %s = アイテム名 |
| `messages.drawer-key.unlocked` | `ドロワーのロックを解除しました！` | なし |
| `messages.drawer-key.cannot-lock-empty` | `空のスロットはロックできません。先にアイテムを入れてください！` | なし |

#### drawer（ドロワー設置関連）

| キー | デフォルト値 |
|------|------------|
| `messages.drawer.placed` | `ドロワーを設置しました` |
| `messages.drawer.broken` | `ドロワーを破壊しました` |

#### error（エラーメッセージ）

| キー | デフォルト値 |
|------|------------|
| `messages.error.no-permission` | `権限がありません` |
| `messages.error.invalid-item` | `このアイテムは格納できません` |
| `messages.error.placement-failed` | `ドロワーの設置に失敗しました。もう一度お試しください。` |

---

### recipes（クラフトレシピ設定）

各レシピの有効/無効、形状、素材を設定します。

#### レシピの構造

```yaml
recipes:
  tier-1:
    enabled: true        # レシピの有効/無効
    shape:               # 3x3 のクラフトパターン（3行、各行3文字）
      - "PPP"
      - "PCP"
      - "PPP"
    ingredients:         # パターン文字に対応する素材
      P: TAG_PLANKS      # TAG_ プレフィックスはタグ（複数素材グループ）
      C: CHEST           # Minecraft の素材名
```

#### 特殊な素材指定

| 素材指定 | 意味 |
|---------|------|
| `TAG_PLANKS` | 全種類の板材（タグによるグループ指定） |
| `PAPER_DRAWERS_TIER_1` ～ `PAPER_DRAWERS_TIER_7` | 対応ティアのドロワーアイテム |

#### 全レシピの有効/無効デフォルト値

| レシピ | デフォルト |
|--------|:---------:|
| drawer-key | 有効 |
| tier-1 ～ tier-6 | 有効 |
| tier-7 | **無効** |
| void | 有効 |
| sorting-tier-1 ～ sorting-tier-6 | 有効 |
| sorting-tier-7 | **無効** |

---

### drawer-display（ドロワーアイテム表示設定）

ドロワーアイテムの名前と説明文をカスタマイズできます。カラーコードと プレースホルダーに対応しています。

#### カラーコード

`&` で始まるカラーコードが使用可能です。

| コード | 色 |
|:------:|:---:|
| &0 | 黒 |
| &1 | 暗い青 |
| &2 | 暗い緑 |
| &3 | 暗い水色 |
| &4 | 暗い赤 |
| &5 | 暗い紫 |
| &6 | 金色 |
| &7 | 灰色 |
| &8 | 暗い灰色 |
| &9 | 青 |
| &a | 緑 |
| &b | 水色 |
| &c | 赤 |
| &d | ピンク |
| &e | 黄色 |
| &f | 白 |

#### プレースホルダー

| プレースホルダー | 説明 |
|-----------------|------|
| `{slots}` | スロット数 |
| `{capacity_per_slot}` | スロットあたりの容量（スタック数） |
| `{total_capacity}` | 総容量（スタック数） |

#### 設定例

```yaml
drawer-display:
  tier-1:
    name: "&f基本のドロワー"
    lore:
      - ""
      - "&7スロット数: &f{slots}"
      - "&7スロット容量: &f{capacity_per_slot} スタック"
      - "&7総容量: &f{total_capacity} スタック"
      - ""
      - "&8設置してストレージドロワーを作成"
```

#### 全ティアのデフォルト表示名

| ティア | 表示名 | カラー |
|:------:|-------|:------:|
| 1 | 基本のドロワー | &f (白) |
| 2 | 銅のドロワー | &6 (金色) |
| 3 | 鉄のドロワー | &7 (灰色) |
| 4 | 金のドロワー | &e (黄色) |
| 5 | ダイヤのドロワー | &b (水色) |
| 6 | ネザライトのドロワー | &4 (暗い赤) |
| 7 | クリエイティブドロワー | &d (ピンク) |
| Void | ボイドドロワー | &8 (暗い灰色) |

仕分けドロワーには「基本の仕分けドロワー」のように「仕分け」が付き、「隣接コンテナからアイテムを自動収集」の説明行が追加されます。

---

[< コマンド](Commands.md) | [ホーム](Home.md) | [次へ: よくある質問 >](FAQ.md)
