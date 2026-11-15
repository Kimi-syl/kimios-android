#pragma once

#include <atomic>
#include <string>
#include <thread>

#include "input_queue.hpp"

class QmpClient {
public:
    QmpClient(std::string socket_path, InputQueue& queue);
    ~QmpClient();

    void start();
    void stop();

private:
    void run();
    bool handshake();
    void send_event(const InputEvent& event);
    void drain_responses();
    void close_socket();

    std::string socket_path_;
    InputQueue& queue_;
    std::thread thread_;
    std::atomic<bool> stopping_{false};
    int fd_ = -1;
};
