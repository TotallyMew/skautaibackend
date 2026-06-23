# Skautu inventoriaus valdymas backend

Backend projektas, skirtas skautu inventoriaus valdymo sistemos REST API. Naudojamos technologijos: Ktor 3, Kotlin, PostgreSQL, Exposed ORM, Flyway ir JWT autentikacija.

## Apie sistema

Skautu inventoriaus valdymo sistema skirta tuntams ir ju padaliniams administruoti bendra, vienetu ir asmenini inventoriu. Backend saugo pagrindine verslo logika, autorizacija, leidimu tikrinima, duomenu bazes prieiga ir failu ikelima Android programai.

Pagrindines sritys:

- inventoriaus apskaita, lokacijos, QR kodai, rinkiniai ir inventorizacijos patikros
- bendras tunto inventorius ir vienetu inventorius pagal `custodianId`
- inventoriaus kilme pagal `origin`, iskaitant vieneto isigyta ir is bendro sandelio perduota inventoriu
- rezervacijos, isdavimas, grazinimas ir perziuros eiga
- pirkimo / papildymo prasymu bei bendro inventoriaus paemimo eiga
- nariu, vaidmenu, kvietimu, pareigu perleidimo ir organizaciniu vienetu valdymas
- renginiu planavimas, inventoriaus poreikiai, pirkimai, pastovykles ir suderinimas
- superadmin funkcijos tuntams tvirtinti ir administruoti
- mobiliam klientui skirti santraukos, uzduociu ir cache busenos endpoint'ai

## Reikalavimai

- JDK 21
- PostgreSQL
- IntelliJ IDEA rekomenduojamam paleidimui
- Gradle wrapper yra projekte testams ir build uzduotims

## Greitas startas

1. Susikurkite PostgreSQL duomenu baze, pvz. `skautu_inventorius`.
2. Jei reikia svarios lokalios DB, pgAdmin'e istrinkite ir sukurkite DB is naujo, tada rankiniu budu paleiskite `database/schema.sql`.
3. Nukopijuokite `.env.example` i `.env`.
4. Uzpildykite `.env` reiksmes.
5. Atidarykite backend projekta IntelliJ IDEA.
6. Paleiskite Ktor per `lt.skautai.ApplicationKt` / EngineMain.

Run config gali likti be jautriu reiksmiu, nes `main()` pries paleisdamas EngineMain ikelia palaikomus raktus is lokalaus `.env` failo i system properties.

## Architekturos santrauka

Projekte naudojama keliu sluoksniu struktura:

- `src/main/kotlin/lt/skautai/Application.kt` - entry point, `.env` ikelimas, DB prijungimas, Flyway migracijos ir bendri Ktor plugin'ai
- `src/main/kotlin/lt/skautai/plugins/` - routing, serialization, JWT security ir leidimu kontekstas
- `src/main/kotlin/lt/skautai/routes/` - ploni HTTP endpoint'ai
- `src/main/kotlin/lt/skautai/services/` - verslo logika
- `src/main/kotlin/lt/skautai/database/tables/` - Exposed lenteliu aprasai
- `src/main/kotlin/lt/skautai/models/requests` ir `models/responses` - API DTO modeliai
- `src/main/resources/db/migration/` - Flyway migracijos
- `database/schema.sql` - pilna schema svariam rankiniam DB atkurimui

Autorizacija pagrista JWT. Dauguma resursu yra susieti su konkreciu `tuntasId`; uzklausos naudoja `X-Tuntas-Id` header'i, o prieigos teises priklauso nuo vartotojo vaidmens ir scope (`ALL` arba konkretus organizaciniai vienetai).

## `.env` konfiguracija

Backend skaito konfiguracija is lokalaus `.env` failo projekto saknyje. Sis failas nera commitinamas.

Palaikomi raktai:

```env
DB_URL=jdbc:postgresql://localhost:5432/skautu_inventorius
DB_USER=postgres
DB_PASSWORD=change-me
JWT_SECRET=change-me-to-a-long-random-secret
SETUP_BOOTSTRAP_TOKEN=change-me-bootstrap-token
PORT=8080
```

Trumpai:

- `DB_URL`, `DB_USER`, `DB_PASSWORD` naudojami prisijungimui prie PostgreSQL.
- `JWT_SECRET` naudojamas JWT pasirasymui.
- `SETUP_BOOTSTRAP_TOKEN` ijungia vienkartini superadmin setup endpoint'a.
- `PORT` leidzia pakeisti backend porta, numatytoji reiksme yra `8080`.

`application.conf` ima sias reiksmes is env/system properties, todel IntelliJ run config'e pakanka tureti `.env` backend projekto saknyje.

## Duomenu baze ir migracijos

Projekte yra du DB palaikymo keliai:

