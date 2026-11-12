実機フィードバック(v0.1.0)への修正対応。

## 修正

### クラッシュ
- **Android 12+でBLE Peripheral起動時にクラッシュ**(`SecurityException: Need android.permission.BLUETOOTH_CONNECT`): 権限ダイアログの応答を待たずにGATTサーバーを開いていた問題。権限が揃うまで起動を遅延し、許可後に自動開始。`openGattServer`/`startAdvertising`も保護
- リモコンアプリのBLEスキャンも同様に権限ガードを追加
- `RECORD_AUDIO`未許可時は録画/配信をビデオのみで継続(クラッシュしない)

### 映像が真っ暗になる問題(2件)
1. **合成パスが背景を上書き**: 合成シェーダの`uBg`サンプラに背景テクスチャがバインドされておらず、アバター層で背景を完全に上書きしプレビューが黒くなる問題。背景→マスク付きアバター層のpremultiplied合成に方式を変更
2. **PRIVACY画像の競合**: GLスレッド初期化前にセットされたPRIVACY画像が破棄され黒画面になる競合を修正(初期化後に適用)

### Safety Gate運用性
- PRIVACYモードでもトラッキングを継続し、0.5秒の正常検知で**LIVEボタンから復帰可能**に(従来はPRIVACYから抜けられなかった)
- 復帰待ちの状態をUIに表示

### UI
- メイン/設定/録画一覧/リモコンアプリを**日本語表記**に
- デフォルトのプライバシー画像も日本語表記
- FREEZE/PRIVACYのトリガー理由を日本語で表示(例: 「トラッキング中断」「信頼度低下」)

## 確認事項(実機)
- カメラの向きが逆さま/反転する場合は端末のsensorOrientation差異です → `GLRenderer.rotationQuarterTurns` の調整が必要な可能性があります
