#pragma once

#include <android/native_window.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// Reads the guest framebuffer directly out of the file that backs QEMU guest
// RAM (memory-backend-file,share=on). No VNC: the guest's stores to ramfb land
// in pages this process also maps, so a poll + row copy is the whole transport.
class ShmFbClient {
public:
    ShmFbClient();
    ~ShmFbClient();

    // Maps the shared RAM region. fb_offset is the framebuffer's guest-physical
    // address minus the virt RAM base (0x40000000). Returns false if the region
    // cannot be mapped or the geometry does not fit inside it.
    bool open_mapping(const std::string& path, size_t region_size, size_t fb_offset,
                      int width, int height, size_t stride_bytes,
                      size_t instr_offset = 0);

    void set_window(ANativeWindow* window);
    void start();
    void stop();

private:
    void run();
    void blit();
    void blit_rect(int x0, int y0, int x1, int y1);
    void close_mapping();

    std::mutex window_mutex_;
    ANativeWindow* window_ = nullptr;
    std::atomic<bool> has_window_{false};
    std::thread thread_;
    std::atomic<bool> stopping_{false};
    std::atomic<bool> force_refresh_{false};

    int fd_ = -1;
    uint8_t* region_ = nullptr;
    size_t region_size_ = 0;
    const uint32_t* fb_ = nullptr;
    size_t fb_offset_ = 0;
    volatile uint64_t* instr_ = nullptr;  // latency mailbox in shared RAM (RW: ack)
    int fb_width_ = 0;
    int fb_height_ = 0;
    size_t fb_stride_px_ = 0;
    bool first_frame_sent_ = false;
    std::vector<uint32_t> shadow_;
};
