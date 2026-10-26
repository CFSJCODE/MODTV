# Reversão funcional do MODTV

O executor tools/revert_modtv.py é conservador: prévia por padrão e --apply
obrigatório para escrever na TV. Este guia documenta um histórico específico
do device G09; não oferece uma lista universal de apps a desativar.

## Uso

```powershell
$tv = Read-Host "Serial ADB autorizado (IP:porta)"
python tools/revert_modtv.py --serial $tv --profile full
```

A prévia consulta o estado e gera plano em reports/. Para executar depois de
revisar o plano e obter autorização, acrescentar --apply.

| Perfil | Reversão |
|---|---|
| home | Reativa Google TV/configuração inicial, seleciona HOME e protetor originais |
| full | home + grupos registrados, Assistente/voz, remoção de overrides de refresh e desativação do lume sem apagar dados |

Antes de aplicar, o programa exige device G09 conectado, todos os pacotes
necessários, cópia do APK e da pasta externa do lume e snapshot das chaves
consultadas. Guarda hashes e cada passo. Falha interrompe a execução; não é
uma transação atômica. HOME original é verificado antes de desativar lume.
Não há root, reboot, reset de fábrica, pm clear ou uninstall no plano.

## Cobertura e limites

Os 26 pacotes desativados pelo projeto estão listados no código do executor.
Prime Video, YouTube Music e Netflix já estavam desativados na linha de base
e não são reativados. Appops de hotword é restaurado ao default; Assistente
reativado pode voltar a usar microfone far-field e consumir CPU como antes.

Para o refresh, remove peak_refresh_rate/min_refresh_rate, escreve
match_content_frame_rate=1 e só depois apaga a chave, para notificar o diretor
de modos. Verificar o modo real após aplicar. A densidade 320 e o override de
interface 1920x1080 eram anteriores às mudanças e não devem ser apagados.
Tentativas antigas de privacidade/hotword/densidade já haviam sido revertidas;
não sobrescrever uma nova escolha de privacidade para repetir essa etapa.

Plex foi desinstalado no histórico. Reinstalar pela Play Store não garante
recuperação dos dados antigos, para os quais não existe backup conhecido.
O backup externo do lume não inclui preferências privadas (allowBackup=false).
Por isso o executor desativa, em vez de remover, o app. Se um dia houver pedido
de remoção completa, primeiro verificar Google TV/Home e preservar conteúdo;
a desinstalação pode apagar dados que esse backup não recupera. Downgrade de
APK também não é garantido e não deve ser forçado por desinstalação sem consentimento.

Para retornar a uma coleção anterior, guardar a coleção atual em pasta de nome
novo e restaurar juntos arquivos/JSON da cópia anterior, sem sobrescrever ou
apagar a única cópia. Isso é separado da reversão funcional e depende dos backups
locais; nenhuma mídia privada ou backup de TV é distribuído no repositório.

As alterações operacionais anteriores incluíram abrir/fechar apps e zerar
contadores de diagnóstico. Esses estados temporários não podem ser restaurados
como uma imagem exata de memória. Nenhum firmware, root ou DRM foi alterado.
Fontes e evidências no PC são preservadas, não apagadas ao reverter a TV.
A automação temporária de acompanhamento já foi excluída no ambiente de origem.

## Sem ADB

Conferir rede/IP e depuração na própria TV. Pelas Configurações/Apps, procurar
Google TV e configuração inicial na lista de apps do sistema/desativados e usar
Ativar, se disponível. O nome e o menu dependem do firmware. Se o comando não
estiver disponível, recuperar a conexão antes de avançar; não presumir sucesso.

## Validação

A prévia e testes locais foram executados; a aplicação completa não foi executada
no aparelho. Ao aplicar, verificar fisicamente Home, configurações, Cast, Play
Store, streaming, áudio, HDMI e protetor. Reversão é funcional, não imagem exata
de fábrica nem garantia de recuperar dados previamente apagados.
