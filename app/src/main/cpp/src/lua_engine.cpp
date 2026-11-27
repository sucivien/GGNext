#include "mem_engine/lua_engine.hpp"

#include "lua.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <errno.h>
#include <fstream>
#include <sstream>
#include <sys/stat.h>
#include <thread>
#include <unordered_map>

namespace mem_engine {

namespace {

int kEngineRegKey = 0;

constexpr const char* kCancelSentinel     = "__GGNEXT_LUA_CANCELLED__";
constexpr const char* kExitSentinelPrefix = "__GGNEXT_LUA_EXIT__:";

LuaEngine* getEngine(lua_State* L) {
    lua_pushlightuserdata(L, &kEngineRegKey);
    lua_rawget(L, LUA_REGISTRYINDEX);
    auto* eng = static_cast<LuaEngine*>(lua_touserdata(L, -1));
    lua_pop(L, 1);
    return eng;
}

// ============================================================================
// POSIX recursive parent directory creation (zero shell invocation)
// ============================================================================
bool ensureParentDirectories(const std::string& filePath) {
    auto slash = filePath.find_last_of('/');
    if (slash == std::string::npos || slash == 0) return true;
    std::string dir = filePath.substr(0, slash);
    std::string cur;
    cur.reserve(dir.size());
    size_t i = 0;
    if (!dir.empty() && dir[0] == '/') {
        cur.push_back('/');
        i = 1;
    }
    while (i < dir.size()) {
        size_t next = dir.find('/', i);
        if (next == std::string::npos) next = dir.size();
        std::string part = dir.substr(i, next - i);
        if (!part.empty()) {
            if (cur.size() > 1 && cur.back() != '/') cur.push_back('/');
            cur += part;
            if (::mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) {
                struct stat st{};
                if (::stat(cur.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
                    return false;
                }
            }
        }
        i = next + 1;
    }
    return true;
}

// ============================================================================
// Lossless 64-bit JSON Encoder / Decoder for Lua <-> Host Bridge
// ============================================================================
void appendJsonString(std::string& out, const char* data, size_t len) {
    out.push_back('"');
    for (size_t i = 0; i < len; ++i) {
        unsigned char c = static_cast<unsigned char>(data[i]);
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
                break;
        }
    }
    out.push_back('"');
}

void appendJsonString(std::string& out, const std::string& s) {
    appendJsonString(out, s.data(), s.size());
}

void luaValueToJson(lua_State* L, int idx, std::string& out, int depth) {
    if (depth > 24) {
        out += "null";
        return;
    }
    int absIdx = lua_absindex(L, idx);
    int t = lua_type(L, absIdx);
    switch (t) {
        case LUA_TNIL:
        case LUA_TNONE:
            out += "null";
            break;
        case LUA_TBOOLEAN:
            out += (lua_toboolean(L, absIdx) ? "true" : "false");
            break;
        case LUA_TNUMBER: {
            if (lua_isinteger(L, absIdx)) {
                char buf[32];
                std::snprintf(buf, sizeof(buf), "%" PRId64, static_cast<int64_t>(lua_tointeger(L, absIdx)));
                out += buf;
            } else {
                double d = static_cast<double>(lua_tonumber(L, absIdx));
                if (!std::isfinite(d)) {
                    out += "null";
                } else {
                    char buf[48];
                    std::snprintf(buf, sizeof(buf), "%.17g", d);
                    out += buf;
                }
            }
            break;
        }
        case LUA_TSTRING: {
            size_t len = 0;
            const char* s = lua_tolstring(L, absIdx, &len);
            appendJsonString(out, s ? s : "", len);
            break;
        }
        case LUA_TTABLE: {
            luaL_checkstack(L, 8, "json encode stack");
            bool hasIntKey = false;
            bool hasStrKey = false;
            bool allDenseSeq = true;
            lua_Integer minInt = 0;
            lua_Integer maxInt = 0;
            size_t count = 0;

            lua_pushnil(L);
            while (lua_next(L, absIdx) != 0) {
                int kt = lua_type(L, -2);
                if (kt == LUA_TNUMBER && lua_isinteger(L, -2)) {
                    lua_Integer k = lua_tointeger(L, -2);
                    if (!hasIntKey) {
                        minInt = maxInt = k;
                    } else {
                        if (k < minInt) minInt = k;
                        if (k > maxInt) maxInt = k;
                    }
                    hasIntKey = true;
                    if (k < 1) allDenseSeq = false;
                } else if (kt == LUA_TSTRING) {
                    hasStrKey = true;
                    allDenseSeq = false;
                } else {
                    allDenseSeq = false;
                }
                ++count;
                lua_pop(L, 1);
            }

            if (count == 0) {
                out += "[]";
                break;
            }

            if (allDenseSeq && hasIntKey && !hasStrKey && minInt == 1 &&
                static_cast<uint64_t>(maxInt) == static_cast<uint64_t>(count)) {
                out.push_back('[');
                for (lua_Integer i = 1; i <= maxInt; ++i) {
                    if (i > 1) out.push_back(',');
                    lua_rawgeti(L, absIdx, i);
                    luaValueToJson(L, -1, out, depth + 1);
                    lua_pop(L, 1);
                }
                out.push_back(']');
                break;
            }

            if (hasStrKey && !hasIntKey) {
                out.push_back('{');
                bool first = true;
                lua_pushnil(L);
                while (lua_next(L, absIdx) != 0) {
                    if (lua_type(L, -2) == LUA_TSTRING) {
                        if (!first) out.push_back(',');
                        first = false;
                        size_t klen = 0;
                        const char* ks = lua_tolstring(L, -2, &klen);
                        appendJsonString(out, ks ? ks : "", klen);
                        out.push_back(':');
                        luaValueToJson(L, -1, out, depth + 1);
                    }
                    lua_pop(L, 1);
                }
                out.push_back('}');
                break;
            }

            // Sparse integer keys or mixed integer/string keys: preserve exact Lua key types!
            out += "{\"__lua_entries\":[";
            bool first = true;
            lua_pushnil(L);
            while (lua_next(L, absIdx) != 0) {
                int kt = lua_type(L, -2);
                if (kt == LUA_TNUMBER && lua_isinteger(L, -2)) {
                    if (!first) out.push_back(',');
                    first = false;
                    char kbuf[32];
                    std::snprintf(kbuf, sizeof(kbuf), "%" PRId64, static_cast<int64_t>(lua_tointeger(L, -2)));
                    out += "[\"i\",";
                    out += kbuf;
                    out.push_back(',');
                    luaValueToJson(L, -1, out, depth + 1);
                    out.push_back(']');
                } else if (kt == LUA_TSTRING) {
                    if (!first) out.push_back(',');
                    first = false;
                    size_t klen = 0;
                    const char* ks = lua_tolstring(L, -2, &klen);
                    out += "[\"s\",";
                    appendJsonString(out, ks ? ks : "", klen);
                    out.push_back(',');
                    luaValueToJson(L, -1, out, depth + 1);
                    out.push_back(']');
                }
                lua_pop(L, 1);
            }
            out += "]}";
            break;
        }
        default:
            out += "null";
            break;
    }
}

std::string encodeArgsToJsonArray(lua_State* L, int startIdx) {
    int top = lua_gettop(L);
    std::string out;
    out.reserve(128);
    out.push_back('[');
    for (int i = startIdx; i <= top; ++i) {
        if (i > startIdx) out.push_back(',');
        luaValueToJson(L, i, out, 0);
    }
    out.push_back(']');
    return out;
}

class JsonParser {
public:
    JsonParser(const char* data, size_t len) : cur_(data), end_(data + len) {}

    void skipWs() {
        while (cur_ < end_ && (*cur_ == ' ' || *cur_ == '\t' || *cur_ == '\r' || *cur_ == '\n')) {
            ++cur_;
        }
    }

