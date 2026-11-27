# GGNext 改造说明（加密脚本 + 网络脚本）

> 目标：让 GGNext 能执行「明文 / Lua 字节码 / 网络」三类脚本。
> 改动日期：2026-11-27
> 原工程备份：`/storage/emulated/0/Download/GGNext-main.backup`（5.2M，完整可回滚）

---

## 一、改动总览

共修改 **3 个文件**，追加 **4 个新 API**，解除 **2 处硬拦截**。

| 文件 | 改动 |
|---|---|
| `app/src/main/cpp/src/lua_engine.cpp` | ① 新增脚本格式探测；② 解除 0x1B 硬拒绝；③ 模式 `"t"`→`"bt"`（2 处）；④ 白名单追加 4 项 |
| `.../lua/GgLuaApiHandler.kt` | ① 注册表追加 4 个分支；② 实现 4 个函数；③ `currentScriptDir` 字段 |
| `.../lua/LuaScriptEngine.kt` | `launchWorker` 中写入当前脚本目录 |

---

## 二、加密脚本支持

### 原来为什么不支持

`lua_engine.cpp` 里有**主动拦截**，不是缺功能：

```cpp
// 原 runFile（约 2216 行）
if (!source.empty() && static_cast<unsigned char>(source[0]) == 0x1B) {
    res.error = "Encrypted or precompiled binary Lua bytecode is not supported; ...";
    return res;
}
```

加上所有 `luaL_loadbufferx` 的模式参数都是 `"t"`（仅文本），二进制 chunk 进不去。

### 改成了什么

**1. 新增格式探测（匿名 namespace，`runFile` 之前）**

```cpp
enum class ScriptFormat {
    Empty, PlainText, LuaBytecode, UnknownBinary
};

detectScriptFormat(s):
  空            -> Empty
  0x1B + "Lua"  -> LuaBytecode   （标准 luac / string.dump 产物）
  0x1B + 其他   -> UnknownBinary （很可能是 GG 私有 .enc）
  0x00          -> UnknownBinary
  其他          -> PlainText
```

**2. 分格式处理**

```cpp
const ScriptFormat fmt = detectScriptFormat(source);
if (fmt == ScriptFormat::UnknownBinary) {
    res.error = encryptedScriptHint(scriptPath);   // 给出明确说明，不再报语法错
    return res;
}
// LuaBytecode 和 PlainText 都继续往下走，交给 luaL_loadbufferx
```

**3. 解开加载模式**

```cpp
luaL_loadbufferx(..., "bt");   // "t" 只收文本，"b" 收二进制，"bt" 两者都收
```
共 2 处：`runFile`（本地文件）和 `runString`（远程/内存源码）。

### 实际能力边界

| 脚本类型 | 改造前 | 改造后 |
|---|---|---|
| 明文 `.lua` | ✅ | ✅ |
| 标准 Lua 5.5 字节码（`luac`/`string.dump`） | ❌ 硬拒绝 | ✅ **可运行** |
| Lua 5.3/5.4 字节码 | ❌ 硬拒绝 | ❌ 仍不可用，但报「版本不兼容」而非「不支持」 |
| GameGuardian 官方 `.enc` | ❌ | ❌ **仍不可用** |

**关于 `.enc` 说明白**：GG 的 `.enc` 是私有加密格式，无公开规范，解密逻辑封在 GG 自己的二进制里，密钥与分发体系绑定。第三方引擎无法实现，要支持只能逆向它——这属于绕过他人加密保护措施，我不做。改造后遇到 `.enc` 会给出明确提示，而不是莫名的语法错误。

---

## 三、网络脚本支持

### 原来为什么不支持

`gg.makeRequest(url, headers, postData)` **本来是完整实现的**（用户授权弹窗、15s 超时、跟随重定向、自定义 headers、POST body、4MB 限流、逐块取消检查）。

缺的是脚本里习惯用的**上层便捷函数**，调用会返回 `Unsupported GG API`。

