"""MODTV — Fase 2: linha de base de desempenho da TV TCL (Android TV 12).

Uso:
    python modtv_baseline.py [--serial 192.0.2.10:5555] [--only series,start,jank,inspect]
                             [--series-min 10] [--rounds 5] [--jank-runs 3]

Saída: reports\\baseline-<timestamp>\\ com CSVs, arquivos brutos e RESUMO.md.

Comandos usados na TV (nenhum desativa/desinstala pacote, muda settings/props ou reinicia):
  leitura: /proc/*, top, dumpsys, getprop, settings list, logcat -d, cmd package resolve-activity
  ação reversível e autorizada: am start -W, am force-stop (só nos apps medidos),
                                input keyevent HOME/DPAD, dumpsys gfxinfo <pkg> reset
"""
import argparse
import csv
import re
import statistics
import subprocess
import shutil
import time
from datetime import datetime
from pathlib import Path

ADB = shutil.which("adb") or "adb"
LAUNCHER = "com.google.android.apps.tv.launcherx"
PSS_PROCS = ["com.tcl.exhibit", LAUNCHER, "com.google.android.katniss:interactor",
             "system", "com.google.android.gms"]
APPS = {
    "YouTube": "com.google.android.youtube.tv",
    "Globoplay": "com.globo.globotv",
    "Plex": "com.plexapp.android",
    "Spotify": "com.spotify.tv.android",
}
HOME_INTENT = "-a android.intent.action.MAIN -c android.intent.category.HOME"
# Padrão de navegação que volta ao ponto de partida (22=RIGHT 21=LEFT 20=DOWN 19=UP).
JANK_KEYS = "22 22 22 22 20 21 21 21 21 20 22 22 22 22 19 21 21 21 21 19"

SERIAL = None


def sh(cmd, timeout=120):
    r = subprocess.run([ADB, "-s", SERIAL, "shell", cmd], capture_output=True,
                       timeout=timeout, encoding="utf-8", errors="replace")
    return r.stdout + r.stderr


def log(msg):
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def write_csv(path, rows):
    if not rows:
        return
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def sections(text):
    out, cur = {}, None
    for line in text.splitlines():
        if line.startswith("@@"):
            cur = line[2:].strip()
            out[cur] = []
        elif cur:
            out[cur].append(line)
    return out


def msd(values, nd=1):
    v = [x for x in values if x is not None]
    if not v:
        return "—"
    sd = statistics.stdev(v) if len(v) > 1 else 0.0
    return f"{statistics.mean(v):.{nd}f} ± {sd:.{nd}f}"


def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(round(p / 100 * (len(v) - 1))))] if v else None


def foreground():
    m = re.search(r"mResumedActivity: ActivityRecord\{\S+ u0 (\S+)",
                  sh("dumpsys activity activities | grep -m1 mResumedActivity"))
    return m.group(1) if m else None


# ---------------------------------------------------------------- inspeção (somente leitura)
def inspect(out):
    log("inspeção de com.tcl.exhibit e katniss")
    cmds = {
        "exhibit_dumpsys_package": "dumpsys package com.tcl.exhibit",
        "exhibit_processes": "dumpsys activity processes | grep -B3 -A25 'com.tcl.exhibit'",
        "exhibit_activities": "dumpsys activity activities | grep -iE 'exhibit|PreviewMode' | head -n 60",
        "exhibit_services": "dumpsys activity services com.tcl.exhibit",
        "exhibit_events": "logcat -d -b events | grep -iE 'exhibit' | tail -n 200",
        "exhibit_main_log": "logcat -d -b main,system | grep -iE 'exhibit|PreviewMode|retail|demo.?mode|store.?mode' | tail -n 300",
        "exhibit_refs_in_packages": "dumpsys package | grep -n 'exhibit' | head -n 200",
        "demo_props": "getprop | grep -iE 'demo|retail|store|exhibit|shop|showroom'",
        "demo_settings": "for n in global secure system; do echo \"## $n\"; settings list $n | grep -iE 'demo|retail|store|exhibit|shop|showroom'; done",
        "katniss_dumpsys_package": "dumpsys package com.google.android.katniss | head -n 200",
        "katniss_services": "dumpsys activity services com.google.android.katniss | head -n 200",
        "home_resolver": f"cmd package resolve-activity --brief {HOME_INTENT}",
    }
    for name, cmd in cmds.items():
        (out / "inspect" / f"{name}.txt").write_text(f"### {cmd}\n" + sh(cmd, 180), encoding="utf-8")


