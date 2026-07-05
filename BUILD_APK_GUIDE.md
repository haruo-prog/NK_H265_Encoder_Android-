# APK生成手順

## Android Studioで作る場合

1. Android Studioを起動
2. `Open` から `NK_H265_Encoder_Android` フォルダを選択
3. 右上または下部に表示される `Sync Now` を押す
4. メニューから `Build > Build Bundle(s) / APK(s) > Build APK(s)`
5. 完了後、`app/build/outputs/apk/debug/app-debug.apk` が生成されます

## Android端末に入れる場合

1. APKをスマホへコピー
2. Android側で「提供元不明のアプリを許可」
3. APKをタップしてインストール

## リリース配布する場合

- 署名付きAPKまたはAABを作成してください。
- `Build > Generate Signed Bundle / APK` から作成できます。
