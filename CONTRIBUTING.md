# Contributing to DevMesh

Thanks for contributing to DevMesh. Before submitting changes, keep the scope clear, make sure tests pass, and do not include API keys, sessions, run traces, or other local data.

## Local validation

Windows：

```powershell
.\gradlew.bat test shadowJar
```

Linux or macOS:

```bash
./gradlew test shadowJar
```

## Branches and commits

1. Create a feature branch from `main`.
2. Commit only files related to the current feature.
3. Use a clear commit message, such as `feat: add trace policy` or `fix: preserve tool result order`.
4. Run tests and inspect `git diff --cached` before committing.

## Security checks

Do not commit any of the following:

- `.devmesh/config.yaml` and any real API key
- `.devmesh/sessions`、`.devmesh/memory`、`.devmesh/traces`
- Local IDE, Agent, or operating-system configuration
- Debug logs containing prompts, tool output, file contents, or credentials

## Documentation conventions

- Use `DevMesh` consistently for user-facing names.
- Use `devmesh`, `devmesh.jar`, `.devmesh`, and `DEVMESH_*` for the CLI, JAR, configuration directory, and environment variables respectively.
- Update the README, relevant architecture docs, and tests when adding behavior.
