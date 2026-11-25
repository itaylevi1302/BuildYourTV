# TMDB no CleanYourTV UI

Levantamento da API v3 do TMDB (152 operações, a partir da especificação OpenAPI oficial,
`https://developer.themoviedb.org/openapi/tmdb-api.json`, em 2026-10-21) e o que cada parte vira na TV.

Legenda: ✅ feito · 🔜 nesta versão · ➖ fora do escopo da TV (e por quê)

## Conta e login

| API | Na TV |
|---|---|
| `GET /authentication/token/new`, `POST /authentication/session/new`, `DELETE /authentication/session` | ✅ Ajustes › Conta TMDB: QR code, aprovação no celular, desconectar |
| `GET /account/{id}` | ✅ nome da conta nos ajustes |
| `GET/POST /account/{id}/watchlist…` | ✅ Minha lista (botão Marcador do controle) |
| `GET/POST /account/{id}/favorite…` | 🔜 Favoritos: botão na página do título e fileira no Explorar |
| `GET /account/{id}/rated/movies`, `/rated/tv`, `/rated/tv/episodes` | 🔜 fileira "Suas notas"; base das recomendações |
| `GET /account/{id}/lists`, `POST /list`, `POST /list/{id}/add_item`, `remove_item`, `item_status`, `clear`, `DELETE /list/{id}` | 🔜 "Adicionar a uma lista" na página do título; listas no Explorar |
| `POST /4/auth/request_token`, `POST /4/auth/access_token`, `POST /3/authentication/session/convert/4`, `DELETE /4/auth/access_token` | ✅ com o token de leitura (`eyJ…`): mesmo QR, e o token v4 vira sessão v3 |
| `GET /4/account/{id}/lists`, `POST /4/list`, `PUT /4/list/{id}` | ✅ acha ou cria as listas privadas `tk:watching` e `tk:watched` (renomeia as do testetv) |
| `GET /4/list/{id}`, `POST/DELETE /4/list/{id}/items` | ✅ "Continuar assistindo" lê `tk:watching`; só o "Já assisti" explícito move para `tk:watched`. Comentários são lidos, nunca escritos |
| `GET /authentication/guest_session/new`, `/guest_session/{id}/rated…` | ➖ notas sem conta: a TV guarda o "Já assisti" localmente |
| `POST /authentication/token/validate_with_login` | ➖ pediria a senha na TV; o QR é mais seguro |

## Títulos (filmes e séries)

| API | Na TV |
|---|---|
| `/movie/{id}`, `/tv/{id}` (com `append_to_response`) | ✅ página do título |
| `/watch/providers` (filme, série, temporada) | ✅ "Onde assistir" no Brasil, abrindo o app mapeado |
| `/credits`, `/tv/{id}/aggregate_credits` | 🔜 elenco com fotos e personagem, direção/criação; cada pessoa abre a página dela |
| `/account_states` | ✅ lista · 🔜 sua nota e favorito |
| `POST/DELETE /rating` (filme, série, episódio) | ✅ avaliar · 🔜 remover nota, mostrar a sua nota, avaliar episódio |
| `/recommendations`, `/similar` | ✅ "Você também pode gostar" e "Recomendado para você" |
| `/videos` | ✅ trailer no YouTube |
| `/release_dates`, `/tv/{id}/content_ratings` | 🔜 classificação indicativa do Brasil (L, 10, 12, 14, 16, 18) |
| `/reviews`, `/review/{id}` | 🔜 resenhas na página do título |
| `/keywords`, `/keyword/{id}/movies` | 🔜 palavras-chave que abrem uma fileira de títulos parecidos |
| `/collection/{id}` | 🔜 "Da mesma coleção" (sagas) |
| `/tv/{id}/season/{n}`, `/episode/{n}` | 🔜 temporadas e episódios, com "assisti" por episódio |
| `/images`, `/translations`, `/alternative_titles`, `/external_ids`, `/lists`, `/changes`, `/screened_theatrically`, `/episode_groups` | ➖ detalhes de catálogo sem uso na tela da TV |

## Listas e descoberta (Explorar)

| API | Na TV |
|---|---|
| `/trending/{all,movie,tv}/{day,week}` | ✅ "Em alta hoje", séries e filmes da semana |
| `/discover/movie`, `/discover/tv` (provedor, gênero, data, nota) | ✅ por serviço, estreias no streaming, gêneros |
| `/movie/now_playing`, `/movie/upcoming` (região BR) | 🔜 "Nos cinemas" e "Em breve" |
| `/movie/top_rated`, `/tv/top_rated`, `/popular` | 🔜 "Mais bem avaliados" |
| `/tv/airing_today`, `/tv/on_the_air` | 🔜 "Episódios novos hoje" |
| `/trending/person`, `/person/popular` | 🔜 "Pessoas em alta" |
| `/search/multi` (e `/search/movie`, `/tv`, `/person`) | 🔜 Buscar, com o teclado da TV |
| `/genre/{movie,tv}/list` | ✅ nomes dos gêneros vêm nas respostas |
| `/watch/providers/{movie,tv}`, `/regions` | ✅ Ajustes › Serviços |
| `/find/{external_id}`, `/search/company`, `/search/collection`, `/search/keyword`, `/company`, `/network`, `/certification…`, `/configuration…`, `/changes` | ➖ uso interno ou de catálogo |

## Pessoas

| API | Na TV |
|---|---|
| `/person/{id}` (+ `combined_credits`) | 🔜 página da pessoa: foto, biografia, filmes e séries |
| `/person/{id}/images`, `/tagged_images`, `/external_ids`, `/translations`, `/changes`, `/latest` | ➖ |

## Limites

- O TMDB não sabe o que você assistiu nos apps (Netflix etc.): o "Já assisti" é guardado na TV e as notas vão para a
  sua conta. As recomendações juntam os dois.
- A API não dá links diretos para dentro dos apps: "Assistir" abre o app do serviço.
- Atribuição exigida pelo TMDB aparece nos ajustes e no Explorar.
