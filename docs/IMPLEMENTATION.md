# 実装状況(仕様書との対応)

仕様書 15章の品質管理ルールに従い、**未実装の機能を実装済みとは報告しません**。以下は完成判定(17章)と各機能の実際の状態です。

最終更新: v0.1.2

## 完成判定チェックリスト(17章)

| # | 判定項目 | 状態 | 備考 |
|---|---|---|---|
| 1 | Android単体でカメラを起動 | ✅ 実装 | CameraX Preview → 自前EGL/GLスレッドのSurfaceTexture |
| 2 | GPUを利用してトラッキング | ✅ 実装 | MediaPipe GPU delegate(CPUフォールバック付き) |
| 3 | Pose / Face / Hand が動作 | ✅ 実装 | 各Task独立・VIDEOモード・タイムスタンプ紐付け |
| 4 | VRM 0.x / 1.0 を読み込める | ✅ 実装 | 自前パーサ(GLB/JSON、humanoid・表情・SpringBone) |
| 5 | 身体の動きがアバターに反映 | ✅ 実装 | 方向ベースFK + 髪・スカートSpringBone + 手指マッピング |
| 6 | 人物だけをアバターに置き換え | ✅ 実装 | セルフィーセグメンテーションマスク合成(時間平滑+ぼかし) |
| 7 | 実写背景が維持される | ✅ 実装 | 背景はカメラOESテクスチャをそのまま合成 |
| 8 | MP4録画 | ✅ 実装 | MediaCodec(H.264/AAC)+ MediaMuxer(RootEncoder経由) |
| 9 | RTMP配信 | ✅ 実装 | RootEncoder RtmpStream、再接続・ビットレート変更・状態表示 |
| 10 | 録画と配信に同一の合成映像 | ✅ 実装 | 単一合成FBO → プレビュー/エンコーダへ同一フレーム |
| 11 | FREEZEが動作 | ✅ 実装 | 安全フレームリングバッファ2枚(仕様7.2の1〜2フレーム遅延相当) |
| 12 | PRIVACYが動作 | ✅ 実装 | ユーザー指定画像/デフォルト生成画像。録画・配信にも同じ画像 |
| 13 | 異常時に未加工カメラ映像を出力しない | ✅ 実装 | 出力ソース選択は合成/凍結/PRIVACYの3択のみ。トラッキング継続で復帰検知 |
| 14 | BLEリモコンから操作 | ✅ 実装 | 別アプリ(`:remote`)、GATTサーバー+ペアリング+6桁トークン |
| 15 | LODによる負荷調整 | ✅ 実装 | 人物サイズ推定+ヒステリシス、Task実行対象・頻度を制御 |
| 16 | 長時間稼働(リーク無し) | ⚠️ 実機検証必要 | リーク防止設計(スレッド/タスク/テクスチャ解放)済み。実機での長時間検証は未実施 || 17 | 温度上昇時に負荷調整 | ✅ 実装 | PowerManager ThermalStatus → 5段階 governors(推論停止→頻度→FPS) |

## 機能別の詳細と既知の制限

### アバター(:avatar)
- 対応: `.vrm`(0.x / 1.0), `.glb`, `.gltf`(埋め込みバッファ/データURIのみ。**外部ファイル参照の.gltfは非対応**)
- Humanoid Bone Mapping(VRM0/1.0の名称正規化あり)、表情プリセット(VRM0の a/i/u/e/o, joy/sorrow/fun も1.0名に正規化)、LookAt(ブレンドシェイプ経由)、SpringBone(UniVRM互換のVerlet+球コライダー)
- スキニング: ボーン行列テキスチャ(RGBA32F)+ GPU頂点シェーダ。モーフターゲット(POSITION)はCPU適用→差分のみ再アップロード
- **制限**: MToonマテリアルは簡易ランバート+リム近似。アニメーション(VRMA/BVH/VMD)は未対応(仕様では優先度「中」以下)。UV変形モーフ・マテリアルカラー バインド未対応

### トラッキング(:tracking)
- One Euro Filter、Confidence Gate、Velocity Gate、欠損ランドマークの予測補間、ワープ検知、ロスト検知
- 距離推定: 画面内占有率+肩幅+目間隔の重み付け合成(単眼カメラのため相対値)
- 入力は合成視点に合わせた回転・ミラー済みの320×180フレーム(GLから小容量readback。仕様6.1の「必要な場合に限定する」転送)

### セグメンテーション
- selfie_segmenter(256×256確信度マスク)→ 時間EMA → 分離ガウシアン → バイリニアアップスケール
- 髪の毛・指の間の境界はマスク解像度の限界がある(仕様5「可能な限り」の範囲)

### 映像パイプライン(:gl)
- OES → trackingFBO(readback) / avatarFBO(透明+深度) / compositeFBO → ゲート選択 → 各出力
- 出力はプレビュー・録画・配信で同一合成フレーム(仕様6.3)
- CPU Bitmap変換はトラッキング入力(320×180)とPRIVACY画像アップロードのみ

### 録画/配信(:stream/:record)
- RootEncoder 2.8.2 を利用。録画もRTMP配信も同一エンコードパス(MediaCodec surface入力)
- 720p/1080p、24/30/60fps設定、AAC 44.1kHz、ビットレートは配信中変更可
- 保存先はアプリ専用ディレクトリ → 録画完了後にMediaStoreへ登録(Movies/ZisshaVR)。録画一覧画面で共有/削除
- **制限**: ユーザー指定フォルダへの直接書き込みは未対応(MediaStore登録で代替)。アプリ強制終了時のMP4復旧はライブラリ依存(moov未書き込みの不完全ファイルの可能性)

