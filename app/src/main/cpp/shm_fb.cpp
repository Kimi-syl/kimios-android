#include "shm_fb.hpp"

#include "native_log.hpp"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <cerrno>
#include <cstring>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

extern void kimios_first_frame();

#define LOG_TAG "KimiOS-SHM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

void slog(const char* message) {
    nlog::line(std::string("SHM: ") + message);
}

// Guest ramfb is DRM_FORMAT_XRGB8888: u32 0x00RRGGBB, i.e. bytes B,G,R,X in
// memory. The Android surface is WINDOW_FORMAT_RGBX_8888 (bytes R,G,B,X), so
// swap bytes 0 and 2 of every pixel. (The VNC path hid this in the pixel
// format negotiation; here we do it explicitly.)
inline uint32_t swizzle(uint32_t v) {
    return (v & 0xFF00FF00u) | ((v & 0x000000FFu) << 16) | ((v >> 16) & 0x000000FFu);
}

void swizzle_row(uint32_t* dst, const uint32_t* src, int pixels) {
#if defined(__aarch64__)
    static const uint8x16_t idx = {2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15};
    int i = 0;
    for (; i + 4 <= pixels; i += 4) {
        uint8x16_t v = vld1q_u8(reinterpret_cast<const uint8_t*>(src + i));
        vst1q_u8(reinterpret_cast<uint8_t*>(dst + i), vqtbl1q_u8(v, idx));
    }
    for (; i < pixels; ++i) {
        dst[i] = swizzle(src[i]);
    }
#else
    for (int i = 0; i < pixels; ++i) {
        dst[i] = swizzle(src[i]);
    }
#endif
}

}  // namespace

ShmFbClient::ShmFbClient() = default;

ShmFbClient::~ShmFbClient() {
    stop();
    set_window(nullptr);
    close_mapping();
}

bool ShmFbClient::open_mapping(const std::string& path, size_t region_size, size_t fb_offset,
                               int width, int height, size_t stride_bytes) {
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
        stride_bytes < static_cast<size_t>(width) * 4 || stride_bytes % 4 != 0) {
        LOGE("bad geometry %dx%d stride=%zu", width, height, stride_bytes);
        return false;
    }
    const size_t stride_px = stride_bytes / 4;
    if (fb_offset + stride_px * height * 4 > region_size) {
        LOGE("framebuffer does not fit in shared region");
        return false;
    }

    fd_ = open(path.c_str(), O_RDWR);
    if (fd_ < 0) {
        LOGE("open %s failed: %s", path.c_str(), strerror(errno));
        return false;
    }
    struct stat st {};
    if (fstat(fd_, &st) != 0 || static_cast<size_t>(st.st_size) < region_size) {
        LOGE("shared region too small: %lld < %zu",
             static_cast<long long>(st.st_size), region_size);
        close_mapping();
        return false;
    }
    void* base = mmap(nullptr, region_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
    if (base == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        close_mapping();
        return false;
    }
    region_ = static_cast<uint8_t*>(base);
    region_size_ = region_size;
    fb_offset_ = fb_offset;
    fb_ = reinterpret_cast<const uint32_t*>(region_ + fb_offset);
    fb_width_ = width;
    fb_height_ = height;
    fb_stride_px_ = stride_px;
    shadow_.assign(stride_px * height, 0xFFFFFFFFu);
    first_frame_sent_ = false;

    char info[192];
    snprintf(info, sizeof(info),
             "mapped %s (%zu MiB), fb %dx%d at offset 0x%zx — VNC bypassed",
             path.c_str(), region_size >> 20, width, height, fb_offset);
    slog(info);
    LOGI("%s", info);
    return true;
}

