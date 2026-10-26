# lume · Paisagens

Galeria local com troca automática, relógio, legenda, escurecimento, seletor
de foto fixa ou rotação e implementação de DreamService para protetor de tela.

## Controle

- Esquerda/direita: trocar foto; OK: mostrar ou esconder legenda; Voltar: sair.
- No lume, Escolher paisagem seleciona foto fixa ou modo rotativo.
- Configurações/Paisagens: intervalo, relógio e escurecimento por sobreposição.
  Isso não altera calibração ou backlight da TCL.

## Conteúdo e procedência

Prepare no PC com python tools/prepare_paisagens.py (FFmpeg/FFprobe no PATH).
O programa baixa as fontes públicas, preserva originais, verifica dimensões e
gera JPEG/clipe HEVC UHD sem upscale. Os arquivos são regeneráveis e não estão
incluídos no Git. [Créditos](../launcher/paisagens/colecao/CREDITOS.md) e metadados
ficam junto da coleção. As fotos são de Luca Bravo e Robert Lukeman, com
licença Unsplash ou CC0 individualmente documentada.

Enviar com launcher/paisagens/enviar.ps1 -Device IP:porta. Esse envio escreve
arquivos no aparelho; exige autorização e backup antes de sobrescrever nomes.
Pasta do app: /sdcard/Android/data/br.modtv.launcher/files/paisagens/.
Também há suporte a /sdcard/lume/paisagens/ se a permissão necessária for concedida.
Não presumir que essa pasta é um pendrive montado automaticamente.

## Caminho UHD e limites

Um clipe HEVC é pausado após o quadro inicial no SurfaceView. Quando o caminho
falha, o app usa JPEG em 1080p. Na TV de teste foram confirmados decoder e
buffer/crop 3840x2160, composição por hardware e imagem física pelo usuário.
O firmware também anuncia modo 3840x1080p120hz; portanto a resolução física
integral a 120 Hz não foi comprovada. Interface Android em 1920x1080.
A captura ADB pode omitir o plano de vídeo; imagem preta na captura não prova
ausência de imagem no painel.

Galeria e protetor concluíram 20 minutos cada, com 41 amostras válidas por
coleta. PSS médio de 62,96 MB e 70,25 MB; CPU média amostral de 0,97% e 1,01%
de um núcleo; temperaturas de 53,0–55,2 e 51,4–55,3 °C. Nenhum alerta térmico
nas leituras. Entrada natural do protetor confirmada. Sem extrapolação para
uso prolongado ou outros modelos. Relatórios brutos ficam locais.

Não limpar dados ou desinstalar o lume para atualizar fotos: conteúdo na pasta
externa do app pode ser apagado. Consulte [reversão](REVERSAO-COMPLETA.md).
