# 验证证据

以验收报告和实际日志为准，历史通过不代表最新版本全项通过。

- 当前 GitHub Actions 每轮上传实际 APK、Lint/JVM 报告、设备日志及取证文件；失败时也保留产物。
- 仓库保留本地已执行构建的退出码、29 项单元测试 XML、签名及原生库对齐检查。
- 云端执行及修复摘要位于 `github/`。
- 接入 GitHub 前的软件模拟器历史完整日志与 PNG 仍保存在此前交付的完整源码包，未重复提交大型日志和旧测试 APK：
  https://chatgpt.com/library?file_id=libfile_d8ec10e09d4c8191b8372aff2ac8d133&account_id=personal

CI 调试签名独立生成，与此前交付 APK 的签名不同；签名检查文件指向的旧 APK 身份仅用于对应历史构建，不可套用于 CI APK。
