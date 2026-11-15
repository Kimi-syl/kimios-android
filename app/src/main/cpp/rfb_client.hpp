#pragma once

#include <android/native_window.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

class RfbClient {
public:
    RfbClient();
    ~RfbClient();

    void set_window(ANativeWindow* window);
    void start();
    void stop();

private:
    void run();
    bool session();
    bool read_full(void* buffer, size_t length);
    bool write_full(const void* buffer, size_t length);
    bool request_update(bool incremental);
    void blit();
    void close_socket();

    std::mutex window_mutex_;
    ANativeWindow* window_ = nullptr;
    std::thread thread_;
    std::atomic<bool> stopping_{false};
    int fd_ = -1;
    int fb_width_ = 0;
    int fb_height_ = 0;
    std::vector<uint32_t> framebuffer_;
};
