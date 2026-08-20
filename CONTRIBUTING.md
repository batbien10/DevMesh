# 参与 DevMesh 开发

感谢关注 DevMesh。提交修改前，请确保变更范围清晰、测试通过，并且没有包含 API Key、会话、运行轨迹或其他本地数据。

## 本地验证

Windows：

```powershell
.\gradlew.bat test shadowJar
```

Linux 或 macOS：

```bash
./gradlew test shadowJar
```

## 分支与提交

1. 从 `main` 创建功能分支。
2. 只提交与当前功能相关的文件。
3. 使用清晰的提交信息，例如 `feat: add trace policy` 或 `fix: preserve tool result order`。
4. 提交前运行测试，并检查 `git diff --cached`。

## 安全检查

以下内容不得提交：

- `.devmesh/config.yaml` 与任何真实 API Key
- `.devmesh/sessions`、`.devmesh/memory`、`.devmesh/traces`
- 本地 IDE、Agent 或操作系统配置
- 包含 prompt、工具输出、文件正文或凭据的调试日志

## 文档约定

- 用户可见名称统一使用 `DevMesh`。
- CLI、JAR、配置目录和环境变量分别使用 `devmesh`、`devmesh.jar`、`.devmesh` 和 `DEVMESH_*`。
- 新增行为需要同步更新 README、相关架构文档和测试。