- `database/schema.sql` - pilna aktuali schema svariam rankiniam DB sukurimui.
- `src/main/resources/db/migration/` - Flyway migracijos, kurias backend bando pritaikyti paleidimo metu.

Dabartines migracijos:

- `V1__initial_schema.sql`
- `V2__leadership_change_requests.sql`
- `V3__inventory_kits.sql`
- `V4__inventory_kits_physical_groups.sql`
- `V5__mobile_cache_support.sql`

Svariai lokalioje aplinkoje paprasciausias kelias yra istrinti ir sukurti DB is naujo, tada paleisti `database/schema.sql` rankiniu budu. Paleidus backend, Flyway su `baselineOnMigrate(true)` gali pazymeti egzistuojancia schema kaip bazine busena ir toliau taikyti naujesnes migracijas.

Jei DB jau turi duomenu:

1. Pirmiausia pasidarykite atsargine kopija.
2. Patikrinkite, ar schema atitinka dabartini `database/schema.sql`.
3. Tik tada paleiskite backend su atnaujintu kodu.

Toliau keiciant DB struktura reikia prideti nauja migracijos faila, pvz. kita versija po esamu failu butu `V6__add_example.sql`. Nepildykite pakeitimu i senas migracijas. Jei pakeitimas keicia schema, atsakyme visada pateikite ir raw SQL uzklausas, kad DB butu galima atnaujinti rankiniu budu.

## Paleidimas per IntelliJ

1. Atidarykite `skautu-inventoriaus-valdymas-backend` kaip projekta IntelliJ IDEA.
2. Isitikinkite, kad backend projekto saknyje yra `.env`.
3. Paleiskite `lt.skautai.ApplicationKt`.

Backend naudoja Ktor EngineMain. Paleidus sekmingai API pasiekiamas per `http://localhost:8080`, nebent pakeistas `PORT`.

## Naudingos Gradle uzduotys

Backend iprastai paleidziamas per IntelliJ, bet Gradle wrapper naudojamas kompiliavimui, testams ir coverage:

```powershell
.\gradlew.bat test --console=plain
.\gradlew.bat compileKotlin --console=plain
.\gradlew.bat coverageSummary --console=plain
```

Testai naudoja `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD` ir `TEST_JWT_SECRET`, jei jie nustatyti aplinkoje.

## Superadmin setup

Jei norite susikurti pirma superadmin:

1. `.env` faile nustatykite `SETUP_BOOTSTRAP_TOKEN`.
2. Paleiskite backend.
3. Iskvieskite `POST /api/setup/super-admin`.
4. Pridekite `X-Bootstrap-Token` header'i su tokia pacia reiksme kaip `.env`.

Jei `SETUP_BOOTSTRAP_TOKEN` tuscias, sis endpoint'as bus neaktyvus.

Pavyzdys su `curl`:

```bash
curl -X POST http://localhost:8080/api/setup/super-admin \
  -H "Content-Type: application/json" \
  -H "X-Bootstrap-Token: your-bootstrap-token" \
  -d '{
    "email": "superadmin@example.com",
    "password": "superadmin123"
  }'
```

Po to tuo paciu el. pastu ir slaptazodziu galima jungtis per Android superadmin prisijungimo ekrana.

## Aktualus API / DTO terminai

- `GET /api/items` filtruoja pagal `custodianId`, `type`, `category`, `status`, `sharedOnly`, `createdByUserId` ir `updatedAfter`; seno `ownerType` filtro nebenaudojama.
- `items.custodian_id = NULL` reiskia bendra tunto sandeli, o ne konkretu vieneta.
- `items.custodian_id != NULL` reiskia vieneto saugoma inventoriu.
- `items.origin` skiria vieneto isigyta inventoriu nuo is tunto perduoto inventoriaus; kode naudojamos reiksmes, pvz. `UNIT_ACQUIRED` ir `TRANSFERRED_FROM_TUNTAS`.
- `item_transfers` naudoja `from_custodian_id` ir `to_custodian_id`; senu `from_owner_type`, `from_owner_id`, `to_owner_type`, `to_owner_id` lauku nera.
- Requisitions, bendro inventoriaus prasymai ir rezervacijos naudoja `requestingUnitId` / `requestingUnitName`.
# Google Play account deletion

The backend serves the public pages required for the Google Play account-deletion and privacy disclosures:

- `/delete-account.html`
- `/privacy.html`

The in-app deletion flow requires the current password, sends a one-time email link, and only deletes the account after an explicit confirmation on the web page.

Production environment:

```text
ACCOUNT_DELETION_PUBLIC_BASE_URL=https://skautaibackend-production.up.railway.app
```

After attaching the custom domain to the Railway service, change the value to `https://skautuinventorius.lt`. The Play Console account-deletion URL can then be:

```text
https://skautuinventorius.lt/delete-account.html
```
