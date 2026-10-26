# Launcher padrão e comparação

O lume foi testado como HOME em uma TCL Android TV 12, device G09. O usuário
confirmou o botão Home e a resolução HOME foi verificada por ADB.

## Comparação local em 25/10/2026

Ordem Google/lume/lume/Google, duas rodadas por launcher, aproximadamente 32 s
de repouso e 20 s de D-pad por rodada. Mesmos ajustes de tela, sem force-stop.

| Métrica | Google TV | lume |
|---|---:|---:|
| CPU do pacote em repouso (% de um núcleo) | 6,77% | 0,00% na resolução amostral |
| PSS dos processos do pacote | 154,94 MB | 59,03 MB |
| CPU total do sistema | 8,17% | 5,49% |
| Memória disponível do sistema | 852,06 MB | 894,72 MB |
| Redesenho em repouso | 3,08 quadros/s | 0,00 quadros/s |
| Quadros atrasados na navegação | 1,44% | 6,76% |
| p50 dos quadros de navegação | 9 ms | 11 ms |
| p99 | 16 ms | 15–16 ms |

O lume usou ~62% menos PSS, mas o Google TV teve menos quadros atrasados.
Os processos Google ainda ocupavam memória quando o lume estava aberto; a
diferença de PSS não equivale a RAM total liberada. Sem medição elétrica.
Os relatórios brutos permanecem locais; a ferramenta reproduzível está em
tools/benchmark_launchers.py e abre telas, envia teclas e reinicia contadores gfxinfo.

## Particularidade observada no firmware

Salvar a preferência com cmd package set-home-activity não bastou: Google TV
e a atividade de recuperação tinham prioridade superior. Alterações isoladas
das atividades foram negadas e elevar a prioridade do app não resolveu.
A troca exigiu desativação por usuário de Google TV e do pacote de configuração
inicial, após autorização específica. Instalações e dados foram preservados.
Isso pode afetar fluxos de configuração após atualizações; não copiar esse
procedimento para outro aparelho sem validar dependências e recuperação.

O código Java dos APKs 2.1 e 2.1.1 foi comparado e é idêntico. A versão atual
tem versionCode 4. O repositório contém fontes, não APKs/chaves de assinatura.

## Recuperação

Preparar a reversão antes de mudar o HOME. Ver [guia completo](REVERSAO-COMPLETA.md).
Sem ADB, usar as Configurações da TV para verificar rede/depuração ou reativar
apps desativados, se o firmware disponibilizar Ativar. Reinício normal não
reativa pacotes; restauração de fábrica não é um atalho para essa reversão.
