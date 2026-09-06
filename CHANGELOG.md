# Changelog

## 2.0.0

- Added explicit keyboard interaction modes: NORMAL, INSERT, SLASH, POPUP, SEARCH, and CONFIRM.
- Added a reusable popup controller with ranked exact, prefix, and fuzzy filtering, keyboard selection, scrolling, empty-result handling, and terminal-size bounds.
- Added `/model` and `/provider` commands backed by the existing configured provider selector.
- Improved slash-command ranking while preserving the central command registry and user-command loading.
- Added mode feedback to the interactive status bar and documented the Vim-inspired keyboard reference.
- Preserved one-shot, remote, agent streaming, tool, permission, session, and context-control paths.
- Added capability-aware `/mode`, `/thinking`, and `/model-settings` controls for reasoning, temperature, top-p, and verbosity settings.
- Added provider-bound tri-state capability detection and request filtering so unsupported or unknown parameters are never guessed onto the wire.
- Added Smart Coding Orchestrator foundations with persisted complexity-aware Todo plans and explicit task states.
- Added cross-platform shell detection, structured process execution, project/build detection, command intents, timeouts, and Windows wrapper resolution.

## 1.0.3

Previous release.