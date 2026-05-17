# Skautų inventoriaus valdymas backend

Backend projektas, skirtas skautų inventoriaus valdymo sistemos REST API. Naudojamos technologijos: Ktor, Kotlin, PostgreSQL.

## Apie sistemą

Skautų inventoriaus valdymo sistema skirta tuntų ir jų padalinių inventoriui administruoti. Sistema apima bendrą tuntui priklausantį inventorių, atskirų vienetų inventorių ir su tuo susijusius procesus.

Pagrindinės sritys:

- inventoriaus apskaita ir daiktų būsenos valdymas
- rezervacijos ir daiktų išdavimas
- narių, vaidmenų ir organizacinių vienetų valdymas
- prašymai bendram inventoriui ir vidaus tvirtinimo eiga
- renginių inventoriaus planavimas ir paskirstymas
- superadmin funkcijos tuntų priežiūrai

Šis backend pateikia REST API Android programėlei ir saugo pagrindinę sistemos verslo logiką, autorizaciją, leidimų tikrinimą ir duomenų bazės prieigą.

## Reikalavimai

- JDK 21
- PostgreSQL
- IntelliJ IDEA arba galimybė paleisti Gradle iš komandinės eilutės

## Greitas startas

1. Susikurkite tuščią PostgreSQL duomenų bazę, pvz. `skautu_inventorius`.
2. Nukopijuokite `.env.example` į `.env`.
3. Užpildykite `.env` reikšmes.
4. Paleiskite backend per IntelliJ arba CLI.
5. Pirmo paleidimo metu bus pritaikytos duomenų bazės migracijos.

## Architektūros santrauka

Projekte naudojama kelių sluoksnių struktūra:

- `routes/` - HTTP endpoint'ai
- `services/` - verslo logika
- `database/tables/` - Exposed lentelių aprašai
- `models/requests` ir `models/responses` - API DTO modeliai

Autorizacija pagrįsta JWT. Dauguma resursų yra susieti su konkrečiu tuntu, o prieigos teisės priklauso nuo vartotojo vaidmens ir jo scope.

## `.env` konfigūracija

Backend skaito konfigūraciją iš lokalaus `.env` failo projekto šaknyje. Šis failas nėra commitinamas.

Palaikomi raktai:

```env
DB_URL=jdbc:postgresql://localhost:5432/skautu_inventorius
DB_USER=postgres
DB_PASSWORD=change-me
JWT_SECRET=change-me-to-a-long-random-secret
SETUP_BOOTSTRAP_TOKEN=
PORT=8080
```

Trumpai:

- `DB_URL`, `DB_USER`, `DB_PASSWORD` naudojami prisijungimui prie PostgreSQL.
- `JWT_SECRET` naudojamas JWT pasirašymui.
- `SETUP_BOOTSTRAP_TOKEN` įjungia vienkartinį superadmin setup endpoint'ą.
- `PORT` leidžia pakeisti backend portą, numatytoji reikšmė yra `8080`.

## Duomenų bazė ir migracijos

Paleidimo metu pritaikomos migracijos iš `src/main/resources/db/migration/`.

### Nauja tuščia DB

Jei duomenų bazė tuščia:

1. Užtenka ją sukurti PostgreSQL serveryje.
2. Paleidus backend, bus pritaikyta pradinė schema ir visos vėlesnės migracijos.
3. Vėlesni schema pakeitimai bus uždedami iš `V2+` migracijų.

### Jau egzistuojanti DB

Jei jau turite ankstesnę DB be `flyway_schema_history` lentelės:

1. Pirmiausia pasidarykite atsarginę kopiją.
2. Paleiskite backend su atnaujintu kodu.
3. Esama schema bus pažymėta kaip bazinė būsena, jei ji atitinka dabartinę projekto schemą.

Tai reiškia:

- pradinė migracija nebus leidžiama ant jau egzistuojančių lentelių
- esami duomenys neturi būti perrašyti
- toliau bus leidžiamos tik naujesnės migracijos

Svarbi sąlyga: ši schema turi realiai atitikti dabartinę projekto bazinę schemą. Jei DB buvo keista rankiniu būdu ir neatitinka projekto, pirmiausia reikėtų tai įvertinti atskirai.

## Paleidimas per IntelliJ

1. Atidarykite šį backend projektą IntelliJ IDEA.
2. Paleiskite `lt.skautai.ApplicationKt`.

Run config gali likti be jautrių reikšmių, nes jos nuskaitomos iš `.env`.

## Paleidimas per CLI

Windows:

```powershell
.\gradlew.bat run
```

Svarbu, kad `.env` būtų backend projekto šaknyje.

## Superadmin setup

Jei norite susikurti pirmą superadmin:

1. `.env` faile nustatykite `SETUP_BOOTSTRAP_TOKEN`.
2. Paleiskite backend.
3. Iškvieskite `POST /api/setup/super-admin`.
4. Pridėkite `X-Bootstrap-Token` header'į su tokia pačia reikšme kaip `.env`.

Jei `SETUP_BOOTSTRAP_TOKEN` tuščias, šis endpoint'as bus neaktyvus.

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

Po to tuo pačiu el. paštu ir slaptažodžiu galima jungtis per Android superadmin prisijungimo ekraną.

## Tolimesni schema pakeitimai

Kai keičiate DB struktūrą:

1. Sukurkite naują migracijos failą `src/main/resources/db/migration/`, pvz. `V2__add_example_table.sql`.
2. Įrašykite tik tą pokytį, kuris reikalingas nuo esamos versijos.
3. Nepildykite pakeitimų į `V1`.
4. Duomenų bazės pakeitimus atlikite per migracijas.