    bool parseToLua(lua_State* L, int depth = 0) {
        if (depth > 32) {
            lua_pushnil(L);
            return false;
        }
        skipWs();
        if (cur_ >= end_) {
            lua_pushnil(L);
            return false;
        }
        char c = *cur_;
        if (c == 'n') {
            if (end_ - cur_ >= 4 && std::memcmp(cur_, "null", 4) == 0) {
                cur_ += 4;
                lua_pushnil(L);
                return true;
            }
            return false;
        }
        if (c == 't') {
            if (end_ - cur_ >= 4 && std::memcmp(cur_, "true", 4) == 0) {
                cur_ += 4;
                lua_pushboolean(L, 1);
                return true;
            }
            return false;
        }
        if (c == 'f') {
            if (end_ - cur_ >= 5 && std::memcmp(cur_, "false", 5) == 0) {
                cur_ += 5;
                lua_pushboolean(L, 0);
                return true;
            }
            return false;
        }
        if (c == '"') {
            std::string s;
            if (!parseString(s)) return false;
            lua_pushlstring(L, s.data(), s.size());
            return true;
        }
        if (c == '[') {
            ++cur_;
            luaL_checkstack(L, 6, "json array decode");
            lua_newtable(L);
            skipWs();
            if (cur_ < end_ && *cur_ == ']') {
                ++cur_;
                return true;
            }
            lua_Integer idx = 1;
            while (cur_ < end_) {
                if (!parseToLua(L, depth + 1)) {
                    lua_pop(L, 1);
                    return false;
                }
                lua_rawseti(L, -2, idx++);
                skipWs();
                if (cur_ < end_ && *cur_ == ',') {
                    ++cur_;
                } else if (cur_ < end_ && *cur_ == ']') {
                    ++cur_;
                    return true;
                } else {
                    lua_pop(L, 1);
                    return false;
                }
            }
            lua_pop(L, 1);
            return false;
        }
        if (c == '{') {
            ++cur_;
            luaL_checkstack(L, 8, "json object decode");
            lua_newtable(L);
            skipWs();
            if (cur_ < end_ && *cur_ == '}') {
                ++cur_;
                return true;
            }
            bool isSparseEntries = false;
            bool isLuaFloat = false;
            int keyCount = 0;
            while (cur_ < end_) {
                skipWs();
                std::string key;
                if (!parseString(key)) {
                    lua_pop(L, 1);
                    return false;
                }
                skipWs();
                if (cur_ >= end_ || *cur_ != ':') {
                    lua_pop(L, 1);
                    return false;
                }
                ++cur_;
                ++keyCount;
                if (keyCount == 1 && key == "__lua_entries") {
                    isSparseEntries = true;
                } else {
                    isSparseEntries = false;
                }
                if (keyCount == 1 && key == "__lua_float") {
                    isLuaFloat = true;
                } else {
                    isLuaFloat = false;
                }
                lua_pushlstring(L, key.data(), key.size());
                if (!parseToLua(L, depth + 1)) {
                    lua_pop(L, 2);
                    return false;
                }
                lua_rawset(L, -3);
                skipWs();
                if (cur_ < end_ && *cur_ == ',') {
                    ++cur_;
                } else if (cur_ < end_ && *cur_ == '}') {
                    ++cur_;
                    break;
                } else {
                    lua_pop(L, 1);
                    return false;
                }
            }
            if (isLuaFloat && keyCount == 1) {
                // Preserve Lua float subtype (e.g. 15.0 -> float 15.0, not integer 15)
                lua_getfield(L, -1, "__lua_float");
                lua_Number dv = lua_tonumber(L, -1);
                lua_pop(L, 2);
                lua_pushnumber(L, dv);
                return true;
            }
            if (isSparseEntries && keyCount == 1) {
                // Reconstruct sparse/mixed Lua table from {"__lua_entries": [["i"|"s", key, val], ...]}
                lua_getfield(L, -1, "__lua_entries");
                if (lua_istable(L, -1)) {
                    lua_newtable(L); // reconstructed
                    lua_Integer n = static_cast<lua_Integer>(lua_rawlen(L, -2));
                    for (lua_Integer i = 1; i <= n; ++i) {
                        lua_rawgeti(L, -2, i);
                        if (lua_istable(L, -1)) {
                            lua_rawgeti(L, -1, 1);
                            const char* kt = lua_tostring(L, -1);
                            lua_pop(L, 1);
                            lua_rawgeti(L, -1, 2);
                            lua_rawgeti(L, -2, 3);
                            if (kt && kt[0] == 'i' && lua_isinteger(L, -2)) {
                                lua_rawset(L, -4);
                            } else if (kt && kt[0] == 's' && lua_isstring(L, -2)) {
                                lua_rawset(L, -4);
                            } else {
                                lua_pop(L, 2);
                            }
                        }
                        lua_pop(L, 1);
                    }
                    // Stack: [origObj, entriesArr, reconstructed]
                    lua_replace(L, -3);
                    lua_pop(L, 1);
                } else {
                    lua_pop(L, 1);
                }
            }
            return true;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber(L);
        }
        return false;
    }

private:
    static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return -1;
    }

