#include "mem_engine/types.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/process_enum.hpp"
#include "mem_engine/proc_io.hpp"
#include "mem_engine/search_engine.hpp"
#include "mem_engine/debugger.hpp"
#include "mem_engine/remote_alloc.hpp"

#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

char nibbleToHex(uint8_t v) {
    return (v < 10) ? static_cast<char>('0' + v) : static_cast<char>('A' + (v - 10));
}

int hexToNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string bytesToHex(const uint8_t* data, size_t len) {
    std::string out;
    out.resize(len * 2);
    for (size_t i = 0; i < len; ++i) {
        out[i * 2]     = nibbleToHex((data[i] >> 4) & 0x0F);
        out[i * 2 + 1] = nibbleToHex(data[i] & 0x0F);
    }
    return out;
}

std::vector<uint8_t> hexToBytes(const std::string& hex) {
    std::vector<uint8_t> out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i + 1 < hex.size(); i += 2) {
        int hi = hexToNibble(hex[i]);
        int lo = hexToNibble(hex[i + 1]);
        if (hi < 0 || lo < 0) break;
        out.push_back(static_cast<uint8_t>((hi << 4) | lo));
    }
    return out;
}

std::string formatPerms(const mem_engine::VmaRegion& r) {
    std::string p = "----";
    if (r.readable)   p[0] = 'r';
    if (r.writable)   p[1] = 'w';
    if (r.executable) p[2] = 'x';
    p[3] = r.isShared ? 's' : 'p';
    return p;
}

