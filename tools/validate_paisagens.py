"""Read-only, bounded observation of lume on an explicitly selected Android TV.

Does not open apps, send keys, reset counters, modify settings, or install agents.
Captures only technical metrics; no logcat, network/account details or screenshots.
"""
import argparse
import csv
from datetime import datetime
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import time

PKG = "br.modtv.launcher"
SAMPLE = """
echo @@cpu; head -n 1 /proc/stat
echo @@uptime; cat /proc/uptime
echo @@mem; grep -E '^(MemAvailable|SwapTotal|SwapFree):' /proc/meminfo
echo @@pid; pidof br.modtv.launcher
echo @@proc; for p in $(pidof br.modtv.launcher); do cat /proc/$p/stat; done
echo @@pss; dumpsys meminfo br.modtv.launcher | grep 'TOTAL PSS:'
echo @@thermal; dumpsys thermalservice
echo @@foreground; dumpsys activity activities | sed -n '/mResumedActivity:/p'
echo @@dream; dumpsys dreams
echo @@player; dumpsys media.player | sed '/Decoder infos by media types:/,$d'
echo @@gfx; dumpsys gfxinfo br.modtv.launcher | grep -E 'Total frames rendered:|Janky frames:'
"""


def sections(text):
    result, key = {}, None
    for line in text.splitlines():
        if line.startswith("@@"):
            key = line[2:].strip()
            result[key] = []
        elif key:
            result[key].append(line)
    return {k: "\n".join(v) for k, v in result.items()}


def match(pattern, text, cast=str):
    m = re.search(pattern, text)
    return cast(m.group(1)) if m else None


def parse(text):
    s = sections(text)
    cpu_line = s.get("cpu", "").split()
    cpu = [int(x) for x in cpu_line[1:9]] if cpu_line and cpu_line[0] == "cpu" else None
    ticks = None
    proc = s.get("proc", "").splitlines()
    if proc:
        # comm may contain spaces or parentheses; fields after the final ')' begin at field 3.
        fields = proc[0].rsplit(")", 1)[-1].split()
        if len(fields) > 12:
            ticks = int(fields[11]) + int(fields[12])
    th = s.get("thermal", "").split("Current temperatures from HAL:", 1)
    temp = match(r"mValue=([\d.]+), mType=0, mName=soc_thermal", th[-1], float) if len(th) == 2 else None
    mem = s.get("mem", "")
    pid = match(r"^(\d+)", s.get("pid", ""))
    players = re.split(r"(?m)^\s*Client\s*$", s.get("player", ""))
    player = next((p for p in players if re.search(r"packageName: " + re.escape(PKG) + r"[,\s]", p)), "")
    owns_player = bool(player)
    return dict(cpu=cpu, ticks=ticks, pid=pid,
                uptime_s=match(r"^([\d.]+)", s.get("uptime", ""), float),
                mem_available_mb=(match(r"MemAvailable:\s+(\d+)", mem, int) or 0) / 1024 if "MemAvailable:" in mem else None,
                pss_mb=(match(r"TOTAL PSS:\s+(\d+)", s.get("pss", ""), int) or 0) / 1024 if "TOTAL PSS:" in s.get("pss", "") else None,
                temp_c=temp, thermal_status=match(r"Thermal Status:\s+(\d+)", s.get("thermal", ""), int),
                foreground=match(r"mResumedActivity:.*?u\d+\s+(\S+)", s.get("foreground", "")),
                dream=match(r"mCurrentDreamName=(.*)", s.get("dream", "")),
                # Do not attribute another app's decoder to lume.
                video_resolution=match(r"resolution\((\d+ x \d+)\)", player) if owns_player else None,
                video_paused=match(r"mPaused:(\d+)", player, int) if owns_player else None,
                frames=match(r"Total frames rendered:\s*(\d+)", s.get("gfx", ""), int))


def deltas(cur, prev, cores):
    result = dict(cpu_total_pct=None, lume_cpu_one_core_pct=None, frames_delta=None)
    if not prev:
        return result
    if cur["cpu"] and prev["cpu"]:
        ds = [a - b for a, b in zip(cur["cpu"], prev["cpu"])]
        total = sum(ds)
        if total > 0 and min(ds) >= 0:
            result["cpu_total_pct"] = round(100 * (total - ds[3] - ds[4]) / total, 2)
            if cur["pid"] and cur["pid"] == prev["pid"] and cur["ticks"] is not None and prev["ticks"] is not None:
                dticks = cur["ticks"] - prev["ticks"]
                if dticks >= 0:
                    result["lume_cpu_one_core_pct"] = round(100 * dticks * cores / total, 2)
    if cur["pid"] and cur["pid"] == prev["pid"] and cur["frames"] is not None and prev["frames"] is not None:
        df = cur["frames"] - prev["frames"]
        result["frames_delta"] = df if df >= 0 else None
    return result


