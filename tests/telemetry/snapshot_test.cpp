// Exercise the actual ABI serialization without building the emulator's LLVM.
// The fixture has no emulated threads: unavailable role shares must stay null.
#include "rpcs3/util/performance_telemetry.hpp"
#include <algorithm>
#include <iostream>
#include <locale>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <unistd.h>

using u32 = std::uint32_t;
using u64 = std::uint64_t;
struct ppu_thread {};
struct spu_thread {};
template <class T> struct named_thread {};
namespace idm
{
template <class T, class F> u32 select(F) { return 0; }
}
struct thread_ctrl
{
    template <class T> static u32 get_native_tid(T&) { return 0; }
};
namespace rpcs3::utils { inline u32 get_rsx_thread_tid() { return 0; } }
namespace fs
{
inline std::string get_log_dir() { return "test/\"quoted\\path\n/"; }
}
struct emulator
{
    bool IsRunning() const { return true; }
    bool IsPaused() const { return false; }
} Emu;

#include "android/src/performance-telemetry.inc"

int main()
{
    _armsx3_telemetryConfigure(true);
    std::cout << _armsx3_telemetrySnapshot() << '\n';
    performance_telemetry::frame();
    performance_telemetry::frame();
    performance_telemetry::pipeline_scope pipeline(performance_telemetry::kind::graphics_pipeline);
    pipeline.finish(-1);
    std::cout << _armsx3_telemetryDrain() << '\n';
    std::cout << _armsx3_telemetrySnapshot() << '\n';
    _armsx3_telemetryConfigure(false);
    std::cout << _armsx3_telemetrySnapshot() << '\n';
}
