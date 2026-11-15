#include "native_log.hpp"

#include <deque>
#include <mutex>

namespace {
std::mutex g_mutex;
std::deque<std::string> g_lines;
constexpr size_t kMaxLines = 60;
}

namespace nlog {

void line(const std::string& text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_lines.push_back(text);
    while (g_lines.size() > kMaxLines) {
        g_lines.pop_front();
    }
}

std::string dump() {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string out;
    for (const auto& item : g_lines) {
        out += item;
        out += '\n';
    }
    return out;
}

}
