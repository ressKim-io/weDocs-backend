-- M2 page-tree 스키마 — 워크스페이스 + 페이지 트리 + 권한 + CRDT 스냅샷 + outbox.
-- 경계(ADR-0012): 페이지 내용 동시성=CRDT(page_snapshots), 페이지 트리 동시성=관계형(pages, 트랜잭션).

create table users (
    id            uuid         primary key,
    email         varchar(255) not null unique,
    password_hash varchar(255) not null,
    display_name  varchar(255) not null,
    -- 전역 운영 역할 (ADR-0016). 워크스페이스 역할과 직교. enum SystemRole.
    system_role   varchar(16)  not null default 'user' check (system_role in ('user', 'system_admin')),
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);

create table workspaces (
    id         uuid         primary key,
    name       varchar(255) not null,
    owner_id   uuid         not null references users(id),
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);

-- 워크스페이스 멤버십 (baseline 권한). role: owner | member (PRD §4.1). enum WorkspaceRole=1b.
create table workspace_members (
    workspace_id uuid        not null references workspaces(id) on delete cascade,
    user_id      uuid        not null references users(id) on delete cascade,
    role         varchar(16) not null check (role in ('owner', 'member')),
    primary key (workspace_id, user_id)
);

-- 페이지 = 한 CRDT 문서. 자기참조 트리. parent_id NULL = 루트 (proto DocMeta는 ""로 매핑).
create table pages (
    id           uuid         primary key,
    workspace_id uuid         not null references workspaces(id) on delete cascade,
    parent_id    uuid         references pages(id) on delete cascade,
    title        varchar(512) not null default '',
    position     integer      not null default 0,
    archived     boolean      not null default false,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now()
);
create index idx_pages_workspace on pages(workspace_id);
create index idx_pages_parent on pages(parent_id);

-- 페이지별 공유(override, 트리 상속). level: editor | viewer (PRD §4.2). enum PagePermissionLevel=1b.
create table page_permissions (
    page_id uuid        not null references pages(id) on delete cascade,
    user_id uuid        not null references users(id) on delete cascade,
    level   varchar(16) not null check (level in ('editor', 'viewer')),
    primary key (page_id, user_id)
);

-- CRDT 스냅샷: 페이지당 최신 1행 UPSERT (ADR-0013, 엔진 권위 버전).
create table page_snapshots (
    page_id    uuid        primary key references pages(id) on delete cascade,
    snapshot   bytea       not null,
    version    bigint      not null,
    created_at timestamptz not null default now()
);

-- 앱레벨 transactional outbox (ADR-0015). relay·Kafka 발행 = M4.
create table outbox (
    id           bigint       generated always as identity primary key,
    aggregate_id uuid         not null,
    event_type   varchar(64)  not null,
    payload      jsonb        not null,
    traceparent  varchar(64),
    created_at   timestamptz  not null default now(),
    published_at timestamptz
);
-- 미발행 이벤트만 순서대로 조회 (relay, M4)
create index idx_outbox_unpublished on outbox(id) where published_at is null;
