# nte-auction-helper Android 功能迁移说明

本分支将 `heianzhihun24/nte-auction-helper` 的功能模型重写为 `NTE-AI-Assistant2` 的 Kotlin / Android 实现。

> 上游仓库根目录当前没有 LICENSE 文件，因此本迁移不直接复制其 Python / JavaScript / HTML / CSS 源码，也不复制其 PNG 等素材；采用独立 Kotlin 实现，并仅迁移功能语义、游戏事实数据（藏品名称 / 价格 / 尺寸）以及公开配置值。

## 已阅读的上游代码与资源

- `app.py`：Flask API、金色组合搜索、红色概率模型、记忆池、RapidOCR、自动出价、配置读写。
- `static/script.js`：分析表单、仓库框选、候选确认、记忆池、出价控制、OCR 调用、前端状态。
- `templates/index.html` / `static/style.css`：桌面网页 UI 结构与交互分区。
- `desktop_app.py` / `启动工具.pyw`：桌面 WebView / Flask 启动壳。
- `custom_bids.json` / `memory_config.json` / `red_weights.json` / `v1.3.json`：出价、记忆组、红色先验与历史数据格式。
- `database/gold_items/` / `database/red_items/` / `picture/`：候选藏品参考图和其他截图资源组织方式。

## 功能映射

| 上游桌面实现 | Android 端实现 |
| --- | --- |
| Flask API | `HelperFeatureStore` + 纯 Kotlin 调用 |
| 浏览器 DOM / localStorage | Jetpack Compose + StateFlow + SharedPreferences |
| RapidOCR 屏幕截图 | MediaProjection + ML Kit 中文 OCR |
| `pyautogui` 截图与点击 | AccessibilityService 截图 + `dispatchGesture` |
| Python 金色 DFS | `BoundedCombinationSearch` + `NteHelperAnalyzer` |
| 红色先验 / 记忆池 | `HelperRedProbabilityModel` + `HelperPersistence` |
| 10×25 手工仓库框选 | 现有自动仓库识别 + 尺寸/品质候选确认 |
| `/api/item-image/<quality>/<price>` | `HelperReferenceImageStore` + Compose 图片候选卡片 |
| 网页分析结果 | Compose “拍卖分析”页 + 悬浮窗快捷区 |
| 自定义出价按钮 | App 本地持久化自定义金额 |

## 核心迁移模块

### `core/.../helper/NteHelperCatalog.kt`

- 50 个金色藏品的名称、标准价值、尺寸。
- 30 个红色藏品的名称、标准价值、尺寸。
- 上游用于估值的 25 个百万以下红色藏品池。
- 上游当前 `red_weights.json` 的先验权重。
- 默认自定义出价金额。
- 严格区分 `宽×高` 方向的尺寸候选。

### `core/.../helper/HelperMemoryModel.kt`

- 多记忆组。
- 当前组 / 多激活组。
- 组权重。
- 每价格权重。
- 历史红色价格记录。
- 小样本时经验分布与先验 50/50 混合。
- 已知红色价格条件化。
- 按尺寸限制红色候选。
- 从历史记录更新价格权重。

### `core/.../helper/NteHelperAnalyzer.kt`

- 金色平均价 ±1 的组合约束。
- 单物品最多重复 2 次。
- 7 件及以下精确组合搜索。
- 8–14 件估算模式。
- 指定金色价格约束。
- 金色所占总格数约束。
- 仓库尺寸区域约束。
- 已知红色价格。
- 未知红色数量。
- 未确认红色尺寸区域估算。
- 保守 / 中性 / 激进红色估值。
- 未知红色 ≤6 时精确卷积，更多时固定种子的 Monte Carlo。
- 金色价值 ×2 模式。

### `app/.../helper/HelperAuctionStatsRecognizer.kt`

将上游 RapidOCR 字段提取逻辑改写为 Android ML Kit 中文 OCR，识别：

- 紫 / 金 / 红总件数。
- 紫色总数量。
- 金色总数量。
- 金色所占格数。
- 金色平均价值。

OCR 直接消费 MediaProjection 的下一帧，不再上传图片到本地 HTTP 服务。

### `app/.../helper/HelperAutoBidAccessibilityService.kt`

保留上游自动出价的操作语义：

- OCR 查找右下区域“出价”按钮。
- 点击出价。
- 按 4×4 数字键盘比例输入金额。
- 连续 0 自动压缩为 `0` / `00` / `0000`。
- 清空后输入。
- 最后确认两次。

Android 端只有用户主动触发时才执行；需要用户自行在系统设置中启用无障碍服务。

### `app/.../helper/HelperReferenceImageStore.kt`

迁移上游“已显示藏品参考图候选”的功能语义：

- 自动仓库识别先给出品质与精确 `宽×高`。
- 只展示该品质、该尺寸对应的候选藏品。
- 候选卡片展示参考图、名称、价格和尺寸，点击即确认具体藏品。
- 没有参考图时自动退化为“暂无参考图”，文字候选仍可正常确认。
- 支持用户从 App 导入参考图 ZIP，导入后存放在 App 私有目录。
- ZIP 兼容上游目录：`database/gold_items/<价格>.png`、`database/red_items/<价格>.png`，也兼容 `gold/<价格>.png`、`red/<价格>.png`。
- 导入过程限制文件类型、单图大小和总图数，并阻止 ZIP 路径穿越。

### `app/.../helper/HelperFeatureStore.kt`

替代 Flask API 和浏览器全局状态，统一管理：

- 拍卖字段。
- OCR 请求。
- 仓库候选确认。
- 分析结果。
- 自定义出价。
- 红色记忆组 CRUD。
- 记忆记录 CRUD / 删除撤销。
- 组权重 / 激活状态 / 当前组。

### Compose UI

主界面增加：

- `总览`
- `拍卖分析`
- `记忆池`

“仓库候选确认”区域增加：

- 参考图 ZIP 导入。
- 当前金/红参考图数量。
- 按尺寸过滤后的横向图片候选卡片。
- 点击图片卡片确认具体名称和价格。

游戏悬浮窗增加：

- 识别参数。
- 分析。
- 最低估价填入。
- 自动出价。

## 与上游不同但功能等价 / 更适合 Android 的部分

1. **不运行 Flask**：App 内部直接调用 Kotlin domain 层。
2. **不使用 WebView 页面作为主 UI**：改成 Compose，状态通过 StateFlow 驱动。
3. **不使用 pyautogui**：Android 无障碍手势承担显式自动出价动作。
4. **仓库识别增强**：上游仓库主要依赖用户在 10×25 网格手工框选；本项目优先使用现有截图自动仓库识别，再允许按尺寸确认具体物品。
5. **不随 APK 复制上游图片素材**：上游没有明确 LICENSE；Android 端实现兼容参考图包导入，用户可导入其有权使用的图片，目录格式与上游兼容。
6. **不内置上游原始历史 JSON**：当前红色先验权重已迁移，新的历史由 App 本地记忆池积累；保留同等的数据模型与学习逻辑。

## 验证

专用验证 workflow 只执行：

```text
gradle :core:test
gradle :app:compileDebugKotlin
```

不构建 APK。当前迁移的 core 单元测试覆盖目录、红色概率模型、已知金色组合、无总件数估值和尺寸方向约束。