# ---------------------------------------------------------------- série temporal
SAMPLE = (
    "echo @@mem; grep -E '^(MemAvailable|SwapTotal|SwapFree):' /proc/meminfo; "
    "echo @@vm; grep -E '^(pswpin|pswpout|pgmajfault) ' /proc/vmstat; "
    "echo @@stat; grep '^cpu' /proc/stat; "
    "echo @@psi_cpu; cat /proc/pressure/cpu 2>/dev/null; "
    "echo @@psi_mem; cat /proc/pressure/memory 2>/dev/null; "
    "echo @@fg; dumpsys activity activities | grep -m1 mResumedActivity; "
    "echo @@top; top -b -n 1 -d 1 -m 15 -s 2 -o PID,%CPU,RES,ARGS | tail -n 15"
)


def psi(lines, kind):
    for ln in lines:
        if ln.startswith(kind):
            m = re.search(r"avg10=([\d.]+)", ln)
            return float(m.group(1)) if m else None
    return None


def series(out, minutes, interval=15):
    log("série temporal: HOME e 30 s de estabilização")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(30)
    n = int(minutes * 60 / interval)
    rows, tops, pss_rows, therm = [], [], [], []
    prev_cpu = prev_vm = prev_t = None
    t_start = time.time()
    for i in range(n + 1):  # +1: a primeira amostra só serve de referência para os deltas
        t0 = time.time()
        txt = sh(SAMPLE)
        (out / "raw" / f"series_{i:03d}.txt").write_text(txt, encoding="utf-8")
        s = sections(txt)
        mem = {k: int(v) for k, v in re.findall(r"(\w+):\s+(\d+)", "\n".join(s.get("mem", [])))}
        vm = {k: int(v) for k, v in re.findall(r"(\w+) (\d+)", "\n".join(s.get("vm", [])))}
        cpu = {ln.split()[0]: [int(x) for x in ln.split()[1:]] for ln in s.get("stat", []) if ln.strip()}
        fg = re.search(r"u0 (\S+)", "\n".join(s.get("fg", [])))
        stamp = datetime.now().strftime("%H:%M:%S")
        if prev_cpu is not None:
            dt = t0 - prev_t
            row = {"t": stamp, "elapsed_s": round(t0 - t_start)}
            for name in sorted(cpu):
                d = [a - b for a, b in zip(cpu[name], prev_cpu.get(name, cpu[name]))]
                tot = sum(d)
                idle = d[3] + d[4]
                row[f"{name}_pct"] = round(100 * (tot - idle) / tot, 1) if tot > 0 else None
            row["mem_available_mb"] = round(mem.get("MemAvailable", 0) / 1024)
            row["swap_used_mb"] = round((mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)) / 1024)
            for k in ("pswpin", "pswpout", "pgmajfault"):
                row[f"{k}_per_s"] = round((vm.get(k, 0) - prev_vm.get(k, 0)) / dt, 2)
            row["psi_cpu_some10"] = psi(s.get("psi_cpu", []), "some")
            row["psi_mem_some10"] = psi(s.get("psi_mem", []), "some")
            row["psi_mem_full10"] = psi(s.get("psi_mem", []), "full")
            row["foreground"] = fg.group(1) if fg else None
            rows.append(row)
            for ln in s.get("top", []):
                p = ln.split(None, 3)
                # descarta os processos da própria coleta (top/sh/tail), registrados à parte
                if len(p) == 4 and p[0].isdigit() and not p[3].startswith(("top -b", "sh -c", "tail -n")):
                    tops.append({"t": stamp, "pid": p[0], "cpu_pct": float(p[1]), "res": p[2],
                                 "proc": p[3][:80]})
            log(f"série {i}/{n}: cpu={row.get('cpu_pct')}% avail={row['mem_available_mb']}MB "
                f"swapout={row['pswpout_per_s']}/s fg={row['foreground']}")
        prev_cpu, prev_vm, prev_t = cpu, vm, t0
        if i % 4 == 0:  # a cada 60 s
            th = re.search(r"Current temperatures from HAL:\s*\n\s*Temperature\{mValue=([\d.]+)",
                           sh("dumpsys thermalservice"))
            therm.append({"t": stamp, "soc_temp_c": float(th.group(1)) if th else None})
            for p in PSS_PROCS:
                m = re.search(r"TOTAL PSS:\s+(\d+).*?TOTAL SWAP PSS:\s+(\d+)",
                              sh(f"dumpsys meminfo '{p}' | grep 'TOTAL PSS'"))
                pss_rows.append({"t": stamp, "process": p,
                                 "pss_kb": int(m.group(1)) if m else None,
                                 "swap_pss_kb": int(m.group(2)) if m else None})
        time.sleep(max(0, interval - (time.time() - t0)))
    write_csv(out / "series.csv", rows)
    write_csv(out / "top_procs.csv", tops)
    write_csv(out / "pss.csv", pss_rows)
    write_csv(out / "thermal.csv", therm)
    return rows, tops, pss_rows, therm


