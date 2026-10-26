# MODTV — orientações para contribuição

Leia README.md e os guias em docs/ antes de alterar o projeto.

- Preserve dados, DRM e serviços essenciais. Prefira mudanças reversíveis.
- Não presuma modelo, capacidades ou estado de uma TV sem conferir no equipamento.
- Sempre use serial ADB explícito. O usuário deve autorizar mudanças persistentes.
- Separe diagnóstico somente leitura de testes que abrem apps ou enviam teclas.
- Documente finalidade, impacto, requisitos, riscos e reversão dos comandos.
- Não execute root, reset de fábrica ou alteração de firmware sem autorização específica.
- Meça antes/depois nas mesmas condições e registre limites e falhas.
- Mantenha processamento intenso no PC e preserve originais e procedência de mídia.
- Na interface, preserve a identidade do lume: hierarquia tipográfica, ritmo de
  espaçamento, contraste e movimento pontual. Tokens em launcher/src/br/modtv/launcher/Lume.java.
- Não publique credenciais, chaves de assinatura, identificadores da rede local,
  relatórios privados, backups de dispositivos ou preferências pessoais.
- Os endereços 192.0.2.x em exemplos são fictícios: forneça o endereço da sua TV.
- Testes locais: python -m unittest discover -s tools -p "test_*.py" -v.
- Nenhuma licença para o código foi definida neste checkout; preserve as licenças
  de fontes e mídia e não atribua uma licença nova sem decisão do responsável.