    static void appendUtf8(std::string& out, uint32_t cp) {
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | ((cp >> 6) & 0x1F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | ((cp >> 12) & 0x0F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0x10FFFF) {
            out.push_back(static_cast<char>(0xF0 | ((cp >> 18) & 0x07)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    bool parseHex4(uint32_t& out) {
        if (end_ - cur_ < 4) return false;
        uint32_t v = 0;
        for (int i = 0; i < 4; ++i) {
            int d = hexDigit(cur_[i]);
            if (d < 0) return false;
            v = (v << 4) | static_cast<uint32_t>(d);
        }
        cur_ += 4;
        out = v;
        return true;
    }

    bool parseString(std::string& out) {
        if (cur_ >= end_ || *cur_ != '"') return false;
        ++cur_;
        while (cur_ < end_) {
            char c = *cur_++;
            if (c == '"') return true;
            if (c == '\\') {
                if (cur_ >= end_) return false;
                char esc = *cur_++;
                switch (esc) {
                    case '"':  out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/':  out.push_back('/'); break;
                    case 'b':  out.push_back('\b'); break;
                    case 'f':  out.push_back('\f'); break;
                    case 'n':  out.push_back('\n'); break;
                    case 'r':  out.push_back('\r'); break;
                    case 't':  out.push_back('\t'); break;
                    case 'u': {
                        uint32_t cp = 0;
                        if (!parseHex4(cp)) return false;
                        if (cp >= 0xD800 && cp <= 0xDBFF && end_ - cur_ >= 6 &&
                            cur_[0] == '\\' && cur_[1] == 'u') {
                            cur_ += 2;
                            uint32_t low = 0;
                            if (!parseHex4(low)) return false;
                            if (low >= 0xDC00 && low <= 0xDFFF) {
                                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                            }
                        }
                        appendUtf8(out, cp);
                        break;
                    }
                    default:
                        out.push_back(esc);
                        break;
                }
            } else {
                out.push_back(c);
            }
        }
        return false;
    }

    bool parseNumber(lua_State* L) {
        const char* start = cur_;
        bool isFloat = false;
        if (*cur_ == '-') ++cur_;
        while (cur_ < end_ && *cur_ >= '0' && *cur_ <= '9') ++cur_;
        if (cur_ < end_ && *cur_ == '.') {
            isFloat = true;
            ++cur_;
            while (cur_ < end_ && *cur_ >= '0' && *cur_ <= '9') ++cur_;
        }
        if (cur_ < end_ && (*cur_ == 'e' || *cur_ == 'E')) {
            isFloat = true;
            ++cur_;
            if (cur_ < end_ && (*cur_ == '+' || *cur_ == '-')) ++cur_;
            while (cur_ < end_ && *cur_ >= '0' && *cur_ <= '9') ++cur_;
        }
        std::string token(start, cur_);
        if (!isFloat) {
            errno = 0;
            char* ep = nullptr;
            long long iv = std::strtoll(token.c_str(), &ep, 10);
            if (errno == 0 && ep && *ep == '\0') {
                lua_pushinteger(L, static_cast<lua_Integer>(iv));
                return true;
            }
        }
        char* ep = nullptr;
        double dv = std::strtod(token.c_str(), &ep);
        if (ep && *ep == '\0') {
            lua_pushnumber(L, static_cast<lua_Number>(dv));
            return true;
        }
        return false;
    }

    const char* cur_;
    const char* end_;
};

// ============================================================================
// Lua Instruction Cancellation Hook
// ============================================================================
void luaCancelHook(lua_State* L, lua_Debug* /*ar*/) {
    auto* eng = getEngine(L);
    if (eng && eng->isCancelled()) {
        // Re-arm hook on every instruction so user pcall() cannot trap cancel
        lua_sethook(L, luaCancelHook, LUA_MASKCOUNT | LUA_MASKRET, 1);
        lua_pushstring(L, kCancelSentinel);
        lua_error(L);
    }
}

// ============================================================================
// Complete bit32 Compatibility Library (Lua 5.2 / GG scripts)
// ============================================================================
uint32_t toU32(lua_State* L, int idx) {
    lua_Number n = luaL_checknumber(L, idx);
    int64_t iv = static_cast<int64_t>(n);
    return static_cast<uint32_t>(iv & 0xFFFFFFFFLL);
}

int b32_band(lua_State* L) {
    int n = lua_gettop(L);
    uint32_t r = 0xFFFFFFFFu;
    for (int i = 1; i <= n; ++i) r &= toU32(L, i);
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_bor(lua_State* L) {
    int n = lua_gettop(L);
    uint32_t r = 0;
    for (int i = 1; i <= n; ++i) r |= toU32(L, i);
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_bxor(lua_State* L) {
    int n = lua_gettop(L);
    uint32_t r = 0;
    for (int i = 1; i <= n; ++i) r ^= toU32(L, i);
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_bnot(lua_State* L) {
    uint32_t r = ~toU32(L, 1);
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_btest(lua_State* L) {
    int n = lua_gettop(L);
    uint32_t r = 0xFFFFFFFFu;
    for (int i = 1; i <= n; ++i) r &= toU32(L, i);
    lua_pushboolean(L, r != 0);
    return 1;
}

int b32_lshift(lua_State* L) {
    uint32_t v = toU32(L, 1);
    lua_Integer disp = luaL_checkinteger(L, 2);
    uint32_t r = 0;
    if (disp >= 0) {
        r = (disp >= 32) ? 0u : (v << disp);
    } else {
        lua_Integer rd = -disp;
        r = (rd >= 32) ? 0u : (v >> rd);
    }
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_rshift(lua_State* L) {
    uint32_t v = toU32(L, 1);
    lua_Integer disp = luaL_checkinteger(L, 2);
    uint32_t r = 0;
    if (disp >= 0) {
        r = (disp >= 32) ? 0u : (v >> disp);
    } else {
        lua_Integer ld = -disp;
        r = (ld >= 32) ? 0u : (v << ld);
    }
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_arshift(lua_State* L) {
    uint32_t v = toU32(L, 1);
    lua_Integer disp = luaL_checkinteger(L, 2);
    uint32_t r = 0;
    if (disp < 0) {
        lua_Integer ld = -disp;
        r = (ld >= 32) ? 0u : (v << ld);
    } else if (disp >= 32) {
        r = (v & 0x80000000u) ? 0xFFFFFFFFu : 0u;
    } else {
        r = static_cast<uint32_t>(static_cast<int32_t>(v) >> disp);
    }
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_lrotate(lua_State* L) {
    uint32_t v = toU32(L, 1);
    int d = static_cast<int>(luaL_checkinteger(L, 2)) & 31;
    uint32_t r = (d == 0) ? v : ((v << d) | (v >> (32 - d)));
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_rrotate(lua_State* L) {
    uint32_t v = toU32(L, 1);
    int d = static_cast<int>(luaL_checkinteger(L, 2)) & 31;
    uint32_t r = (d == 0) ? v : ((v >> d) | (v << (32 - d)));
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

int b32_extract(lua_State* L) {
    uint32_t v = toU32(L, 1);
    lua_Integer f = luaL_checkinteger(L, 2);
    lua_Integer w = luaL_optinteger(L, 3, 1);
    if (f < 0 || w <= 0 || f + w > 32) {
        return luaL_error(L, "trying to access non-existent bits");
    }
    uint32_t mask = (w == 32) ? 0xFFFFFFFFu : ((1u << w) - 1u);
    lua_pushinteger(L, static_cast<lua_Integer>((v >> f) & mask));
    return 1;
}

int b32_replace(lua_State* L) {
    uint32_t n = toU32(L, 1);
    uint32_t v = toU32(L, 2);
    lua_Integer f = luaL_checkinteger(L, 3);
    lua_Integer w = luaL_optinteger(L, 4, 1);
    if (f < 0 || w <= 0 || f + w > 32) {
        return luaL_error(L, "trying to access non-existent bits");
    }
    uint32_t mask = (w == 32) ? 0xFFFFFFFFu : ((1u << w) - 1u);
    uint32_t r = (n & ~(mask << f)) | ((v & mask) << f);
    lua_pushinteger(L, static_cast<lua_Integer>(r));
    return 1;
}

const luaL_Reg kBit32Funcs[] = {
    {"band",    b32_band},
    {"bor",     b32_bor},
    {"bxor",    b32_bxor},
    {"bnot",    b32_bnot},
    {"btest",   b32_btest},
    {"lshift",  b32_lshift},
    {"rshift",  b32_rshift},
    {"arshift", b32_arshift},
    {"lrotate", b32_lrotate},
    {"rrotate", b32_rrotate},
    {"extract", b32_extract},
    {"replace", b32_replace},
    {nullptr,   nullptr}
};

// ============================================================================
// Pretty-printer for print(...) (GG prints tables nicely!)
// ============================================================================
void formatLuaValue(lua_State* L, int idx, std::string& out, int depth, std::vector<const void*>& seen) {
    int absIdx = lua_absindex(L, idx);
    int t = lua_type(L, absIdx);
    switch (t) {
        case LUA_TNIL:
            out += "nil";
            break;
        case LUA_TBOOLEAN:
            out += (lua_toboolean(L, absIdx) ? "true" : "false");
            break;
        case LUA_TNUMBER: {
            if (lua_isinteger(L, absIdx)) {
                char buf[32];
                std::snprintf(buf, sizeof(buf), "%" PRId64, static_cast<int64_t>(lua_tointeger(L, absIdx)));
                out += buf;
            } else {
                char buf[48];
                std::snprintf(buf, sizeof(buf), "%.14g", static_cast<double>(lua_tonumber(L, absIdx)));
                out += buf;
            }
            break;
        }
        case LUA_TSTRING: {
            size_t len = 0;
            const char* s = lua_tolstring(L, absIdx, &len);
            if (depth > 0) {
                out.push_back('\'');
                out.append(s ? s : "", len);
                out.push_back('\'');
            } else {
                out.append(s ? s : "", len);
            }
            break;
        }
        case LUA_TTABLE: {
            if (depth >= 4) {
                out += "{...}";
                break;
            }
            const void* ptr = lua_topointer(L, absIdx);
            if (std::find(seen.begin(), seen.end(), ptr) != seen.end()) {
                out += "{<cycle>}";
                break;
            }
            seen.push_back(ptr);
            out += "{\n";
            int shown = 0;
            lua_pushnil(L);
            while (lua_next(L, absIdx) != 0) {
                if (shown >= 64) {
                    lua_pop(L, 2);
                    for (int k = 0; k <= depth; ++k) out += "  ";
                    out += "...\n";
                    break;
                }
                for (int k = 0; k <= depth; ++k) out += "  ";
                out.push_back('[');
                formatLuaValue(L, -2, out, depth + 1, seen);
                out += "] = ";
                formatLuaValue(L, -1, out, depth + 1, seen);
                out += ",\n";
                ++shown;
                lua_pop(L, 1);
            }
            for (int k = 0; k < depth; ++k) out += "  ";
            out.push_back('}');
            seen.pop_back();
            break;
        }
        default: {
            size_t len = 0;
            const char* s = luaL_tolstring(L, absIdx, &len);
            if (s) out.append(s, len);
            lua_pop(L, 1);
            break;
        }
    }
}

int luaSafePrint(lua_State* L) {
    auto* eng = getEngine(L);
    int n = lua_gettop(L);
    std::string line;
    std::vector<const void*> seen;
    for (int i = 1; i <= n; ++i) {
        if (i > 1) line.push_back('\t');
        formatLuaValue(L, i, line, 0, seen);
    }
    if (eng) {
        eng->emitPrint(line);
    }
    return 0;
}

// ============================================================================
// Sandbox Overrides: safe os.exit, disabled os.execute/io.popen, mkdir io.open
// ============================================================================
int luaSafeOsExit(lua_State* L) {
    int code = 0;
    if (lua_isboolean(L, 1)) {
        code = lua_toboolean(L, 1) ? 0 : 1;
    } else if (lua_isinteger(L, 1)) {
        code = static_cast<int>(lua_tointeger(L, 1));
    }
    std::string sentinel = std::string(kExitSentinelPrefix) + std::to_string(code);
    lua_pushlstring(L, sentinel.data(), sentinel.size());
    return lua_error(L);
}

int luaDisabledShell(lua_State* L) {
    lua_pushnil(L);
    lua_pushliteral(L, "shell execution is disabled in GGNext sandbox");
    return 2;
}

int luaWrappedIoOpen(lua_State* L) {
    const char* filename = luaL_checkstring(L, 1);
    const char* mode = luaL_optstring(L, 2, "r");
    if (filename && mode && (std::strchr(mode, 'w') != nullptr || std::strchr(mode, 'a') != nullptr)) {
        ensureParentDirectories(filename);
    }
    // Call original io.open stored in upvalue 1
    lua_pushvalue(L, lua_upvalueindex(1));
    lua_insert(L, 1);
    lua_call(L, lua_gettop(L) - 1, LUA_MULTRET);
    return lua_gettop(L);
}

// ============================================================================
// Native GG functions: getFile, getLine, sleep, bytes, saveVariable
// ============================================================================
int gg_getFile(lua_State* L) {
    auto* eng = getEngine(L);
    lua_Debug ar{};
    for (int level = 1; level <= 16; ++level) {
        if (!lua_getstack(L, level, &ar)) break;
        if (lua_getinfo(L, "S", &ar) && ar.source && ar.source[0] == '@') {
            lua_pushstring(L, ar.source + 1);
            return 1;
        }
    }
    if (eng && !eng->currentScriptPath().empty()) {
        lua_pushlstring(L, eng->currentScriptPath().data(), eng->currentScriptPath().size());
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

int gg_getLine(lua_State* L) {
    lua_Debug ar{};
    for (int level = 1; level <= 16; ++level) {
        if (!lua_getstack(L, level, &ar)) break;
        if (lua_getinfo(L, "l", &ar) && ar.currentline > 0) {
            lua_pushinteger(L, static_cast<lua_Integer>(ar.currentline));
            return 1;
        }
    }
    lua_pushinteger(L, 0);
    return 1;
}

int gg_sleep(lua_State* L) {
    auto* eng = getEngine(L);
    lua_Integer ms = luaL_checkinteger(L, 1);
    if (ms <= 0) return 0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    while (true) {
        if (eng && eng->isCancelled()) {
            lua_pushstring(L, kCancelSentinel);
            return lua_error(L);
        }
        auto now = std::chrono::steady_clock::now();
        if (now >= deadline) break;
        auto rem = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
        auto step = std::min<int64_t>(rem, 20);
        if (step > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(step));
        }
    }
    return 0;
}

// UTF-8 decode helper for gg.bytes
std::vector<uint32_t> decodeUtf8(const char* s, size_t len) {
    std::vector<uint32_t> cps;
    size_t i = 0;
    while (i < len) {
        uint8_t c = static_cast<uint8_t>(s[i]);
        if (c < 0x80) {
            cps.push_back(c);
            ++i;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
            uint32_t cp = ((c & 0x1F) << 6) | (static_cast<uint8_t>(s[i + 1]) & 0x3F);
            cps.push_back(cp);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
            uint32_t cp = ((c & 0x0F) << 12) |
                          ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 6) |
                          (static_cast<uint8_t>(s[i + 2]) & 0x3F);
            cps.push_back(cp);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len) {
            uint32_t cp = ((c & 0x07) << 18) |
                          ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 12) |
                          ((static_cast<uint8_t>(s[i + 2]) & 0x3F) << 6) |
                          (static_cast<uint8_t>(s[i + 3]) & 0x3F);
            cps.push_back(cp);
            i += 4;
        } else {
            cps.push_back('?');
            ++i;
        }
    }
    return cps;
}

int gg_bytes(lua_State* L) {
    size_t len = 0;
    const char* text = luaL_checklstring(L, 1, &len);
    const char* encRaw = luaL_optstring(L, 2, "UTF-8");
    std::string enc(encRaw ? encRaw : "UTF-8");
    std::transform(enc.begin(), enc.end(), enc.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });

    std::vector<uint8_t> out;
    if (enc == "UTF-8" || enc == "UTF8") {
        out.assign(reinterpret_cast<const uint8_t*>(text), reinterpret_cast<const uint8_t*>(text) + len);
    } else if (enc == "US-ASCII" || enc == "ASCII") {
        auto cps = decodeUtf8(text, len);
        for (uint32_t cp : cps) out.push_back(cp <= 0x7F ? static_cast<uint8_t>(cp) : static_cast<uint8_t>('?'));
    } else if (enc == "ISO-8859-1" || enc == "LATIN1") {
        auto cps = decodeUtf8(text, len);
        for (uint32_t cp : cps) out.push_back(cp <= 0xFF ? static_cast<uint8_t>(cp) : static_cast<uint8_t>('?'));
    } else if (enc == "UTF-16LE" || enc == "UTF-16BE" || enc == "UTF-16") {
        bool be = (enc == "UTF-16BE" || enc == "UTF-16");
        if (enc == "UTF-16") {
            out.push_back(0xFE);
            out.push_back(0xFF);
        }
        auto pushUnit = [&](uint16_t u) {
            if (be) {
                out.push_back(static_cast<uint8_t>((u >> 8) & 0xFF));
                out.push_back(static_cast<uint8_t>(u & 0xFF));
            } else {
                out.push_back(static_cast<uint8_t>(u & 0xFF));
                out.push_back(static_cast<uint8_t>((u >> 8) & 0xFF));
            }
        };
        auto cps = decodeUtf8(text, len);
        for (uint32_t cp : cps) {
            if (cp <= 0xFFFF) {
                pushUnit(static_cast<uint16_t>(cp));
            } else {
                uint32_t v = cp - 0x10000;
                pushUnit(static_cast<uint16_t>(0xD800 + ((v >> 10) & 0x3FF)));
                pushUnit(static_cast<uint16_t>(0xDC00 + (v & 0x3FF)));
            }
        }
    } else {
        out.assign(reinterpret_cast<const uint8_t*>(text), reinterpret_cast<const uint8_t*>(text) + len);
    }

    lua_createtable(L, static_cast<int>(out.size()), 0);
    for (size_t i = 0; i < out.size(); ++i) {
        lua_pushinteger(L, static_cast<lua_Integer>(out[i]));
        lua_rawseti(L, -2, static_cast<lua_Integer>(i + 1));
    }
    return 1;
}

// Cycle-safe gg.saveVariable implementation generating valid Lua code loadable via loadfile()
struct SaveVarCtx {
    std::unordered_map<const void*, std::string> tableNames;
    std::ostringstream decls;
    std::ostringstream assigns;
    int nextId = 1;

    static std::string quoteLuaString(const char* data, size_t len) {
        std::string out;
        out.reserve(len + 2);
        out.push_back('"');
        for (size_t i = 0; i < len; ++i) {
            unsigned char c = static_cast<unsigned char>(data[i]);
            switch (c) {
                case '"':  out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                case '\0': out += "\\000"; break;
                default:
                    if (c < 0x20 || c == 0x7F) {
                        char b[8];
                        std::snprintf(b, sizeof(b), "\\%03u", static_cast<unsigned>(c));
                        out += b;
                    } else {
                        out.push_back(static_cast<char>(c));
                    }
                    break;
            }
        }
        out.push_back('"');
        return out;
    }

    std::string serializeExpr(lua_State* L, int idx, int depth) {
        if (depth > 64) return "nil";
        int absIdx = lua_absindex(L, idx);
        int t = lua_type(L, absIdx);
        switch (t) {
            case LUA_TNIL:
                return "nil";
            case LUA_TBOOLEAN:
                return lua_toboolean(L, absIdx) ? "true" : "false";
            case LUA_TNUMBER: {
                if (lua_isinteger(L, absIdx)) {
                    char buf[32];
                    std::snprintf(buf, sizeof(buf), "%" PRId64, static_cast<int64_t>(lua_tointeger(L, absIdx)));
                    return buf;
                }
                double d = static_cast<double>(lua_tonumber(L, absIdx));
                if (std::isnan(d)) return "(0/0)";
                if (std::isinf(d)) return d > 0 ? "(1/0)" : "(-1/0)";
                char buf[48];
                std::snprintf(buf, sizeof(buf), "%.17g", d);
                return buf;
            }
            case LUA_TSTRING: {
                size_t len = 0;
                const char* s = lua_tolstring(L, absIdx, &len);
                return quoteLuaString(s ? s : "", len);
            }
            case LUA_TTABLE: {
                const void* ptr = lua_topointer(L, absIdx);
                auto it = tableNames.find(ptr);
                if (it != tableNames.end()) {
                    return it->second;
                }
                std::string varName = "t" + std::to_string(nextId++);
                tableNames[ptr] = varName;
                decls << "local " << varName << " = {}\n";

                luaL_checkstack(L, 6, "saveVariable");
                lua_pushnil(L);
                while (lua_next(L, absIdx) != 0) {
                    int kt = lua_type(L, -2);
                    int vt = lua_type(L, -1);
                    if ((kt == LUA_TNUMBER || kt == LUA_TSTRING || kt == LUA_TBOOLEAN || kt == LUA_TTABLE) &&
                        (vt == LUA_TNUMBER || vt == LUA_TSTRING || vt == LUA_TBOOLEAN || vt == LUA_TTABLE)) {
                        std::string kExpr = serializeExpr(L, -2, depth + 1);
                        std::string vExpr = serializeExpr(L, -1, depth + 1);
                        assigns << varName << "[" << kExpr << "] = " << vExpr << "\n";
                    }
                    lua_pop(L, 1);
                }
                return varName;
            }
            default:
                return "nil";
        }
    }
};

int gg_saveVariable(lua_State* L) {
    const char* filename = luaL_checkstring(L, 2);
    if (!filename || filename[0] == '\0') {
        lua_pushliteral(L, "Invalid filename");
        return 1;
    }
    std::string path(filename);
    ensureParentDirectories(path);

    SaveVarCtx ctx;
    std::string rootExpr = ctx.serializeExpr(L, 1, 0);
    std::ofstream ofs(path, std::ios::out | std::ios::binary | std::ios::trunc);
    if (!ofs.is_open()) {
        std::string err = "Failed to open file for writing: " + path;
        lua_pushlstring(L, err.data(), err.size());
        return 1;
    }
    ofs << ctx.decls.str();
    ofs << ctx.assigns.str();
    ofs << "return " << rootExpr << "\n";
    ofs.close();
    if (ofs.fail()) {
        std::string err = "Failed to write file: " + path;
        lua_pushlstring(L, err.data(), err.size());
        return 1;
    }
    lua_pushboolean(L, 1);
    return 1;
}

// ============================================================================
// Generic Host RPC Bridge for gg.* and ui.*
// ============================================================================
int invokeHostAndPushResult(lua_State* L, const std::string& method, const std::string& argsJson) {
    auto* eng = getEngine(L);
    if (!eng) {
        return luaL_error(L, "GGNext LuaEngine not attached");
    }
    if (eng->isCancelled()) {
        lua_pushstring(L, kCancelSentinel);
        return lua_error(L);
    }

    std::string resp = eng->invokeHost(method, argsJson);

    if (eng->isCancelled()) {
        lua_pushstring(L, kCancelSentinel);
        return lua_error(L);
    }

    if (resp.empty()) {
        lua_pushnil(L);
        return 1;
    }

    JsonParser parser(resp.data(), resp.size());
    if (!parser.parseToLua(L) || !lua_istable(L, -1)) {
        lua_pop(L, 1);
        lua_pushlstring(L, resp.data(), resp.size());
        return 1;
    }

    // Envelope: {"ok":bool, "value":any, "error":string, "raise":bool, "cancelled":bool}
    lua_getfield(L, -1, "cancelled");
    bool isCancelled = lua_toboolean(L, -1) != 0;
    lua_pop(L, 1);
    if (isCancelled) {
        lua_pop(L, 1);
        lua_pushstring(L, kCancelSentinel);
        return lua_error(L);
    }

    lua_getfield(L, -1, "ok");
    bool ok = lua_toboolean(L, -1) != 0;
    lua_pop(L, 1);

    if (!ok) {
        lua_getfield(L, -1, "raise");
        bool shouldRaise = lua_toboolean(L, -1) != 0;
        lua_pop(L, 1);

        lua_getfield(L, -1, "error");
        std::string errMsg = lua_isstring(L, -1) ? lua_tostring(L, -1) : "Host call failed";
        lua_pop(L, 2); // pop error + envelope

        if (shouldRaise) {
            return luaL_error(L, "%s", errMsg.c_str());
        }
        lua_pushlstring(L, errMsg.data(), errMsg.size());
        return 1;
    }

    lua_getfield(L, -1, "multi");
    if (lua_istable(L, -1)) {
        lua_Integer n = static_cast<lua_Integer>(lua_rawlen(L, -1));
        luaL_checkstack(L, static_cast<int>(n) + 2, "multi return");
        int arrIdx = lua_gettop(L);
        for (lua_Integer i = 1; i <= n; ++i) {
            lua_rawgeti(L, arrIdx, i);
        }
        // Remove envelope and multi array beneath the pushed results
        lua_remove(L, arrIdx);
        lua_remove(L, arrIdx - 1);
        return static_cast<int>(n);
    }
    lua_pop(L, 1); // pop nil multi

    lua_getfield(L, -1, "value");
    lua_remove(L, -2); // remove envelope
    return 1;
}

int ggHostDispatch(lua_State* L) {
    const char* method = lua_tostring(L, lua_upvalueindex(1));
    std::string argsJson = encodeArgsToJsonArray(L, 1);
    return invokeHostAndPushResult(L, std::string("gg.") + (method ? method : ""), argsJson);
}

int uiHostDispatch(lua_State* L) {
    const char* method = lua_tostring(L, lua_upvalueindex(1));
    std::string argsJson = encodeArgsToJsonArray(L, 1);
    return invokeHostAndPushResult(L, std::string("ui.") + (method ? method : ""), argsJson);
}

// ============================================================================
// Plugin Module Native C Functions (`plugin.*`)
// ============================================================================
bool isValidModuleIdentifier(const std::string& name) {
    if (name.empty() || name.size() > 64) return false;
    for (size_t i = 0; i < name.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(name[i]);
        bool ok = (std::isalpha(c) || c == '_' || (i > 0 && std::isdigit(c)));
        if (!ok) return false;
    }
    return true;
}

int plugin_apiVersion(lua_State* L) {
    lua_pushinteger(L, static_cast<lua_Integer>(GGNEXT_HOST_API_VERSION));
    return 1;
}

int plugin_loadNative(lua_State* L) {
    auto* eng = getEngine(L);
    const char* modName = luaL_checkstring(L, 1);
    const char* soPath  = luaL_checkstring(L, 2);
    if (!eng) return luaL_error(L, "LuaEngine unavailable");

    // Ask host to validate / stage .so path (e.g. Android 14/15 0555 read-only policy)
    std::string argsJson = "[";
    appendJsonString(argsJson, modName ? modName : "");
    argsJson.push_back(',');
    appendJsonString(argsJson, soPath ? soPath : "");
    argsJson.push_back(']');

    std::string stagedPath = soPath ? soPath : "";
    std::string hostResp = eng->invokeHost("plugin.prepareNative", argsJson);
    if (!hostResp.empty()) {
        JsonParser p(hostResp.data(), hostResp.size());
        if (p.parseToLua(L) && lua_istable(L, -1)) {
            lua_getfield(L, -1, "ok");
            bool ok = lua_toboolean(L, -1) != 0;
            lua_pop(L, 1);
            if (!ok) {
                lua_getfield(L, -1, "error");
                std::string err = lua_isstring(L, -1) ? lua_tostring(L, -1) : "Native plugin blocked by host policy";
                lua_pop(L, 2);
                lua_pushnil(L);
                lua_pushlstring(L, err.data(), err.size());
                return 2;
            }
            lua_getfield(L, -1, "value");
            if (lua_isstring(L, -1)) {
                stagedPath = lua_tostring(L, -1);
            }
            lua_pop(L, 2);
        } else {
            lua_pop(L, 1);
        }
    }

    std::string err;
    if (!eng->loadNativePlugin(modName ? modName : "", stagedPath, &err)) {
        lua_pushnil(L);
        lua_pushlstring(L, err.data(), err.size());
        return 2;
    }

    // Return package.loaded[modName]
    lua_getglobal(L, "package");
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, "loaded");
        if (lua_istable(L, -1)) {
            lua_getfield(L, -1, modName);
            lua_remove(L, -2);
            lua_remove(L, -2);
            return 1;
        }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
    lua_pushboolean(L, 1);
    return 1;
}

int plugin_registerPreload(lua_State* L) {
    const char* modName = luaL_checkstring(L, 1);
    if (!lua_isfunction(L, 2) && !lua_isstring(L, 2)) {
        return luaL_argerror(L, 2, "expected function or Lua source string");
    }
    lua_getglobal(L, "package");
    lua_getfield(L, -1, "preload");
    if (lua_isstring(L, 2)) {
        size_t len = 0;
        const char* src = lua_tolstring(L, 2, &len);
        std::string chunkName = std::string("@preload:") + modName;
        if (luaL_loadbufferx(L, src, len, chunkName.c_str(), "t") != LUA_OK) {
            return lua_error(L);
        }
    } else {
        lua_pushvalue(L, 2);
    }
    lua_setfield(L, -2, modName);
    lua_pop(L, 2);
    lua_pushboolean(L, 1);
    return 1;
}

int plugin_listLoaded(lua_State* L) {
    auto* eng = getEngine(L);
    lua_newtable(L);
    if (eng) {
        auto names = eng->loadedPluginNames();
        for (size_t i = 0; i < names.size(); ++i) {
            lua_pushlstring(L, names[i].data(), names[i].size());
            lua_rawseti(L, -2, static_cast<lua_Integer>(i + 1));
        }
    }
    return 1;
}

// ============================================================================
// Lua Bootstrap: Compatibility Shims + Declarative UI Builder (`ui.*`)
// ============================================================================
constexpr const char* kCompatAndUiBootstrap = R"LUA(
-- 1. Lua 5.1/5.2/5.3 compatibility shims on Lua 5.5.1
if loadstring == nil then loadstring = load end
if unpack == nil then unpack = table.unpack end
if table.getn == nil then
    table.getn = function(t) return #t end
end
if table.maxn == nil then
    table.maxn = function(t)
        local m = 0
        for k in pairs(t) do
            if type(k) == 'number' and k > m then m = k end
        end
        return m
    end
end
if table.foreach == nil then
    table.foreach = function(t, f)
        for k, v in pairs(t) do
            local r = f(k, v)
            if r ~= nil then return r end
        end
    end
end
if table.foreachi == nil then
    table.foreachi = function(t, f)
        for i = 1, #t do
            local r = f(i, t[i])
            if r ~= nil then return r end
        end
    end
end
if math.pow == nil then math.pow = function(a, b) return a ^ b end end
if math.atan2 == nil then math.atan2 = math.atan end
if math.log10 == nil then math.log10 = function(x) return math.log(x, 10) end end
if math.cosh == nil then math.cosh = function(x) return 0.5 * (math.exp(x) + math.exp(-x)) end end
if math.sinh == nil then math.sinh = function(x) return 0.5 * (math.exp(x) - math.exp(-x)) end end
if math.tanh == nil then math.tanh = function(x) local e2 = math.exp(2 * x); return (e2 - 1) / (e2 + 1) end end
if math.ldexp == nil then math.ldexp = function(m, e) return m * (2.0 ^ e) end end
if math.frexp == nil then
    math.frexp = function(x)
        if x == 0 then return 0, 0 end
        local absx = math.abs(x)
        local e = math.floor(math.log(absx, 2)) + 1
        local m = x / (2.0 ^ e)
        if math.abs(m) >= 1.0 then m = m * 0.5; e = e + 1 end
        return m, e
    end
end

-- 2. Declarative Lua <-> Compose UI Bridge (`ui.*`)
-- Pure Lua tables describing Compose trees mapped to GGNext GgPrimitives/Rose.
local _cbRegistry = {}
local _cbSeq = 0

local function _regCallback(fn)
    if type(fn) ~= 'function' then return nil end
    _cbSeq = _cbSeq + 1
    local id = 'cb_' .. tostring(_cbSeq)
    _cbRegistry[id] = fn
    return id
end

local function _normalizeNode(node)
    if type(node) ~= 'table' then return node end
    local out = {}
    for k, v in pairs(node) do
        if k == 'onClick' and type(v) == 'function' then
            out.onClickId = _regCallback(v)
        elseif k == 'onChange' and type(v) == 'function' then
            out.onChangeId = _regCallback(v)
        elseif k == 'onSelect' and type(v) == 'function' then
            out.onSelectId = _regCallback(v)
        elseif k == 'onSubmit' and type(v) == 'function' then
            out.onSubmitId = _regCallback(v)
        elseif k == 'children' and type(v) == 'table' then
            local arr = {}
            for i = 1, #v do
                arr[i] = _normalizeNode(v[i])
            end
            out.children = arr
        elseif type(v) ~= 'function' then
            out[k] = v
        end
    end
    return out
end

local function _makeContainer(kind, propsOrChildren, maybeChildren)
    local node = { type = kind }
    if type(propsOrChildren) == 'table' and propsOrChildren[1] ~= nil and maybeChildren == nil then
        node.children = propsOrChildren
    elseif type(propsOrChildren) == 'table' then
        for k, v in pairs(propsOrChildren) do node[k] = v end
        if maybeChildren ~= nil then node.children = maybeChildren end
    end
    if node.children == nil then node.children = {} end
    return node
end

ui.column = function(p, c) return _makeContainer('column', p, c) end
ui.row    = function(p, c) return _makeContainer('row', p, c) end
ui.card   = function(p, c) return _makeContainer('card', p, c) end
ui.scroll = function(p, c) return _makeContainer('scroll', p, c) end

ui.text = function(arg)
    if type(arg) == 'string' or type(arg) == 'number' then
        return { type = 'text', text = tostring(arg) }
    end
    local n = { type = 'text' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.badge = function(arg)
    if type(arg) == 'string' or type(arg) == 'number' then
        return { type = 'badge', text = tostring(arg) }
    end
    local n = { type = 'badge' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.code = function(arg)
    if type(arg) == 'string' then
        return { type = 'code', text = arg }
    end
    local n = { type = 'code' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.button = function(arg, onClick)
    if type(arg) == 'string' then
        return { type = 'button', label = arg, onClick = onClick }
    end
    local n = { type = 'button' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    if onClick ~= nil then n.onClick = onClick end
    return n
end

ui.input = function(arg)
    local n = { type = 'input' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.switch = function(arg)
    local n = { type = 'switch' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.checkbox = function(arg)
    local n = { type = 'checkbox' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.slider = function(arg)
    local n = { type = 'slider' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.list = function(arg)
    local n = { type = 'list' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.icon = function(arg)
    if type(arg) == 'string' then return { type = 'icon', name = arg } end
    local n = { type = 'icon' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.image = function(arg)
    if type(arg) == 'string' then return { type = 'image', path = arg } end
    local n = { type = 'image' }
    if type(arg) == 'table' then for k, v in pairs(arg) do n[k] = v end end
    return n
end

ui.divider = function() return { type = 'divider' } end
ui.spacer = function(size) return { type = 'spacer', size = tonumber(size) or 8 } end

ui._dispatch = function(cbId, payload)
    local fn = _cbRegistry[cbId]
    if type(fn) == 'function' then
        return fn(payload)
    end
end

ui.pumpEvents = function(timeoutMs)
    local ev = ui._pollEvent(timeoutMs or 0)
    while type(ev) == 'table' do
        if ev.type == 'closed' then
            return false
        elseif ev.type == 'event' and ev.callbackId then
            local ok, err = pcall(ui._dispatch, ev.callbackId, ev.payload)
            if not ok then
                print('[UI Callback Error] ' .. tostring(err))
            end
        end
        ev = ui._pollEvent(0)
    end
    return true
end

ui.show = function(tree, opts)
    opts = opts or {}
    if opts.clearCallbacks ~= false then
        _cbRegistry = {}
    end
    local normalized = _normalizeNode(tree)
    local ok = ui._show({
        title = opts.title or 'Script UI',
        subtitle = opts.subtitle or '',
        dismissible = (opts.dismissible ~= false),
        root = normalized
    })
    if not ok then return false end
    if opts.modal == false then
        return true
    end
    -- Modal event loop executed cooperatively on the script worker thread
    while true do
        local ev = ui._pollEvent(80)
        if type(ev) == 'table' then
            if ev.type == 'closed' then
                return true
            elseif ev.type == 'event' and ev.callbackId then
                local cok, cerr = pcall(ui._dispatch, ev.callbackId, ev.payload)
                if not cok then
                    print('[UI Callback Error] ' .. tostring(cerr))
                end
            end
        end
    end
end

ui.update = function(targetOrTree, patch)
    if type(targetOrTree) == 'table' then
        return ui._update({ mode = 'replace', root = _normalizeNode(targetOrTree) })
    elseif type(targetOrTree) == 'string' and type(patch) == 'table' then
        return ui._update({ mode = 'patch', id = targetOrTree, patch = _normalizeNode(patch) })
    end
    return false
end

ui.close = function()
    return ui._close()
end

ui.pollEvent = function(timeoutMs)
    return ui._pollEvent(timeoutMs or 0)
end

-- 3. GGNext ShadowHook Lua callback dispatcher (strictly under gg.*)
local _hookCallbacks = {}
local _hookLastSeq = 0

local function _splitHookOpts(optsOrFn)
    local cleanOpts = {}
    local cbs = nil
    if type(optsOrFn) == 'function' then
        cbs = { onEnter = optsOrFn }
    elseif type(optsOrFn) == 'table' then
        for k, v in pairs(optsOrFn) do
            if (k == 'onEnter' or k == 'onLeave') and type(v) == 'function' then
                cbs = cbs or {}
                cbs[k] = v
            elseif type(v) ~= 'function' then
                cleanOpts[k] = v
            end
        end
    end
    return cleanOpts, cbs
end

local _rawHookSymbol = gg.hookSymbol
gg.hookSymbol = function(libName, symName, optsOrFn)
    local cleanOpts, cbs = _splitHookOpts(optsOrFn)
    local id, info = _rawHookSymbol(libName, symName, cleanOpts)
    if id and cbs then
        _hookCallbacks[id] = cbs
    end
    return id, info
end

local _rawHookAddress = gg.hookAddress
gg.hookAddress = function(address, optsOrFn)
    local cleanOpts, cbs = _splitHookOpts(optsOrFn)
    local id, info = _rawHookAddress(address, cleanOpts)
    if id and cbs then
        _hookCallbacks[id] = cbs
    end
    return id, info
end

local _rawInterceptAddress = gg.interceptAddress
gg.interceptAddress = function(address, optsOrFn)
    local cleanOpts, cbs = _splitHookOpts(optsOrFn)
    local id, info = _rawInterceptAddress(address, cleanOpts)
    if id and cbs then
        _hookCallbacks[id] = cbs
    end
    return id, info
end

local _rawUnhook = gg.unhook
gg.unhook = function(hookId)
    if hookId ~= nil then _hookCallbacks[hookId] = nil end
    return _rawUnhook(hookId)
end

local _rawUnhookAll = gg.unhookAll
gg.unhookAll = function()
    _hookCallbacks = {}
    return _rawUnhookAll()
end

gg.pollHooks = function(limit)
    local logs = gg.getHookLogs(0, _hookLastSeq, limit or 64)
    if type(logs) ~= 'table' then return 0 end
    local handled = 0
    for i = 1, #logs do
        local rec = logs[i]
        if type(rec) == 'table' then
            if rec.seq and rec.seq > _hookLastSeq then
                _hookLastSeq = rec.seq
            end
            local cbs = _hookCallbacks[rec.hookId]
            if cbs then
                if cbs.onEnter then
                    local ok, patch = pcall(cbs.onEnter, rec)
                    if ok and type(patch) == 'table' then
                        gg.updateHook(rec.hookId, patch)
                    elseif not ok then
                        print('[Hook onEnter Error] ' .. tostring(patch))
                    end
                end
                if cbs.onLeave then
                    local ok, patch = pcall(cbs.onLeave, rec)
                    if ok and type(patch) == 'table' then
                        gg.updateHook(rec.hookId, patch)
                    elseif not ok then
                        print('[Hook onLeave Error] ' .. tostring(patch))
                    end
                end
                handled = handled + 1
            end
        end
    end
    return handled
end
)LUA";

} // anonymous namespace

// ============================================================================
// LuaEngine Implementation
// ============================================================================

LuaEngine::LuaEngine() = default;

LuaEngine::~LuaEngine() {
    closeState();
}

void LuaEngine::setHostCallback(HostCallFn cb) {
    hostCb_ = std::move(cb);
}

void LuaEngine::setPrintCallback(PrintFn cb) {
    printCb_ = std::move(cb);
}

void LuaEngine::setRuntimeProps(const LuaRuntimeProps& props) {
    props_ = props;
}

void LuaEngine::setPluginHostApi(const GGNextHostApiV1* api) {
    pluginHostApi_ = api;
}

void LuaEngine::requestCancel() {
    cancelFlag_.store(true, std::memory_order_release);
}

bool LuaEngine::isCancelled() const {
    return cancelFlag_.load(std::memory_order_acquire);
}

std::string LuaEngine::invokeHost(const std::string& method, const std::string& argsJson) {
    if (!hostCb_) {
        return "{\"ok\":false,\"error\":\"Host bridge not configured\"}";
    }
    return hostCb_(method, argsJson);
}

void LuaEngine::emitPrint(const std::string& line) {
    if (printCb_) {
        printCb_(line);
    }
}

void LuaEngine::recordLoadedNativePlugin(const std::string& name, void* dlHandle) {
    std::lock_guard<std::mutex> lk(pluginMtx_);
    dlHandles_.emplace_back(name, dlHandle);
}

std::vector<std::string> LuaEngine::loadedPluginNames() const {
    std::lock_guard<std::mutex> lk(pluginMtx_);
    std::vector<std::string> out;
    out.reserve(dlHandles_.size());
    for (const auto& kv : dlHandles_) out.push_back(kv.first);
    return out;
}

void LuaEngine::closeState() {
    if (L_) {
        lua_close(L_);
        L_ = nullptr;
    }
    std::lock_guard<std::mutex> lk(pluginMtx_);
    for (auto it = dlHandles_.rbegin(); it != dlHandles_.rend(); ++it) {
        if (it->second) {
            ::dlclose(it->second);
        }
    }
    dlHandles_.clear();
}

bool LuaEngine::initState(const std::string& scriptPath) {
    closeState();
    cancelFlag_.store(false, std::memory_order_release);
    currentScriptPath_ = scriptPath;

    L_ = luaL_newstate();
    if (!L_) return false;

    // Open standard Lua 5.5.1 libraries
    luaL_openlibs(L_);

    // Store LuaEngine* in registry
    lua_pushlightuserdata(L_, &kEngineRegKey);
    lua_pushlightuserdata(L_, this);
    lua_rawset(L_, LUA_REGISTRYINDEX);

    // Expose versioned C Host API table in registry for external C++ .so plugins
    if (pluginHostApi_) {
        lua_pushlightuserdata(L_, const_cast<GGNextHostApiV1*>(pluginHostApi_));
        lua_setfield(L_, LUA_REGISTRYINDEX, GGNEXT_HOST_API_REGISTRY_KEY);
    }

    // Install cancellation hook (every 2000 VM instructions)
    lua_sethook(L_, luaCancelHook, LUA_MASKCOUNT, 2000);

    registerSandboxOverrides(L_);
    registerCompatShim(L_);
    registerGgModule(L_);
    registerUiModule(L_);
    registerPluginModule(L_);
    configurePackagePaths(L_, scriptPath);

    // Execute Lua compatibility + declarative UI bootstrap
    if (luaL_loadbufferx(L_, kCompatAndUiBootstrap, std::strlen(kCompatAndUiBootstrap), "@ggnext_bootstrap", "t") != LUA_OK ||
        lua_pcall(L_, 0, 0, 0) != LUA_OK) {
        const char* err = lua_tostring(L_, -1);
        emitPrint(std::string("[Bootstrap Error] ") + (err ? err : "unknown"));
        lua_pop(L_, 1);
        closeState();
        return false;
    }

    // Install any pre-registered pure Lua preload modules
    for (const auto& kv : pendingPreloads_) {
        std::string err;
        registerPreloadModule(kv.first, kv.second, &err);
    }

    return true;
}

void LuaEngine::registerCompatShim(lua_State* L) {
    luaL_newlib(L, kBit32Funcs);
    lua_setglobal(L, "bit32");

    // Register bit32 in package.loaded so `require("bit32")` also works
    lua_getglobal(L, "package");
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, "loaded");
        if (lua_istable(L, -1)) {
            lua_getglobal(L, "bit32");
            lua_setfield(L, -2, "bit32");
        }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
}

void LuaEngine::registerSandboxOverrides(lua_State* L) {
    // 1. Replace global print with GG-style table pretty-printer
    lua_pushcfunction(L, luaSafePrint);
    lua_setglobal(L, "print");

    // 2. Sandbox `os`: safe os.exit sentinel + disable os.execute
    lua_getglobal(L, "os");
    if (lua_istable(L, -1)) {
        lua_pushcfunction(L, luaSafeOsExit);
        lua_setfield(L, -2, "exit");

        lua_pushcfunction(L, luaDisabledShell);
        lua_setfield(L, -2, "execute");
    }
    lua_pop(L, 1);

    // 3. Sandbox `io`: disable io.popen + wrap io.open to auto-create parent dirs on write modes
    lua_getglobal(L, "io");
    if (lua_istable(L, -1)) {
        lua_pushcfunction(L, luaDisabledShell);
        lua_setfield(L, -2, "popen");

        lua_getfield(L, -1, "open");
        if (lua_isfunction(L, -1)) {
            lua_pushcclosure(L, luaWrappedIoOpen, 1);
            lua_setfield(L, -2, "open");
        } else {
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);
}

void LuaEngine::configurePackagePaths(lua_State* L, const std::string& scriptPath) {
    lua_getglobal(L, "package");
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return;
    }

    std::string extraPaths;
    auto addDir = [&](const std::string& dir) {
        if (dir.empty()) return;
        std::string clean = dir;
        while (clean.size() > 1 && clean.back() == '/') clean.pop_back();
        if (!extraPaths.empty()) extraPaths.push_back(';');
        extraPaths += clean + "/?.lua;" + clean + "/?/init.lua";
    };

    if (!scriptPath.empty()) {
        auto slash = scriptPath.find_last_of('/');
        if (slash != std::string::npos && slash > 0) {
            addDir(scriptPath.substr(0, slash));
        }
    }
    if (!props_.pluginDir.empty()) {
        addDir(props_.pluginDir);
    }

    if (!extraPaths.empty()) {
        lua_getfield(L, -1, "path");
        std::string curPath = lua_isstring(L, -1) ? lua_tostring(L, -1) : "";
        lua_pop(L, 1);
        std::string merged = extraPaths;
        if (!curPath.empty()) merged += ";" + curPath;
        lua_pushlstring(L, merged.data(), merged.size());
        lua_setfield(L, -2, "path");
    }

    lua_pop(L, 1);
}

void LuaEngine::registerGgModule(lua_State* L) {
    lua_newtable(L);

    auto setInt = [&](const char* k, lua_Integer v) {
        lua_pushinteger(L, v);
        lua_setfield(L, -2, k);
    };
    auto setStr = [&](const char* k, const std::string& v) {
        lua_pushlstring(L, v.data(), v.size());
        lua_setfield(L, -2, k);
    };
    auto setHostFn = [&](const char* name) {
        lua_pushstring(L, name);
        lua_pushcclosure(L, ggHostDispatch, 1);
        lua_setfield(L, -2, name);
    };
    auto setNativeFn = [&](const char* name, lua_CFunction fn) {
        lua_pushcfunction(L, fn);
        lua_setfield(L, -2, name);
    };

    // ---- Public Properties (gg_class_api.md) ----
    setStr("ANDROID_SDK_INT", props_.androidSdkInt);
    setStr("PACKAGE",         props_.packageName);
    setStr("VERSION",         props_.version);
    setInt("VERSION_INT",     props_.versionInt);
    setInt("BUILD",           props_.build);
    setStr("FILES_DIR",       props_.filesDir);
    setStr("CACHE_DIR",       props_.cacheDir);
    setStr("EXT_FILES_DIR",   props_.extFilesDir);
    setStr("EXT_CACHE_DIR",   props_.extCacheDir);
    setStr("EXT_STORAGE",     props_.extStorage);

    // ---- TYPE_* Constants (exact GG & ValueType.kt bitmasks) ----
    setInt("TYPE_BYTE",   1);
    setInt("TYPE_WORD",   2);
    setInt("TYPE_DWORD",  4);
    setInt("TYPE_XOR",    8);
    setInt("TYPE_FLOAT",  16);
    setInt("TYPE_QWORD",  32);
    setInt("TYPE_DOUBLE", 64);
    setInt("TYPE_AUTO",   127);

    // ---- SIGN_* & SIGN_FUZZY_* Constants ----
    setInt("SIGN_EQUAL",            0x20000000);
    setInt("SIGN_NOT_EQUAL",        0x10000000);
    setInt("SIGN_LESS_OR_EQUAL",    0x08000000);
    setInt("SIGN_GREATER_OR_EQUAL", 0x04000000);
    setInt("SIGN_FUZZY_EQUAL",      0x20000000);
    setInt("SIGN_FUZZY_NOT_EQUAL",  0x10000000);
    setInt("SIGN_FUZZY_LESS",       0x08000000);
    setInt("SIGN_FUZZY_GREATER",    0x04000000);

    // ---- REGION_* Constants (exact original GG non-overlapping bitmasks) ----
    setInt("REGION_C_HEAP",    1);
    setInt("REGION_JAVA_HEAP", 2);
    setInt("REGION_C_ALLOC",   4);
    setInt("REGION_C_DATA",    8);
    setInt("REGION_C_BSS",     16);
    setInt("REGION_ANONYMOUS", 32);
    setInt("REGION_STACK",     64);
    setInt("REGION_CODE_APP",  16384);
    setInt("REGION_CODE_SYS",  32768);
    setInt("REGION_JAVA",      65536);
    setInt("REGION_BAD",       131072);
    setInt("REGION_PPSSPP",    262144);
    setInt("REGION_ASHMEM",    524288);
    setInt("REGION_VIDEO",     1048576);
    setInt("REGION_OTHER",     -2080896);

    // ---- LOAD_* / SAVE_* / FREEZE_* Constants ----
    setInt("LOAD_VALUES",        2);
    setInt("LOAD_VALUES_FREEZE", 3);
    setInt("LOAD_APPEND",        8);
    setInt("SAVE_AS_TEXT",       1);

    setInt("FREEZE_NORMAL",       0);
    setInt("FREEZE_MAY_INCREASE", 1);
    setInt("FREEZE_MAY_DECREASE", 2);
    setInt("FREEZE_IN_RANGE",     3);

    // ---- PROT_* Constants ----
    setInt("PROT_NONE",  0);
    setInt("PROT_READ",  1);
    setInt("PROT_WRITE", 2);
    setInt("PROT_EXEC",  4);

    // ---- POINTER_* Constants ----
    setInt("POINTER_EXECUTABLE_WRITABLE", 1);
    setInt("POINTER_EXECUTABLE",          2);
    setInt("POINTER_NO",                  4);
    setInt("POINTER_READ_ONLY",           8);
    setInt("POINTER_WRITABLE",            16);

    // ---- DUMP_* / TAB_* / ASM_* Constants ----
    setInt("DUMP_SKIP_SYSTEM_LIBS", 1);

    setInt("TAB_SETTINGS",      0);
    setInt("TAB_SEARCH",        1);
    setInt("TAB_SAVED_LIST",    2);
    setInt("TAB_MEMORY_EDITOR", 3);

    setInt("ASM_ARM",   4);
    setInt("ASM_THUMB", 5);
    setInt("ASM_ARM64", 6);

    // ---- GGNext Exclusive Constants (under gg.*) ----
    setInt("BP_READ",       1);
    setInt("BP_WRITE",      2);
    setInt("BP_READ_WRITE", 3);
    setInt("BP_EXEC",       4);
    setInt("BP_MODE_PERF",   0);
    setInt("BP_MODE_PTRACE", 1);
    setStr("HOOK_MODE_INLINE",    "hook");
    setStr("HOOK_MODE_INTERCEPT", "intercept");

    // ---- Native C++ Direct GG Functions ----
    setNativeFn("getFile",      gg_getFile);
    setNativeFn("getLine",      gg_getLine);
    setNativeFn("sleep",        gg_sleep);
    setNativeFn("bytes",        gg_bytes);
    setNativeFn("saveVariable", gg_saveVariable);

    // ---- Host-Bridged GG Functions (Full Coverage of gg_class_api.md + GGNext Extensions) ----
    static const char* kHostGgMethods[] = {
        // General & Memory
        "allocatePage", "copyMemory", "copyText", "disasm", "dumpMemory",
        "getRangesList", "getTargetInfo", "getTargetPackage", "isPackageInstalled",
        "makeRequest", "processKill", "require", "skipRestoreState", "unrandomizer",
        // [GGNext-Patch] 网络脚本标准 API
        "downloadFile", "loadRemoteScript", "getFileContent", "getScriptDir",
        // Dialogs & Toast
        "toast", "alert", "prompt", "choice", "multiChoice",
        // UI
        "isVisible", "setVisible", "getActiveTab",
        "showUiButton", "hideUiButton", "isClickedUiButton",
        // Regions
        "getRanges", "setRanges",
        // Search & Results (including legacy alias getResultCount)
        "searchNumber", "refineNumber", "startFuzzy", "searchFuzzy",
        "searchAddress", "refineAddress", "searchPointer",
        "getResultsCount", "getResultCount", "getResults", "clearResults",
        "removeResults", "loadResults", "getSelectedResults",
        // Values & Edit
        "editAll", "setValues", "getValues", "getValuesRange",
        // Memory Editor
        "gotoAddress", "getSelectedElements",
        // Process & Speedhack
        "processPause", "processResume", "processToggle", "isProcessPaused",
        "timeJump", "getSpeed", "setSpeed",
        // Saved List
        "loadList", "saveList", "clearList",
        "addListItems", "getListItems", "removeListItems", "getSelectedListItems",
        // Locale
        "getLocale", "numberToLocale", "numberFromLocale",
        // ---- GGNext Exclusive Extensions (strictly under gg.*) ----
        // ShadowHook & Trampoline Active Call
        "hookSymbol", "hookAddress", "interceptAddress", "updateHook",
        "unhook", "unhookAll", "getHooks", "getHookLogs", "clearHookLogs",
        "callOriginal", "callFunction",
        // Hardware Breakpoints & Live Registers
        "addBreakpoint", "removeBreakpoint", "getBreakpoints",
        "setBreakpointPaused", "clearBreakpointHits",
        "getBreakpointTrace", "clearBreakpointTrace", "setBreakpointRegister",
        // ELF / .so Module Inspection
        "getModuleBase", "getModuleExports", "findSymbol"
    };
    for (const char* m : kHostGgMethods) {
        setHostFn(m);
    }

    lua_setglobal(L, "gg");

    // Also register in package.loaded["gg"]
    lua_getglobal(L, "package");
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, "loaded");
        if (lua_istable(L, -1)) {
            lua_getglobal(L, "gg");
            lua_setfield(L, -2, "gg");
        }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
}

void LuaEngine::registerUiModule(lua_State* L) {
    lua_newtable(L);
    auto setUiHostFn = [&](const char* luaName, const char* hostMethod) {
        lua_pushstring(L, hostMethod);
        lua_pushcclosure(L, uiHostDispatch, 1);
        lua_setfield(L, -2, luaName);
    };
    setUiHostFn("_show",      "show");
    setUiHostFn("_update",    "update");
    setUiHostFn("_close",     "close");
    setUiHostFn("_pollEvent", "pollEvent");
    lua_setglobal(L, "ui");

    lua_getglobal(L, "package");
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, "loaded");
        if (lua_istable(L, -1)) {
            lua_getglobal(L, "ui");
            lua_setfield(L, -2, "ui");
        }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
}

void LuaEngine::registerPluginModule(lua_State* L) {
    lua_newtable(L);
    lua_pushcfunction(L, plugin_apiVersion);
    lua_setfield(L, -2, "apiVersion");
    lua_pushcfunction(L, plugin_loadNative);
    lua_setfield(L, -2, "loadNative");
    lua_pushcfunction(L, plugin_registerPreload);
    lua_setfield(L, -2, "registerPreload");
    lua_pushcfunction(L, plugin_listLoaded);
    lua_setfield(L, -2, "listLoaded");
    lua_setglobal(L, "plugin");

    lua_getglobal(L, "package");
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, "loaded");
        if (lua_istable(L, -1)) {
            lua_getglobal(L, "plugin");
            lua_setfield(L, -2, "plugin");
        }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
}

LuaRunResult LuaEngine::executeProtected(lua_State* L, int loadStatus) {
    LuaRunResult res;
    if (loadStatus != LUA_OK) {
        const char* err = lua_tostring(L, -1);
        res.ok = false;
        res.error = err ? err : "Failed to load Lua script";
        lua_pop(L, 1);
        return res;
    }

    int status = lua_pcall(L, 0, 0, 0);
    if (status == LUA_OK) {
        res.ok = true;
        return res;
    }

    const char* err = lua_tostring(L, -1);
    std::string msg = err ? err : "Unknown Lua runtime error";
    lua_pop(L, 1);

    if (msg.find(kCancelSentinel) != std::string::npos || isCancelled()) {
        res.ok = false;
        res.cancelled = true;
        res.error = "Script cancelled by user";
        return res;
    }

    auto exitPos = msg.find(kExitSentinelPrefix);
    if (exitPos != std::string::npos) {
        res.ok = true;
        res.exited = true;
        std::string codeStr = msg.substr(exitPos + std::strlen(kExitSentinelPrefix));
        res.exitCode = std::atoi(codeStr.c_str());
        return res;
    }

    res.ok = false;
    res.error = msg;
    return res;
}

// ============================================================================
// [GGNext-Patch] 脚本格式探测：区分「明文 Lua / 标准 Lua 字节码 / GG 私有加密」
//   标准字节码以 ESC(0x1B) + "Lua" 开头，Lua 5.5.1 的 undump 原生支持，直接放行。
//   GameGuardian 的 .enc 为私有加密格式，无公开规范，此处给出明确提示而非语法错误。
// ============================================================================
namespace {

enum class ScriptFormat {
    Empty,
    PlainText,
    LuaBytecode,   // 标准 luac / string.dump 产物，交给 undump
    UnknownBinary  // 非标准二进制（很可能为 GG 私有 .enc）
};

inline ScriptFormat detectScriptFormat(const std::string& s) {
    if (s.empty()) return ScriptFormat::Empty;
    const unsigned char c0 = static_cast<unsigned char>(s[0]);
    if (c0 == 0x1B) {
        // Lua signature: ESC 'L' 'u' 'a'
        if (s.size() >= 4 &&
            s[1] == 'L' && s[2] == 'u' && s[3] == 'a') {
            return ScriptFormat::LuaBytecode;
        }
        return ScriptFormat::UnknownBinary;
    }
    if (c0 == 0x00) return ScriptFormat::UnknownBinary;
    return ScriptFormat::PlainText;
}

// 提取字节码头部里的版本字节（位于 "Lua" 之后：格式号1字节 + 版本1字节）
inline std::string describeBytecodeVersion(const std::string& s) {
    if (s.size() < 6) return "unknown";
    const unsigned char fmt = static_cast<unsigned char>(s[4]);
    const unsigned char ver = static_cast<unsigned char>(s[5]);
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%d.%d (format %d)", (ver >> 4) & 0xF, ver & 0xF, fmt);
    return std::string(buf);
}

inline std::string encryptedScriptHint(const std::string& path) {
    std::string name = path;
    const size_t slash = name.find_last_of("/\\");
    if (slash != std::string::npos) name = name.substr(slash + 1);
    return std::string(
        "无法执行加密脚本：") + name +
        " 不是标准 Lua 脚本。\n"
        "可能原因与处理方式：\n"
        "  1) 若为 GameGuardian 官方 .enc 私有加密格式 —— 该格式无公开规范，"
        "第三方引擎无法解密，请使用原版 GameGuardian 运行；\n"
        "  2) 若为预编译 Lua 字节码 —— 请确认由 Lua 5.5 生成"
        "（低版本 luac 产物与本引擎字节码不兼容）；\n"
        "  3) 若为自己分发的加密脚本 —— 请使用 GGNext 的加密工具重新加密，"
        "或直接提供明文 .lua 源码。";
}

} // anonymous namespace

LuaRunResult LuaEngine::runFile(const std::string& scriptPath) {
    LuaRunResult res;
    if (scriptPath.empty()) {
        res.error = "Script path is empty";
        return res;
    }

    // Read file as plain text (explicitly rejecting binary Lua bytecode per requirements)
    std::ifstream ifs(scriptPath, std::ios::in | std::ios::binary);
    if (!ifs.is_open()) {
        res.error = "Cannot open script file: " + scriptPath;
        return res;
    }
    std::ostringstream ss;
    ss << ifs.rdbuf();
    std::string source = ss.str();

    // Strip UTF-8 BOM if present
    if (source.size() >= 3 &&
        static_cast<unsigned char>(source[0]) == 0xEF &&
        static_cast<unsigned char>(source[1]) == 0xBB &&
        static_cast<unsigned char>(source[2]) == 0xBF) {
        source.erase(0, 3);
    }

    // [GGNext-Patch] 分格式处理：明文 / 标准字节码 / 未知二进制
    const ScriptFormat fmt = detectScriptFormat(source);
    if (fmt == ScriptFormat::UnknownBinary) {
        res.error = encryptedScriptHint(scriptPath);
        return res;
    }

    if (!initState(scriptPath)) {
        res.error = "Failed to initialize Lua 5.5.1 VM";
        return res;
    }

    std::string chunkName = "@" + scriptPath;
    int loadStatus = luaL_loadbufferx(L_, source.data(), source.size(), chunkName.c_str(), "bt"); // [GGNext-Patch] 允许二进制 chunk
    res = executeProtected(L_, loadStatus);
    closeState();
    return res;
}

LuaRunResult LuaEngine::runString(const std::string& sourceIn, const std::string& chunkNameIn) {
    LuaRunResult res;
    std::string source = sourceIn;
    if (source.size() >= 3 &&
        static_cast<unsigned char>(source[0]) == 0xEF &&
        static_cast<unsigned char>(source[1]) == 0xBB &&
        static_cast<unsigned char>(source[2]) == 0xBF) {
        source.erase(0, 3);
    }
    // [GGNext-Patch] 分格式处理（远程脚本同样支持标准字节码）
    {
        const ScriptFormat fmt = detectScriptFormat(source);
        if (fmt == ScriptFormat::UnknownBinary) {
            res.error = encryptedScriptHint(chunkNameIn.empty() ? std::string("<string>") : chunkNameIn);
            return res;
        }
    }
    if (!initState(chunkNameIn)) {
        res.error = "Failed to initialize Lua 5.5.1 VM";
        return res;
    }
    std::string chunkName = chunkNameIn.empty() ? "@script.lua" :
        (chunkNameIn[0] == '@' || chunkNameIn[0] == '=' ? chunkNameIn : "@" + chunkNameIn);
    int loadStatus = luaL_loadbufferx(L_, source.data(), source.size(), chunkName.c_str(), "bt"); // [GGNext-Patch] 允许二进制 chunk
    res = executeProtected(L_, loadStatus);
    closeState();
    return res;
}

bool LuaEngine::dispatchUiEvent(const std::string& callbackId, const std::string& payloadJson, std::string* outError) {
    if (!L_) {
        if (outError) *outError = "Lua VM is not active";
        return false;
    }
    lua_getglobal(L_, "ui");
    if (!lua_istable(L_, -1)) {
        lua_pop(L_, 1);
        if (outError) *outError = "ui table missing";
        return false;
    }
    lua_getfield(L_, -1, "_dispatch");
    lua_remove(L_, -2);
    if (!lua_isfunction(L_, -1)) {
        lua_pop(L_, 1);
        if (outError) *outError = "ui._dispatch missing";
        return false;
    }
    lua_pushlstring(L_, callbackId.data(), callbackId.size());
    if (payloadJson.empty()) {
        lua_pushnil(L_);
    } else {
        JsonParser parser(payloadJson.data(), payloadJson.size());
        if (!parser.parseToLua(L_)) {
            lua_pushlstring(L_, payloadJson.data(), payloadJson.size());
        }
    }
    if (lua_pcall(L_, 2, 0, 0) != LUA_OK) {
        const char* err = lua_tostring(L_, -1);
        if (outError) *outError = err ? err : "UI callback error";
        lua_pop(L_, 1);
        return false;
    }
    return true;
}

bool LuaEngine::loadNativePlugin(const std::string& moduleName, const std::string& soPath, std::string* outError) {
    if (!L_) {
        if (outError) *outError = "Lua VM is not active";
        return false;
    }
    if (!isValidModuleIdentifier(moduleName)) {
        if (outError) *outError = "Invalid plugin module name (use [A-Za-z_][A-Za-z0-9_]*)";
        return false;
    }
    if (soPath.empty()) {
        if (outError) *outError = "Empty plugin .so path";
        return false;
    }

    void* handle = ::dlopen(soPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* dlErr = ::dlerror();
        if (outError) *outError = std::string("dlopen failed: ") + (dlErr ? dlErr : "unknown");
        return false;
    }

    std::string initSym = "ggnext_plugin_init_" + moduleName;
    std::string openSym = "luaopen_" + moduleName;

    auto initFn = reinterpret_cast<GGNextPluginInitFn>(::dlsym(handle, initSym.c_str()));
    auto openFn = reinterpret_cast<GGNextPluginLuaOpenFn>(::dlsym(handle, openSym.c_str()));
    if (!initFn && !openFn) {
        ::dlclose(handle);
        if (outError) *outError = "Neither " + initSym + " nor " + openSym + " exported by " + soPath;
        return false;
    }

    int retCount = 0;
    if (initFn) {
        retCount = initFn(L_, pluginHostApi_);
    } else {
        lua_pushcfunction(L_, openFn);
        lua_pushlstring(L_, moduleName.data(), moduleName.size());
        if (lua_pcall(L_, 1, 1, 0) != LUA_OK) {
            const char* err = lua_tostring(L_, -1);
            if (outError) *outError = err ? err : "luaopen failed";
            lua_pop(L_, 1);
            ::dlclose(handle);
            return false;
        }
        retCount = 1;
    }

    if (retCount < 1) {
        lua_pushboolean(L_, 1);
    }

    // Store module result in package.loaded[moduleName]
    lua_getglobal(L_, "package");
    if (lua_istable(L_, -1)) {
        lua_getfield(L_, -1, "loaded");
        if (lua_istable(L_, -1)) {
            lua_pushvalue(L_, -3);
            lua_setfield(L_, -2, moduleName.c_str());
        }
        lua_pop(L_, 1);
    }
    lua_pop(L_, 2); // pop package + module value

    recordLoadedNativePlugin(moduleName, handle);
    return true;
}

bool LuaEngine::registerPreloadModule(const std::string& moduleName, const std::string& luaSource, std::string* outError) {
    if (!isValidModuleIdentifier(moduleName)) {
        if (outError) *outError = "Invalid module name";
        return false;
    }
    if (!L_) {
        pendingPreloads_.emplace_back(moduleName, luaSource);
        return true;
    }
    lua_getglobal(L_, "package");
    if (!lua_istable(L_, -1)) {
        lua_pop(L_, 1);
        if (outError) *outError = "package table missing";
        return false;
    }
    lua_getfield(L_, -1, "preload");
    if (!lua_istable(L_, -1)) {
        lua_pop(L_, 2);
        if (outError) *outError = "package.preload missing";
        return false;
    }
    std::string chunkName = "@preload:" + moduleName;
    if (luaL_loadbufferx(L_, luaSource.data(), luaSource.size(), chunkName.c_str(), "t") != LUA_OK) {
        const char* err = lua_tostring(L_, -1);
        if (outError) *outError = err ? err : "load preload failed";
        lua_pop(L_, 3);
        return false;
    }
    lua_setfield(L_, -2, moduleName.c_str());
    lua_pop(L_, 2);
    return true;
}

} // namespace mem_engine
