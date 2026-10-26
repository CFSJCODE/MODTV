"""MODTV — compara o launcher do Google TV com o MODTV Launcher (e cada nível de vidro).

Uso: python compare_launchers.py [--serial 192.0.2.10:5555] [--idle 30] [--nav 60]

Para cada alvo: abre o launcher com `am start`, mede em repouso (quadros desenhados, CPU do
processo, temperatura, PSS) e depois navega com o D-pad medindo jank (gfxinfo). Para comparar
os níveis de vidro, troque o nível no cartão "Vidro" do MODTV e rode de novo com um rótulo,
por exemplo `--targets modtv:alto`, depois `--targets modtv:medio` e `--targets modtv:desligado`.
Saída: reports\\launchers-<timestamp>\\resultado.csv e RESUMO.md.
"""
import argparse
import csv
import re
import time
from datetime import datetime
from pathlib import Path

import modtv_baseline as mb

TARGETS = {
    "launcherx": ("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx/.home.HomeActivity"),
    "modtv": ("br.modtv.launcher", "br.modtv.launcher/.HomeActivity"),
}


def measure(name, pkg, comp, idle_s, nav_s):
    mb.sh(f"am start -W -n {comp}")
    time.sleep(5)
    mb.sh(f"dumpsys gfxinfo {pkg} reset")
    pid = mb.sh(f"pidof {pkg}").strip().split()[0] if mb.sh(f"pidof {pkg}").strip() else ""
    cpu = []
    t_end = time.time() + idle_s
    while time.time() < t_end:
        out = mb.sh(f"top -b -n 1 -d 5 -p {pid} -o %CPU | tail -n 1") if pid else ""
        m = re.search(r"([\d.]+)\s*$", out.strip())
        if m:
            cpu.append(float(m.group(1)))
    idle_frames = mb.parse_gfx(mb.sh(f"dumpsys gfxinfo {pkg}"))["total_frames"]
    temp = re.search(r"Current temperatures from HAL:\s*\n\s*Temperature\{mValue=([\d.]+)", mb.sh("dumpsys thermalservice"))
    pss = re.search(r"TOTAL PSS:\s+(\d+)", mb.sh(f"dumpsys meminfo {pkg} | grep 'TOTAL PSS'"))
    hz = re.search(r"refresh-rate\s*:\s*([\d.]+)", mb.sh("dumpsys SurfaceFlinger | grep refresh-rate"))

    mb.sh(f"dumpsys gfxinfo {pkg} reset")
    loop = (f"end=$(( $(date +%s) + {nav_s} )); n=0; while [ $(date +%s) -lt $end ]; do "
            f"for k in {mb.JANK_KEYS}; do [ $(date +%s) -ge $end ] && break; "
            "input keyevent $k; n=$((n+1)); sleep 0.3; done; done; echo KEYS=$n")
    mb.sh(loop, nav_s + 60)
    nav = mb.parse_gfx(mb.sh(f"dumpsys gfxinfo {pkg}"))
    return {"alvo": name, "hz": hz.group(1) if hz else None,
            "repouso_quadros": idle_frames, "repouso_cpu_media": round(sum(cpu) / len(cpu), 1) if cpu else None,
            "repouso_cpu_max": max(cpu) if cpu else None,
            "temp_c": float(temp.group(1)) if temp else None,
            "pss_mb": round(int(pss.group(1)) / 1024, 1) if pss else None,
            **{f"nav_{k}": v for k, v in nav.items()}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="192.0.2.10:5555")
    ap.add_argument("--idle", type=int, default=30)
    ap.add_argument("--nav", type=int, default=60)
    ap.add_argument("--targets", default="launcherx,modtv")
    a = ap.parse_args()
    mb.SERIAL = a.serial
    out = Path(__file__).resolve().parent.parent / "reports" / f"launchers-{datetime.now():%Y%m%d-%H%M%S}"
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for name in a.targets.split(","):
        pkg, comp = TARGETS[name.split(":")[0]]
        mb.log(f"medindo {name}")
        rows.append(measure(name, pkg, comp, a.idle, a.nav))
        mb.log(str(rows[-1]))
    mb.write_csv(out / "resultado.csv", rows)
    lines = ["# Comparação de launchers", "", "| " + " | ".join(rows[0]) + " |",
             "|" + "---|" * len(rows[0])]
    lines += ["| " + " | ".join(str(v) for v in r.values()) + " |" for r in rows]
    (out / "RESUMO.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    mb.log(f"concluído: {out}")


if __name__ == "__main__":
    main()
