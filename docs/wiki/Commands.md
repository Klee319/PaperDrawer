# コマンド

PaperDrawers のメインコマンドは `/drawer` です。エイリアスとして `/drawers` および `/pd` も使用可能です。

## コマンド一覧

### /drawer give \<player\> \<type\> [amount]

指定したプレイヤーにドロワーアイテムを付与します。

| パラメータ | 説明 | 必須 |
|-----------|------|:----:|
| player | 付与先のプレイヤー名 | はい |
| type | ドロワーの種類（下記参照） | はい |
| amount | 個数（1～64、省略時は1） | いいえ |

**必要権限:** `paperdrawers.give`

#### type に指定できる値

| カテゴリ | 値 |
|---------|-----|
| ティア番号 | `tier1`, `tier2`, `tier3`, `tier4`, `tier5`, `tier6`, `tier7` |
| 素材名 | `basic`, `copper`, `iron`, `gold`, `diamond`, `netherite`, `creative` |
| 数字 | `1`, `2`, `3`, `4`, `5`, `6`, `7` |
| 特殊 | `void` |
| 仕分けドロワー | `sorting-tier1` ～ `sorting-tier7` |

**使用例:**
```
/drawer give Steve tier3 5       -- Steve に鉄のドロワーを5個
/drawer give Alex diamond        -- Alex にダイヤのドロワーを1個
/drawer give Steve void 3        -- Steve にボイドドロワーを3個
/drawer give Alex sorting-tier2  -- Alex に銅の仕分けドロワーを1個
```

---

### /drawer key [amount]

自分自身にドロワーキーを付与します（プレイヤーのみ実行可能）。

| パラメータ | 説明 | 必須 |
|-----------|------|:----:|
| amount | 個数（1～64、省略時は1） | いいえ |

**必要権限:** `paperdrawers.give`

**使用例:**
```
/drawer key       -- ドロワーキーを1個取得
/drawer key 10    -- ドロワーキーを10個取得
```

---

### /drawer reload

設定ファイルを再読み込みします。サーバーを再起動せずに設定変更を反映できます。

**必要権限:** `paperdrawers.admin`

---

### /drawer info

プラグインの情報を表示します。

表示内容:
- プラグインバージョン
- サーバーの Minecraft バージョン
- Floodgate の利用可否
- キャッシュの状態
- 非同期リポジトリの状態（保留中の保存数）

**必要権限:** `paperdrawers.use`

---

### /drawer debug

デバッグモードの有効/無効を切り替えます。デバッグモードが有効な場合、詳細なログがコンソールに出力されます。

**必要権限:** `paperdrawers.admin`

---

## 権限一覧

| 権限ノード | 説明 | デフォルト |
|-----------|------|:---------:|
| `paperdrawers.use` | ドロワーの使用と基本コマンド（info） | 全プレイヤー |
| `paperdrawers.give` | ドロワーアイテムの付与（give, key） | OP のみ |
| `paperdrawers.admin` | 管理コマンド（reload, debug） | OP のみ |

---

[< ホッパー連携](Hopper-Integration.md) | [ホーム](Home.md) | [次へ: 設定ファイル >](Configuration.md)
