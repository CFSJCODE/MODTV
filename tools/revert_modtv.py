"""MODTV rollback preparation. Defaults to PREVIEW; --apply explicitly required.

home: restore original Google TV HOME and screensaver, keep lume data.
full: home + re-enable project-disabled packages + restore refresh and Assistant;
      disable lume while preserving its data and photos. Does NOT reinstall Plex.
No root, reboot, factory reset, pm clear, uninstall, recursive delete or ADB server restart.
"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
DREAM = "com.google.android.apps.tv.dreamx/.service.Backdrop"
VOICE = "com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService"
PREEXISTING_DISABLED = {"com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tvmusic", "com.netflix.ninja"}
GROUP1 = "com.google.android.play.games com.google.android.syncadapters.calendar com.tcl.ocean.instructions com.tcl.usercenter android.autoinstalls.config.google.gtvpai com.android.printspooler com.google.android.feedback com.google.android.marvin.talkback com.netflix.tokenmanager com.tcl.channelplus com.tcl.hotelmenu com.tcl.interactive com.tcl.magiconnectfree com.tcl.messagebox com.tcl.repairguide com.tcl.smartalexa com.tcl.waterfall.overseas".split()
GROUP2 = "com.tcl.airplay2 com.amlogic.airplay com.tcl.miracast com.google.android.videos com.google.android.tts com.tcl.ui_mediaCenter".split()
RESTORE_PACKAGES = ["com.google.android.tungsten.setupwraith", "com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.dreamx"]


def plan(profile):
    steps = []
    for pkg in RESTORE_PACKAGES:
        steps.append(("Reativar interface original", f"pm default-state --user 0 {pkg}"))
    steps += [("Selecionar Google TV", f"cmd package set-home-activity --user 0 {HOME}"),
              ("Restaurar protetor Google", f"settings put secure screensaver_components {DREAM}"),
              ("Abrir HOME original", "input keyevent KEYCODE_HOME")]
    if profile == "full":
        for pkg in GROUP1 + GROUP2 + ["com.google.android.katniss"]:
            steps.append(("Reativar pacote alterado pelo projeto", f"pm default-state --user 0 {pkg}"))
        steps += [
            ("Remover limite superior forçado", "settings delete system peak_refresh_rate"),
            ("Remover limite inferior forçado", "settings delete system min_refresh_rate"),
            ("Notificar retorno à troca dentro do grupo", "settings put secure match_content_frame_rate 1"),
            ("Remover override de matching", "settings delete secure match_content_frame_rate"),
            ("Restaurar serviço de voz original", f"settings put secure voice_interaction_service {VOICE}"),
            ("Restaurar Assistente original", f"settings put secure assistant {VOICE}"),
            ("Restaurar appop de hotword", "cmd appops set com.google.android.katniss RECORD_AUDIO_HOTWORD default"),
            ("Desativar lume sem apagar seus dados", "pm disable-user --user 0 br.modtv.launcher"),
        ]
    return steps


class Device:
    def __init__(self, serial):
        self.adb = shutil.which("adb")
        if not self.adb:
            raise RuntimeError("ADB não encontrado no PATH")
        self.serial = serial

    def call(self, *args, timeout=30):
        r = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        text = r.stdout + r.stderr
        if r.returncode or re.search(r"(?mi)^(?:Error:|Failure|Exception occurred|Security exception)", text):
            raise RuntimeError(f"Falha ADB: {args[0]} ({r.returncode}): {text[:400]}")
        return r.stdout.strip()

    def shell(self, command):
        return self.call("shell", command)

    def identity(self):
        if self.call("get-state") != "device":
            raise RuntimeError("Dispositivo não autorizado/conectado")
        model = self.shell("getprop ro.product.device")
        if model != "G09":
            raise RuntimeError(f"Dispositivo inesperado ({model}); nenhuma alteração permitida")

    def snapshot(self):
        data = {"capturado_em": datetime.now().astimezone().isoformat(), "serial": self.serial}
        queries = {
            "home": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
            "disabled": "pm list packages -d --user 0", "packages": "pm list packages --user 0",
            "display": "wm size; wm density",
        }
        data.update({k: self.shell(cmd) for k, cmd in queries.items()})
        for ns, key in (("system", "peak_refresh_rate"), ("system", "min_refresh_rate"),
                        ("secure", "match_content_frame_rate"), ("secure", "screensaver_components"),
                        ("secure", "voice_interaction_service"), ("secure", "assistant")):
            data[f"{ns}.{key}"] = self.shell(f"settings get {ns} {key}")
        return data


def check(snapshot, profile):
    failures = []
    if HOME not in snapshot["home"]:
        failures.append("HOME não resolve para Google TV")
    if snapshot["secure.screensaver_components"] != DREAM:
        failures.append("protetor original não selecionado")
    disabled = set(re.findall(r"package:(\S+)", snapshot["disabled"]))
    required = RESTORE_PACKAGES + (GROUP1 + GROUP2 + ["com.google.android.katniss"] if profile == "full" else [])
    failures += [f"pacote continua desativado: {p}" for p in required if p in disabled]
    if profile == "full":
        failures += [f"override continua presente: {key}" for key in
                     ("system.peak_refresh_rate", "system.min_refresh_rate", "secure.match_content_frame_rate")
                     if snapshot[key] != "null"]
        if "br.modtv.launcher" not in disabled:
            failures.append("lume não está desativado")
        for key in ("secure.voice_interaction_service", "secure.assistant"):
            if snapshot[key] != VOICE:
                failures.append(f"serviço de voz divergente: {key}")
    return failures


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True, help="IP:porta autorizado da TV; não usar o emulador")
    ap.add_argument("--profile", choices=("home", "full"), default="home")
    ap.add_argument("--apply", action="store_true", help="EXECUTA reversão; ausência = apenas leitura e plano")
    ap.add_argument("--backup-content", action="store_true", help="Copia fotos e APK ao PC; somente leitura na TV")
    a = ap.parse_args()
    device = Device(a.serial)
    device.identity()
    out = ROOT / "reports" / ("reversao-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
    out.mkdir()
    before = device.snapshot()
    (out / "antes.json").write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding="utf-8")
    steps = plan(a.profile)
    (out / "plano.json").write_text(json.dumps(steps, ensure_ascii=False, indent=2), encoding="utf-8")
    present = set(re.findall(r"package:(\S+)", before["packages"]))
    needed = RESTORE_PACKAGES + (GROUP1 + GROUP2 + ["com.google.android.katniss", "br.modtv.launcher"] if a.profile == "full" else [])
    missing = sorted(set(needed) - present)
    if missing:
        raise RuntimeError("Pré-requisito ausente; sem reversão: " + ", ".join(missing))
    if a.backup_content or a.apply:
        # Copy only external content and installed APK; private prefs cannot be backed up by shell.
        device.call("pull", "/sdcard/Android/data/br.modtv.launcher/files", str(out / "lume-files"), timeout=120)
        paths = device.shell("pm path br.modtv.launcher").splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("APK não único; abortado antes de alterar a TV")
        device.call("pull", paths[0][8:], str(out / "lume-instalado.apk"), timeout=120)
        hashes = {}
        for f in out.rglob("*"):
            if f.is_file():
                with f.open("rb") as stream:
                    hashes[str(f.relative_to(out))] = hashlib.file_digest(stream, "sha256").hexdigest()
        (out / "sha256.json").write_text(json.dumps(hashes, indent=2), encoding="utf-8")
    print(f"{'APLICAR' if a.apply else 'PRÉVIA — SEM ALTERAR A TV'}: {a.profile}; relatório {out}")
    for purpose, command in steps:
        print(f"{purpose}: {command}")
    if not a.apply:
        print("Nenhum comando de reversão executado. Plex requer reinstalação manual; dados antigos não têm backup.")
        return
    # Hard gate: original HOME must resolve before any full-profile operations or lume disable.
    log = []
    try:
        for i, (purpose, command) in enumerate(steps):
            result = device.shell(command)
            log.append({"purpose": purpose, "command": command, "result": result})
            (out / "execucao.json").write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
            if i == 4 and HOME not in device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"):
                raise RuntimeError("HOME original não confirmou; interrompido antes de desativar lume")
        after = device.snapshot()
        (out / "depois.json").write_text(json.dumps(after, ensure_ascii=False, indent=2), encoding="utf-8")
        failures = check(after, a.profile)
        # Packages disabled before the project must stay exactly as they were immediately before execution.
        old_disabled = set(re.findall(r"package:(\S+)", before["disabled"]))
        new_disabled = set(re.findall(r"package:(\S+)", after["disabled"]))
        if old_disabled & PREEXISTING_DISABLED != new_disabled & PREEXISTING_DISABLED:
            failures.append("estado de pacotes anteriores ao projeto divergiu")
        if failures:
            raise RuntimeError("Verificação incompleta: " + "; ".join(failures))
        print("Reversão funcional verificada. Confirmar visualmente HOME, streaming, áudio e entradas.")
        if a.profile == "full":
            print("PENDÊNCIA: reinstalar Plex pela Play Store. APK/dados antigos não foram recuperados.")
    except Exception:
        print(f"Interrompido; passos já executados estão em {out}. Não houve apagamento de dados.")
        raise


if __name__ == "__main__":
    main()
