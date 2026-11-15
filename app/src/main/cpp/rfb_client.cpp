#include "rfb_client.hpp"

#include "native_log.hpp"

#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <cstring>

extern void kimios_first_frame();

namespace {
void rlog(const char* message) {
    nlog::line(std::string("RFB: ") + message);
}
}

#define LOG_TAG "KimiOS-RFB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kVncPort = 5900;
constexpr int kTargetWidth = 1080;
constexpr int kTargetHeight = 1920;

uint16_t read_u16(const uint8_t* p) {
    return static_cast<uint16_t>((p[0] << 8) | p[1]);
}

uint32_t read_u32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

void put_u16(uint8_t* p, uint16_t value) {
    p[0] = static_cast<uint8_t>(value >> 8);
    p[1] = static_cast<uint8_t>(value & 0xff);
}

void put_u32(uint8_t* p, uint32_t value) {
    p[0] = static_cast<uint8_t>(value >> 24);
    p[1] = static_cast<uint8_t>(value >> 16);
    p[2] = static_cast<uint8_t>(value >> 8);
    p[3] = static_cast<uint8_t>(value & 0xff);
}

}  // namespace

RfbClient::RfbClient() = default;

RfbClient::~RfbClient() {
    stop();
    set_window(nullptr);
}

void RfbClient::set_window(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(window_mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = window;
    if (window_ != nullptr) {
        const int w = fb_width_ > 0 ? fb_width_ : kTargetWidth;
        const int h = fb_height_ > 0 ? fb_height_ : kTargetHeight;
        ANativeWindow_setBuffersGeometry(window_, w, h, WINDOW_FORMAT_RGBA_8888);
    }
}

void RfbClient::start() {
    stopping_ = false;
    if (thread_.joinable()) {
        return;
    }
    thread_ = std::thread([this] { run(); });
}

void RfbClient::stop() {
    stopping_ = true;
    close_socket();
    if (thread_.joinable()) {
        thread_.join();
    }
}

void RfbClient::close_socket() {
    if (fd_ >= 0) {
        shutdown(fd_, SHUT_RDWR);
        close(fd_);
        fd_ = -1;
    }
}

bool RfbClient::read_full(void* buffer, size_t length) {
    auto* out = static_cast<uint8_t*>(buffer);
    size_t done = 0;
    while (done < length) {
        ssize_t n = recv(fd_, out + done, length - done, 0);
        if (n <= 0) {
            return false;
        }
        done += static_cast<size_t>(n);
    }
    return true;
}

bool RfbClient::write_full(const void* buffer, size_t length) {
    auto* in = static_cast<const uint8_t*>(buffer);
    size_t done = 0;
    while (done < length) {
        ssize_t n = send(fd_, in + done, length - done, MSG_NOSIGNAL);
        if (n <= 0) {
            return false;
        }
        done += static_cast<size_t>(n);
    }
    return true;
}

bool RfbClient::request_update(bool incremental) {
    uint8_t message[10];
    message[0] = 3;
    message[1] = incremental ? 1 : 0;
    put_u16(message + 2, 0);
    put_u16(message + 4, 0);
    put_u16(message + 6, static_cast<uint16_t>(fb_width_));
    put_u16(message + 8, static_cast<uint16_t>(fb_height_));
    return write_full(message, sizeof(message));
}

void RfbClient::blit() {
    std::lock_guard<std::mutex> lock(window_mutex_);
    if (window_ == nullptr || framebuffer_.empty()) {
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        return;
    }

    auto* dst = static_cast<uint32_t*>(buffer.bits);
    const int dst_width = buffer.width;
    const int dst_height = buffer.height;
    const int dst_stride = buffer.stride;

    for (int y = 0; y < dst_height; ++y) {
        const int src_y = static_cast<int>(
            static_cast<int64_t>(y) * fb_height_ / dst_height);
        const uint32_t* src_row = framebuffer_.data() + src_y * fb_width_;
        uint32_t* dst_row = dst + y * dst_stride;
        if (dst_width == fb_width_) {
            memcpy(dst_row, src_row, static_cast<size_t>(fb_width_) * 4);
        } else {
            for (int x = 0; x < dst_width; ++x) {
                const int src_x = static_cast<int>(
                    static_cast<int64_t>(x) * fb_width_ / dst_width);
                dst_row[x] = src_row[src_x];
            }
        }
    }

    ANativeWindow_unlockAndPost(window_);
    if (!first_frame_sent_) {
        first_frame_sent_ = true;
        rlog("first frame posted to the surface");
        kimios_first_frame();
    }
}

bool RfbClient::session() {
    uint8_t version[12];
    if (!read_full(version, sizeof(version))) {
        return false;
    }
    const char* reply = "RFB 003.008\n";
    if (!write_full(reply, 12)) {
        return false;
    }

    uint8_t count = 0;
    if (!read_full(&count, 1)) {
        return false;
    }
    if (count == 0) {
        uint8_t reason_len[4];
        if (!read_full(reason_len, 4)) {
            return false;
        }
        std::vector<char> reason(read_u32(reason_len) + 1, 0);
        read_full(reason.data(), reason.size() - 1);
        LOGE("VNC refused: %s", reason.data());
        return false;
    }
    std::vector<uint8_t> types(count);
    if (!read_full(types.data(), count)) {
        return false;
    }
    bool has_none = false;
    for (uint8_t type : types) {
        if (type == 1) {
            has_none = true;
        }
    }
    if (!has_none) {
        LOGE("VNC server requires authentication");
        return false;
    }
    uint8_t security_choice = 1;
    if (!write_full(&security_choice, 1)) {
        return false;
    }
    uint8_t result[4];
    if (!read_full(result, 4)) {
        return false;
    }
    if (read_u32(result) != 0) {
        LOGE("VNC security handshake failed");
        return false;
    }

    uint8_t client_init = 1;
    if (!write_full(&client_init, 1)) {
        return false;
    }

    uint8_t server_init[24];
    if (!read_full(server_init, sizeof(server_init))) {
        return false;
    }
    fb_width_ = read_u16(server_init);
    fb_height_ = read_u16(server_init + 2);
    const uint32_t name_length = read_u32(server_init + 20);
    std::vector<char> name(name_length + 1, 0);
    if (name_length > 0 && !read_full(name.data(), name_length)) {
        return false;
    }
    LOGI("VNC server init: %dx%d name=%s", fb_width_, fb_height_, name.data());
    {
        std::lock_guard<std::mutex> lock(window_mutex_);
        if (window_ != nullptr) {
            ANativeWindow_setBuffersGeometry(window_, fb_width_, fb_height_,
                                             WINDOW_FORMAT_RGBA_8888);
        }
    }
    {
        char info[96];
        snprintf(info, sizeof(info), "connected, server framebuffer %dx%d", fb_width_, fb_height_);
        rlog(info);
    }
    if (fb_width_ <= 0 || fb_height_ <= 0 || fb_width_ > 4096 || fb_height_ > 4096) {
        return false;
    }
    framebuffer_.assign(static_cast<size_t>(fb_width_) * fb_height_, 0xff000000u);
    first_frame_sent_ = false;

    uint8_t pixel_format[20];
    memset(pixel_format, 0, sizeof(pixel_format));
    pixel_format[0] = 0;
    pixel_format[4] = 32;
    pixel_format[5] = 24;
    pixel_format[6] = 0;
    pixel_format[7] = 1;
    put_u16(pixel_format + 8, 255);
    put_u16(pixel_format + 10, 255);
    put_u16(pixel_format + 12, 255);
    pixel_format[14] = 0;
    pixel_format[15] = 8;
    pixel_format[16] = 16;
    if (!write_full(pixel_format, sizeof(pixel_format))) {
        return false;
    }

    uint8_t encodings[12];
    encodings[0] = 2;
    encodings[1] = 0;
    put_u16(encodings + 2, 2);
    put_u32(encodings + 4, 0);
    put_u32(encodings + 8, 1);
    if (!write_full(encodings, sizeof(encodings))) {
        return false;
    }

    if (!request_update(false)) {
        return false;
    }

    std::vector<uint32_t> tile;
    while (!stopping_) {
        uint8_t type = 0;
        if (!read_full(&type, 1)) {
            return false;
        }

        if (type == 0) {
            uint8_t header[3];
            if (!read_full(header, sizeof(header))) {
                return false;
            }
            const uint16_t rectangles = read_u16(header + 1);
            for (uint16_t i = 0; i < rectangles && !stopping_; ++i) {
                uint8_t rect[12];
                if (!read_full(rect, sizeof(rect))) {
                    return false;
                }
                const int x = read_u16(rect);
                const int y = read_u16(rect + 2);
                const int w = read_u16(rect + 4);
                const int h = read_u16(rect + 6);
                const int32_t encoding = static_cast<int32_t>(read_u32(rect + 8));
                if (x < 0 || y < 0 || w <= 0 || h <= 0 ||
                    x + w > fb_width_ || y + h > fb_height_) {
                    LOGE("VNC rect out of bounds");
                    return false;
                }

                if (encoding == 0) {
                    tile.resize(static_cast<size_t>(w) * h);
                    if (!read_full(tile.data(), tile.size() * 4)) {
                        return false;
                    }
                    for (int row = 0; row < h; ++row) {
                        memcpy(framebuffer_.data() + (y + row) * fb_width_ + x,
                               tile.data() + static_cast<size_t>(row) * w,
                               static_cast<size_t>(w) * 4);
                    }
                } else if (encoding == 1) {
                    uint8_t source[4];
                    if (!read_full(source, sizeof(source))) {
                        return false;
                    }
                    const int sx = read_u16(source);
                    const int sy = read_u16(source + 2);
                    if (sx < 0 || sy < 0 || sx + w > fb_width_ || sy + h > fb_height_) {
                        return false;
                    }
                    for (int row = 0; row < h; ++row) {
                        memmove(framebuffer_.data() + (y + row) * fb_width_ + x,
                                framebuffer_.data() + (sy + row) * fb_width_ + sx,
                                static_cast<size_t>(w) * 4);
                    }
                } else {
                    LOGE("VNC unsupported encoding %d", encoding);
                    return false;
                }
            }
            blit();
            if (!request_update(true)) {
                return false;
            }
        } else if (type == 2) {
            continue;
        } else if (type == 3) {
            uint8_t length_bytes[4];
            if (!read_full(length_bytes, 4)) {
                return false;
            }
            uint32_t remaining = read_u32(length_bytes);
            std::vector<char> skip(4096);
            while (remaining > 0) {
                const size_t chunk = remaining < skip.size() ? remaining : skip.size();
                if (!read_full(skip.data(), chunk)) {
                    return false;
                }
                remaining -= static_cast<uint32_t>(chunk);
            }
        } else {
            LOGE("VNC unknown message type %u", type);
            return false;
        }
    }
    return true;
}

void RfbClient::run() {
    while (!stopping_) {
        fd_ = socket(AF_INET, SOCK_STREAM, 0);
        if (fd_ < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }
        int one = 1;
        setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_port = htons(kVncPort);
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

        if (connect(fd_, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
            rlog("connecting to 127.0.0.1:5900 (waiting for QEMU)");
        } else if (session()) {
            LOGI("VNC session ended");
            rlog("session ended, reconnecting");
        } else {
            rlog("session failed, reconnecting");
        }
        close_socket();
        if (!stopping_) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
}
