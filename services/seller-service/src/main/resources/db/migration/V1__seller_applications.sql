-- Seller applications. The workflow engine keeps its own tables (ACT_*) in the same database, so an
-- application's state and its process state always change in one transaction.

create table seller_application (
    id            uuid         primary key,
    applicant_id  varchar(64)  not null,  -- the applicant's Keycloak subject
    seller_handle varchar(40)  not null,  -- becomes the seller_id claim once approved
    kyc           jsonb        not null,  -- validated against the seller-kyc Form.io form
    status        varchar(32)  not null,
    risk_tier     varchar(16),
    escalated     boolean      not null default false,
    version       bigint       not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    submitted_at  timestamptz,
    decided_at    timestamptz
);

-- One live application per person, and a handle can be claimed by only one live application.
create unique index ux_application_open_per_applicant on seller_application (applicant_id)
    where status not in ('REJECTED', 'EXPIRED');
create unique index ux_application_handle on seller_application (seller_handle)
    where status not in ('REJECTED', 'EXPIRED');
-- Screening looks for other applicants with the same bank account or trade licence.
create index ix_application_iban on seller_application ((kyc ->> 'iban'));
create index ix_application_licence on seller_application ((kyc ->> 'tradeLicenceNumber'));

create table application_document (
    id             uuid         primary key,
    application_id uuid         not null references seller_application (id),
    type           varchar(32)  not null,
    content_type   varchar(64)  not null,
    size_bytes     bigint       not null,
    status         varchar(16)  not null,
    created_at     timestamptz  not null,
    verified_at    timestamptz
);
create index ix_document_application on application_document (application_id);

-- Who decided what, and why: the compliance audit trail.
create table review_decision (
    id             bigserial    primary key,
    application_id uuid         not null references seller_application (id),
    stage          varchar(32)  not null,
    reviewer_id    varchar(64)  not null,
    decision       varchar(16)  not null,
    note           varchar(1000),
    decided_at     timestamptz  not null
);
create index ix_decision_application on review_decision (application_id);