# ---------------------------------------------------------------- cold/warm start
def am_start(args):
    txt = sh(f"am start -W {args}", 60)
    g = lambda k: (re.search(rf"{k}: (\S+)", txt) or [None, None])[1]
    return {"launch_state": g("LaunchState"), "total_ms": int(g("TotalTime")) if g("TotalTime") else None,
            "wait_ms": int(g("WaitTime")) if g("WaitTime") else None, "status": g("Status")}, txt


def starts(out, rounds):
    rows, raw = [], []
    for app, pkg in APPS.items():
        comp = sh(f"cmd package resolve-activity --brief -a android.intent.action.MAIN "
                  f"-c android.intent.category.LEANBACK_LAUNCHER {pkg}").strip().splitlines()[-1]
        if "/" not in comp:
            log(f"{app}: activity não resolvida ({comp}); pulando")
            continue
        for mode in ("cold", "warm"):
            for r in range(1, rounds + 1):
                if mode == "cold":
                    sh(f"am force-stop {pkg}")
                    time.sleep(3)
                res, txt = am_start(f"-n {comp}")
                raw.append(f"## {app} {mode} {r}\n{txt}")
                rows.append({"app": app, "mode": mode, "round": r, **res})
                log(f"{app} {mode} #{r}: {res}")
                time.sleep(5)
                res, txt = am_start(HOME_INTENT)  # volta à home medindo o launcher (warm)
                raw.append(f"## Launcher (home de {app}) {r}\n{txt}")
                rows.append({"app": "Launcher", "mode": f"home_from_{app}", "round": r, **res})
                time.sleep(3)
        sh(f"am force-stop {pkg}")  # não deixar o app medido rodando em segundo plano
    (out / "raw" / "am_start.txt").write_text("\n".join(raw), encoding="utf-8")
    write_csv(out / "app_start.csv", rows)
    return rows


# ---------------------------------------------------------------- jank do launcher
def parse_gfx(txt):
    g = lambda pat: (re.search(pat, txt) or [None, None])[1]
    return {"total_frames": g(r"Total frames rendered: (\d+)"),
            "janky_frames": g(r"Janky frames: (\d+)"),
            "janky_pct": g(r"Janky frames: \d+ \(([\d.]+)%\)"),
            **{f"p{p}_ms": g(rf"{p}th percentile: (\d+)ms") for p in (50, 90, 95, 99)},
            "slow_ui_thread": g(r"Number Slow UI thread: (\d+)"),
            "slow_draw": g(r"Number Slow issue draw commands: (\d+)"),
            "high_input_latency": g(r"Number High input latency: (\d+)")}


def jank(out, runs, seconds=60):
    rows = []
    loop = (f"end=$(( $(date +%s) + {seconds} )); n=0; while [ $(date +%s) -lt $end ]; do "
            f"for k in {JANK_KEYS}; do [ $(date +%s) -ge $end ] && break; "
            "input keyevent $k; n=$((n+1)); sleep 0.3; done; done; echo KEYS=$n")
    for r in range(1, runs + 1):
        sh("input keyevent KEYCODE_HOME")
        time.sleep(4)
        fg = foreground()
        sh(f"dumpsys gfxinfo {LAUNCHER} reset")
        log(f"jank #{r}: navegando {seconds}s (fg={fg})")
        keys = re.search(r"KEYS=(\d+)", sh(loop, seconds + 60))
        txt = sh(f"dumpsys gfxinfo {LAUNCHER}")
        (out / "raw" / f"gfxinfo_run{r}.txt").write_text(txt, encoding="utf-8")
        row = {"run": r, "foreground_before": fg, "keys_sent": keys.group(1) if keys else None,
               **parse_gfx(txt)}
        rows.append(row)
        log(f"jank #{r}: {row}")
    sh("input keyevent KEYCODE_HOME")
    write_csv(out / "jank.csv", rows)
    return rows


