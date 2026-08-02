-- Phase 5 outbox 하드닝: aggregate_type + actor_id 추가.
-- relay(M4)가 aggregate_type으로 토픽 라우팅, actor_id는 감사·인덱서 필수 정보.

-- F3: aggregate_type — 이벤트 대상 aggregate 종류. 현재 행은 전부 page 이벤트.
alter table outbox add column aggregate_type varchar(32);
update outbox set aggregate_type = 'page' where aggregate_type is null;
alter table outbox alter column aggregate_type set not null;

-- F4: actor_id — 변경 주체. 기존 행은 복원 불가 → NOT NULL 제약은 앱 레벨에서만 강제.
alter table outbox add column actor_id uuid;

-- F5: cleanup 인덱스 — 발행 완료 행 주기 삭제 조회용.
create index idx_outbox_cleanup on outbox(created_at) where published_at is not null;
