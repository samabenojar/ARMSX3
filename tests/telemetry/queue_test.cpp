#include "rpcs3/util/performance_telemetry.hpp"
#include <cassert>
#include <thread>

int main()
{
    namespace t = performance_telemetry;
    t::frame();
    assert(t::drain().empty());
    t::configure(true);
    t::frame();
    t::frame();
    auto frames = t::drain();
    assert(frames.size() == 2 && frames[0].duration_ns == 0);
    assert(frames[1].timestamp_ns >= frames[0].timestamp_ns);
    t::discontinuity();
    t::frame();
    assert(t::drain()[0].duration_ns == 0);
    t::pipeline_scope stale(t::kind::graphics_pipeline);
    t::configure(false);
    t::configure(true);
    stale.finish(0);
    assert(t::drain().empty());
    const auto session = t::generation.load();
    for (int i = 0; i < 5000; ++i) t::push({1, 2, t::kind::graphics_pipeline, 0}, session);
    assert(t::drain().size() == 4096);
    assert(t::dropped.load() == 904);
    t::configure(true);
    std::thread a([&] { for (int i = 0; i < 10000; ++i) t::frame(); });
    std::thread b([&] { for (int i = 0; i < 10000; ++i) t::frame(); });
    a.join(); b.join();
    auto events = t::drain();
    assert(events.size() + t::dropped.load() == 20000);
    t::configure(false);
}
