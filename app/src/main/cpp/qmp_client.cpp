#include "qmp_client.hpp"

#include "native_log.hpp"

#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>

#define LOG_TAG "KimiOS-QMP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

bool write_all(int fd, const char* data, size_t length) {
    size_t done = 0;
    while (done < length) {
        ssize_t n = send(fd, data + done, length - done, MSG_NOSIGNAL);
        if (n <= 0) {
            return false;
        }
        done += static_cast<size_t>(n);
    }
    return true;
}

bool read_line(int fd, std::string& line) {
    line.clear();
    char c = 0;
    while (true) {
        ssize_t n = recv(fd, &c, 1, 0);
        if (n <= 0) {
            return false;
        }
        if (c == '\n') {
            return true;
        }
        if (line.size() < 65536) {
            line.push_back(c);
        }
    }
}

}  // namespace

QmpClient::QmpClient(std::string socket_path, InputQueue& queue)
    : socket_path_(std::move(socket_path)), queue_(queue) {}

QmpClient::~QmpClient() { stop(); }

void QmpClient::start() {
    stopping_ = false;
    thread_ = std::thread([this] { run(); });
}

void QmpClient::stop() {
    stopping_ = true;
    close_socket();
    if (thread_.joinable()) {
        thread_.join();
    }
}

void QmpClient::close_socket() {
    if (fd_ >= 0) {
        shutdown(fd_, SHUT_RDWR);
        close(fd_);
        fd_ = -1;
    }
}

bool QmpClient::handshake() {
    std::string line;
    if (!read_line(fd_, line)) {
        return false;
    }
    const char* capabilities = "{\"execute\":\"qmp_capabilities\"}\n";
    if (!write_all(fd_, capabilities, strlen(capabilities))) {
        return false;
    }
    if (!read_line(fd_, line)) {
        return false;
    }
    LOGI("QMP connected: %s", line.c_str());
    nlog::line("QMP: connected, input injection ready");
    return true;
}

void QmpClient::drain_responses() {
    char buffer[4096];
    while (true) {
        ssize_t n = recv(fd_, buffer, sizeof(buffer), MSG_DONTWAIT);
        if (n <= 0) {
            break;
        }
    }
}

void QmpClient::send_event(const InputEvent& event) {
    char message[1024];
    int length = 0;

    if (event.kind == InputKind::Pointer) {
        int x = static_cast<int>(event.x * 32767.0f);
        int y = static_cast<int>(event.y * 32767.0f);
        if (x < 0) x = 0;
        if (x > 32767) x = 32767;
        if (y < 0) y = 0;
        if (y > 32767) y = 32767;

        length = snprintf(
            message, sizeof(message),
            "{\"execute\":\"input-send-event\",\"arguments\":{\"events\":["
            "{\"type\":\"abs\",\"data\":{\"axis\":\"x\",\"value\":%d}},"
            "{\"type\":\"abs\",\"data\":{\"axis\":\"y\",\"value\":%d}}",
            x, y);

        if (event.pointer_state == 1) {
            length += snprintf(message + length, sizeof(message) - length,
                               ",{\"type\":\"btn\",\"data\":{\"button\":\"left\",\"down\":true}}");
        } else if (event.pointer_state == 2) {
            length += snprintf(message + length, sizeof(message) - length,
                               ",{\"type\":\"btn\",\"data\":{\"button\":\"left\",\"down\":false}}");
        }
        length += snprintf(message + length, sizeof(message) - length, "]}}\n");
    } else {
        length = snprintf(
            message, sizeof(message),
            "{\"execute\":\"input-send-event\",\"arguments\":{\"events\":["
            "{\"type\":\"key\",\"data\":{\"down\":%s,"
            "\"key\":{\"type\":\"qcode\",\"data\":\"%s\"}}}]}}\n",
            event.key_down ? "true" : "false", event.qcode.c_str());
    }

    if (length <= 0 || static_cast<size_t>(length) >= sizeof(message)) {
        return;
    }
    if (!write_all(fd_, message, static_cast<size_t>(length))) {
        LOGE("QMP write failed");
        close_socket();
        return;
    }
    drain_responses();
}

void QmpClient::run() {
    while (!stopping_) {
        fd_ = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd_ < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        if (socket_path_.size() >= sizeof(address.sun_path)) {
            LOGE("QMP socket path too long");
            close_socket();
            return;
        }
        strncpy(address.sun_path, socket_path_.c_str(), sizeof(address.sun_path) - 1);

        if (connect(fd_, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
            !handshake()) {
            close_socket();
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        while (!stopping_ && fd_ >= 0) {
            InputEvent event;
            if (queue_.pop(event, 100)) {
                send_event(event);
            }
        }
        close_socket();
    }
}
