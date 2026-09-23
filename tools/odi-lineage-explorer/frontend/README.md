# ODI Lineage Explorer — frontend

Niezależny frontend React/Vite do bezpiecznego przeglądania Load Planów, scenariuszy,
mappingów oraz lineage ODI. Obecny MVP obsługuje zarówno jawne demo, jak i efemeryczną sesję z
lokalnym backendem ODI SDK. Wybrane wystąpienia mappingów można pobrać w stabilnym formacie JSON v1;
integracja zapisująca lineage do OpenMetadata pozostaje osobnym etapem.

## Uruchomienie

```bash
yarn install
yarn dev
```

Domyślnie UI używa lokalnego adaptera demo. Aby połączyć je z uruchomionym backendem,
ustaw jawnie:

```bash
VITE_API_MODE=http yarn dev
```

Dev server przekazuje `/api` do `http://127.0.0.1:8080`, dzięki czemu frontend i backend
działają bez dodatkowej konfiguracji CORS. Cel można zmienić przez `VITE_BACKEND_TARGET`.

Testy i build:

```bash
yarn lint
yarn test:run
yarn test:coverage
yarn lint:e2e
yarn test:e2e
yarn build
```

## Kontrakt API

Klient HTTP w `src/api/client.ts` przyjmuje następujący kontrakt:

| Metoda | Endpoint | Wynik |
|---|---|---|
| `POST` | `/api/sessions` | efemeryczna sesja repozytorium z sześciopolowego formularza połączenia |
| `POST` | `/api/sessions/demo` | `{ token, repository, expiresAt }` |
| `GET` | `/api/contexts` | lista Contextów |
| `GET` | `/api/load-plans` | lista Load Planów |
| `GET` | `/api/load-plans/{id}?contextCode=DEV` | Load Plan wraz z rozwiązanymi scenariuszami/mappingami |
| `GET` | `/api/mappings/{id}?contextCode=DEV` | graf mappingu i metadane obiektów fizycznych |
| `POST` | `/api/load-plan-exports` | plik JSON v1 dla wybranego Contextu i listy `mappingOccurrenceIds` |
| `DELETE` | `/api/sessions/current` | zakończenie sesji |

Wszystkie operacje poza utworzeniem sesji wymagają `Authorization: Bearer <token>`. Wyłącznie
`/api/load-plan-exports` jest wersjonowanym kontraktem wymiany. Pozostały JSON jest wewnętrznym
protokołem UI↔backend.

Format pobieranego pliku opisują
[`../docs/odi-lineage-export-v1.md`](../docs/odi-lineage-export-v1.md) oraz
[`../docs/odi-lineage-export-v1.schema.json`](../docs/odi-lineage-export-v1.schema.json).

Hasła formularza pozostają w stanie komponentu i są czyszczone po każdej próbie. Kod nie używa
`localStorage`, `sessionStorage` ani IndexedDB. Akcja demo nie wysyła pól formularza; akcja realna
wysyła je wyłącznie do lokalnego `/api/sessions` i backend nie zwraca ich w odpowiedzi.