### 新增的 4 个 API

| API | 签名 | 行为 |
|---|---|---|
| `gg.downloadFile` | `(url[, path][, headers])` | 下载并写入本地，返回绝对路径 |
| `gg.loadRemoteScript` | `(url[, headers])` | 拉取远程源码并立即执行，返回 `true` |
| `gg.getFileContent` | `(path)` | 读本地文本，不存在返回 `nil` |
| `gg.getScriptDir` | `()` | 当前脚本所在目录 |

**全部复用 `ggMakeRequest` 的既有安全策略**，没有另开网络通道，所以用户授权、超时、限流、取消检查依然生效。

**路径解析**：相对路径落到当前脚本目录（`currentScriptDir`），绝对路径原样使用；未给文件名时从 URL 末段推断。

**`currentScriptDir` 的来源**：`LuaScriptEngine.launchWorker` 启动脚本时设置（`GgLuaApiHandler.currentScriptDir`，`@Volatile`）。

### 关键：C++ 白名单

`gg` 表是**白名单注册**的，Kotlin 里有函数但没进白名单，Lua 侧照样看不到：

```cpp
static const char* kHostGgMethods[] = {
    ...
    "makeRequest", "processKill", "require", "skipRestoreState", "unrandomizer",
    // [GGNext-Patch] 网络脚本标准 API
    "downloadFile", "loadRemoteScript", "getFileContent", "getScriptDir",
    ...
};
```

这一步容易漏，已补。

---

## 四、静态自检结果

无 NDK 环境下能做的检查全部通过（`selfcheck.py` + `balcheck.py`）：

- C++ 括号平衡：`{ 359 } 359` / `( 1458 ) 1458` / `[ 56 ] 56`
- Kotlin 括号平衡：`{ 388 } 388` / `( 2204 ) 2204` / `[ 16 ] 16`
- 旧硬拒绝报错：已完全移除（0 处残留）
- `companion object`：仅 1 个（未重复定义）
- 4 个 API：注册表与实现均齐备
- 白名单：4 项已加入
- `namespace` 作用域：正确闭合

**无法验证的部分**：真实编译。本机只有 JDK 17，无 Android SDK / NDK / Gradle，编不出 APK。

---

## 五、编译方法

在**你的电脑**上（已装 JDK 17 + Android SDK + NDK）：

```bash
cd GGNext-main
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

如果报错，最可能的三处：

1. **`<cstdio>` 缺失** — 已确认 `lua_engine.cpp` 第 10 行有 `#include <cstdio>`，`snprintf` 可用。
2. **`File` 未导入** — `GgLuaApiHandler.kt` 第 23 行已有 `import java.io.File`。
3. **`Charsets` 未导入** — 用的是 Kotlin 标准库 `kotlin.text.Charsets`，默认可见，无需导入。

---

## 六、测试建议

编完后依次验证：

**1. 明文脚本**（回归，应无变化）
```lua
print("hello")
```

**2. 字节码**
```bash
# 用 Lua 5.5 编译
luac5.5 -o test.luac test.lua
```
把 `test.luac` 放进 GGNext 运行。改造前报「不支持」，改造后应正常运行。

**3. 网络脚本**
```lua
local r = gg.makeRequest("https://httpbin.org/get")
print(r.code)
```
首次会弹授权窗，允许后应返回 200。

```lua
local p = gg.downloadFile("https://example.com/a.txt", "/sdcard/GGNext/t.txt")
print(p)
print(gg.getFileContent("/sdcard/GGNext/t.txt"))
```

**4. `.enc` 文件**
应看到明确提示（"GameGuardian 官方 .enc 私有加密格式…请使用原版 GameGuardian 运行"），而不是语法错误。

---

## 七、回滚

```bash
cd /storage/emulated/0/Download
rm -rf GGNext-main
mv GGNext-main.backup GGNext-main
```
