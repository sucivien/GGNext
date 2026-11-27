#pragma once

#include "mem_engine/plugin_host_api.h"
#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

struct lua_State;

namespace mem_engine {

/**
 * Runtime metadata injected into the `gg` global table as public read-only properties.
 */
struct LuaRuntimeProps {
    std::string androidSdkInt = "34";
    std::string packageName   = "com.littlecodesauce.ggnext";
    std::string version       = "101.1";
    int32_t     versionInt    = 10101;
    int32_t     build         = 16142;
    std::string filesDir      = "/data/local/tmp";
    std::string cacheDir      = "/data/local/tmp";
    std::string extFilesDir   = "/sdcard/GGNext";
    std::string extCacheDir   = "/sdcard/GGNext/cache";
    std::string extStorage    = "/sdcard";
    std::string pluginDir     = "";
};

struct LuaRunResult {
    bool        ok        = false;
    bool        cancelled = false;
    bool        exited    = false;
    int32_t     exitCode  = 0;
    std::string error;
};

/**
 * Native Lua 5.5.1 embedded script engine for GGNext.
 *
 * Design guarantees:
 * - Isolated lua_State per execution instance; all invocations run via lua_pcall.
 * - LUA_MASKCOUNT instruction hook polls an atomic cancellation flag so infinite loops
 *   abort immediately without freezing the overlay or main thread.
 * - Built-in compatibility shim for legacy GameGuardian Lua scripts:
 *   bit32 library, loadstring, unpack, table.getn/maxn/foreach, math.pow/atan2/log10,
 *   safe os.exit sentinel (never terminates host process), sandboxed os.execute/io.popen,
 *   and auto-mkdir io.open wrapper for write modes.
 * - Full gg.* API & constants surface forwarded cleanly to the host callback bridge.
 * - Declarative `ui.*` module for Lua -> Compose UI trees and interactive callback dispatch.
 * - Unified `plugin.*` loader supporting both pure Lua modules (package.preload/path)
 *   and native C++ .so plugins exporting luaopen_<name> / ggnext_plugin_init_<name>.
 */
class LuaEngine {
public:
    /**
     * Generic host bridge callback:
     *   (methodName, argsJson) -> responseJson
     *
     * Response JSON envelope:
     *   {"ok":true, "value": <any json>, "multi": [<optional multiple return values>]}
     *   {"ok":false, "error":"message", "raise":false}
     *   When raise==true, luaL_error is raised; when raise==false, error string is returned
     *   directly (matching GameGuardian's `mixed` return convention).
     */
    using HostCallFn = std::function<std::string(const std::string& method, const std::string& argsJson)>;
    using PrintFn    = std::function<void(const std::string& line)>;

    LuaEngine();
    ~LuaEngine();

    LuaEngine(const LuaEngine&) = delete;
    LuaEngine& operator=(const LuaEngine&) = delete;

    void setHostCallback(HostCallFn cb);
    void setPrintCallback(PrintFn cb);
    void setRuntimeProps(const LuaRuntimeProps& props);
    void setPluginHostApi(const GGNextHostApiV1* api);

    /** Request immediate cancellation from any thread (checked in lua_sethook + sleep). */
    void requestCancel();
    bool isCancelled() const;

    /** Run a plain-text Lua script file from disk (blocks calling worker thread). */
    LuaRunResult runFile(const std::string& scriptPath);

    /** Run a plain-text Lua source chunk in memory (blocks calling worker thread). */
    LuaRunResult runString(const std::string& source, const std::string& chunkName = "script.lua");

    /**
     * Dispatch a UI event callback registered by `ui.*` back into the active lua_State.
     * MUST be called from the same thread executing the Lua VM (e.g. inside ui.show modal pump
     * or event poll loop).
     */
    bool dispatchUiEvent(const std::string& callbackId, const std::string& payloadJson, std::string* outError = nullptr);

    /**
     * Load an external C++ native plugin shared library (.so) into the active lua_State.
     * Looks up `ggnext_plugin_init_<moduleName>` or standard `luaopen_<moduleName>`.
     */
    bool loadNativePlugin(const std::string& moduleName, const std::string& soPath, std::string* outError = nullptr);

    /** Register a pure Lua module source into package.preload[moduleName]. */
    bool registerPreloadModule(const std::string& moduleName, const std::string& luaSource, std::string* outError = nullptr);

    const std::string& currentScriptPath() const { return currentScriptPath_; }

    /** Internal helpers exposed to static C callbacks in lua_engine.cpp */
    std::string invokeHost(const std::string& method, const std::string& argsJson);
    void emitPrint(const std::string& line);
    const GGNextHostApiV1* pluginHostApi() const { return pluginHostApi_; }
    const LuaRuntimeProps& runtimeProps() const { return props_; }
    void recordLoadedNativePlugin(const std::string& name, void* dlHandle);
    std::vector<std::string> loadedPluginNames() const;

private:
    bool initState(const std::string& scriptPath);
    void closeState();
    void registerCompatShim(lua_State* L);
    void registerSandboxOverrides(lua_State* L);
    void registerGgModule(lua_State* L);
    void registerUiModule(lua_State* L);
    void registerPluginModule(lua_State* L);
    void configurePackagePaths(lua_State* L, const std::string& scriptPath);
    LuaRunResult executeProtected(lua_State* L, int loadStatus);

    lua_State*             L_ = nullptr;
    std::atomic<bool>      cancelFlag_{false};
    std::string            currentScriptPath_;
    LuaRuntimeProps        props_;
    HostCallFn             hostCb_;
    PrintFn                printCb_;
    const GGNextHostApiV1* pluginHostApi_ = nullptr;

    mutable std::mutex                      pluginMtx_;
    std::vector<std::pair<std::string, void*>> dlHandles_;
    std::vector<std::pair<std::string, std::string>> pendingPreloads_;
};

} // namespace mem_engine
