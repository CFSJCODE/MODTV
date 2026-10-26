# Envia as paisagens (JPEG 4K + clipe HEVC .mp4 + paisagens.json) para a pasta do lume na TV.
# Uso: ./launcher/paisagens/enviar.ps1 -Device IP:porta [-Pasta caminho]
# A pasta de destino é do app: some se o app for desinstalado ou tiver os dados limpos (pm clear).
param(
    [Parameter(Mandatory=$true)][string]$Device,
    [string]$Pasta = "$PSScriptRoot\colecao"
)
$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb -ErrorAction Stop).Source
$dest = '/sdcard/Android/data/br.modtv.launcher/files/paisagens'
& $adb -s $Device shell mkdir -p $dest
Get-ChildItem $Pasta -File | Where-Object { $_.Extension -in '.jpg', '.jpeg', '.png', '.webp', '.mp4', '.json' } | ForEach-Object {
    & $adb -s $Device push $_.FullName "$dest/" | Out-Null
    if ($LASTEXITCODE) { throw "falhou: $($_.Name)" }
    Write-Output "enviado: $($_.Name)"
}
& $adb -s $Device shell ls -la $dest
