# GitHub ActionsでAPKを自動生成する方法

このプロジェクトをGitHubリポジトリへアップロードすると、GitHub上でAPKを自動ビルドできます。

## 手順

1. GitHubでこのリポジトリを開く
2. `Actions` タブを開く
3. `Build Android APK` を選択
4. `Run workflow` を押す
5. ビルド完了後、`Artifacts` から `NK-H265-Encoder-debug-apk` をダウンロード

## 生成されるAPK

- debug APK: `app-debug.apk`

## 注意

- 初回ビルド時にAndroid Gradle PluginとFFmpegライブラリをインターネットから取得します。
- GitHub Actions上で生成されるAPKはdebug署名です。
- 本番配布用にする場合は、Android Studioで署名付きAPK/AABを作成してください。
