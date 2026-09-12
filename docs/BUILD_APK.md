# APK 打包说明

当前 Android 构建基线：

- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 29`
- Java/JDK 17
- Gradle 9.3.1
- Compose BOM `2025.12.00`（Compose 1.10 系列）

> Compose 1.12+ 要求 `compileSdk 37`。当前 GitHub Hosted Runner 的稳定 Android SDK 仓库无法安装 `platforms;android-37`，因此项目固定使用 API 36 + Compose 1.10。该组合已在 GitHub Actions 实际验证可以完成 Debug APK 构建。

## GitHub 在线手动打包

仓库已经配置：

```text
.github/workflows/build-apk.yml
```

它只响应 `workflow_dispatch`，不会在 push / PR 时自动打包。

操作：

1. 打开仓库 `liqing180/NTE-AI-Assistant2`。
2. 进入 **Actions**。
3. 选择 **Build APK**。
4. 点击 **Run workflow**。
5. 选择 `debug` 或 `release`。
6. 构建成功后，在该次 Run 页面底部 **Artifacts** 下载 APK。

云端会依次执行：

```text
Checkout
→ JDK 17
→ Android SDK 36 / Build Tools 36.0.0
→ Gradle 9.3.1
→ :core:test
→ :app:assembleDebug 或 :app:assembleRelease
→ Upload Artifact
```

Debug Artifact 中的 APK：

```text
NTE-Auction-Assistant-debug.apk
```

Release 为未签名包：

```text
NTE-Auction-Assistant-release-unsigned.apk
```

## Windows 11 本地打 Debug APK

PowerShell：

```powershell
cd D:\path\to\NTE-AI-Assistant2

$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

$AndroidSdk="$env:LOCALAPPDATA\Android\Sdk"
"sdk.dir=$($AndroidSdk -replace '\\','\\')" | Set-Content -Encoding ASCII .\local.properties
```

如果仓库中还没有可用的 Gradle Wrapper，可先生成：

```powershell
$GradleVersion="9.3.1"
$GradleZip="$env:TEMP\gradle-$GradleVersion-bin.zip"
$GradleHome="$env:TEMP\gradle-$GradleVersion"

Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $GradleZip
if (Test-Path $GradleHome) { Remove-Item $GradleHome -Recurse -Force }
Expand-Archive $GradleZip -DestinationPath $env:TEMP -Force
$env:Path="$GradleHome\bin;$env:Path"
gradle wrapper --gradle-version $GradleVersion
```

安装 Android API 36（如本机尚未安装）：

```powershell
& "$AndroidSdk\cmdline-tools\latest\bin\sdkmanager.bat" `
  "platform-tools" `
  "platforms;android-36" `
  "build-tools;36.0.0"
```

运行核心测试：

```powershell
.\gradlew.bat :core:test
```

构建 Debug APK：

```powershell
.\gradlew.bat clean :app:assembleDebug
```

输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```

安装到连接的手机：

```powershell
& "$AndroidSdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## Release APK

构建未签名 Release：

```powershell
.\gradlew.bat clean :app:assembleRelease
```

输出通常为：

```text
app\build\outputs\apk\release\app-release-unsigned.apk
```

首次创建签名证书：

```powershell
New-Item -ItemType Directory -Force .\keystore | Out-Null
keytool -genkeypair -v `
  -keystore .\keystore\nte-release.jks `
  -alias nte `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

`keystore/` 不应提交到 GitHub。

签名：

```powershell
$AndroidSdk="$env:LOCALAPPDATA\Android\Sdk"
$BuildTools="$AndroidSdk\build-tools\36.0.0"
$Unsigned=".\app\build\outputs\apk\release\app-release-unsigned.apk"
$Aligned=".\app\build\outputs\apk\release\app-release-aligned.apk"
$Signed=".\app\build\outputs\apk\release\NTE-Auction-Assistant-release.apk"

& "$BuildTools\zipalign.exe" -p -f 4 $Unsigned $Aligned
& "$BuildTools\apksigner.bat" sign `
  --ks .\keystore\nte-release.jks `
  --ks-key-alias nte `
  --out $Signed `
  $Aligned
& "$BuildTools\apksigner.bat" verify --verbose --print-certs $Signed
```

## macOS / Linux

安装好 JDK 17 和 Android SDK 36 后：

```bash
./gradlew :core:test
./gradlew clean :app:assembleDebug
```

Debug 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 常用命令

```powershell
.\gradlew.bat clean
.\gradlew.bat :core:test
.\gradlew.bat :core:build
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:dependencies
.\gradlew.bat tasks
```
