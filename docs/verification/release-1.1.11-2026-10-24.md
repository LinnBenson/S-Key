# 1.1.11 发布校验（2026-10-24）

版本 1.1.11，versionCode 117。输入修复的设备回归记录见 [预览替换验证](spacing-preview-fix-2026-10-24.md)。本轮仅调整版本号，未重复执行整组设备回归。

- 单元测试：256 项通过，0 失败、0 错误。
- 隐私静态检查与 git diff --check：通过。
- lintRelease：0 错误、52 条警告。
- ARM64、x86_64、通用 APK：签名和词库资源校验通过，不包含测试模型或音频。
- BlueStacks ARM64：覆盖安装成功，versionName / versionCode 匹配，已安装 APK 哈希与交付包一致，主页面冷启动成功。
- README 无改动；更新说明单独用于 GitHub Release。

| 安装包 | SHA-256 |
| --- | --- |
| arm64-v8a | 47cd316eff1585716bcb6d15d7fb1a026a5635b77e4a5fe40a67f23ba82df997 |
| x86_64 | 7fc312841673e85b69654a3ec8c1f72631f1fda3375f6aa84a7c9d3507017951 |
| universal | 2202a4210a383509decdc99b3bd649799c363c98a683941043b0488297998caa |
