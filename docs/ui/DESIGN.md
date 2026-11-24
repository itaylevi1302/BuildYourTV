# cleanyourtv-ui: design

Fork do [CleanYourTV](https://github.com/Gathin23/cleanyourtv) que reaproveita 100% do original e acrescenta uma
segunda tela inicial, inspirada no [Free My TV](https://github.com/simbasjungles/free-my-tv).

## Princípios

1. **Compatível sempre.** O fork só **acrescenta** arquivos. Nada do upstream é editado (README, `tools/`,
   `launcher/`, `examples/`, `PROMPT.md`), então `git merge upstream/main` nunca conflita. Tudo do CleanYourTV
   continua funcionando igual, inclusive o launcher original.
2. **Tudo pelo computador, uma vez.** Debloat, troca de Home e ligação do serviço de botões usam os scripts do
   CleanYourTV. O app **não** usa o ADB da própria TV, então a depuração pode ser desligada no fim.
3. **Configuração na TV só do que não precisa de ADB.** Prateleira, botões, recomendações e cidade, numa tela de
   ajustes no estilo do Free My TV.
4. **Créditos claros.** O visual e partes do código vêm do Free My TV (MIT). `launcher-ui/NOTICE` traz o aviso de
   copyright dele, ao lado do MIT do CleanYourTV.
5. **Custo em tokens declarado.** O launcher novo é maior (mais código, rede, telas) que o original de ~429 KB.
   Adaptá-lo a uma TV com um assistente lê mais arquivos e gasta mais tokens; a documentação diz isso e indica o
   launcher original para quem quer o mínimo.

## O que entra no repositório

| Caminho | O que é |
|---|---|
| `launcher-ui/` | O app novo (Java, sem Gradle como o Free My TV ou com o Gradle do `launcher/`, ver Decisões). Pacote próprio, então convive com o launcher original instalado |
| `launcher-ui/build-and-install.sh` (+ `.ps1`) | Mesmo contrato do `launcher/build-and-install.sh`: `TV_HOST`, `STOCK_LAUNCHER`, `HOME_KEY_SERVICE=1`, rollback se a Home não pegar. Usa `tools/tv.py` e `tools/b64push.py` sem modificá-los |
| `launcher-ui/theme.sh` | Envia um `config.json` para o app novo (o `tools/theme.sh` do upstream só conhece o pacote do launcher original) |
| `examples/<modelo>/remote.json` | Novo, opcional, por modelo: os botões do controle, o código de cada um e se é **nativo** (nunca remapeado) ou **configurável**, com o padrão |
| `README.md`, `PROMPT.ui.md` | Como usar o launcher novo; o prompt do upstream continua intacto |
| `docs/ui/` | Este documento e o que vier |

## Telas e recursos do launcher novo (do Free My TV)

- Home: "Para você" com 3 sugestões por hora (TMDB, só de serviços instalados ou mapeados), abas Filmes/Séries,
  prateleira de vidro, Entradas, clima, tema dia/noite pelo pôr do sol, screensaver com fotos e relógio.
- Ajustes na TV: Ajustes do Início, Prateleira, **Botões do Controle**, **Recomendações**, Ajustes da TV.
- Sai do Free My TV: a troca Free/Original pelo app, o cliente ADB interno (`Adb.java`), o `HomeMode` e o
  `Watchdog`, que dependem do ADB aberto. A volta ao original é `tools/restore.sh`, como no CleanYourTV.

## Botões do controle

Formato do `examples/tcl-55c725/remote.json` (o seu controle, já levantado):

```json
{
  "remote": "TCL_RC902V_M2",
  "buttons": [
    { "name": "Marcador",     "scan": 395, "label": "TCL_LIST",      "kind": "configurable", "default": "tmdb:watchlist" },
    { "name": "TV",           "scan": 365, "label": "TCL_EPG",       "kind": "configurable", "default": "" },
    { "name": "Entrada",      "scan": 417, "label": "TV_INPUT",      "kind": "native" },
    { "name": "Menu",         "scan": 139, "label": "MENU",          "kind": "native" },
    { "name": "Camadas",      "scan": 749, "label": "TCL_HOVRING",   "kind": "native" },
    { "name": "Teclas coloridas", "scan": 235, "label": "TCL_NUMBER", "kind": "configurable", "default": "" },
    { "name": "Grade vermelha", "scan": 204, "label": "TCL_DASHBOARD", "kind": "configurable", "default": "" },
    { "name": "Netflix",      "scan": 242, "label": "TCL_NETFLIX",   "kind": "configurable", "default": "com.netflix.ninja" }
  ]
}
```

(mais Prime 752, Globoplay 586, Disney+ 762, YouTube 245, TCL Channel 227, Engrenagem 233; mudo, volume, canal,
voltar e Home ficam `native`.)

Ações possíveis de um botão configurável: um app (inclusive de celular, como o Tomato), `input:<id>` (uma entrada
HDMI), `live` (TV ao vivo), `settings`, `tmdb:watchlist`, ou vazio (o botão faz o que fazia). A tela **Botões do
Controle** só lista os `configurable`; os `native` nunca aparecem nem são interceptados.

Campos opcionais no topo do arquivo (#50, vindos do Free My TV): `vendor` e `product` (número como o Android informa,
`2391`, ou hex como no nome do `.kl`, `"0957"`) fazem só as teclas daquele controle contarem, e o `BUTTON_3` de um
gamepad, teclado ou outro controle segue normal; `debounce_ms` (0 a 5000) faz uma rajada do mesmo botão dentro desse
tempo valer um toque só (controle Xiaomi: `1000`). Sem eles, nada muda. Um `vendor`/`product` inválido faz o arquivo
inteiro ser ignorado (no log: `remote.json not read`).

O serviço de acessibilidade que lê os botões é ligado pelo `build-and-install.sh` (`HOME_KEY_SERVICE=1`), como no
CleanYourTV, e funciona com o ADB desligado depois.

## Explorar: o diferencial (TMDB)

Uma tela própria para **navegar** em filmes e séries, não só as 3 sugestões da Home:

- **Em alta**, **lançamentos**, **populares** e por **gênero**, em fileiras roláveis com pôster, separadas em Filmes e
  Séries (`/trending`, `/discover`, `/movie/now_playing`, `/tv/on_the_air`, `/genre`).
- **Página do título:** sinopse, nota, elenco, temporadas, trailer, e **onde está disponível no Brasil**
  (`/watch/providers`, região BR), com um botão que abre o app mapeado (ou o "abrir com" escolhido, ex.: Tomato).
- **Recomendações pelo seu histórico:** a partir do que você marca como assistido ou avalia, da sua watchlist e dos
  favoritos da conta TMDB, o app junta `/recommendations` e `/similar` de cada título e monta "Porque você viu X".
  Com login TMDB, avaliar e salvar na watchlist pela TV escrevem na sua conta.
- O histórico de visualização dos apps (Netflix etc.) não é acessível a outro app; o "histórico" aqui é o que você
  marca na TV e o da sua conta TMDB.

## Recomendações e watchlist (TMDB)

- Chave TMDB em `launcher-ui/config.properties` (fora do git), como no Free My TV.
- Tela **Recomendações**: lista os serviços do TMDB no país; cada um tem ligado/desligado e **"abrir com"** (ex.:
  Crunchyroll → Tomato). Padrão: só serviços com app instalado; uma opção **"mostrar todos"**.
- **Botão Marcador → watchlist da conta TMDB.** Login uma vez: a TV mostra um QR/código, você aprova no celular
  (fluxo de request token v4 do TMDB) e o app guarda o token de acesso. A tela da watchlist lista as séries e filmes
  salvos e abre no app mapeado.

## Fases (cada uma termina testada na sua TV, com screenshots)

1. **Esqueleto:** `launcher-ui/` com a Home do Free My TV sem ADB interno, build e instalação pelo script novo,
   restore pelo `tools/restore.sh`. NOTICE e README.md.
2. **Configuração:** `config.json` por tema (prateleira, cidade, colunas) enviado pelo `launcher-ui/theme.sh`, e a
   tela de ajustes.
3. **Botões:** `remote.json` da 55C725, serviço de teclas, tela Botões do Controle.
4. **TMDB:** tela Explorar (em alta, gêneros, página do título com onde assistir); tela Recomendações com "abrir
   com" e "mostrar todos"; login, watchlist no botão Marcador, avaliações e recomendações pelo histórico.
5. **Docs e prompt:** `PROMPT.ui.md`, custo em tokens, como manter o fork em dia com o upstream.

## Decisões (aprovadas em 2026-10-21)

- **Build do `launcher-ui`:** o `build.sh` sem Gradle do Free My TV.
- **Nome do pacote Android:** `io.cleanyourtv.ui`.
- **Idiomas:** foco em português nesta primeira versão; o código de tradução do Free My TV fica, sem trabalho extra
  nos outros idiomas por enquanto.
