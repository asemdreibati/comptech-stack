-- Orders as the returns service needs them, replicated from orders.order-events.v1.
create table purchased_order (
    order_id     varchar(64)   primary key,
    buyer_id     varchar(64)   not null,
    status       varchar(32)   not null,
    currency     varchar(3)    not null,
    payment_id   varchar(128),
    lines        jsonb         not null,
    confirmed_at timestamptz,
    version      bigint        not null
);

create table return_request (
    id              uuid          primary key,
    order_id        varchar(64)   not null references purchased_order (order_id),
    buyer_id        varchar(64)   not null,
    seller_id       varchar(64)   not null,
    idempotency_key varchar(64)   not null,
    request_hash    varchar(64)   not null,
    reason          varchar(32)   not null,
    comment         varchar(1000),
    lines           jsonb         not null,
    refund_amount   numeric(19, 4) not null,
    currency        varchar(3)    not null,
    status          varchar(32)   not null,
    refund_id       varchar(128),
    version         bigint        not null,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    decided_at      timestamptz
);

-- A retried request returns the same return instead of creating a second one.
create unique index ux_return_idempotency on return_request (buyer_id, idempotency_key);
create index ix_return_order on return_request (order_id);
create index ix_return_buyer on return_request (buyer_id, created_at desc);

-- Who decided what at each stage: seller, buyer, arbitrator, warehouse.
create table return_decision (
    id         bigserial     primary key,
    return_id  uuid          not null references return_request (id),
    stage      varchar(32)   not null,
    actor_id   varchar(64)   not null,
    decision   varchar(16)   not null,
    note       varchar(1000),
    decided_at timestamptz   not null
);
create index ix_return_decision on return_decision (return_id);
