# ZisshaVR

Android単体で動作する、実写背景合成型リアルタイムアバター配信アプリ。
カメラ映像からMediaPipeで人物をリアルタイムトラッキングし、VRMアバターに反映。人物部分をアバターで置き換え、実写背景と合成した1枚の映像をプレビュー・MP4録画・RTMP配信します。

仕様書(`AABOUT.md`)に基づく実装です。**ビルド・テスト・リリースはすべてGitHub Actionsで実行**され、ローカルマシンにAndroid SDKは不要です。

## 構成

| モジュール | 内容 |
|---|---|
| `:app` | メインアプリ(カメラ → トラッキング → GL合成 → 録画/配信) |
| `:remote` | BLEリモコンアプリ(BLE Central) |
| `:protocol` | BLE通信プロトコル(純Kotlin、単体テストあり) |

## 技術スタック

- Kotlin / Jetpack Compose / Coroutines
- CameraX(Preview → 自前GLスレッドへSurfaceTexture供給)
- MediaPipe Tasks Vision 0.10.14(GPU delegate: Pose / Face / Hand / Selfie Segmentation)
- OpenGL ES 3.0(FBOマルチパス合成、テクスチャスキニング、GPUセグメンテーションマスク処理)
- RootEncoder 2.8.2(MediaCodec H.264 + AAC、RTMP、MediaMuxer録画)
- Android BLE GATT(Peripheral/Central、ペアリング+セッショントークン認証)
- DataStore(設定)

## ビルド

ローカルにSDKがなくてもCIがビルドします。

- **CI**: pushするたびに [.github/workflows/ci.yml](.github/workflows/ci.yml) が単体テスト+デバッグAPKをビルドし、Artifactsに保存します。
- **ローカル**: `./gradlew :app:assembleDebug :remote:assembleDebug`(Android SDKが必要)

### リリース

`v*` タグをpushすると [release workflow](.github/workflows/release.yml) が署名済みリリースAPKをビルドしてGitHub Releasesへ添付します。

署名するには以下のリポジトリSecretを設定してください(未設定の場合はdebug署名で公開):

- `RELEASE_KEYSTORE_BASE64`: keystoreのbase64(`base64 -w0 release.keystore`)
- `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## 使い方

1. メイン端末に`:app`、サブ端末に`:remote`をインストール
2. カメラ・マイク・(BLE用)Nearby devices権限を許可
3. Settings → **Import VRM / glb** でVRMファイル(.vrm / .glb / .gltf)を取り込み
4. メイン画面の **LIVE / FREEZE / PRIVACY** が出力モード(緊急時はPRIVACY)
5. RECORD / STREAM で録画・配信(RTMP URLとStream KeyはSettingsで分離保存、キーはログに出力されません)
6. リモコンアプリで6桁のペアリングトークンを入力して認証すると遠隔操作可能

## 安全設計(仕様7章)

- トラッキング信頼度低下・ワープ・タイムアウト・セグメンテーション異常・カメラ停止・内部エラーで **FREEZE**(最後の安全確認済み合成フレームを静止表示)
- 安全フレームが無い場合は **PRIVACY** 画像へ自動切替
- FREEZE/PRIVACYからの復帰は必ずユーザー操作。0.5秒以上の連続正常判定でのみLIVEボタンが有効化
- 異常時に未加工カメラ映像が出力される経路は存在しない(出力ソースは合成フレーム/凍結フレーム/PRIVACY画像の3択のみ)

## ドキュメント

- [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md): 仕様書17章の完成判定に対する実装状況(正直な対応表)とライセンス記録

## ライセンス

- 本コード: プロジェクトの定めるライセンスに従う
- 依存: MediaPipe (Apache-2.0), RootEncoder (Apache-2.0), CameraX/Compose/DataStore (Apache-2.0) — 詳細は docs/IMPLEMENTATION.md