def write_summary(out, rows, state, requested):
    valid = [r for r in rows if r["status"] == "ok"]
    elapsed = rows[-1]["elapsed_s"] if rows else 0
    lines = ["# Paisagens — observação técnica", "", f"Estado: **{state}**.",
             f"Duração solicitada: {requested:.0f} s; tempo observado: {elapsed:.1f} s.",
             f"Amostras ADB válidas: {len(valid)}/{len(rows)}. Cada métrica informa seu próprio n. Horário local com fuso no CSV.", "",
             "Nenhuma mudança na TV realizada por este coletor. A coleta causa uma pequena carga adicional.", "",
             "| Métrica | Média | Mínimo | Máximo | n |", "|---|---:|---:|---:|---:|"]
    for key in ("cpu_total_pct", "lume_cpu_one_core_pct", "pss_mb", "mem_available_mb", "temp_c", "frames_delta"):
        values = [r[key] for r in valid if r.get(key) is not None]
        if values:
            lines.append(f"| {key} | {statistics.mean(values):.2f} | {min(values):.2f} | {max(values):.2f} | {len(values)} |")
    active = [r for r in valid if (r.get("foreground") or "").startswith(PKG) or PKG in (r.get("dream") or "")]
    lines += ["", f"Amostras com lume em primeiro plano ou dream ativo: {len(active)}/{len(valid)}.",
              f"Amostras com player UHD atribuído ao lume: {sum(r.get('video_resolution') == '3840 x 2160' for r in valid)}.",
              f"Amostras com status térmico diferente de zero: {sum(r.get('thermal_status') not in (None, 0) for r in valid)}.",
              f"Amostras sem status térmico disponível: {sum(r.get('thermal_status') is None for r in valid)}.",
              "", "CPU do lume em % de um núcleo, calculada por deltas de /proc. CPU total normalizada por todos os núcleos.",
              "Valores ausentes, quedas de ADB e reinícios de processo não são convertidos em CPU zero.",
              "Buffer/decoder UHD não comprovam resolução física do painel; consulte as evidências do SurfaceFlinger.",
              "Amostragem não prova ausência de falhas entre leituras nem teste de todos os aplicativos.", ""]
    temp = out / "RESUMO.tmp"
    temp.write_text("\n".join(lines), encoding="utf-8")
    temp.replace(out / "RESUMO.md")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--minutes", type=float, default=20)
    ap.add_argument("--interval", type=float, default=30)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    if a.minutes <= 0 or a.interval < 5:
        ap.error("minutes deve ser positivo; interval deve ser >= 5 segundos")
    adb = shutil.which("adb")
    if not adb:
        ap.error("adb não encontrado")
    a.out.mkdir(parents=True, exist_ok=False)

    def shell(cmd, timeout=20):
        r = subprocess.run([adb, "-s", a.serial, "shell", cmd], capture_output=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        if r.returncode:
            raise RuntimeError("adb retornou " + str(r.returncode))
        return r.stdout

    shell("true")
    cores = int(shell("grep -c '^processor' /proc/cpuinfo").strip())
    for name, cmd in {
        "config_antes": "settings get secure screensaver_components; settings get secure screensaver_enabled; settings get system peak_refresh_rate; settings get system min_refresh_rate; settings get secure match_content_frame_rate; wm size; wm density",
        "display_antes": "dumpsys SurfaceFlinger",
    }.items():
        (a.out / (name + ".txt")).write_text(shell(cmd), encoding="utf-8")
    rows, prev = [], None
    start = time.monotonic()
    duration = a.minutes * 60
    state = "em andamento"
    try:
        while True:
            stamp = datetime.now().astimezone().isoformat(timespec="seconds")
            elapsed = time.monotonic() - start
            row = dict(time=stamp, elapsed_s=round(elapsed, 1), status="ok")
            try:
                cur = parse(shell(SAMPLE))
                if not cur["cpu"] or cur["uptime_s"] is None:
                    raise RuntimeError("amostra incompleta")
                row.update({k: v for k, v in cur.items() if k not in ("cpu", "ticks")})
                row.update(deltas(cur, prev, cores))
                prev = cur
            except (subprocess.TimeoutExpired, RuntimeError, ValueError) as e:
                row["status"] = type(e).__name__
                prev = None  # next sample establishes a fresh baseline
            rows.append(row)
            keys = list(dict.fromkeys(k for r in rows for k in r))
            with (a.out / "metricas.csv").open("w", newline="", encoding="utf-8") as f:
                w = csv.DictWriter(f, fieldnames=keys)
                w.writeheader()
                w.writerows(rows)
            write_summary(a.out, rows, state, duration)
            print(f"{stamp} {row['status']} {elapsed:.0f}s PSS={row.get('pss_mb')} temp={row.get('temp_c')}", flush=True)
            if elapsed >= duration:
                state = "concluído"
                break
            time.sleep(max(0, min(a.interval, duration - (time.monotonic() - start))))
    except KeyboardInterrupt:
        state = "interrompido"
    finally:
        write_summary(a.out, rows, state, duration)
    (a.out / "display_depois.txt").write_text(shell("dumpsys SurfaceFlinger"), encoding="utf-8")


if __name__ == "__main__":
    main()
