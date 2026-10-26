"""Prepare a small attributed, offline landscape collection; never writes to the TV.

Uses ffprobe/ffmpeg from PATH. Originals are preserved; 16:9 derivatives are
center-cropped and downscaled to UHD, never upscaled. No API key is needed.
"""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
from datetime import datetime, timezone

SOURCES = [
    dict(stem="01-braies", titulo="Reflexos no lago", lugar="Lago di Braies, Itália",
         fotografo="Luca Bravo", fonte="https://unsplash.com/photos/brown-house-near-body-of-water-zAjdgNXsMeg",
         download="https://images.unsplash.com/photo-1470770841072-f978cf4d019e?fm=jpg&q=100",
         licenca="Unsplash License", licenca_url="https://unsplash.com/license"),
    dict(stem="02-dolomitas", titulo="Vale das Dolomitas", lugar="San Martin de Tor, Itália",
         fotografo="Luca Bravo", fonte="https://unsplash.com/photos/wUSoHXq3v4g",
         pagina="https://commons.wikimedia.org/wiki/File:Granite_peaks_over_a_forested_valley_(Unsplash).jpg",
         licenca="CC0 1.0 (publicação anterior a junho de 2017)",
         licenca_url="https://creativecommons.org/publicdomain/zero/1.0/"),
    dict(stem="03-seljalandsfoss", titulo="Luz sobre a cascata", lugar="Seljalandsfoss, Islândia",
         fotografo="Robert Lukeman", fonte="https://unsplash.com/photos/zNN6ubHmruI",
         pagina="https://commons.wikimedia.org/wiki/File:Seljalandsfoss_Waterfall.jpg",
         licenca="CC0 1.0 (licença revisada no Wikimedia Commons)",
         licenca_url="https://creativecommons.org/publicdomain/zero/1.0/"),
]


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "MODTV/1.0 (personal offline landscape gallery)"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()


def probe(path):
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_streams",
        "-of", "json", str(path)], encoding="utf-8"))["streams"][0]


def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def run(args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-n", *args], check=True, timeout=240)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parents[1] / "launcher/paisagens/colecao")
    a = ap.parse_args()
    if not all(shutil.which(x) for x in ("ffmpeg", "ffprobe")):
        ap.error("ffmpeg e ffprobe devem estar no PATH")
    originals = a.out.parent / "originais"
    originals.mkdir(parents=True, exist_ok=True)
    a.out.mkdir(parents=True, exist_ok=True)
    records = []
    for src in SOURCES:
        item = dict(src)
        original = originals / (item["stem"] + ".jpg")
        if "download" not in item:
            page = fetch(item["pagina"]).decode("utf-8")
            candidates = re.findall(r'href="(https://upload\.wikimedia\.org/wikipedia/commons/[^\"]+)"', page)
            candidates = [html.unescape(u).split("?", 1)[0] for u in candidates]
            candidates = [u for u in candidates if "/thumb/" not in u and u.lower().endswith(".jpg")]
            if not candidates:
                raise RuntimeError("Link original não encontrado: " + item["pagina"])
            item["download"] = candidates[0]
        if not original.exists():
            data = fetch(item["download"])
            temp = original.with_suffix(".part")
            temp.write_bytes(data)
            probe(temp)  # reject HTML/error responses before promoting
            temp.replace(original)
        info = probe(original)
        if info["width"] < 3840 or info["height"] < 2160:
            raise RuntimeError(f"Fonte insuficiente para UHD sem upscale: {original.name}")
        jpg = a.out / (item["stem"] + ".jpg")
        mp4 = jpg.with_suffix(".mp4")
        if not jpg.exists():
            run(["-i", str(original), "-vf", "scale=3840:2160:force_original_aspect_ratio=increase:flags=lanczos,crop=3840:2160,setsar=1",
                 "-frames:v", "1", "-q:v", "2", str(jpg)])
        if not mp4.exists():
            # SDR BT.709, limited range, no audio. Pause after first rendered frame on TV.
            run(["-loop", "1", "-framerate", "24", "-i", str(jpg), "-t", "1", "-an",
                 "-vf", "scale=in_range=pc:out_range=tv,format=yuv420p", "-c:v", "libx265",
                 "-preset", "medium", "-crf", "18", "-threads", "4",
                 "-x265-params", "pools=4:frame-threads=2:log-level=error:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=limited",
                 "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv",
                 "-tag:v", "hvc1", "-movflags", "+faststart", str(mp4)])
        video = probe(mp4)
        if (video["width"], video["height"], video["codec_name"]) != (3840, 2160, "hevc"):
            raise RuntimeError("Clipe inválido: " + str(mp4))
        item.update(arquivo=jpg.name, original_resolucao=[info["width"], info["height"]],
                    saida_resolucao=[3840, 2160], recorte="central 16:9, sem upscale",
                    original_sha256=sha(original), jpg_sha256=sha(jpg), mp4_sha256=sha(mp4),
                    preparado_em=datetime.now(timezone.utc).isoformat(),
                    video_cor={k: video.get(k) for k in ("color_space", "color_transfer", "color_primaries", "color_range")})
        records.append(item)
        print(f"Pronto: {jpg.name}, fonte {info['width']}x{info['height']}, HEVC UHD SDR", flush=True)
    (a.out / "paisagens.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (a.out / "CREDITOS.md").write_text("# Paisagens — créditos e procedência\n\nPreparadas para uso pessoal offline na TV. Originais preservados no PC.\n\n" +
        "\n\n".join(f"- **{r['titulo']}** — {r['fotografo']}, {r['lugar']}. [Fonte]({r['fonte']}); "
                      f"[licença]({r['licenca_url']}): {r['licenca']}. Fonte: {r['original_resolucao']}; "
                      f"saída UHD com {r['recorte']}." for r in records) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