### BLEリモコン(:ble, :remote, :protocol)
- メイン端末=Peripheral(アドバタイズ+GATTサーバー)、リモコン=Central
- コマンド: SET_MODE(LIVE/FREEZE/PRIVACY), RECORD_START/STOP, STREAM_START/STOP, GET_STATUS, SET_BITRATE, PING/PONG
- 認証: プラットフォームのLEペアリング暗号化(ENCRYPT権限特性)+ 6桁セッショントークン
- ステータスは1秒間隔でnotify、コマンドフレームはCRC16で保護(単体テストあり)
- **制限**: リモコンからの詳細設定UI(仕様10.2「〜したい」)はビットレート変更まで。サーマル/LODの遠隔変更は未対応

### 省電力・熱対策(:core PerformanceGovernor)
- ThermalStatus(LIGHT〜CRITICAL)→ レベル0〜4:
  1. Face/Hand推論停止・ stride 2x
  2. セグメンテーション 1/3頻度
  3. 全推論 1/2頻度 + 描画30fps制限
  4. 描画15fps制限 + LOD0固定
- 安全機能(ゲート)は省電力でも無効化されない

### UI(:ui デザインシステム・UI再構築)
- `:app` と `:remote` が共通モジュール `:ui` を参照(Material 3テーマ + 共通コンポーネント `StatusPill` / `SectionCard` / `ToggleRow` / `InfoRow` / `ChoiceChips`)
- システムのライト/ダーク設定に追従(`values` / `values-night` の `windowBackground` + Compose `isSystemInDarkTheme()`)
- 出力モード(LIVE/FREEZE/PRIVACY)・健全性・警告の色を意味色 `ZisshaTheme.semantic` として体系化
- メイン画面: プレビューは従来どおりSurfaceView(描画は専用GLスレッド、UI再描画は映像処理に影響しない=仕様12)。状態表示はプレビュー上のオーバーレイと下部の統計行に分離し、LIVE/FREEZE/PRIVACYを大型ボタンで配置(仕様7.4)。カメラバインド失敗時は再試行UI、`Engine.lastError` はSnackbarで表示
- 設定画面: トピック別カード化、アバター/プライバシー/RTMPの状態明示、アバター削除の確認ダイアログ、ストリームキーのマスク表示(表示切替)、保存時のフィードバック
- 録画一覧: 明示的な再読み込み、削除の確認ダイアログ、空状態の案内
- リモコン: 接続・認証・出力モード・録画/配信・状態をカード化。FREEZE/PRIVACYは大きく分離配置(仕様10.2)
- **未検証**: 実機での視認性・操作性・ダーク/ライト双方の表示確認は未実施(CIでのビルド確認のみ)

## ライセンス記録(仕様15.10)

| コンポーネント | バージョン | ライセンス |
|---|---|---|
| MediaPipe Tasks Vision | 0.10.14 | Apache-2.0 |
| RootEncoder (pedroSG94) | 2.8.2 | Apache-2.0 |
| Jetpack Compose / CameraX / DataStore / Navigation | BOM 2024.10.01 ほか | Apache-2.0 |
| Kotlin / Coroutines / kotlinx.serialization | 2.0.21 / 1.9.0 / 1.7.3 | Apache-2.0 |
| 同梱モデル: pose_landmarker_lite / face_landmarker / hand_landmarker / selfie_segmenter | float16 | MediaPipeモデル利用規約(terms-of-service)に従う |

## 実機テスト(仕様15.6)

CI(ubuntu-latest + JDK17 + Android SDK 35)での検証状況:

- ✅ `:protocol` 単体テスト(CRC16、フレームコーデック、JSONステータス)9件グリーン
- ✅ `:app` 単体テスト(Quaternion/Mat4数値、One Euro Filter特性)14件グリーン
- ✅ `:app` / `:remote` デバッグAPKビルド成功(compileSdk 35 / minSdk 26)
- ✅ リリースAPK署名ビルド(CI上のシークレットkeystore)

実機フィードバックで修正済み(v0.1.1):

- Android 12+でのBLUETOOTH_CONNECT権限未取得時のGATTサーバー起動クラッシュ → 権限取得まで遅延+許可後に自動開始
- 合成パスがカメラ背景を上書きしプレビューが黒くなる問題(uBgサンプラ未バインド)→ premultipliedオーバーレイ方式に変更
- PRIVACY画像がGL初期化前に破棄される競合(黒画面)→ 初期化後適用
- PRIVACYモードから復帰できない問題 → 全モードでトラッキング継続+0.5秒復帰アーム

コード解析で修正(v0.1.3、実機未検証):

- **プレビュー恒久ブラック**: `GLRenderer.setPreviewSurface` がSurfaceHolder所有のSurfaceを`release()`しており、`surfaceCreated`直後の`surfaceChanged`でSurfaceが無効化 → EGLサーフェスを作れず黒。Surfaceは解放せず、同一Surfaceの再通知はスキップ、GL初期化前のSurfaceは初期化後に適用するよう変更
- **FREEZE/PRIVACYが出力に反映されない**: `glRenderer.gateMode` が更新されていなかった → セーフティゲートのモードをレンダラへ同期(仕様7)
- **アバターが原点に縮退**: ボーン行列を充填前にアップロードしていた → 充填後にアップロード
- メイン画面に描画FPS(合成出力)を追加し、推定FPS(トラッキング)と区別

**まだ未検証**の項目と確認すべき内容:

1. カメラ回転・フロントミラーの向き(端末のsensorOrientation差異)→ `GLRenderer.rotationQuarterTurns` / `PoseRigger.Z_SIGN`
2. フロントカメラのhandedness反転の妥当性
3. MediaPipe GPU delegateの動作(非対応端末はCPUに自動フォールバック)
4. 長時間配信での温度・メモリ
5. VRMファイル各種(VRM0/1.0、外部ツール書き出し差分)でのリグ表示
