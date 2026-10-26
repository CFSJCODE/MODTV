"""MODTV — auditoria de pacotes (somente leitura, processa arquivos já coletados).

Uso: python audit_packages.py <pasta reports\\audit-...>
Entrada: pm_all.txt, pm_system.txt, pm_disabled.txt, meminfo_pss.txt, boot_completed.txt,
         locked_boot_completed.txt, usagestats_full.txt (gerados por adb shell).
Saída: pacotes.csv na mesma pasta.
"""
import csv
import re
import sys
from pathlib import Path

# Classificação: (classe, descrição, o que deixa de funcionar / risco)
KEEP_USER = {
    "com.globo.globotv": "Globoplay",
    "com.google.android.youtube.tv": "YouTube",
    "com.spotify.tv.android": "Spotify",
    "com.tcl.exhibit": "TCL Galeria/Arte (modo arte)",
    "com.droidlogic.imageplayer": "Visualizador de imagens nativo (usado por modos de arte/fotos)",
    "com.google.android.apps.tv.dreamx": "Protetor de tela/ambiente do Google TV",
    "com.android.dreams.basic": "Protetor de tela básico",
}

CANDIDATE = {
    "com.plexapp.android": ("Plex (app de terceiros)", "Plex. Por ser de terceiros, pode ser desinstalado.", "baixo"),
    "com.google.android.videos": ("Google TV (loja de filmes/séries)", "Compra/aluguel de filmes e a lista 'Minha lista' da loja; as abas Filmes/Programas podem ficar vazias", "médio"),
    "com.google.android.play.games": ("Google Play Games", "Jogos da Play com login Google", "baixo"),
    "com.netflix.tokenmanager": ("Netflix Token Manager", "Login automático da Netflix (a Netflix já está desativada)", "baixo"),
    "android.autoinstalls.config.google.gtvpai": ("Play Auto Install", "Instalação automática de apps promocionais", "baixo"),
    "com.tcl.channelplus": ("TCL Channel (canais grátis com anúncios)", "Canais FAST da TCL", "baixo"),
    "com.tcl.waterfall.overseas": ("Conteúdo da aba TCL", "Aba 'TCL' do launcher sem conteúdo", "baixo/médio"),
    "com.tcl.messagebox": ("TCL Mensagens/avisos", "Avisos e promoções da TCL", "baixo"),
    "com.tcl.usercenter": ("TCL Conta de usuário", "Conta TCL (serviços online TCL)", "baixo"),
    "com.tcl.magiconnectfree": ("TCL MagiConnect", "Controle/espelhamento pelo app TCL no celular", "baixo"),
    "com.tcl.smartalexa": ("Alexa integrada TCL", "Controle por Alexa", "baixo"),
    "com.tcl.ocean.instructions": ("Manual eletrônico TCL", "Manual na TV", "baixo"),
    "com.tcl.repairguide": ("Guia de reparo TCL", "Guia de diagnóstico", "baixo"),
    "com.tcl.hotelmenu": ("Menu de hotel TCL", "Modo hotel (não usado em casa)", "baixo"),
    "com.tcl.interactive": ("TCL TIAS (TV interativa/anúncios)", "Recursos interativos de canais", "baixo"),
    "com.tcl.miracast": ("Miracast (espelhar tela Android/Windows)", "Espelhamento Miracast; o Google Cast continua", "baixo"),
    "com.tcl.airplay2": ("AirPlay (serviço TCL)", "AirPlay do iPhone/Mac; libera a porta 40521", "baixo"),
    "com.amlogic.airplay": ("AirPlay (Amlogic)", "AirPlay do iPhone/Mac", "baixo"),
    "com.google.android.marvin.talkback": ("TalkBack (acessibilidade)", "Leitor de tela", "baixo"),
    "com.google.android.syncadapters.calendar": ("Sincronização de agenda Google", "Agenda (sem uso na TV)", "baixo"),
    "com.google.android.feedback": ("Google Feedback", "Envio de relatórios de erro ao Google", "baixo"),
    "com.android.printspooler": ("Impressão", "Imprimir (sem uso na TV)", "baixo"),
    "com.google.android.tts": ("Google Fala (TTS/reconhecimento)", "Leitura em voz e reconhecimento de voz (o Assistente já está desativado)", "baixo/médio"),
}

