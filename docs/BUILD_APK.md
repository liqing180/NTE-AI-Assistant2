# APK 打包说明

本项目 Android 模块为 `:app`，当前配置：

- `compileSdk = 37`
- `targetSdk = 37`
- `minSdk = 29`
- Java/JDK 17
- Gradle 9.3.1

> 当前仓库保留了 `gradle/wrapper/gradle-wrapper.properties`，但 Milestone 1 原始工程没有生成 `gradle-wrapper.jar / gradlew / gradlew.bat`。下面提供不依赖全局 Gradle 的初始化命令。初始化一次后，后续直接使用 `gradlew` 即可。

## 1. Windows 11：从零初始化并打 Debug APK

在 PowerShell 中进入项目根目录：

```powershell
cd D:\path\to\NTE-AI-Assistant2
```

确认 JDK：

```powershell
java -version
```

推荐 JDK 17。如果使用 Android Studio 自带 JBR，可临时设置：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

设置 Android SDK 路径。默认通常是：

```powershell
$AndroidSdk="$env:LOCALAPPDATA\Android\Sdk"
"sdk.dir=$($AndroidSdk -replace '\\','\\')" | Set-Content -Encoding ASCII .\local.properties
```

如果当前目录还没有 `gradlew.bat`，下载 Gradle 9.3.1 并生成 Wrapper：

```powershell
$GradleVersion="9.3.1"
$GradleZip="$env:TEMP\gradle-$GradleVersion-bin.zip"
$GradleHome="$env:TEMP\gradle-$GradleVersion"

Invoke-WebRequest `
  "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" `
  -OutFile $GradleZip

if (Test-Path $GradleHome) { Remove-Item $GradleHome -Recurse -Force }
Expand-Archive $GradleZip -DestinationPath $env:TEMP -Force

$env:Path="$GradleHome\bin;$env:Path"
gradle --version
gradle wrapper --gradle-version $GradleVersion
```

检查 Wrapper：

```powershell
.\gradlew.bat --version
```

打 Debug APK：

```powershell
.\gradlew.bat clean :app:assembleDebug
```

生成文件：

```text
app\build\outputs\apk\debug\app-debug.apk
```

连接 Android 手机并安装：

```powershell
adb devices
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

如果 `adb` 不在 PATH：

```powershell
& "$AndroidSdk\platform-tools\adb.exe" devices
& "$AndroidSdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## 2. Windows：Release APK

先构建未签名 Release：

```powershell
.\gradlew.bat clean :app:assembleRelease
```

通常输出：

```text
app\build\outputs\apk\release\app-release-unsigned.apk
```

### 2.1 第一次创建签名证书

只做一次：

```powershell
New-Item -ItemType Directory -Force .\keystore | Out-Null
keytool -genkeypair -v `
  -keystore .\keystore\nte-release.jks `
  -alias nte `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

`keystore/` 已加入 `.gitignore`，不要上传私钥和密码。

### 2.2 zipalign + apksigner

自动寻找本机最新 Android Build Tools：

```powershell
$AndroidSdk="$env:LOCALAPPDATA\Android\Sdk"
$BuildTools=(Get-ChildItem "$AndroidSdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
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

最终可安装 APK：

```text
app\build\outputs\apk\release\NTE-Auction-Assistant-release.apk
```

安装验证：

```powershell
& "$AndroidSdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\release\NTE-Auction-Assistant-release.apk
```

## 3. macOS / Linux

项目根目录：

```bash
cd /path/to/NTE-AI-Assistant2
```

如果没有 `./gradlew`，初始化 Wrapper：

```bash
GRADLE_VERSION=9.3.1
TMP_DIR="${TMPDIR:-/tmp}"
curl -L "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$TMP_DIR/gradle.zip"
unzip -q -o "$TMP_DIR/gradle.zip" -d "$TMP_DIR"
export PATH="$TMP_DIR/gradle-${GRADLE_VERSION}/bin:$PATH"
gradle wrapper --gradle-version "$GRADLE_VERSION"
chmod +x ./gradlew
```

Debug：

```bash
./gradlew clean :app:assembleDebug
```

Release 未签名：

```bash
./gradlew clean :app:assembleRelease
```

Debug 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 输出：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

## 4. 常用构建命令

```powershell
# 清理
.\gradlew.bat clean

# 只编译核心 Kotlin 模块
.\gradlew.bat :core:build

# 核心测试
.\gradlew.bat :core:test

# Debug APK
.\gradlew.bat :app:assembleDebug

# Release APK
.\gradlew.bat :app:assembleRelease

# 查看依赖
.\gradlew.bat :app:dependencies

# 查看所有任务
.\gradlew.bat tasks
```

macOS/Linux 将 `.\gradlew.bat` 换成 `./gradlew`。

## 5. Android Studio 打包

如果命令行环境还未配置完成，也可以：

1. Android Studio 打开仓库根目录。
2. 设置 Gradle JDK 为 JDK 17 / Android Studio JBR。
3. 等待 Gradle Sync。
4. Debug：`Build > Build APK(s)`。
5. 正式签名包：`Build > Generate Signed App Bundle or APK > APK`。

## 6. 常见问题

### `SDK location not found`

创建 `local.properties`：

```properties
sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

### `JAVA_HOME is not set`

PowerShell：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

### `SDK platform android-37 not found`

Android Studio 的 SDK Manager 安装 Android API 37；或使用 `sdkmanager`：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-37" "platform-tools" "build-tools;37.0.0"
```

如果本机实际可用的 Build Tools 版本不同，在 SDK Manager 安装一个可用版本即可；Gradle 通常会自动选择兼容版本。
