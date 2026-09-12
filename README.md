# 异环·即刻落槌 真珠场估价助手

Android 本地辅助估价工具。当前为 Milestone 1：核心估价引擎、滚动仓库扫描基础和 MediaProjection 截图骨架。

目标：一局只做一次完整建仓，之后每回合通过快速滑动仓库完成增量识别；把品质、类型、轮廓、尺寸、数量、均价/总价等信息统一转换为 Evidence，再做候选收敛、联合约束和概率估价。

## 当前已实现

- 藏品领域模型：品质、六大类型、尺寸、价格、轮廓、真珠场权重
- 单件 Evidence：品质 / 类型 / 轮廓 / 尺寸
- 全局 Evidence：品质数量 / 类型数量 / 品质均价 / 品质总价 / 品质总格数 / 系统最低估价
- 候选藏品过滤
- 全局约束传播：数量约束、价格上下界、总格数上下界
- 参考 `nte-auction-helper` 思路独立重写的“上下界 + 回溯剪枝”组合搜索器
- Monte Carlo 联合估值
- P10/P25/P50/P75/P90、已知价值、未知价值、未知占比、可信度
- 安全 / 均衡 / 激进买入价与回合成交阈值建议
- 仓库扫描 Session：`FULL_SCAN / FAST_REFRESH / READY`
- `EMPTY / UNKNOWN / UNSCANNED` 三态区域模型
- 滚动 viewport 重叠匹配、跨屏藏品合并基础
- FrameChangeGate 增量变化过滤
- Android MediaProjection 前台截图服务骨架
- Jetpack Compose 主界面骨架
- 核心测试入口

## 工程结构

```text
app/     Android UI、MediaProjection、后续视觉识别
core/    纯 Kotlin 领域模型、扫描、Solver、Estimator
data/    数据快照/中间数据
tools/   数据整理工具
docs/    构建与开发文档
```

## 核心测试

如果本机已有 Kotlin CLI：

```bash
./run-core-tests.sh
```

完整 Gradle 工程测试：

```powershell
.\gradlew.bat :core:test
```

## Windows 11：最快打 Debug APK

第一次拉取工程，如果尚未生成 Gradle Wrapper：

```powershell
cd D:\path\to\NTE-AI-Assistant2

$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

$AndroidSdk="$env:LOCALAPPDATA\Android\Sdk"
"sdk.dir=$($AndroidSdk -replace '\\','\\')" | Set-Content -Encoding ASCII .\local.properties

$GradleVersion="9.3.1"
$GradleZip="$env:TEMP\gradle-$GradleVersion-bin.zip"
$GradleHome="$env:TEMP\gradle-$GradleVersion"
Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $GradleZip
if (Test-Path $GradleHome) { Remove-Item $GradleHome -Recurse -Force }
Expand-Archive $GradleZip -DestinationPath $env:TEMP -Force
$env:Path="$GradleHome\bin;$env:Path"
gradle wrapper --gradle-version $GradleVersion

.\gradlew.bat clean :app:assembleDebug
```

APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

安装到手机：

```powershell
& "$AndroidSdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## Release APK

构建未签名 Release：

```powershell
.\gradlew.bat clean :app:assembleRelease
```

输出：

```text
app\build\outputs\apk\release\app-release-unsigned.apk
```

完整的 **Windows / macOS / Linux 初始化、Debug、Release、keystore、zipalign、apksigner、安装与故障排查命令**：

**[docs/BUILD_APK.md](docs/BUILD_APK.md)**

## 下一阶段

1. 导入完整 `DT_BidKingCollectionItemConfig` 数据快照。
2. 仓库 viewport / 网格视觉定位。
3. 藏品分割和 fingerprint。
4. 滚动仓库 Full Scan 实机闭环。
5. 每回合 Fast Refresh 差异识别。
6. OCR Evidence Parser。
7. 品质颜色识别、轮廓 Top-K 匹配。
8. Compose 悬浮窗。

## 说明

- 当前只做辅助识别和估价，不自动点击/自动出价。
- 游戏截图设计为本地处理。
- 参考 `nte-auction-helper` 的算法思想，不复制其 Python 源码；核心使用 Kotlin 独立实现。
