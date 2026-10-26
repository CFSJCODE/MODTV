"""Paired launcher comparison: opens both launchers, measures idle, sends DPAD only.

No installs, force-stops, package/config changes or app launches via OK/Enter.
Returns to the selected default HOME in finally. Explicit ADB serial required.
CPU uses /proc deltas, normalized to one core for each package's processes.
"""
import argparse
import csv
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

import modtv_baseline as mb

TARGETS = {"google": "com.google.android.apps.tv.launcherx", "lume": "br.modtv.launcher"}
COMPONENTS = {"google": TARGETS["google"] + "/.home.HomeActivity", "lume": TARGETS["lume"] + "/.HomeActivity"}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    a.out.mkdir(exist_ok=False, parents=True)

    def shell(cmd, timeout=25):
        p = subprocess.run([shutil.which("adb"), "-s", a.serial, "shell", cmd],
                           capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)
        if p.returncode:
            raise RuntimeError(p.stderr.strip())
        return p.stdout

    cores = int(shell("grep -c '^processor' /proc/cpuinfo").strip())

    def foreground():
        return shell('dumpsys activity activities | sed -n "/mResumedActivity:/p"')

    def snapshot():
        cmd = "echo @@cpu; head -n 1 /proc/stat; "
        for label, pkg in TARGETS.items():
            cmd += (f"echo @@{label}; ps -A -o PID,NAME | grep '{pkg}' | "
                    "while read p n; do cat /proc/$p/stat; done; ")
        raw = shell(cmd)
        sec = mb.sections(raw)
        cpu = [int(x) for x in sec["cpu"][0].split()[1:9]]
        groups = {}
        for label in TARGETS:
            ticks, pids = 0, []
            for line in sec.get(label, []):
                if not re.match(r"^\d+ \(", line):
                    continue
                fields = line.rsplit(")", 1)[1].split()
                ticks += int(fields[11]) + int(fields[12])
                pids.append(line.split()[0])
            groups[label] = (tuple(sorted(pids)), ticks)
        return cpu, groups

    rows = []
    try:
        # Counterbalance order: Google / lume, then lume / Google.
        for run, target in enumerate(("google", "lume", "lume", "google"), 1):
            shell("am start -W -n " + COMPONENTS[target])
            time.sleep(5)
            fg = foreground()
            if COMPONENTS[target].replace("/.", "/" + TARGETS[target] + ".") not in fg and TARGETS[target] + "/" not in fg:
                raise RuntimeError("Launcher esperado não está em primeiro plano: " + fg)
            before_gfx = mb.parse_gfx(shell("dumpsys gfxinfo " + TARGETS[target]))
            prev_cpu, prev_groups = snapshot()
            total_delta = busy_delta = 0
            ticks_sum = {k: 0 for k in TARGETS}
            denominators = {k: 0 for k in TARGETS}
            samples = []
            started = time.monotonic()
            for _ in range(6):
                time.sleep(5)
                cur_cpu, groups = snapshot()
                ds = [x - y for x, y in zip(cur_cpu, prev_cpu)]
                dt = sum(ds)
                if dt <= 0 or min(ds) < 0:
                    raise RuntimeError("Contadores CPU inconsistentes")
                total_delta += dt
                busy_delta += dt - ds[3] - ds[4]
                sample = {"run": run, "foreground": target, "cpu_total_pct": 100 * (dt-ds[3]-ds[4])/dt}
                for name in TARGETS:
                    pids, ticks = groups[name]
                    old_pids, old_ticks = prev_groups[name]
                    value = None
                    if pids == old_pids and ticks >= old_ticks:
                        ticks_sum[name] += ticks - old_ticks
                        denominators[name] += dt
                        value = 100 * cores * (ticks-old_ticks)/dt
                    sample[name + "_cpu_one_core_pct"] = value
                samples.append(sample)
                prev_cpu, prev_groups = cur_cpu, groups
            elapsed = time.monotonic() - started
            after_fg = foreground()
            if TARGETS[target] + "/" not in after_fg:
                raise RuntimeError("Primeiro plano mudou durante repouso")
            after_gfx = mb.parse_gfx(shell("dumpsys gfxinfo " + TARGETS[target]))
            row = {"run": run, "target": target, "idle_seconds": round(elapsed, 2),
                   "cpu_total_pct": round(100*busy_delta/total_delta, 2)}
            for name in TARGETS:
                row[name + "_cpu_one_core_pct"] = round(100*cores*ticks_sum[name]/denominators[name], 3) if denominators[name] else None
                procs = shell("ps -A -o PID,NAME | grep '" + TARGETS[name] + "'")
                pss = 0
                count = 0
                for line in procs.splitlines():
                    parts = line.split()
                    if parts and parts[0].isdigit():
                        mem = shell("dumpsys meminfo " + parts[0])
                        m = re.search(r"TOTAL PSS:\s+(\d+)", mem)
                        if m:
                            pss += int(m.group(1))
                            count += 1
                row[name + "_pss_mb"] = round(pss/1024, 2) if count else None
                row[name + "_process_count"] = count
            mem = shell("cat /proc/meminfo")
            row["mem_available_mb"] = round(int(re.search(r"MemAvailable:\s+(\d+)", mem).group(1))/1024, 2)
            th = shell("dumpsys thermalservice").split("Current temperatures from HAL:")[-1]
            row["temp_c"] = float(re.search(r"mValue=([\d.]+), mType=0", th).group(1))
            row["idle_frames"] = int(after_gfx["total_frames"] or 0) - int(before_gfx["total_frames"] or 0)
            row["idle_fps"] = round(row["idle_frames"]/elapsed, 3)
            row["hz"] = shell("dumpsys SurfaceFlinger | grep 'refresh-rate'").strip()
            # Same 20 s DPAD sequence. No HOME (default may differ), OK or Enter.
            shell("dumpsys gfxinfo " + TARGETS[target] + " reset")
            loop = ("end=$(( $(date +%s) + 20 )); while [ $(date +%s) -lt $end ]; do "
                    "for k in 22 22 21 21; do [ $(date +%s) -ge $end ] && break; input keyevent $k; sleep 0.3; done; done")
            shell(loop, timeout=35)
            gfx = shell("dumpsys gfxinfo " + TARGETS[target])
            (a.out/f"run{run}-gfx.txt").write_text(gfx, encoding="utf-8")
            row.update({"nav_"+k: v for k, v in mb.parse_gfx(gfx).items()})
            row["foreground_after_navigation"] = foreground().strip()
            mb.write_csv(a.out/f"run{run}-samples.csv", samples)
            rows.append(row)
            mb.write_csv(a.out/"resultado.csv", rows)
            (a.out/"resultado.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
            print(json.dumps(row, ensure_ascii=False), flush=True)
    finally:
        shell("input keyevent KEYCODE_HOME")


if __name__ == "__main__":
    main()
