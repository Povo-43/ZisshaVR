録画一覧の「共有」でクラッシュする問題を修正しました。

## 原因
`FileProvider` に登録された共有可能パスが `files/recordings/` だけでしたが、録画ファイルは
`files/Movies/recordings/` に保存されるため、共有URIを作れず
`IllegalArgumentException: Failed to find configured root that contains .../files/Movies/recordings/...`
でクラッシュしていました。

## 修正
- `res/xml/file_paths.xml` を次のように拡張:
  - `external-files-path`(外部アプリ領域全体) … `Movies/recordings/` を含む
  - `files-path`(内部領域) … 外部が使えない場合のフォールバック保存先
  - `cache-path`
- 共有処理を`try/catch`で保護し、失敗しても**クラッシュせず**トーストで理由を表示。

## 備考
- このクラッシュは「録画ファイルが正常に作成されている」ことの裏返しでもあります
  (v0.1.17でエンコーダSurfaceTextureの受け渡しを修正した効果)。
- 共有以外の操作(削除・再読み込み)は従来どおりです。
