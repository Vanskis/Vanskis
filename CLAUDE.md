# Project notes

## context-mode

context-mode runs as a project MCP server (`.mcp.json`) with its hooks wired in `.claude/settings.json`,
not as a Claude Code plugin. Its tools are therefore named `mcp__context-mode__<tool>`. When a
context-mode hook message names `mcp__plugin_context-mode_context-mode__<tool>`, call
`mcp__context-mode__<tool>` instead.

`ctx_doctor` reports "PreToolUse hook: Could not read ~/.claude/settings.json" because it only checks
user-level settings; the project-level hooks still run. To upgrade, bump the version in `.mcp.json` and
in every hook command in `.claude/settings.json` together.
