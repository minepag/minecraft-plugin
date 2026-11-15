# Plugin MinePag para Minecraft

[![Build](https://github.com/minepag/minecraft-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/minepag/minecraft-plugin/actions/workflows/build.yml)
[![Licença](https://img.shields.io/github/license/minepag/minecraft-plugin)](LICENSE)

Entrega automaticamente as compras da sua loja MinePag no servidor Minecraft: executa os comandos configurados nos produtos (VIP, itens, chaves, permissões) e anuncia as compras no chat.

O plugin busca as entregas na MinePag (não abre portas no servidor) e confirma cada resultado. Ele nunca executa o mesmo comando duas vezes, mesmo que o servidor caia no meio de uma entrega.

## Compatibilidade

| Plataforma | Versões | Java |
|---|---|---|
| Paper e Purpur | 1.21.x e 26.x | 21 (1.21.x) ou 25 (26.x) |
| Folia | 1.21.x e 26.x | 21 ou 25 |
| Spigot | 1.21.x e 26.x | 21 ou 25 |

O mesmo `MinePag.jar` funciona em todas. No Spigot, as mensagens usam cores no formato legado.

## Instalação

1. Baixe o `MinePag.jar` no painel da MinePag, em **Entregas › Minecraft**, ou na [última release](https://github.com/minepag/minecraft-plugin/releases/latest). Cada release traz o SHA-256 do arquivo em `MinePag.jar.sha256`.
2. Coloque o arquivo na pasta `plugins/` do servidor e reinicie o servidor (não use `/reload`).
3. Na primeira inicialização, o plugin baixa a biblioteca SQLite (`sqlite-jdbc`) e cria `plugins/MinePag/config.yml`.

## Pareamento

O pareamento liga este servidor a uma loja. Faça uma vez por servidor.

1. No painel, abra **Entregas › Minecraft** e gere um código de pareamento. O código tem 8 caracteres e vale por 10 minutos.
2. No **console** do servidor, rode:

   ```
   minepag pair <código>
   ```

3. O console confirma a loja e o servidor pareados, e as entregas começam na hora.

O token do servidor fica em `plugins/MinePag/data/credentials.yml`, com permissão de leitura só para o dono do processo. Não compartilhe esse arquivo. O `config.yml` não tem segredos e pode ser enviado ao suporte.

Para trocar de loja ou depois de revogar o servidor no painel, gere um novo código e rode `minepag pair <código>` de novo.

Para desconectar o servidor da loja, rode `minepag unpair confirm` no console: o token é revogado na MinePag e o `credentials.yml` é apagado. As entregas pendentes ficam na fila até um novo pareamento.

## Como as entregas funcionam

- O plugin busca as entregas pendentes a cada 30 segundos (ou no intervalo que a MinePag pedir) e também logo depois que um jogador entra no servidor.
- Comandos que exigem o jogador online esperam ele entrar. Se o produto precisa de espaço no inventário, o jogador recebe um aviso e a entrega acontece quando houver espaço.
- Quando o jogador sai ou está sem espaço no momento da execução, o plugin devolve o comando à MinePag como adiado: ele volta para a fila sem gastar tentativa.
- Antes de executar, o plugin grava o comando em `plugins/MinePag/data/journal.db`. Se o servidor cair durante a execução, o comando **não** é repetido: ele aparece no painel com resultado desconhecido para você conferir.
- Cada resultado (sucesso, falha ou desconhecido) é confirmado à MinePag. Se a internet cair, as confirmações ficam guardadas e são enviadas depois.
- Se a MinePag ficar fora do ar, o plugin espera cada vez mais entre as tentativas (até 5 minutos) e respeita os limites de requisição.
- Se o token for revogado, o plugin para de buscar entregas e avisa no console. Gere um novo código e pareie de novo.
- Se o servidor for pausado no painel, o plugin avisa no console, consulta a MinePag a cada 5 minutos e volta sozinho quando o servidor for reativado. O `/minepag status` mostra "pausado no painel da MinePag".
- Os comandos de um benefício chegam e rodam na ordem em que foram cadastrados.

### Segurança

- A lista fixa de bloqueio (`CommandPolicy.ALWAYS_BLOCKED`, igual a `x-minepag-blocked-commands` no [contrato do protocolo](protocol/minecraft-agent.openapi.json)) nunca é executada, inclusive dentro de `execute ... run` e `return run`: `op`, `deop`, `stop`, `restart`, `reload`, `rl`, `whitelist`, `ban-ip`, `pardon-ip`, `save-off`, `transfer`, `sudo`, `esudo`, `plugman`, `minepag`, `mpag`, `lp editor`, `luckperms editor`, `lp import` e `luckperms import`. `delivery.blocked-commands` só acrescenta comandos.
- O nick do jogador é validado antes de qualquer comando: 3 a 16 letras, números ou `_` (Java) ou o prefixo `.` do Floodgate seguido de até 15 caracteres (Bedrock).
- A conexão com a MinePag usa HTTPS e não segue redirecionamentos. O token nunca aparece no log.

## Comandos

| Comando | Quem pode | O que faz |
|---|---|---|
| `/minepag help` | todos | Lista só os comandos que quem pediu pode usar: o jogador comum vê o `link`; o console não vê o `link`, que é só para jogadores. |
| `/minepag link <código>` | jogadores | Vincula o nick à conta do jogador na loja. O código aparece na conta do cliente, na loja. |
| `/minepag pair <código>` | admin | Pareia o servidor com a loja. Prefira usar no console. |
| `/minepag unpair confirm` | admin | Desconecta o servidor da loja e revoga o token. |
| `/minepag status` | admin | Mostra loja, servidor, token mascarado, última e próxima busca, falhas e confirmações pendentes. |
| `/minepag forcecheck` | admin | Busca entregas agora (intervalo mínimo de 10 segundos entre usos). Também retoma as entregas pausadas. |
| `/minepag reload` | admin | Recarrega o `config.yml` e o `credentials.yml`. |

O comando também responde por `/mpag`. O tab-complete segue as mesmas permissões e completa o `confirm` do `unpair`.

## Permissões

| Permissão | Padrão | Libera |
|---|---|---|
| `minepag.use` | todos | `/minepag help` |
| `minepag.link` | todos | `/minepag link` |
| `minepag.admin` | op | Todas as permissões abaixo |
| `minepag.admin.pair` | op | `/minepag pair` e `/minepag unpair` |
| `minepag.admin.status` | op | `/minepag status` |
| `minepag.admin.forcecheck` | op | `/minepag forcecheck` |
| `minepag.admin.reload` | op | `/minepag reload` |

## Configuração (`config.yml`)

| Chave | Padrão | Descrição |
|---|---|---|
| `api-url` | `https://api.minepag.com.br` | Endereço da API da MinePag. Só altere com orientação do suporte. Precisa ser HTTPS. |
| `poll-interval-seconds` | `30` | Intervalo mínimo entre buscas (5 a 300). A MinePag pode pedir intervalos maiores; o plugin nunca busca mais rápido que isto. |
| `check-on-join` | `true` | Busca as entregas do jogador assim que ele entra. |
| `delivery.blocked-commands` | `[]` | Comandos extras que nunca são executados, além da lista fixa. Vale o comando raiz (`kill`, `minecraft:kill`) ou um prefixo (`eco reset`). |
| `announcements.enabled` | `true` | Anuncia as compras no chat. |
| `announcements.min-interval-seconds` | `10` | Intervalo mínimo entre anúncios. No máximo 20 anúncios ficam na fila. |
| `language` | `pt-BR` | Idioma das mensagens: `pt-BR` ou `en`. |
| `debug` | `false` | Registra detalhes extras no console. |
| `messages.<idioma>.*` | — | Todas as mensagens, em [MiniMessage](https://docs.advntr.dev/minimessage/format). A ajuda usa `help-header`, `help-empty` e uma linha `help-<subcomando>` por comando. |

### Mensagens e anúncios

As mensagens usam MiniMessage, como `<green>texto</green>`. Placeholders disponíveis no anúncio de compra:

- `<player>`: nick do comprador;
- `<product>`: nome do produto;
- `<store_url>`: endereço da loja (clicável no Paper).

Os valores vindos da loja nunca são interpretados como formatação, então um nome de produto com `<red>` aparece como texto.

## Desenvolvimento

Requisitos: Docker (ou JDK 25 local).

```bash
git clone https://github.com/minepag/minecraft-plugin.git
cd minecraft-plugin

# Build e testes (gera build/libs/MinePag.jar)
docker run --rm -v "$PWD":/home/gradle/project -v minepag-gradle-cache:/home/gradle/.gradle \
  -w /home/gradle/project gradle:9.8.1-jdk25 gradle build --no-daemon

# Ou, com JDK 25 instalado
./gradlew build
```

- Compila contra `paper-api` 26.3 com JDK 25 e gera bytecode Java 21 (`options.release = 21`), para rodar também no Paper 1.21.x.
- Testes JUnit 5 cobrem ajuda e tab-complete por permissão, parsing do protocolo, contrato com a MinePag, backoff e Retry-After, journal SQLite, lista de bloqueio e conversão de mensagens para o formato legado.

| Pasta | O que tem |
|---|---|
| `src/main/java/com/minepag/plugin/command` | `/minepag` e subcomandos |
| `src/main/java/com/minepag/plugin/config` | `config.yml`, mensagens e `credentials.yml` |
| `src/main/java/com/minepag/plugin/polling` | Busca de entregas, confirmações e heartbeat |
| `src/main/java/com/minepag/plugin/delivery` | Execução dos comandos, journal SQLite, lista de bloqueio e anúncios |
| `src/main/java/com/minepag/plugin/protocol` | Tipos e cliente HTTP do protocolo |
| `src/main/java/com/minepag/plugin/platform` | Diferenças entre Paper, Folia e Spigot |
| `protocol/` | Contrato do protocolo publicado pela MinePag |

### Testar num servidor de verdade

Servidor Paper 26.3 em Docker com o plugin compilado:

```bash
docker run --rm -e EULA=TRUE -e TYPE=PAPER -e VERSION=26.3 -p 25565:25565 \
  -v "$PWD/build/libs:/plugins" itzg/minecraft-server
```

Para usar uma MinePag rodando na sua máquina, ajuste o `api-url` em `plugins/MinePag/config.yml`. HTTP só é aceito para endereços locais:

- servidor fora do Docker: `api-url: "http://localhost:3100"`;
- servidor no Docker (como no exemplo acima): `api-url: "http://host.docker.internal:3100"`.

Gere o código de pareamento no painel local e rode `minepag pair <código>` no console do servidor.

### Protocolo

O plugin conversa com a MinePag pelas rotas `/api/minecraft/v1` (`pair`, `pull`, `ack`, `heartbeat`, `link` e `unpair`). O contrato fica em [`protocol/minecraft-agent.openapi.json`](protocol/minecraft-agent.openapi.json), uma cópia do arquivo que a MinePag publica em `https://docs.minepag.com.br/minecraft-agent.openapi.json`. Além das rotas, ele traz a lista fixa de comandos bloqueados (`x-minepag-blocked-commands`) e os limites (`x-minepag-limits`).

O `ProtocolContractTest` confere o plugin contra essa cópia:

- os campos de cada tipo em `AgentProtocol.java` (o plugin só envia o que a MinePag aceita, sempre envia o obrigatório e só lê o que ela devolve);
- os status de confirmação;
- a lista de comandos bloqueados;
- as rotas e os limites.

Para trazer uma versão nova do contrato e ver o que mudou:

```bash
scripts/sync-protocol.sh
# ou, com o repositório da MinePag ao lado:
scripts/sync-protocol.sh ../minepag/docs-site/minecraft-agent.openapi.json
```

Servidores ficam meses em versões antigas do plugin. Por isso a MinePag só faz mudanças compatíveis em `/api/minecraft/v1`; uma mudança incompatível vira uma nova versão do caminho.

### Release

1. Atualize `version` em `build.gradle.kts` num pull request e faça o merge depois que o check `Build e testes` passar.
2. Na `main` atualizada, crie e envie a tag com a mesma versão:

   ```bash
   git switch main && git pull
   git tag v1.0.1
   git push origin v1.0.1
   ```

3. O workflow `Build` compila, testa e publica a release com `MinePag.jar` e `MinePag.jar.sha256`. O link `releases/latest/download/MinePag.jar` passa a entregar a versão nova, e é ele que o painel da MinePag usa.

## Contribuir

- **Bugs e sugestões:** abra uma [issue](https://github.com/minepag/minecraft-plugin/issues) com a versão do plugin, a plataforma (Paper, Folia, Purpur ou Spigot), a versão do Minecraft e o trecho do console. Nunca cole o `credentials.yml` nem o token do servidor.
- **Pull requests:** toda mudança entra por PR, inclusive da equipe: a `main` é protegida e só aceita merge com o check `Build e testes` verde e as conversas resolvidas. Rode `./gradlew build` antes de enviar; o workflow `Build` roda os mesmos testes em cada PR. Mudanças que alteram o que o plugin envia ou lê precisam continuar passando no `ProtocolContractTest`. Se a mudança depender de algo novo na MinePag, abra uma issue antes.
- **Dúvidas de uso da loja:** fale com o suporte da MinePag pelo painel.

## Segurança

Não abra issue pública para falhas de segurança. Use o [formulário privado do GitHub](https://github.com/minepag/minecraft-plugin/security/advisories/new) (Security › Report a vulnerability) ou escreva para security@minepag.com.br. Os detalhes estão em [SECURITY.md](SECURITY.md).

## Licença

Código aberto sob a [licença Apache 2.0](LICENSE).