void ShmFbClient::close_mapping() {
    if (region_ != nullptr) {
        munmap(region_, region_size_);
        region_ = nullptr;
        fb_ = nullptr;
    }
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

void ShmFbClient::set_window(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(window_mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = window;
    has_window_.store(window_ != nullptr, std::memory_order_relaxed);
    if (window_ != nullptr && fb_width_ > 0) {
        ANativeWindow_setBuffersGeometry(window_, fb_width_, fb_height_, WINDOW_FORMAT_RGBX_8888);
        // Fresh surface has empty buffers: post a full frame even if the
        // guest framebuffer is currently static.
        force_refresh_.store(true, std::memory_order_relaxed);
        first_frame_sent_ = false;
    }
}

void ShmFbClient::start() {
    stopping_ = false;
    if (thread_.joinable() || fb_ == nullptr) {
        return;
    }
    thread_ = std::thread([this] { run(); });
}

void ShmFbClient::stop() {
    stopping_ = true;
    if (thread_.joinable()) {
        thread_.join();
    }
}

void ShmFbClient::blit() {
    std::lock_guard<std::mutex> lock(window_mutex_);
    if (window_ == nullptr) {
        return;
    }
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        return;
    }

    auto* dst = static_cast<uint32_t*>(buffer.bits);
    const bool direct = buffer.width == fb_width_ && buffer.height == fb_height_;

    // Every row must be written: ANativeWindow_lock hands back recycled
    // buffers with undefined content, so skipping "unchanged" rows would
    // leave black/garbage bands wherever the guest is static. Change
    // detection gates whether we post at all (run()), not which rows we copy.
    if (direct) {
        for (int y = 0; y < fb_height_; ++y) {
            const uint32_t* src_row = fb_ + static_cast<size_t>(y) * fb_stride_px_;
            swizzle_row(dst + static_cast<size_t>(y) * buffer.stride, src_row, fb_width_);
        }
        memcpy(shadow_.data(), fb_, fb_stride_px_ * fb_height_ * 4);
    } else {
        // Buffer geometry differs from the guest framebuffer: scale into the
        // destination bounds (never the source bounds) and refresh the whole
        // shadow, since destination rows do not map 1:1 to source rows.
        for (int y = 0; y < buffer.height; ++y) {
            const int src_y = static_cast<int>(static_cast<int64_t>(y) * fb_height_ / buffer.height);
            const uint32_t* scaled_row = fb_ + static_cast<size_t>(src_y) * fb_stride_px_;
            uint32_t* dst_row = dst + static_cast<size_t>(y) * buffer.stride;
            if (buffer.width == fb_width_) {
                swizzle_row(dst_row, scaled_row, fb_width_);
            } else {
                for (int x = 0; x < buffer.width; ++x) {
                    const int src_x = static_cast<int>(
                        static_cast<int64_t>(x) * fb_width_ / buffer.width);
                    dst_row[x] = swizzle(scaled_row[src_x]);
                }
            }
        }
        memcpy(shadow_.data(), fb_, fb_stride_px_ * fb_height_ * 4);
    }

    ANativeWindow_unlockAndPost(window_);
    if (!first_frame_sent_) {
        first_frame_sent_ = true;
        slog("first frame posted to the surface");
        LOGI("first frame posted to the surface");
        kimios_first_frame();
    }
}

void ShmFbClient::run() {
    using clock = std::chrono::steady_clock;
    const auto interval = std::chrono::milliseconds(16);
    auto next = clock::now();
    auto stats_start = clock::now();
    uint64_t frames = 0;
    uint64_t blit_ns = 0;
    const size_t frame_bytes = fb_stride_px_ * fb_height_ * 4;

    while (!stopping_) {
        next += interval;
        const bool refresh = force_refresh_.exchange(false, std::memory_order_relaxed);
        if (has_window_.load(std::memory_order_relaxed) &&
            (refresh || memcmp(fb_, shadow_.data(), frame_bytes) != 0)) {
            const auto t0 = clock::now();
            blit();
            blit_ns += std::chrono::duration_cast<std::chrono::nanoseconds>(
                           clock::now() - t0).count();
            ++frames;
        }

        const auto now = clock::now();
        if (now - stats_start >= std::chrono::seconds(5)) {
            if (frames > 0) {
                char info[128];
                snprintf(info, sizeof(info),
                         "%llu frames/5s, blit avg %.2f ms (%dx%d)",
                         static_cast<unsigned long long>(frames),
                         static_cast<double>(blit_ns) / frames / 1e6,
                         fb_width_, fb_height_);
                slog(info);
                LOGI("%s", info);
            }
            frames = 0;
            blit_ns = 0;
            stats_start = now;
        }
        if (next > now) {
            std::this_thread::sleep_for(next - now);
        } else {
            next = now;
        }
    }
}
