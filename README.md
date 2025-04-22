[![Build](https://github.com/kartverket/altinn3-file-transfer-proxy/actions/workflows/build.yml/badge.svg)](https://github.com/kartverket/altinn3-file-transfer-proxy/actions/workflows/build.yml)

# Altinn3 file transfer proxy

## altinn3-api

Genererer api kode baser på openapi spec. Samt oppsett for klienter med autentisering.

Openapi generatoren har pt. problemer med enums.
Legg til fil i openapi-generator-ignore og opprett tilsvarende fil manuelt i src

Api kan extendes dersom man behov for det. Eks. generator lager feil retur verdi. Legg til en extension function med
ønsket retur type. Se f.eks. no/kartverket/altinn3/authentication/apis/Extensions.kt

## altinn3-server

Den enkleste måten å kjøre serveren lokalt er å benytte testcontainers for å spinne opp databasen. Med en
Docker-daemon/tjeneste kjørende i bakgrunnen, start applikasjonen fra main-metoden i
`/test/.../server/TestApplication.kt`.
For å bruke webhooks lokalt vil det være enklest å sette opp en tunell med f.eks. [Pinggy](https://pinggy.io/) og sette
`webhook-external-url` i `application.yaml` til den genererte hosten.
For å autentisere mot Altinn, må også client-keystore-parameterne konfigureres mot Maskinporten..

Serveren kan startes med profilen `poll` for å kjøre uten webhooks.

## altinn3-persistence

Fellesmodul for databaselaget. Inneholder repoer, entiteter og hjelpefunksjoner.
Flyway-migreringer er definert i denne modulen. Denne modulen har en manuell konfigurasjon av
jdbc, slik at mer enn én datakilde med ulike sql-dialekter kan benyttes samtidig, der hvor den
dras inn som en avhengighet. 