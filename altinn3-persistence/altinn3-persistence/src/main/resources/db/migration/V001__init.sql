CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TYPE transit_status AS ENUM ('NEW', 'COMPLETED', 'ERROR');
CREATE TYPE direction AS ENUM ('IN','OUT');

create table altinn_failed_event
(
    id                 uuid unique not null primary key default gen_random_uuid(),
    altinn_id          uuid unique not null,
    -- Denne må være med for å ha et utgangspunkt i Altinn sitt API. Man ikke kan hente ut et enkelt CloudEvent på ID
    previous_event_id  uuid unique not null,
    altinn_proxy_state varchar,
    created            timestamptz not null
);

create table altinn_fil_overview
(
    id                 uuid unique    not null primary key default gen_random_uuid(),
    file_name          varchar(256),
    received           timestamptz,
    sent               timestamptz,
    created            timestamptz    not null,
    modified           timestamptz,
    file_transfer_id   uuid unique,
    checksum           varchar,
    direction          direction      not null,
    transit_status     transit_status not null,
    sender             varchar,
    senders_reference  varchar,
    resource_id        varchar,
    json_property_list json,
    version            int            not null
);

create table altinn_fil
(
    id               uuid unique not null primary key default gen_random_uuid(),
    payload          bytea       not null,
    created          timestamptz not null             default now(),
    file_overview_id uuid unique not null,
    constraint file_overview_id_fk foreign key (file_overview_id)
        references altinn_fil_overview (id)
);

create table altinn_event
(
    id               uuid unique primary key not null default gen_random_uuid(),
    altinn_id        uuid unique             not null,
    resourceinstance uuid                    not null,
    spec_version     varchar,
    type             varchar                 not null,
    time             timestamptz             not null,
    resource         varchar,
    source           varchar,
    received         timestamptz             not null default now(),

    constraint altinn_fil_overview_fk foreign key (resourceinstance)
        references altinn_fil_overview (file_transfer_id)
);
