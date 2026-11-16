// Included by armsx3-core.cpp: sampling stays on the app's single IO worker.
// Do NOT call get_cycles(): it consumes the overlay's counter deltas.
namespace
{
std::unordered_map<u32, u64> telemetry_thread_clocks;
u64 telemetry_sample_ns = 0;
u64 telemetry_process_ns = 0;

u64 telemetry_clock(clockid_t id)
{
    timespec time{};
    return clock_gettime(id, &time) == 0 ? u64(time.tv_sec) * 1'000'000'000 + time.tv_nsec : 0;
}

std::string telemetry_quote(std::string_view value)
{
    std::string out = "\"";
    for (unsigned char c : value)
    {
        if (c == '\\' || c == '"') { out += '\\'; out += char(c); }
        else if (c < 32) { constexpr char h[] = "0123456789abcdef"; out += "\\u00"; out += h[c >> 4]; out += h[c & 15]; }
        else out += char(c);
    }
    return out + '"';
}
}

extern "C" void _armsx3_telemetryConfigure(bool enabled)
{
    performance_telemetry::configure(enabled);
    telemetry_thread_clocks.clear();
    telemetry_sample_ns = 0;
    telemetry_process_ns = 0;
}

extern "C" const char* _armsx3_telemetryDrain()
{
    const auto events = performance_telemetry::drain();
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << '[';
    bool first = true;
    for (const auto& event : events)
    {
        if (!first) out << ',';
        first = false;
        out << "{\"kind\":\"" << (event.type == performance_telemetry::kind::frame ? "frame" :
            event.type == performance_telemetry::kind::graphics_pipeline ? "graphics_pipeline" : "compute_pipeline")
            << "\",\"ts_ns\":" << event.timestamp_ns << ",\"duration_ns\":" << event.duration_ns
            << ",\"result\":" << event.result << '}';
    }
    out << ']';
    thread_local std::string serialized;
    serialized = out.str();
    return serialized.c_str();
}

extern "C" const char* _armsx3_telemetrySnapshot()
{
    const u64 now = performance_telemetry::now_ns();
    const u64 process = telemetry_clock(CLOCK_PROCESS_CPUTIME_ID);
    const bool baseline = telemetry_sample_ns && now > telemetry_sample_ns && process >= telemetry_process_ns;
    const u64 elapsed = baseline ? now - telemetry_sample_ns : 0;
    const auto cores = std::max(1L, sysconf(_SC_NPROCESSORS_ONLN));
    double cpu = baseline ? (process - telemetry_process_ns) * 100.0 / elapsed / cores : 0.0;
    std::unordered_map<u32, u64> current;
    auto delta = [&](u32 tid) -> u64
    {
        if (!tid) return 0;
        const u64 value = telemetry_clock(static_cast<clockid_t>((~tid << 3) | 6));
        if (!value) return 0;
        current[tid] = value;
        const auto it = telemetry_thread_clocks.find(tid);
        return it != telemetry_thread_clocks.end() && value >= it->second ? value - it->second : 0;
    };
    u64 ppu_ns = 0, spu_ns = 0, rsx_ns = 0;
    u32 ppus = 0, spus = 0;
    if (Emu.IsRunning() || Emu.IsPaused())
    {
        ppus = idm::select<named_thread<ppu_thread>>([&](u32, named_thread<ppu_thread>& thread)
        { ppu_ns += delta(thread_ctrl::get_native_tid(thread)); });
        spus = idm::select<named_thread<spu_thread>>([&](u32, named_thread<spu_thread>& thread)
        { spu_ns += delta(thread_ctrl::get_native_tid(thread)); });
        rsx_ns = delta(rpcs3::utils::get_rsx_thread_tid());
    }
    telemetry_thread_clocks = std::move(current);
    const u64 total = ppu_ns + spu_ns + rsx_ns;
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << "{\"version\":1,\"ts_ns\":" << now << ",\"dropped_events\":" << performance_telemetry::dropped.load()
        << ",\"cpu_percent\":";
    if (baseline) out << cpu; else out << "null";
    for (const auto& [name, value] : std::array<std::pair<const char*, u64>, 3>{{{"ppu", ppu_ns}, {"spu", spu_ns}, {"rsx", rsx_ns}}})
    {
        out << ",\"" << name << "_cpu_share_percent\":";
        if (baseline && total) out << cpu * value / total; else out << "null";
        out << ",\"" << name << "_cpu_ns\":" << value;
    }
    out << ",\"ppu_threads\":" << ppus << ",\"spu_threads\":" << spus
        << ",\"native_log_path\":" << telemetry_quote(fs::get_log_dir() + "ARMSX3.log") << '}';
    telemetry_sample_ns = now;
    telemetry_process_ns = process;
    thread_local std::string serialized;
    serialized = out.str();
    return serialized.c_str();
}
