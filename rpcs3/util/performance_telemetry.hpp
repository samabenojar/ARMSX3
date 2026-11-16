#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <vector>

// Producers never wait for the consumer or touch files. A lost event is counted,
// not a reason to delay emulation. Generation tags reject a compile that finishes
// after its recording session has closed.
namespace performance_telemetry
{
using clock = std::chrono::steady_clock;
inline std::uint64_t now_ns()
{
    return std::chrono::duration_cast<std::chrono::nanoseconds>(clock::now().time_since_epoch()).count();
}
enum class kind : std::uint8_t { frame, graphics_pipeline, compute_pipeline };
struct event
{
    std::uint64_t timestamp_ns;
    std::uint64_t duration_ns;
    kind type;
    std::int32_t result;
};
inline std::atomic<bool> enabled{false};
inline std::atomic<std::uint64_t> generation{0};
inline std::atomic<std::uint64_t> dropped{0};
inline std::atomic<std::uint64_t> previous_frame_ns{0};
inline std::mutex queue_mutex;
inline std::array<event, 4096> queue{};
inline std::size_t count = 0;

inline void configure(bool on)
{
    enabled.store(false, std::memory_order_release);
    std::lock_guard lock(queue_mutex);
    generation.fetch_add(1, std::memory_order_relaxed);
    count = 0;
    dropped.store(0, std::memory_order_relaxed);
    previous_frame_ns.store(0, std::memory_order_relaxed);
    enabled.store(on, std::memory_order_release);
}

inline void push(event value, std::uint64_t session)
{
    if (!enabled.load(std::memory_order_acquire)) return;
    std::unique_lock lock(queue_mutex, std::try_to_lock);
    if (!lock.owns_lock())
    {
        dropped.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (!enabled.load(std::memory_order_relaxed) || session != generation.load(std::memory_order_relaxed)) return;
    if (count == queue.size()) dropped.fetch_add(1, std::memory_order_relaxed);
    else queue[count++] = value;
}

inline void frame()
{
    if (!enabled.load(std::memory_order_acquire)) return;
    const auto session = generation.load(std::memory_order_relaxed);
    const auto now = now_ns();
    const auto previous = previous_frame_ns.exchange(now, std::memory_order_relaxed);
    push({now, previous && now >= previous ? now - previous : 0, kind::frame, 0}, session);
}

inline void discontinuity()
{
    previous_frame_ns.store(0, std::memory_order_relaxed);
}

inline std::vector<event> drain()
{
    std::vector<event> result;
    // Allocate outside the lock; emulation producers can keep recording meanwhile.
    result.reserve(queue.size());
    std::lock_guard lock(queue_mutex);
    result.insert(result.end(), queue.begin(), queue.begin() + count);
    count = 0;
    return result;
}

class pipeline_scope
{
    std::uint64_t session = 0;
    std::uint64_t started = 0;
    kind type;
public:
    explicit pipeline_scope(kind value) : type(value)
    {
        if (enabled.load(std::memory_order_acquire))
        {
            session = generation.load(std::memory_order_relaxed);
            started = now_ns();
        }
    }
    void finish(std::int32_t result)
    {
        if (started) push({started, now_ns() - started, type, result}, session);
        started = 0;
    }
};
}
