#pragma once

#include <cstdint>
#include <string>
#include <time.h>

namespace nlog {
void line(const std::string& text);
std::string dump();

// Monotonic nanoseconds (CLOCK_MONOTONIC) — the same time base QEMU's TCG
// virtual counter derives from, used to correlate Android and guest stages.
inline int64_t now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}
}  // namespace nlog
