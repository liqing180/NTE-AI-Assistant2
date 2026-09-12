# 开发进度

## 已完成：Milestone 1 — 核心估价与扫描基础

- [x] Android/核心模块工程骨架
- [x] 藏品 Domain Model
- [x] 六大类型、品质、尺寸、价格、轮廓字段
- [x] Evidence 历史模型
- [x] 单件候选过滤
- [x] 全局数量约束传播
- [x] 品质总价/均价约束传播
- [x] 品质总占格约束传播
- [x] 有界组合回溯剪枝搜索
- [x] Monte Carlo 联合估值
- [x] P10/P25/P50/P75/P90
- [x] 系统估价作为最低值约束
- [x] BidAdvisor
- [x] FULL_SCAN / FAST_REFRESH 状态模型
- [x] 滚动视口重叠匹配
- [x] 跨屏同一藏品逻辑去重
- [x] EMPTY / UNKNOWN / UNSCANNED 模型
- [x] MediaProjection 前台截图服务骨架
- [x] Compose 主界面骨架
- [x] 游戏 DataTable 标准化脚本
- [x] 7 个核心测试全部通过

## 下一编码层

- [ ] 完整藏品 DataTable 数据快照导入
- [ ] Android FrameSampler + ROI 裁剪
- [ ] 仓库 viewport 定位
- [ ] 网格检测
- [ ] 藏品分割与 fingerprint
- [ ] 首次完整滚动建仓联调
- [ ] Fast Refresh ROI 差异检测
- [ ] OCR Evidence Parser
- [ ] 品质分类器
- [ ] 轮廓模板 Top-K 匹配
- [ ] 悬浮窗 OverlayService
- [ ] 真珠场真实刷新权重/历史学习
