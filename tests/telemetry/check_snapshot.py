"""Validate the native JSON contract consumed by Android's recorder."""
import json
import subprocess
import sys

first, events, second, reset = map(
    json.loads, subprocess.check_output([sys.argv[1]], text=True).splitlines()
)
assert first["version"] == 1
assert first["cpu_percent"] is None
assert first["native_log_path"] == 'test/"quoted\\path\n/ARMSX3.log'
assert first["ppu_cpu_share_percent"] is None
assert first["spu_cpu_share_percent"] is None
assert first["rsx_cpu_share_percent"] is None
assert first["ppu_threads"] == first["spu_threads"] == 0
assert [e["kind"] for e in events] == ["frame", "frame", "graphics_pipeline"]
assert events[0]["duration_ns"] == 0
assert events[1]["ts_ns"] >= events[0]["ts_ns"]
assert events[2]["result"] == -1
assert second["ts_ns"] >= first["ts_ns"]
assert 0 <= second["cpu_percent"] <= 100
assert reset["cpu_percent"] is None
print("Native telemetry JSON contract passed")
