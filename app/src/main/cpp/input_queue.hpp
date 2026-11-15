#pragma once

#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>
#include <utility>

enum class InputKind { Pointer, Key };

struct InputEvent {
    InputKind kind = InputKind::Pointer;
    float x = 0.0f;
    float y = 0.0f;
    int pointer_state = 0;
    bool key_down = false;
    std::string qcode;
};

class InputQueue {
public:
    void push(InputEvent event) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            queue_.push_back(std::move(event));
        }
        cv_.notify_one();
    }

    bool pop(InputEvent& out, int timeout_ms) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms),
                          [this] { return !queue_.empty(); })) {
            return false;
        }
        out = std::move(queue_.front());
        queue_.pop_front();
        return true;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        queue_.clear();
    }

private:
    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<InputEvent> queue_;
};