UNCERTAIN = {
    "com.google.android.tv.remote.service": ("Controle remoto pelo app Google TV no celular", "Usar o celular como controle; libera as portas 6466/6467"),
    "com.tcl.smartlink.core": ("TCL SmartLink (porta 6000)", "Integração com o app TCL do celular/T-Cast"),
    "com.tcl.dashboard": ("Painel rápido TCL", "Pode ser o painel aberto por um botão do controle"),
    "com.tcl.suspension": ("Janela flutuante TCL", "Sobreposições/atalhos da TCL"),
    "com.tcl.esticker": ("TCL eSticker", "Adesivos/sobreposições de tela da TCL"),
    "com.tcl.ui_mediaCenter": ("Media Center (USB)", "Tocar vídeos/fotos de pen drive"),
    "com.tcl.tv.tclhome_passive": ("TCL Home passivo", "Componente da tela inicial TCL"),
    "com.tcl.logkit": ("TCL Logkit", "Coleta de logs TCL"),
    "com.tcl.android.webview": ("WebView da TCL", "Páginas internas de apps TCL"),
    "com.mirakulo.astrotv.service": ("Serviço Mirakulo/AstroTV", "Desconhecido; possível serviço de canais/EPG"),
    "com.tcl.inputmethod.international": ("Teclado TCL", "Teclado alternativo (o Gboard fica)"),
    "com.tcl.useragreement": ("Termos/privacidade TCL", "Telas de consentimento; desativar pode travar fluxos da TCL"),
}

ESSENTIAL_PREFIX = ("android", "com.android.", "com.google.android.overlay", "com.google.android.ext.",
                    "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
                    "com.droidlogic", "com.tvos", "com.dolby.", "com.android.tv.")
ESSENTIAL = {
    "com.google.android.gms": "Google Play Services", "com.google.android.gsf": "Google Services Framework",
    "com.android.vending": "Play Store (atualiza YouTube/Globoplay/Spotify)",
    "com.google.android.webview": "WebView (usado por apps de streaming)",
    "com.google.android.apps.tv.launcherx": "Tela inicial Google TV",
    "com.google.android.apps.mediashell": "Google Cast", "com.google.android.inputmethod.latin": "Gboard (teclado)",
    "com.google.android.tungsten.setupwraith": "Configuração inicial", "com.google.android.partnersetup": "Partner setup",
    "com.google.android.onetimeinitializer": "Inicializador", "com.google.android.modulemetadata": "Módulos do sistema",
    "com.tcl.tv": "TV ao vivo/entradas TCL", "com.tcl.tvinput": "Entradas HDMI/TV", "com.tcl.systemserver": "Serviço de sistema TCL",
    "com.tcl.ttvs": "Serviço central TCL", "com.tcl.providers.config": "Config TCL", "com.tcl.guard": "TGuard (segurança TCL)",
    "com.tcl.globalkeyoverlay": "Teclas do controle", "com.tcl.autopair": "Pareamento do controle",
    "com.tcl.UpdatePeripheral": "Atualização do controle", "com.tcl.partnercustomizer": "Customização TCL",
    "com.tcl.initsetup": "Configuração inicial TCL", "com.tcl.factory.view": "Menu de fábrica",
    "com.tcl.hearaid": "Efeito de áudio", "com.tcl.gamebar": "Barra de jogo (HDMI/consoles)",
    "com.google.android.katniss": "Assistente (já desativado)",
    "com.amazon.amazonvideo.livingroom": "Prime Video (já desativado)", "com.netflix.ninja": "Netflix (já desativado)",
    "com.google.android.youtube.tvmusic": "YouTube Music (já desativado)",
}


