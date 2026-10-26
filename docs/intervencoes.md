# Intervenções e evidências

O histórico operacional detalhado e os relatórios da TV de desenvolvimento são
mantidos localmente, pois incluem informações da rede e do equipamento.
Esta versão pública registra o escopo e os resultados, sem transcrições de operação.

## Resumo das alterações estudadas

- Inventário de Android, CPU/GPU, memória, armazenamento, pacotes e display.
- Desativação autorizada do Assistente e de grupos selecionados de aplicativos.
- Tentativas de hotword/privacidade e densidade, posteriormente revertidas.
- Ajuste experimental de refresh; buffer UHD não comprova resolução física a 120 Hz.
- Instalação do lume, Paisagens e protetor; originais e créditos preservados.
- Troca do HOME após autorização; particularidades de prioridade do firmware TCL.
- Comparação pareada de recursos e preparação de reversão funcional.

Em 25/10/2026, a comparação encontrou PSS médio de 154,94 MB no Google TV e
59,03 MB no lume. Quadros atrasados: 1,44% e 6,76%, respectivamente. Menor consumo
em repouso não demonstrou superioridade geral de fluidez ou economia elétrica.

Consulte [launcher](launcher-padrao.md), [Paisagens](paisagens.md) e
[reversão completa](REVERSAO-COMPLETA.md). Resultados refletem um equipamento;
não são autorização nem receita universal para modificar outras TVs.

## Modelo para novos registros locais

Registrar data, equipamento validado, autorização, estado anterior, comando,
finalidade, requisitos, impacto, riscos, saída resumida, verificação e reversão.
Armazenar evidências locais em reports/, que não é versionado.