# ---------------------------------------------------------------- resumo
def summary(out, ser, tops, pss, therm, st, jk):
    L = [f"# MODTV — Fase 2: linha de base ({datetime.now():%d/%m/%Y %H:%M})", "",
         f"TV `{SERIAL}`. Valores = média ± desvio padrão. Arquivos: CSVs e `raw/` nesta pasta.", ""]
    if ser:
        L += [f"## Série temporal ({len(ser)} amostras de 15 s, TV na home)", "",
              "| Métrica | Média ± DP | Mín | Máx |", "|---|---|---|---|"]
        keys = [k for k in ser[0] if k not in ("t", "elapsed_s", "foreground")]
        for k in keys:
            v = [r[k] for r in ser if r[k] is not None]
            if v:
                L.append(f"| {k} | {msd(v, 2)} | {min(v)} | {max(v)} |")
        fgc = {}
        for r in ser:
            fgc[r["foreground"]] = fgc.get(r["foreground"], 0) + 1
        L += ["", "Primeiro plano durante a série: " +
              ", ".join(f"`{k}` ({v})" for k, v in sorted(fgc.items(), key=lambda x: -x[1])), ""]
    if tops:
        n = len({t["t"] for t in tops})
        agg = {}
        for t in tops:
            agg.setdefault(t["proc"], []).append(t["cpu_pct"])
        L += ["## Top processos por CPU média (top -b, % de um núcleo)", "",
              "| Processo | CPU média* | Máx | Amostras no top 15 |", "|---|---|---|---|"]
        for p, v in sorted(agg.items(), key=lambda x: -sum(x[1]) / n)[:15]:
            L.append(f"| `{p}` | {sum(v) / n:.1f} | {max(v)} | {len(v)}/{n} |")
        L += ["", "*Média sobre todas as amostras (0 quando fora do top 15). Os processos da própria "
              "coleta (`top`, `sh`, `tail`) foram excluídos; o `top` sozinho usa ~30% de um núcleo "
              "durante ~1 s a cada amostra e entra no cpu_pct total.", ""]
    if pss:
        L += ["## PSS por processo (a cada 60 s)", "", "| Processo | PSS MB | Swap PSS MB |", "|---|---|---|"]
        for p in PSS_PROCS:
            v = [r for r in pss if r["process"] == p and r["pss_kb"]]
            L.append(f"| `{p}` | {msd([r['pss_kb'] / 1024 for r in v])} | "
                     f"{msd([r['swap_pss_kb'] / 1024 for r in v])} |")
        L.append("")
    if therm:
        L += [f"Temperatura SoC: {msd([t['soc_temp_c'] for t in therm])} °C "
              f"(n={len(therm)}).", ""]
    if st:
        L += ["## Abertura de apps (`am start -W`, ms)", "",
              "| App | Modo | n | TotalTime | WaitTime | LaunchState |", "|---|---|---|---|---|---|"]
        groups = {}
        for r in st:
            mode = "warm (home)" if r["app"] == "Launcher" else r["mode"]
            groups.setdefault((r["app"], mode), []).append(r)
        for (app, mode), v in groups.items():
            ls = sorted({str(r["launch_state"]) for r in v})
            L.append(f"| {app} | {mode} | {len(v)} | {msd([r['total_ms'] for r in v], 0)} | "
                     f"{msd([r['wait_ms'] for r in v], 0)} | {', '.join(ls)} |")
        L.append("")
    if jk:
        L += ["## Jank do launcher (60 s de navegação por execução)", "",
              "| Exec. | Teclas | Frames | Janky | Janky % | p50 | p90 | p95 | p99 | UI lenta | Draw lento |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in jk:
            L.append(f"| {r['run']} | {r['keys_sent']} | {r['total_frames']} | {r['janky_frames']} | "
                     f"{r['janky_pct']} | {r['p50_ms']} | {r['p90_ms']} | {r['p95_ms']} | "
                     f"{r['p99_ms']} | {r['slow_ui_thread']} | {r['slow_draw']} |")
        L.append("")
    (out / "RESUMO.md").write_text("\n".join(L), encoding="utf-8")


def main():
    global SERIAL
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="192.0.2.10:5555")
    ap.add_argument("--only", default="inspect,series,start,jank")
    ap.add_argument("--series-min", type=float, default=10)
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--jank-runs", type=int, default=3)
    a = ap.parse_args()
    SERIAL = a.serial
    steps = set(a.only.split(","))
    out = Path(__file__).resolve().parent.parent / "reports" / f"baseline-{datetime.now():%Y%m%d-%H%M%S}"
    for d in ("raw", "inspect"):
        (out / d).mkdir(parents=True, exist_ok=True)
    log(f"saída: {out}")
    ser = tops = pss = therm = st = jk = []
    if "inspect" in steps:
        inspect(out)
    if "series" in steps:
        ser, tops, pss, therm = series(out, a.series_min)
    if "start" in steps:
        st = starts(out, a.rounds)
    if "jank" in steps:
        jk = jank(out, a.jank_runs)
    summary(out, ser, tops, pss, therm, st, jk)
    log(f"concluído: {out / 'RESUMO.md'}")


if __name__ == "__main__":
    main()