def main(d):
    d = Path(d)
    pkgs = {}
    for ln in (d / "pm_all.txt").read_text(encoding="utf-8").splitlines():
        m = re.match(r"package:(.+)=(\S+) uid:(\d+)", ln.strip())
        if m:
            pkgs[m.group(2)] = {"path": m.group(1), "uid": m.group(3)}
    system = {l.split(":", 1)[1].strip() for l in (d / "pm_system.txt").read_text().splitlines() if ":" in l}
    disabled = {l.split(":", 1)[1].strip() for l in (d / "pm_disabled.txt").read_text().splitlines() if ":" in l}
    pss = {}
    for ln in (d / "meminfo_pss.txt").read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*([\d,]+)K: (\S+?)(?::\S+)? \(pid", ln)
        if m:
            pss[m.group(2)] = pss.get(m.group(2), 0) + int(m.group(1).replace(",", ""))
    boot = set()
    for f in ("boot_completed.txt", "locked_boot_completed.txt"):
        for ln in (d / f).read_text(encoding="utf-8").splitlines():
            if "/" in ln:
                boot.add(ln.strip().split("/")[0])
    last = {}
    for m in re.finditer(r'package=(\S+) totalTimeUsed="([^"]+)" lastTimeUsed="([^"]+)"',
                         (d / "usagestats_full.txt").read_text(encoding="utf-8", errors="replace")):
        if m.group(3) > last.get(m.group(1), ("", ""))[1]:
            last[m.group(1)] = (m.group(2), m.group(3))
    rows = []
    for p, info in sorted(pkgs.items()):
        if p in KEEP_USER:
            cls, desc, impact, risk = "MANTER-USUARIO", KEEP_USER[p], "", ""
        elif p in CANDIDATE:
            cls, (desc, impact, risk) = "CANDIDATO", CANDIDATE[p]
        elif p in UNCERTAIN:
            cls, (desc, impact) = "INCERTO", UNCERTAIN[p]
            risk = "médio"
        elif p in ESSENTIAL or p.startswith(ESSENTIAL_PREFIX):
            cls, desc, impact, risk = "ESSENCIAL", ESSENTIAL.get(p, "Componente do sistema"), "", "alto"
        else:
            cls, desc, impact, risk = "INCERTO", "Não classificado", "", "médio"
        tp = "sistema" if p in system else "terceiros"
        rows.append({
            "pacote": p, "descricao": desc, "classe": cls, "tipo": tp,
            "caminho": info["path"].rsplit("/", 1)[0], "habilitado": "não" if p in disabled else "sim",
            "pss_mb": round(pss.get(p, 0) / 1024, 1), "inicia_no_boot": "sim" if p in boot else "não",
            "ultimo_uso": last.get(p, ("", ""))[1], "o_que_deixa_de_funcionar": impact, "risco": risk,
            "comando": (f"pm uninstall {p}" if tp == "terceiros" else f"pm disable-user --user 0 {p}") if cls == "CANDIDATO" else "",
            "reversao": ("reinstalar pela Play Store" if tp == "terceiros" else f"pm enable {p}") if cls == "CANDIDATO" else "",
        })
    with open(d / "pacotes.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    for cls in ("CANDIDATO", "INCERTO"):
        sel = sorted([r for r in rows if r["classe"] == cls and r["habilitado"] == "sim"],
                     key=lambda r: (-r["pss_mb"], r["inicia_no_boot"] != "sim"))
        print(f"== {cls} ({len(sel)})")
        for r in sel:
            print(f"{r['pacote']}|{r['pss_mb']}|boot={r['inicia_no_boot']}|uso={r['ultimo_uso']}|{r['descricao']}")
    print("contagem:", {c: sum(1 for r in rows if r["classe"] == c) for c in
                        ("ESSENCIAL", "MANTER-USUARIO", "CANDIDATO", "INCERTO")})


if __name__ == "__main__":
    main(sys.argv[1])
