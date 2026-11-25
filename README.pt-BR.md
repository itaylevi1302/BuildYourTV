# CleanYourTV UI

[English](README.md) · **Português**

Uma tela inicial para Android TV com cara de Apple TV, em cima do **[CleanYourTV](https://github.com/Gathin23/cleanyourtv)**
(que tira o lixo de fábrica da TV) e com o visual do **[Free My TV](https://github.com/simbasjungles/free-my-tv)**.
Em inglês e português (e espanhol, alemão, russo, francês e turco). Explorar filmes e séries com o TMDB, ver onde cada um passa e abrir direto no app.

![Tela inicial](docs/ui/screenshots/home.jpg)

Este repositório é um **fork do CleanYourTV**: tudo o que o original tem continua aqui, igual (listas de debloat por
modelo em `examples/`, os scripts em `tools/`, o launcher mínimo em `launcher/`). O README do CleanYourTV está em
**[`README.cleanyourtv.md`](README.cleanyourtv.md)**, atualizado junto com o resto.
O fork só **acrescenta** arquivos, e um workflow traz as novidades do CleanYourTV todo dia
([`sync-upstream.yml`](.github/workflows/sync-upstream.yml)).

## Qual a diferença

| | [CleanYourTV](https://github.com/Gathin23/cleanyourtv) | [Free My TV](https://github.com/simbasjungles/free-my-tv) | **CleanYourTV UI** (este) |
|---|---|---|---|
| O que é | Debloat por modelo + um launcher mínimo | Uma tela inicial bonita para TVs | O CleanYourTV inteiro + uma tela inicial no estilo do Free My TV |
| Debloat da TV | Listas por modelo, com backup e volta | — | O do CleanYourTV, sem mudanças |
| Tela inicial | Dock e grade, sem internet | Prateleira de vidro, sugestões, clima | A do Free My TV, adaptada, mais o **Explorar** |
| ADB ligado depois de instalar | Não precisa | Usa ADB da própria TV para manter a Home | Não precisa: tudo é feito pelo computador uma vez |
| Explorar filmes e séries | — | Sugestões na tela inicial | Banner, catálogos, Top 10, categorias com filtro, busca, página de cada título, elenco, temporadas |
| Conta TMDB | — | — | Login por QR code: minha lista, favoritos, notas, listas |
| "Assistir em…" | — | Abre o app | Abre **o título** no app (Netflix, Disney+, Apple TV, Max, Prime) quando o Wikidata sabe o ID |
| Botões do controle | `app_keys` no tema | — | Um `remote.json` por modelo + tela de ajustes na TV |
| Configuração | `config.json` do tema | Ajustes na TV | `config.json` enviado pelo computador + ajustes na TV |
| Idioma | Inglês | Vários idiomas | Inglês e português, mais espanhol, alemão, russo, francês e turco; segue o idioma da TV |
| Custo para um assistente de IA adaptar | Baixo | Médio | **Maior**: mais código, rede e telas, mais tokens |

Quer só uma TV limpa e uma tela mínima? Use o `launcher/` do CleanYourTV. Quer a tela inicial sem o debloat? Use o
Free My TV. Este projeto é para quem quer os dois e um Explorar completo.

## Funcionalidades

**Tela inicial**
- "Para você" com sugestões do TMDB nos seus serviços, abas Explorar, Filmes e Séries.
- Prateleira de apps com logos oficiais; segurar OK num app abre um menu a partir do card: mover, logo e cor
  (URL de um PNG e cor de fundo, ou pelo `config.json`), configurações do app no Android (parar, limpar cache) e sair.
- Clima da sua cidade, tema que escurece com o pôr do sol, screensaver com fotos.
- Funciona sem ADB ligado: debloat, troca da Home e o serviço dos botões são feitos uma vez pelo computador.

**Explorar** (o botão ▶ da tela inicial ou um botão do controle)
- Banner como o do Apple TV, com a logo do título; a imagem se mistura ao fundo, que acompanha o pôster focado.
- Fileiras de até 10 títulos com "Ver mais", que abre a lista inteira em grade, carregada conforme você desce.
- Menu lateral (seta para a esquerda na borda): Explorar e ajustes, Buscar, Início, Minha lista, e o resto na ordem
  que você quiser, podendo ocultar: Top 10 do dia, Filmes, Séries, Infantil, Animes, Categorias, filtros salvos e uma
  página por serviço de streaming que você tem na TV.
- Filmes, Séries, Infantil e Animes como catálogos: abas de tipo em cima, grade de pôsteres embaixo.
- Busca e Categorias com o mesmo filtro: tipo, uma ou mais categorias (incluindo Anime, Doramas, Nacionais) e ano;
  categorias podem ser salvas no menu. Buscas recentes.
- Minha lista em abas: lista, favoritos, já assisti, suas notas.
- Títulos já vistos com ✓ na capa; títulos longos rolam como letreiro no card focado.
- Página do título: classificação, direção, onde assistir no Brasil, minha lista, já assisti, favoritar, nota,
  listas, trailer, temporadas e episódios, elenco (com a página de cada pessoa), coleção, palavras-chave e resenhas.
- Rápido e leve: respostas do TMDB em cache no disco (listas por 3 h, títulos por 12 h, fotos e pessoas por uma
  semana; sem internet, usa a cópia antiga) e imagens também (até 160 MB, uma semana). Quando você abre um app ou
  a TV, o launcher solta o que guardava na memória: numa TCL de 2 GB, cerca de 66 MB em segundo plano, contra
  ~155 MB antes.
- Sem anúncios e sem telemetria: só fala com o TMDB, o Wikidata (IDs dos serviços) e o Open-Meteo (clima), além da
  Binance para as cotações de cripto quando `show_crypto=true` (desligado por padrão).

**Controle e configuração**
- `examples/<modelo>/remote.json` diz quais botões do controle podem ser trocados; na TV, cada um abre um app, uma
  entrada HDMI, o Explorar ou a sua lista. Ele pode identificar o controle (`vendor`, `product`), para os botões de um
  gamepad continuarem dele, e juntar uma rajada de toques em um só (`debounce_ms`): veja
  [launcher-ui/README.md](launcher-ui/README.md#remotejson).
- `launcher-ui/config.sh` envia um `config.json` (prateleira, apps ocultos, cidade, botões, serviços, logos) pelo
  ADB, sem compilar de novo. Só o shell do ADB pode escrever essa configuração no app.
- Temas do CleanYourTV: `launcher-ui/config.sh --theme themes/<nome> [meu-config.json]` aproveita o que os dois
  launchers têm em comum (os apps do dock viram a prateleira, os apps ocultos e os botões do controle). O resto de um
  tema (wallpaper, colunas, vidro) é do launcher original: aqui o fundo vem das fotos das sugestões.

## Prints

| | |
|---|---|
| ![Explorar](docs/ui/screenshots/explore.jpg) | ![Menu do Explorar](docs/ui/screenshots/explore-menu.jpg) |
| ![Catálogo Infantil](docs/ui/screenshots/explore-kids.jpg) | ![Top 10 do dia](docs/ui/screenshots/explore-top10.jpg) |
| ![Filtros](docs/ui/screenshots/explore-filters.jpg) | ![Categorias filtradas](docs/ui/screenshots/explore-categories.jpg) |
| ![Página do título](docs/ui/screenshots/title.jpg) | ![Menu de um app na tela inicial](docs/ui/screenshots/home-app-menu.jpg) |

## Quick start

Você precisa de um computador no mesmo Wi-Fi da TV e de um assistente de código com acesso ao terminal (Claude
Code, Codex ou Gemini CLI), como no CleanYourTV.

| Onde | O que fazer |
|---|---|
| **1. Na TV** | Configurações → Preferências do dispositivo → Sobre → aperte OK em **Build** sete vezes → volte → Opções do desenvolvedor → **Depuração USB** ligada (e **Depuração por rede/ADB**, se houver). Deixe a TV ligada. |
| **2. No computador** | Cole o [`PROMPT.md`](PROMPT.md) no assistente com o modelo da sua TV e peça a tela inicial do `launcher-ui/` (este fork) no lugar do `launcher/`. |
| **3. Acompanhe** | Aceite "Permitir depuração USB?" na TV e responda o checklist: apps que você usa, ordem da prateleira, assistente de voz, Chromecast e atualizações. |

O assistente faz backup, o debloat, compila e instala a tela inicial e confere cada passo com prints da TV.
Detalhes do debloat, dos modelos suportados e de como voltar estão no [README do CleanYourTV](README.cleanyourtv.md).

## A partir do APK de release

Um único APK serve para todas as TVs: baixe o `CleanYourTV-UI-<versão>.apk` em
[releases](https://github.com/matheus-caldeira/cleanyourtv-ui/releases), instale com `adb install` e passe as
configurações da TV (chave do TMDB, cidade, prateleira, botões do controle) por um `config.json` ou nos Ajustes da TV.
Sem JDK, sem Android SDK, sem compilar. Os comandos e todas as chaves do `config.json` estão em
[`launcher-ui/README.md`](launcher-ui/README.md); o [site](https://matheus-caldeira.github.io/cleanyourtv-ui-web/) tem um
formulário que escreve o `config.json` para você.

## Instalar à mão

1. Siga o [Quick start do CleanYourTV](README.cleanyourtv.md#quick-start) até aplicar a lista de debloat da sua TV.
2. Escreva as configurações da TV num `config.json`: sua chave do TMDB (grátis, em themoviedb.org → Settings → API),
   sua cidade, a prateleira e os botões do controle. Parta do `launcher-ui/config.example.json`; as chaves estão em
   [`launcher-ui/README.md`](launcher-ui/README.md#configjson). A chave é do app e continua necessária mesmo com o
   login por QR code: o login só diz ao TMDB quem é você, para a sua lista e as suas notas. (O `config.properties`
   continua valendo num build pessoal, como valores iniciais.)
3. Compile e instale (JDK 17 e Android SDK com build-tools 34+ e a platform android-34; sem Gradle):
   ```bash
   TV_HOST=192.168.1.50 CONFIG=meu-config.json KEY_SERVICE=1 launcher-ui/build-and-install.sh
   ```
   `KEY_SERVICE=1` liga o serviço dos botões do controle, uma vez: as reinstalações mantêm ligado. Em TVs TCL com
   Android 11 ele é recusado (e desligado se uma reinstalação o encontra ligado): lá qualquer filtro de teclas faz um
   toque no volume andar dois passos e uma tecla segurada travar, então os botões funcionam só na tela inicial.
   `SCREENSAVER=0` mantém o screensaver atual. `APK=<arquivo>` instala um APK de release em vez de compilar.
4. Mudanças depois: `TV_HOST=… launcher-ui/config.sh meu-config.json` e
   `launcher-ui/config.sh --remote examples/<modelo>/remote.json`.

O script tem as proteções do original: confere o Android antes de compilar, inicia a Home nova antes de mexer na
antiga e desfaz a troca se ela não pegar. Para abrir o Explorar pelo computador:
`adb shell am start -n io.cleanyourtv.ui/.HomeActivity --es open explore` (ou `--es open watchlist`).

## Voltar

- Para o launcher do CleanYourTV: instale o `launcher/` como no README original.
- Para a TV de fábrica: `tools/restore.sh`, como no [README do CleanYourTV](README.cleanyourtv.md#restoring-your-tv).

## Atualizações do CleanYourTV

O workflow [`sync-upstream.yml`](.github/workflows/sync-upstream.yml) roda todo dia e faz o merge do `main` do CleanYourTV.
Este fork não altera os arquivos do original, com uma exceção: o `README.md` é o deste fork. A cada atualização o workflow
copia o README novo do CleanYourTV para [`README.cleanyourtv.md`](README.cleanyourtv.md) e mantém os deste fork. Qualquer outro
conflito vira uma issue com os comandos para resolver à mão.

Os testes do CleanYourTV (`tests/`) leem o README dele, então aqui eles rodam pelo
[`launcher-ui/test-upstream.sh`](launcher-ui/test-upstream.sh): o script copia o repositório para uma pasta
temporária, põe lá o README do CleanYourTV como `README.md` e roda a suíte. Sua pasta e este README ficam como estão.

## Licença e uso

Todo o código está sob a licença MIT: o do CleanYourTV UI ([`launcher-ui/LICENSE`](launcher-ui/LICENSE)), o do
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) ([`LICENSE`](LICENSE)) e o do
[Free My TV](https://github.com/simbasjungles/free-my-tv) ([`launcher-ui/LICENSE.free-my-tv`](launcher-ui/LICENSE.free-my-tv)).
Você pode usar, alterar e compartilhar, inclusive comercialmente, mantendo os avisos de copyright e os textos das
licenças. Vem como está, sem garantia.

O projeto mistura várias coisas, cada uma com seus próprios termos, que a licença MIT não cobre:
- **Serviços online.** A chave gratuita do TMDB e a API gratuita do Open-Meteo são para uso não comercial; o uso
  comercial é licenciado à parte por cada um. Os dados do Wikidata são CC0; a Binance tem os próprios termos.
- **Fontes e ícones.** Inter e Outfit (SIL Open Font License 1.1), Material Symbols (Apache 2.0) e os logos das moedas
  (CC0).
- **Marcas.** Netflix, YouTube, Prime Video, Disney+, Apple TV, Max, TMDB e os demais nomes e logos pertencem aos seus
  donos. Este projeto não tem vínculo com nenhum deles, e nada feito a partir dele deve parecer oficial.

Tudo, item por item, está em [`launcher-ui/NOTICE`](launcher-ui/NOTICE). Se você usar este projeto, inclusive num
serviço, um crédito com link para este repositório é bem-vindo (um pedido, não uma condição), para que outras pessoas
o encontrem e usem nas próprias TVs.

## Créditos e contato

- [CleanYourTV](https://github.com/Gathin23/cleanyourtv), de Gathin, e [Free My TV](https://github.com/simbasjungles/free-my-tv),
  dos seus autores, ambos sob a licença MIT. Detalhes em [`launcher-ui/NOTICE`](launcher-ui/NOTICE).
- Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB. IDs dos serviços pelo Wikidata.
  Clima por Open-Meteo.com (CC BY 4.0).
- Matheus Caldeira, [github.com/matheus-caldeira](https://github.com/matheus-caldeira). Dúvidas e problemas nas
  [issues](https://github.com/matheus-caldeira/cleanyourtv-ui/issues).
