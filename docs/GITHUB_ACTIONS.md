# GitHub 在线手动打包 APK

仓库已配置手动 GitHub Actions 工作流：

```text
.github/workflows/build-apk.yml
```

该工作流 **不会在 push、PR 或定时任务时自动运行**，只有手动点击 `Run workflow` 才会执行。

## 网页端打包步骤

1. 打开仓库：`liqing180/NTE-AI-Assistant2`。
2. 点击顶部 **Actions**。
3. 左侧选择 **Build APK**。
4. 点击右侧 **Run workflow**。
5. Branch 选择 `main`。
6. `APK build type`：
   - `debug`：推荐测试使用，可直接安装。
   - `release`：生成未签名 Release APK，正式分发前还需要签名。
7. 点击绿色 **Run workflow**。
8. 等待工作流完成并显示绿色勾。
9. 打开本次 Workflow Run。
10. 页面底部 **Artifacts** 下载：

```text
NTE-Auction-Assistant-debug-<run_number>
```

或：

```text
NTE-Auction-Assistant-release-<run_number>
```

下载得到 ZIP，解压后 Debug APK 文件名为：

```text
NTE-Auction-Assistant-debug.apk
```

Release 文件名为：

```text
NTE-Auction-Assistant-release-unsigned.apk
```

## 云端实际执行内容

工作流会自动完成：

```text
Checkout 源码
→ JDK 17
→ Android SDK
→ 安装 Android API 37
→ Gradle 9.3.1
→ :core:test
→ :app:assembleDebug / :app:assembleRelease
→ 上传 APK Artifact
```

如果 `:core:test` 或 Android 编译失败，APK 不会被上传，直接查看失败步骤的日志即可。

## Debug APK

云端原始输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

上传 Artifact 前会重命名为：

```text
NTE-Auction-Assistant-debug.apk
```

## Release APK

云端原始输出：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

上传 Artifact 前会重命名为：

```text
NTE-Auction-Assistant-release-unsigned.apk
```

当前 Release APK 没有在 GitHub Actions 中配置签名密钥，避免把私钥写进仓库。后续如需要，可使用 GitHub Actions Secrets 保存 keystore/base64、alias 和密码，再增加自动签名步骤。
