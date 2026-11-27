# GGNext（个人改造版）

基于 GGNext 源码的个人构建版本。

## 本版本改动

- 支持标准 Lua 字节码脚本（`luaL_loadbufferx` 模式改为 `bt`）
- 遇到非标准二进制时给出明确的中文提示，而非语法错误
- 新增 `downloadFile` / `loadRemoteScript` / `getFileContent` / `getScriptDir`
- 脚本目录感知，支持相对路径下载

详细说明见 `GGNext改造说明.md`。