bool isPidAlive(int pid) {
    if (pid <= 0) return false;
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    return ::access(path, F_OK) == 0;
}

} // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IOFBF, 65536);
    std::string spillDir = "/data/local/tmp";
    size_t threads = 4;
    if (argc >= 2 && argv[1] && argv[1][0] != '\0') {
        spillDir = argv[1];
    }
    if (argc >= 3 && argv[2]) {
        int t = std::atoi(argv[2]);
        if (t >= 1 && t <= 16) threads = static_cast<size_t>(t);
    }

    mem_engine::ProcIO io;
    mem_engine::SearchEngine scanner(io, threads, spillDir);
    mem_engine::ValueLocker locker(io, 10);
    mem_engine::BreakpointManager bpManager;
    mem_engine::CancellationToken cancelToken;
    std::vector<mem_engine::VmaRegion> cachedAllRegions;
    mem_engine::VmaIndex cachedVmaIndex;
    std::vector<uint64_t> pendingRemoveAddrs;

    std::mutex stdoutMutex;
    std::mutex progressMutex;
    std::thread scanThread;
    std::atomic<bool> scanActive{false};

    auto waitForScan = [&]() {
        if (scanThread.joinable()) {
            scanThread.join();
        }
        scanActive.store(false, std::memory_order_release);
    };

    auto makeProgressReporter = [&]() -> mem_engine::ProgressCallback {
        auto lastReport = std::make_shared<std::chrono::steady_clock::time_point>(
            std::chrono::steady_clock::now() - std::chrono::milliseconds(100)
        );
        return [&, lastReport](size_t done, size_t total) {
            auto now = std::chrono::steady_clock::now();
            std::lock_guard<std::mutex> lk(progressMutex);
            auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - *lastReport).count();
            if (elapsedMs >= 45 || done >= total) {
                *lastReport = now;
                std::lock_guard<std::mutex> outLk(stdoutMutex);
                std::printf("PROGRESS %zu %zu %zu\n", done, total, scanner.totalMatchedCount());
                std::fflush(stdout);
            }
        };
    };

    auto launchScan = [&](std::function<mem_engine::EngineErrc(mem_engine::ProgressCallback)> task) {
        waitForScan();
        cancelToken.reset();
        scanActive.store(true, std::memory_order_release);
        auto reporter = makeProgressReporter();
        scanThread = std::thread([&, task = std::move(task), reporter = std::move(reporter)]() {
            mem_engine::EngineErrc rc = task(reporter);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (rc == mem_engine::EngineErrc::ERR_CANCELLED) {
                std::printf("CANCELLED %zu\n", scanner.totalMatchedCount());
            } else if (rc == mem_engine::EngineErrc::ERR_SRCH) {
                std::printf("ERR -2\n");
            } else if (rc != mem_engine::EngineErrc::OK) {
                std::printf("ERR %d\n", static_cast<int>(rc));
            } else {
                std::printf("OK %zu\n", scanner.totalMatchedCount());
            }
            std::fflush(stdout);
            scanActive.store(false, std::memory_order_release);
        });
    };

    std::string line;
    while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        if (line.empty()) continue;

        std::istringstream iss(line);
        std::string cmd;
        iss >> cmd;

        // Asynchronous out-of-band commands handled immediately without blocking on scanThread
        if (cmd == "CANCEL") {
            cancelToken.cancel();
            continue;
        }
        if (cmd == "EXIT" || cmd == "QUIT") {
            cancelToken.cancel();
            waitForScan();
            bpManager.stopAll();
            locker.unlockAll();
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK BYE\n");
            std::fflush(stdout);
            break;
        }
        if (cmd == "PING") {
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK PONG\n");
            std::fflush(stdout);
            continue;
        }

        // All state-mutating / scan / IO commands synchronize with any prior scanThread
        waitForScan();

        if (cmd == "ALIVE") {
            bool alive = isPidAlive(io.pid());
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %d\n", alive ? 1 : 0);
            std::fflush(stdout);
        } else if (cmd == "ATTACH") {
            int pid = 0;
            iss >> pid;
            locker.unlockAll();
            auto rc = io.attach(pid);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (rc == mem_engine::EngineErrc::OK) {
                scanner.refreshRegions();
                cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(pid, 0, false);
                cachedVmaIndex.rebuild(cachedAllRegions);
                std::printf("OK 0 %zu\n", cachedAllRegions.size());
            } else {
                cachedAllRegions.clear();
                cachedVmaIndex.rebuild(cachedAllRegions);
                std::printf("ERR %d\n", static_cast<int>(rc));
            }
            std::fflush(stdout);
        } else if (cmd == "SET_MASK") {
            uint32_t mask = 0;
            iss >> mask;
            scanner.setRegionMask(mask);
            if (io.pid() > 0) {
                scanner.refreshRegions();
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "SET_ADDR_FILTER") {
            std::string minHex = "0", maxHex = "0", maskHex = "0", patHex = "0";
            iss >> minHex >> maxHex >> maskHex >> patHex;
            uint64_t minA = std::strtoull(minHex.c_str(), nullptr, 16);
            uint64_t maxA = std::strtoull(maxHex.c_str(), nullptr, 16);
            uint64_t maskA = std::strtoull(maskHex.c_str(), nullptr, 16);
            uint64_t patA = std::strtoull(patHex.c_str(), nullptr, 16);
            scanner.setAddressFilter(minA, maxA, maskA, patA);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "REGIONS") {
            int pid = io.pid();
            if (pid > 0 && !isPidAlive(pid)) {
                std::lock_guard<std::mutex> outLk(stdoutMutex);
                std::printf("ERR -2\n");
                std::fflush(stdout);
                continue;
            }
            if (pid > 0) {
                cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(pid, 0, false);
                cachedVmaIndex.rebuild(cachedAllRegions);
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", cachedAllRegions.size());
            for (const auto& r : cachedAllRegions) {
                std::string perms = formatPerms(r);
                const char* code = mem_engine::regionFlagToTag(r.regionFlag);
                std::string path = r.pathname.empty() ? "-" : r.pathname;
                std::printf(
                    "%llx %llx %s %s %s\n",
                    static_cast<unsigned long long>(r.startAddress),
                    static_cast<unsigned long long>(r.endAddress),
                    perms.c_str(),
                    code,
                    path.c_str()
                );
            }
            std::fflush(stdout);
        } else if (cmd == "READ") {
            std::string addrHex;
            size_t size = 0;
            iss >> addrHex >> size;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (size == 0 || size > 16 * 1024 * 1024) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            std::vector<uint8_t> buf(size, 0);
            size_t bytesRead = 0;
            auto rc = io.readMemory(addr, buf.data(), size, &bytesRead);
            if (rc == mem_engine::EngineErrc::ERR_SRCH) {
                std::printf("ERR -2\n");
            } else {
                std::string hex = bytesToHex(buf.data(), size);
                std::printf("OK %s\n", hex.c_str());
            }
            std::fflush(stdout);
        } else if (cmd == "BATCH_READ") {
            size_t count = 0;
            iss >> count;
            if (count == 0 || count > 8192) {
                std::lock_guard<std::mutex> outLk(stdoutMutex);
                std::printf("OK 0\n");
                std::fflush(stdout);
                continue;
            }
            if (io.pid() > 0 && !isPidAlive(io.pid())) {
                std::lock_guard<std::mutex> outLk(stdoutMutex);
                std::printf("ERR -2\n");
                std::fflush(stdout);
                continue;
            }
            std::vector<uintptr_t> addrs(count, 0);
            std::vector<size_t> sizes(count, 4);
            for (size_t i = 0; i < count; ++i) {
                std::string token;
                if (!(iss >> token)) {
                    count = i;
                    break;
                }
                size_t colonPos = token.find(':');
                if (colonPos != std::string::npos) {
                    addrs[i] = static_cast<uintptr_t>(std::strtoull(token.substr(0, colonPos).c_str(), nullptr, 16));
                    size_t sz = static_cast<size_t>(std::strtoull(token.substr(colonPos + 1).c_str(), nullptr, 10));
                    sizes[i] = (sz >= 1 && sz <= 8) ? sz : 4;
                } else {
                    addrs[i] = static_cast<uintptr_t>(std::strtoull(token.c_str(), nullptr, 16));
                    sizes[i] = 4;
                }
            }
            std::vector<uint8_t> slab(count * 8, 0);
            std::vector<mem_engine::BatchReadRange> ranges(count);
            for (size_t i = 0; i < count; ++i) {
                ranges[i].remoteAddr = addrs[i];
                ranges[i].length     = sizes[i];
                ranges[i].localBuf   = slab.data() + (i * 8);
                ranges[i].success    = false;
            }
            auto batchRc = io.readBatchScattered(ranges);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (batchRc == mem_engine::EngineErrc::ERR_SRCH) {
                std::printf("ERR -2\n");
                std::fflush(stdout);
                continue;
            }
            std::printf("OK %zu\n", count);
            for (size_t i = 0; i < count; ++i) {
                int64_t rawVal = 0;
                if (ranges[i].success) {
                    std::memcpy(&rawVal, slab.data() + (i * 8), sizes[i]);
                } else {
                    size_t bytesRead = 0;
                    if (io.readMemory(addrs[i], slab.data() + (i * 8), sizes[i], &bytesRead) == mem_engine::EngineErrc::OK && bytesRead > 0) {
                        ranges[i].success = true;
                        std::memcpy(&rawVal, slab.data() + (i * 8), sizes[i]);
                    }
                }
                std::printf(
                    "%llx %d %lld\n",
                    static_cast<unsigned long long>(addrs[i]),
                    ranges[i].success ? 1 : 0,
                    static_cast<long long>(rawVal)
                );
            }
            std::fflush(stdout);
        } else if (cmd == "WRITE") {
            std::string addrHex, hexBytes;
            iss >> addrHex >> hexBytes;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            auto bytes = hexToBytes(hexBytes);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (bytes.empty()) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            auto rc = io.writeMemory(addr, bytes.data(), bytes.size());
            if (rc == mem_engine::EngineErrc::OK) {
                std::printf("OK 1\n");
            } else {
                std::printf("ERR %d\n", static_cast<int>(rc));
            }
            std::fflush(stdout);
        } else if (cmd == "BATCH_WRITE") {
            size_t count = 0;
            iss >> count;
            const size_t safeCount = std::min<size_t>(count, 32768);
            std::vector<std::vector<uint8_t>> payloads;
            std::vector<mem_engine::BatchWriteRange> ranges;
            payloads.reserve(safeCount);
            ranges.reserve(safeCount);
            for (size_t i = 0; i < safeCount; ++i) {
                std::string token;
                if (!(iss >> token)) break;
                size_t sep = token.find(':');
                if (sep == std::string::npos || sep == 0 || sep + 1 >= token.size()) continue;
                uintptr_t addr = static_cast<uintptr_t>(std::strtoull(token.substr(0, sep).c_str(), nullptr, 16));
                auto bytes = hexToBytes(token.substr(sep + 1));
                if (bytes.empty()) continue;
                payloads.push_back(std::move(bytes));
                ranges.push_back({addr, payloads.back().size(), payloads.back().data(), false});
            }
            size_t written = io.writeBatchScattered(ranges);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", written);
            std::fflush(stdout);
        } else if (cmd == "BATCH_WRITE_UNIFORM") {
            std::string hexBytes;
            size_t count = 0;
            iss >> hexBytes >> count;
            auto bytes = hexToBytes(hexBytes);
            const size_t safeCount = std::min<size_t>(count, 65536);
            size_t written = 0;
            if (!bytes.empty() && safeCount > 0) {
                std::vector<mem_engine::BatchWriteRange> ranges;
                ranges.reserve(safeCount);
                for (size_t i = 0; i < safeCount; ++i) {
                    std::string addrHex;
                    if (!(iss >> addrHex)) break;
                    uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
                    ranges.push_back({addr, bytes.size(), bytes.data(), false});
                }
                written = io.writeBatchScattered(ranges);
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", written);
            std::fflush(stdout);
        } else if (cmd == "COPY") {
            std::string fromHex, toHex;
            size_t length = 0;
            iss >> fromHex >> toHex >> length;
            uintptr_t fromAddr = static_cast<uintptr_t>(std::strtoull(fromHex.c_str(), nullptr, 16));
            uintptr_t toAddr = static_cast<uintptr_t>(std::strtoull(toHex.c_str(), nullptr, 16));
            const size_t total = std::min<size_t>(length, 16 * 1024 * 1024);
            const size_t chunk = 64 * 1024;
            std::vector<uint8_t> buf(chunk);
            size_t copied = 0;
            while (copied < total) {
                size_t step = std::min(chunk, total - copied);
                size_t bytesRead = 0;
                auto rrc = io.readMemory(fromAddr + copied, buf.data(), step, &bytesRead);
                if (rrc != mem_engine::EngineErrc::OK || bytesRead == 0) break;
                auto wrc = io.writeMemory(toAddr + copied, buf.data(), bytesRead);
                if (wrc != mem_engine::EngineErrc::OK) break;
                copied += bytesRead;
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", copied);
            std::fflush(stdout);
        } else if (cmd == "DUMP") {
            std::string startHex, endHex, outPath;
            iss >> startHex >> endHex;
            std::getline(iss >> std::ws, outPath);
            uintptr_t startAddr = static_cast<uintptr_t>(std::strtoull(startHex.c_str(), nullptr, 16));
            uintptr_t endAddr = static_cast<uintptr_t>(std::strtoull(endHex.c_str(), nullptr, 16));
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (endAddr <= startAddr || outPath.empty()) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            std::ofstream ofs(outPath, std::ios::out | std::ios::binary | std::ios::trunc);
            if (!ofs.is_open()) {
                std::printf("ERR 0\n");
                std::fflush(stdout);
                continue;
            }
            const size_t total = static_cast<size_t>(std::min<uint64_t>(endAddr - startAddr, 64ULL * 1024ULL * 1024ULL));
            const size_t chunk = 64 * 1024;
            std::vector<char> buf(chunk, 0);
            size_t written = 0;
            while (written < total) {
                size_t step = std::min(chunk, total - written);
                size_t bytesRead = 0;
                std::memset(buf.data(), 0, step);
                io.readMemory(startAddr + written, buf.data(), step, &bytesRead);
                ofs.write(buf.data(), static_cast<std::streamsize>(step));
                written += step;
            }
            ofs.close();
            std::printf("OK %zu\n", written);
            std::fflush(stdout);
        } else if (cmd == "LOCK") {
            std::string addrHex, hexBytes;
            iss >> addrHex >> hexBytes;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            auto bytes = hexToBytes(hexBytes);
            if (!bytes.empty()) {
                locker.lockAddress(addr, bytes.data(), bytes.size());
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "LOCK_CONFIG") {
            std::string addrHex, hexBytes;
            int dt = 2, freezeMode = 0;
            int64_t minVal = 0, maxVal = 0;
            iss >> addrHex >> hexBytes >> dt >> freezeMode >> minVal >> maxVal;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            auto bytes = hexToBytes(hexBytes);
            if (!bytes.empty()) {
                locker.lockAddressConfigured(
                    addr,
                    bytes.data(),
                    bytes.size(),
                    static_cast<mem_engine::DataType>(dt),
                    static_cast<mem_engine::FreezeMode>(freezeMode),
                    minVal,
                    maxVal
                );
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "LOCK_PAUSE") {
            int paused = 0;
            iss >> paused;
            locker.setPaused(paused != 0);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "LOCK_INTERVAL") {
            uint32_t ms = 10;
            iss >> ms;
            locker.setIntervalMs(ms);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "UNLOCK") {
            std::string addrHex;
            iss >> addrHex;
            uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
            locker.unlockAddress(addr);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "UNLOCK_ALL") {
            locker.unlockAll();
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "BATCH_LOCK") {
            size_t count = 0;
            iss >> count;
            size_t locked = 0;
            for (size_t i = 0; i < count && i < 16384; ++i) {
                std::string addrHex, hexBytes;
                int dt = 2, freezeMode = 0;
                int64_t minVal = 0, maxVal = 0;
                if (!(iss >> addrHex >> hexBytes >> dt >> freezeMode >> minVal >> maxVal)) break;
                uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
                auto bytes = hexToBytes(hexBytes);
                if (!bytes.empty()) {
                    locker.lockAddressConfigured(
                        addr,
                        bytes.data(),
                        bytes.size(),
                        static_cast<mem_engine::DataType>(dt),
                        static_cast<mem_engine::FreezeMode>(freezeMode),
                        minVal,
                        maxVal
                    );
                    ++locked;
                }
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", locked);
            std::fflush(stdout);
        } else if (cmd == "BATCH_UNLOCK") {
            size_t count = 0;
            iss >> count;
            for (size_t i = 0; i < count && i < 65536; ++i) {
                std::string addrHex;
                if (!(iss >> addrHex)) break;
                uintptr_t addr = static_cast<uintptr_t>(std::strtoull(addrHex.c_str(), nullptr, 16));
                locker.unlockAddress(addr);
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "CLEAR_SESSION") {
            scanner.clearSession();
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "REMOVE_PREFIX") {
            size_t count = 0;
            iss >> count;
            if (count > 0) {
                scanner.removePrefixCandidates(count);
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "REMOVE_BEGIN") {
            pendingRemoveAddrs.clear();
            size_t reserveHint = 0;
            if (iss >> reserveHint && reserveHint <= 2000000) {
                pendingRemoveAddrs.reserve(reserveHint);
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "REMOVE_CHUNK") {
            size_t count = 0;
            iss >> count;
            for (size_t i = 0; i < count && i < 65536; ++i) {
                std::string hex;
                if (!(iss >> hex)) break;
                pendingRemoveAddrs.push_back(std::strtoull(hex.c_str(), nullptr, 16));
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", pendingRemoveAddrs.size());
            std::fflush(stdout);
        } else if (cmd == "REMOVE_COMMIT") {
            if (!pendingRemoveAddrs.empty()) {
                scanner.removeCandidates(pendingRemoveAddrs.data(), pendingRemoveAddrs.size());
                pendingRemoveAddrs.clear();
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "REMOVE_CANDIDATES") {
            size_t count = 0;
            iss >> count;
            if (count > 0 && count <= 1000000) {
                std::vector<uint64_t> addrs;
                addrs.reserve(count);
                for (size_t i = 0; i < count; ++i) {
                    std::string hex;
                    if (!(iss >> hex)) break;
                    addrs.push_back(std::strtoull(hex.c_str(), nullptr, 16));
                }
                scanner.removeCandidates(addrs.data(), addrs.size());
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", scanner.totalMatchedCount());
            std::fflush(stdout);
        } else if (cmd == "SEARCH_EXACT") {
            int dt = 0, aligned = 1;
            int64_t iTarget = 0;
            double fTarget = 0.0, eps = 1e-4;
            iss >> dt >> iTarget >> fTarget >> eps >> aligned;
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.searchExact(static_cast<mem_engine::DataType>(dt), iTarget, fTarget, eps, aligned != 0, &cancelToken, cb);
            });
        } else if (cmd == "REFINE_EXACT") {
            int dt = 0;
            int64_t iTarget = 0;
            double fTarget = 0.0, eps = 1e-4;
            iss >> dt >> iTarget >> fTarget >> eps;
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.refineExact(static_cast<mem_engine::DataType>(dt), iTarget, fTarget, eps, &cancelToken, cb);
            });
        } else if (cmd == "SEARCH_NEQ") {
            int dt = 0, aligned = 1;
            int64_t iTarget = 0;
            double fTarget = 0.0, eps = 1e-4;
            iss >> dt >> iTarget >> fTarget >> eps >> aligned;
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.searchNotEqual(static_cast<mem_engine::DataType>(dt), iTarget, fTarget, eps, aligned != 0, &cancelToken, cb);
            });
        } else if (cmd == "REFINE_NEQ") {
            int dt = 0;
            int64_t iTarget = 0;
            double fTarget = 0.0, eps = 1e-4;
            iss >> dt >> iTarget >> fTarget >> eps;
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.refineNotEqual(static_cast<mem_engine::DataType>(dt), iTarget, fTarget, eps, &cancelToken, cb);
            });
        } else if (cmd == "SEARCH_RANGE") {
            int dt = 0, inclusive = 1, aligned = 1, excludeRange = 0;
            int64_t iMin = 0, iMax = 0;
            double fMin = 0.0, fMax = 0.0;
            iss >> dt >> iMin >> iMax >> fMin >> fMax >> inclusive >> aligned;
            if (iss >> excludeRange) { /* optional 8th field */ }
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.searchRange(static_cast<mem_engine::DataType>(dt), iMin, iMax, fMin, fMax, inclusive != 0, aligned != 0, &cancelToken, cb, excludeRange != 0);
            });
        } else if (cmd == "REFINE_RANGE") {
            int dt = 0, inclusive = 1, excludeRange = 0;
            int64_t iMin = 0, iMax = 0;
            double fMin = 0.0, fMax = 0.0;
            iss >> dt >> iMin >> iMax >> fMin >> fMax >> inclusive;
            if (iss >> excludeRange) { /* optional 7th field */ }
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.refineRange(static_cast<mem_engine::DataType>(dt), iMin, iMax, fMin, fMax, inclusive != 0, &cancelToken, cb, excludeRange != 0);
            });
        } else if (cmd == "SEARCH_POINTER") {
            int dt = 0, aligned = 1;
            std::string targetHex;
            uint64_t maxOffset = 0;
            uint32_t protMask = mem_engine::PointerProtMask::ALL;
            iss >> dt >> targetHex >> maxOffset >> protMask >> aligned;
            uintptr_t targetAddr = static_cast<uintptr_t>(std::strtoull(targetHex.c_str(), nullptr, 16));
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.searchPointers(
                    static_cast<mem_engine::DataType>(dt),
                    targetAddr,
                    maxOffset,
                    protMask,
                    aligned != 0,
                    &cancelToken,
                    cb
                );
            });
        } else if (cmd == "REFINE_POINTER") {
            int dt = 0;
            std::string targetHex;
            uint64_t maxOffset = 0;
            uint32_t protMask = mem_engine::PointerProtMask::ALL;
            iss >> dt >> targetHex >> maxOffset >> protMask;
            uintptr_t targetAddr = static_cast<uintptr_t>(std::strtoull(targetHex.c_str(), nullptr, 16));
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.refinePointers(
                    static_cast<mem_engine::DataType>(dt),
                    targetAddr,
                    maxOffset,
                    protMask,
                    &cancelToken,
                    cb
                );
            });
        } else if (cmd == "SEARCH_GROUP" || cmd == "REFINE_GROUP") {
            int windowSize = 512, ordered = 0, aligned = 1, count = 0;
            iss >> windowSize >> ordered >> aligned >> count;
            mem_engine::GroupSearchQuery query;
            query.maxSpanBytes = static_cast<size_t>(windowSize > 0 ? windowSize : 512);
            query.ordered = (ordered != 0);
            query.aligned = (aligned != 0);
            for (int i = 0; i < count; ++i) {
                int dt = 0, op = 0;
                int64_t iv = 0, iMax = 0;
                double fv = 0.0, fMax = 0.0;
                iss >> dt >> iv >> fv >> iMax >> fMax >> op;
                mem_engine::GroupFieldItem f;
                f.type = static_cast<mem_engine::DataType>(dt);
                f.iVal = iv;
                f.fVal = fv;
                f.iMax = iMax;
                f.fMax = fMax;
                f.op = static_cast<uint8_t>(op);
                f.epsilon = 1e-4;
                f.origIndex = static_cast<size_t>(i);
                query.fields.push_back(f);
            }
            const bool isRefine = (cmd == "REFINE_GROUP");
            launchScan([query = std::move(query), isRefine, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return isRefine
                    ? scanner.refineGroup(query, &cancelToken, cb)
                    : scanner.searchGroup(query, &cancelToken, cb);
            });
        } else if (cmd == "SEARCH_PATTERN") {
            std::string patHex, maskStr;
            iss >> patHex >> maskStr;
            auto pat = hexToBytes(patHex);
            std::vector<uint8_t> maskBytes(pat.size(), 0xFF);
            for (size_t i = 0; i < std::min(pat.size(), maskStr.size()); ++i) {
                maskBytes[i] = (maskStr[i] == '?' || maskStr[i] == '.') ? 0x00 : 0xFF;
            }
            launchScan([pat = std::move(pat), maskBytes = std::move(maskBytes), &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.searchBytePattern(pat, maskBytes, &cancelToken, cb);
            });
        } else if (cmd == "SEARCH_ENCODED") {
            int dt = 0, encMode = 0, aligned = 1;
            int64_t logicalInt = 0;
            double logicalFloat = 0.0;
            uint64_t keyOrMask = 0;
            iss >> dt >> encMode >> logicalInt >> logicalFloat >> keyOrMask >> aligned;
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.searchEncoded(
                    static_cast<mem_engine::DataType>(dt),
                    static_cast<mem_engine::EncodedMode>(encMode),
                    logicalInt,
                    logicalFloat,
                    keyOrMask,
                    aligned != 0,
                    &cancelToken,
                    cb
                );
            });
        } else if (cmd == "REFINE_ENCODED") {
            int dt = 0, encMode = 0;
            int64_t logicalInt = 0;
            double logicalFloat = 0.0;
            uint64_t keyOrMask = 0;
            iss >> dt >> encMode >> logicalInt >> logicalFloat >> keyOrMask;
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.refineEncoded(
                    static_cast<mem_engine::DataType>(dt),
                    static_cast<mem_engine::EncodedMode>(encMode),
                    logicalInt,
                    logicalFloat,
                    keyOrMask,
                    &cancelToken,
                    cb
                );
            });
        } else if (cmd == "FUZZY_INIT") {
            int dt = 0;
            iss >> dt;
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.startUnknownSnapshot(static_cast<mem_engine::DataType>(dt), &cancelToken, cb);
            });
        } else if (cmd == "FUZZY_REFINE") {
            int deltaOp = 0;
            int64_t iDelta = 0;
            double fDelta = 0.0, eps = 1e-4;
            uint32_t onlyNth = 0;
            iss >> deltaOp >> iDelta >> fDelta >> eps;
            if (iss >> onlyNth) { /* optional 5th field */ }
            launchScan([=, &scanner, &cancelToken](mem_engine::ProgressCallback cb) {
                return scanner.refineFuzzy(static_cast<mem_engine::SearchOp>(deltaOp), iDelta, fDelta, eps, &cancelToken, cb, onlyNth);
            });
        } else if (cmd == "GET_CANDIDATES") {
            size_t offset = 0, limit = 1000;
            iss >> offset >> limit;
            std::vector<mem_engine::CandidateEntry> slice;
            scanner.fetchCandidates(offset, limit, slice);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %zu\n", slice.size());
            for (const auto& c : slice) {
                uint64_t meta = static_cast<uint64_t>(c.type) |
                                (static_cast<uint64_t>(c.regionTag) << 8) |
                                (static_cast<uint64_t>(c.flags) << 16);
                std::printf(
                    "%llx %lld %llu\n",
                    static_cast<unsigned long long>(c.address),
                    static_cast<long long>(static_cast<int64_t>(c.rawValue)),
                    static_cast<unsigned long long>(meta)
                );
            }
            std::fflush(stdout);
        } else if (cmd == "BP_ADD") {
            std::string addrHex;
            int bpType = 3, bpSize = 4, bpMode = 0, captureStack = 1;
            iss >> addrHex >> bpType >> bpSize >> bpMode >> captureStack;
            uint64_t addr = std::strtoull(addrHex.c_str(), nullptr, 16);
            std::string errMsg;
            int32_t bpId = bpManager.addBreakpoint(
                io.pid(),
                addr,
                static_cast<mem_engine::BpType>(bpType),
                bpSize,
                static_cast<mem_engine::BpMode>(bpMode),
                captureStack != 0,
                &errMsg
            );
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (bpId > 0) {
                std::printf("OK %d\n", bpId);
            } else {
                std::printf("ERR %s\n", errMsg.empty() ? "ADD_BP_FAILED" : errMsg.c_str());
            }
            std::fflush(stdout);
        } else if (cmd == "BP_REMOVE") {
            int32_t bpId = 0;
            iss >> bpId;
            bool ok = bpManager.removeBreakpoint(bpId);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("%s\n", ok ? "OK" : "ERR NOT_FOUND");
            std::fflush(stdout);
        } else if (cmd == "BP_PAUSE") {
            int32_t bpId = 0, paused = 1;
            iss >> bpId >> paused;
            bool ok = bpManager.setPaused(bpId, paused != 0);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("%s\n", ok ? "OK" : "ERR NOT_FOUND");
            std::fflush(stdout);
        } else if (cmd == "BP_PAUSE_ALL") {
            int32_t paused = 1;
            iss >> paused;
            bpManager.setAllPaused(paused != 0);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "BP_CLEAR_HITS") {
            int32_t bpId = 0;
            iss >> bpId;
            if (bpId <= 0) bpManager.clearAllHits();
            else bpManager.clearHits(bpId);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "BP_STOP_ALL") {
            bpManager.stopAll();
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "BP_SNAPSHOT") {
            std::string json = bpManager.snapshotsToJson();
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %s\n", json.c_str());
            std::fflush(stdout);
        } else if (cmd == "BP_TRACE") {
            int32_t bpId = 0;
            uint64_t sinceSeq = 0;
            size_t limit = 120;
            iss >> bpId >> sinceSeq >> limit;
            std::string json = bpManager.traceEventsToJson(bpId, sinceSeq, limit);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK %s\n", json.c_str());
            std::fflush(stdout);
        } else if (cmd == "BP_CLEAR_TRACE") {
            int32_t bpId = 0;
            iss >> bpId;
            bpManager.clearTrace(bpId);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("OK\n");
            std::fflush(stdout);
        } else if (cmd == "BP_SET_REG") {
            int32_t bpId = 0, tid = 0, isFp = 0;
            std::string pcHex, regName, hexVal;
            iss >> bpId >> tid >> pcHex >> isFp >> regName >> hexVal;
            uint64_t hitPc = std::strtoull(pcHex.c_str(), nullptr, 16);
            std::string errMsg;
            bool ok = bpManager.modifyRegister(bpId, tid, hitPc, regName, hexVal, isFp != 0, &errMsg);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (ok) {
                std::printf("OK\n");
            } else {
                std::printf("ERR %s\n", errMsg.empty() ? "SET_REG_FAILED" : errMsg.c_str());
            }
            std::fflush(stdout);
        } else if (cmd == "SET_SPEED_CFG") {
            int targetPid = io.pid();
            double rate = 1.0;
            iss >> targetPid >> rate;
            if (targetPid <= 0) targetPid = io.pid();
            char path[96];
            std::snprintf(path, sizeof(path), "/data/local/tmp/.ggnext_spd_%d", targetPid);
            int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
            bool ok = false;
            if (fd >= 0) {
                char buf[32];
                int len = std::snprintf(buf, sizeof(buf), "%.4f\n", rate);
                if (len > 0) {
                    ok = (::write(fd, buf, static_cast<size_t>(len)) == len);
                }
                ::fchmod(fd, 0644);
                ::close(fd);
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("%s\n", ok ? "OK" : "ERR SPEED_CFG");
            std::fflush(stdout);
        } else if (cmd == "ALLOC_PAGE" || cmd == "ALLOC_PAGE_LOCAL") {
            std::string hintHex;
            size_t len = 4096;
            int prot = PROT_READ | PROT_WRITE;
            iss >> hintHex >> len >> prot;
            uint64_t hint = std::strtoull(hintHex.c_str(), nullptr, 16);
            auto allocRes = mem_engine::allocateTargetProcessPage(io.pid(), hint, len, prot);
            if (allocRes.ok) {
                scanner.refreshRegions();
            }
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (allocRes.ok) {
                std::printf(
                    "OK %llx %zu %d\n",
                    static_cast<unsigned long long>(allocRes.address),
                    allocRes.size,
                    allocRes.actualProt
                );
            } else {
                std::printf("ERR %s\n", allocRes.error.empty() ? "ALLOC_FAILED" : allocRes.error.c_str());
            }
            std::fflush(stdout);
        } else if (cmd == "HOOK_CMD") {
            std::string reqHex;
            iss >> reqHex;
            auto reqBytes = hexToBytes(reqHex);
            std::string reqJson(reqBytes.begin(), reqBytes.end());
            std::string respJson;
            std::string errMsg;
            bool ok = mem_engine::sendHookAgentCommand(io.pid(), reqJson, &respJson, &errMsg);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (ok) {
                std::replace(respJson.begin(), respJson.end(), '\n', ' ');
                std::replace(respJson.begin(), respJson.end(), '\r', ' ');
                std::printf("OK %s\n", respJson.c_str());
            } else {
                std::replace(errMsg.begin(), errMsg.end(), '\n', ' ');
                std::printf("ERR %s\n", errMsg.empty() ? "HOOK_CMD_FAILED" : errMsg.c_str());
            }
            std::fflush(stdout);
        } else if (cmd == "ELF_EXPORTS") {
            std::string libHex, symHex;
            size_t limit = 500;
            iss >> libHex >> symHex >> limit;
            auto libBytes = hexToBytes(libHex);
            auto symBytes = hexToBytes(symHex);
            std::string libName(libBytes.begin(), libBytes.end());
            std::string symFilter(symBytes.begin(), symBytes.end());
            uint64_t modBase = 0;
            std::string errMsg;
            auto syms = mem_engine::listProcessModuleExports(io.pid(), libName, symFilter, limit, &modBase, nullptr, &errMsg);
            std::string json = mem_engine::elfSymbolsToJson(syms);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (syms.empty() && !errMsg.empty()) {
                std::replace(errMsg.begin(), errMsg.end(), '\n', ' ');
                std::printf("ERR %s\n", errMsg.c_str());
            } else {
                std::printf("OK %llx %s\n", static_cast<unsigned long long>(modBase), json.c_str());
            }
            std::fflush(stdout);
        } else if (cmd == "ELF_FIND_SYM") {
            std::string libHex, symHex;
            iss >> libHex >> symHex;
            auto libBytes = hexToBytes(libHex);
            auto symBytes = hexToBytes(symHex);
            std::string libName(libBytes.begin(), libBytes.end());
            std::string symName(symBytes.begin(), symBytes.end());
            uint64_t modBase = 0;
            std::string errMsg;
            uint64_t addr = mem_engine::findProcessSymbolAddress(io.pid(), libName, symName, &modBase, &errMsg);
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            if (addr != 0) {
                std::printf("OK %llx %llx\n",
                            static_cast<unsigned long long>(addr),
                            static_cast<unsigned long long>(modBase));
            } else {
                std::replace(errMsg.begin(), errMsg.end(), '\n', ' ');
                std::printf("ERR %s\n", errMsg.empty() ? "SYMBOL_NOT_FOUND" : errMsg.c_str());
            }
            std::fflush(stdout);
        } else {
            std::lock_guard<std::mutex> outLk(stdoutMutex);
            std::printf("ERR UNKNOWN_CMD\n");
            std::fflush(stdout);
        }
    }

    cancelToken.cancel();
    waitForScan();
    bpManager.stopAll();
    locker.unlockAll();
    return 0;
}
