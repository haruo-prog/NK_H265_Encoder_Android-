# NK H.265 Encoder for Android

MP4 / AVI をインポートし、MP4 H.265（HEVC）へ変換するAndroidアプリです。

## Version

- App version: 1.1.0
- compileSdk: 36
- targetSdk: 36
- Android 16 / API 36 世代対応
- 16KBページサイズ対応FFmpegランタイム同梱

Android 16はAPI 36世代として扱われます。Android 15以降ではネイティブライブラリの16KBページサイズ対応が重要になるため、このアプリでは16KB対応版のFFmpegKit系ランタイムをAPK内に同梱しています。

## 機能

- MP4 / AVI のインポート
- 出力形式：MP4
- 動画コーデック：H.265 / HEVC（libx265）
- 音声コーデック：AAC
- 解像度プリセット
  - 高解像度：1920×1080 上限
  - 中解像度：1280×720 上限
  - 低解像度：854×480 上限
- Androidのファイル選択画面から動画を選択
- 変換後、Movies / NK Encoder に保存
- FFmpeg / libx265 / AAC エンコードに必要なランタイムをAPKに同梱
- arm64-v8a / armeabi-v7a / x86 / x86_64 を対象ABIとして同梱

## ビルド方法

1. Android Studioでこのフォルダを開く
2. Gradle Syncを実行
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)` を実行
4. 生成されたAPKをAndroid端末へ転送してインストール

## GitHub ActionsでAPKを作る場合

このリポジトリでは `.github/workflows/build-apk.yml` により、pushまたは手動実行でdebug APKを生成します。

## 注意

- 初回ビルド時にMaven CentralからFFmpegライブラリを取得します。
- H.265変換は端末性能に強く依存します。長い動画では時間がかかります。
- AVI入力対応のためFFmpeg系ライブラリを使用しています。
- 商用配布する場合は、FFmpegおよび同梱ライブラリのライセンス確認を行ってください。

## 主要ファイル

- `app/src/main/java/jp/co/nkts/encoder/MainActivity.java`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `.github/workflows/build-apk.yml`
