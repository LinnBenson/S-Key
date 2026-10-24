# 1.1.10 发布检查（2026-10-24）

版本为 1.1.10 / versionCode 116。本次发布包含此前未发布的存储、输入和生命周期修复，以及主题默认样式、外屏竖屏默认高度和 README 调整。

## 本次检查

- testDebugUnitTest：256 项通过，无失败或跳过。
- Python unittest：12 项通过。
- privacy_audit.py：通过；更新其 Keystore 检查路径，覆盖重构后的 StorageKeys 调用与同步入口。
- lintRelease：0 错误、52 条警告。
- git diff --check：通过。
- BlueStacks ARM64 覆盖安装成功，安装版本和 APK 哈希匹配，主页面启动成功。
- ARM64、x86_64、通用 Release APK：沿用既有发布证书，签名与词库资源校验通过；生产包不包含测试模型和音频。

完整设备回归及未决项见 [1.1.9 验证记录](release-1.1.9-2026-10-24.md)。本次未重复全量设备测试，不能将前次结果视为全部运行于 1.1.10。BlueStacks 真实录音仍受无样本和停止延迟影响。

## 安装包 SHA-256

| 架构 | SHA-256 |
| --- | --- |
| arm64-v8a | de68620f98294da7d7d9b97985afbbe9b5e7f392c7e320aaa8e1c0715e705859 |
| x86_64 | e8696f883ab0eba2d3984d626b625de87aad40386530b9a88bc0cfb2aa26de5e |
| universal | e758d462faa406113f88e57baa08d7bfe223d56078a5da4adde583a17d2072e3 |
