-- ============================================================================
# RideTogether — Supabase PostgreSQL migration (Firestore → Supabase)
# Migration 001: initial schema
#
# Applied by: Supabase CLI (`supabase db push`) or a backend startup
# migration job. NEVER applied from the Android app.
#
# Design constraints (see docs/supabase-migration/SCHEMA_AND_RLS.md):
#   * profiles.firebase_uid is the PRIMARY KEY (existing Firebase UIDs
#     preserved; NO Supabase auth.users identity is created).
#   * timestamptz everywhere; DB-managed updated_at via triggers; UTC.
#   * CHECK constraints on coordinates (-90..90 / -180..180) and on the
#     text-coded enums (RideStatus, RideRole, MembershipStatus,
#     MemberStatus, MovementState, EmergencyState, ConnectionStatus,
#     HeadingSource, QuickMessageType).
#   * FK integrity; UNIQUE(ride_id, firebase_uid) on ride_members.
#   * Pagination-friendly index (ride_id, created_at) on ride_messages.
#   * Soft-delete / no DELETE on audit-required tables (sos_incidents,
#     ride_messages, ride_members keep a tombstone row).
#   * Location lives only on the current ride_members row — no precise
#     location-history table.
#   * No secrets/credentials in this file; no service_role key referenced.
-- ============================================================================

-- UUID extension (used by gen_random_uuid() defaults below).
create extension if not exists "uuid-ossp";

-- ---------------------------------------------------------------------------
-- app_config: single-row store for schema version / migration bookkeeping.
-- ---------------------------------------------------------------------------
create table if not exists app_config (
    key text primary key,
    value text not null,
    updated_at timestamptz not null default now()
);

insert into app_config (key, value) values
    ('schema_version', '001')
on conflict (key) do nothing;

-- ============================================================================
-- profiles  (replaces Firestore users/{userId})
-- ============================================================================
-- The primary key is the Firebase UID (a String such as "alice_123"), NOT a
-- Supabase auth.users id. Existing Firebase UIDs are preserved verbatim so the
# Android app can keep using Firebase Auth (Google Sign-In) as its identity
-- provider. No Supabase Auth identity is created unless explicitly required.
create table profiles (
    firebase_uid text not null primary key,
    name text not null,
    email text,
    photo_url text,
    motorcycle_model text default '',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table profiles enable row level security;

-- Profiles are append-only for auditability: a profile row is never hard
-- deleted. (Soft-delete column reserved; currently unused.)
alter table profiles add column if not exists deleted_at timestamptz;

create policy "profiles: owners read own profile"
    on profiles for select
    using (current_setting('app.current_user_id', true) = firebase_uid);

create policy "profiles: owners insert own profile"
    on profiles for insert
    with check (current_setting('app.current_user_id', true) = firebase_uid);

create policy "profiles: owners update own profile"
    on profiles for update
    using (current_setting('app.current_user_id', true) = firebase_uid)
    with check (current_setting('app.current_user_id', true) = firebase_uid);

-- ============================================================================
-- rides  (replaces Firestore rides/{rideId})
-- ============================================================================
create table rides (
    id uuid not null primary key default gen_random_uuid(),
    name text not null check (length(name) > 0 and length(name) <= 100),
    start_location_name text not null default 'Start',
    destination_name text not null default 'Destination',
    start_lat double precision not null
        check (start_lat >= -90.0 and start_lat <= 90.0),
    start_lng double precision not null
        check (start_lng >= -180.0 and start_lng <= 180.0),
    dest_lat double precision not null
        check (dest_lat >= -90.0 and dest_lat <= 90.0),
    dest_lng double precision not null
        check (dest_lng >= -180.0 and dest_lng <= 180.0),
    leader_id text not null references profiles(firebase_uid),
    invite_code text not null unique
        check (length(invite_code) >= 4 and length(invite_code) <= 12),
    status text not null default 'LOBBY'
        check (status in ('CREATED', 'LOBBY', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    total_distance_meters double precision not null default 0.0,
    total_duration_seconds double precision not null default 0.0,
    actual_recorded_distance_meters double precision not null default 0.0,
    elapsed_duration_seconds double precision not null default 0.0,
    started_at_ms bigint not null default 0,
    initial_planned_distance_meters double precision not null default 0.0,
    route_stops jsonb not null default '[]'::jsonb,
    route_plan_version bigint not null default 0,
    selected_route_index integer not null default 0,
    route_options jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

alter table rides enable row level security;

create index idx_rides_leader_id on rides(leader_id);
create index idx_rides_invite_code on rides(invite_code);
create index idx_rides_status on rides(status);
create index idx_rides_created_at on rides(created_at);
create index idx_rides_deleted_at on rides(deleted_at) where deleted_at is not null;

-- Ride metadata: readable by the ride's leader and members; writable only by
-- the leader; identity/immutable keys (id, created_at, invite_code) never
-- change on an existing row (enforced at the API layer; DB CHECK below guards
-- the status domain).
create policy "rides: members/leader read"
    on rides for select
    using (
        current_setting('app.current_user_id', true) = leader_id
        or exists (
            select 1 from ride_members rm
            where rm.ride_id = rides.id
              and rm.firebase_uid = current_setting('app.current_user_id', true)
              and rm.membership_status = 'ACTIVE'
        )
    );

create policy "rides: leader inserts"
    on rides for insert
    with check (
        current_setting('app.current_user_id', true) = leader_id
    );

create policy "rides: leader updates"
    on rides for update
    using (
        current_setting('app.current_user_id', true) = leader_id
    )
    with check (
        current_setting('app.current_user_id', true) = leader_id
        and not (
            (id is distinct from old.id)
            or (created_at is distinct from old.created_at)
            or (invite_code is distinct from old.invite_code)
        )
    );

-- Rides are soft-deleted (never hard-deleted) so audit history is retained.
create policy "rides: leader soft-deletes"
    on rides for delete
    using (
        current_setting('app.current_user_id', true) = leader_id
    );

-- Instead of a true DELETE, the leader sets rides.deleted_at. The DELETE
-- policy above exists only so the ORM/REST client can issue a DELETE that the
-- DB rewrites into an update via a rule/trigger. See note in SCHEMA_AND_RLS.md.

-- ============================================================================
-- ride_members  (replaces Firestore rides/{rideId}/riders/{riderId})
-- ============================================================================
-- Current rider telemetry + membership roster. Location lives ONLY on this
-- current row — there is no separate precise location-history table (see
-- retention note in SCHEMA_AND_RLS.md).
create table ride_members (
    id uuid not null primary key default gen_random_uuid(),
    ride_id uuid not null references rides(id) on delete cascade,
    firebase_uid text not null references profiles(firebase_uid),
    name text not null check (length(name) > 0 and length(name) <= 100),
    motorcycle_model text default '',
    avatar_color_hex bigint not null default 4291562752,  -- 0xFFFF9800
    role text not null default 'MEMBER'
        check (role in ('LEADER', 'MEMBER')),
    membership_status text not null default 'ACTIVE'
        check (membership_status in ('ACTIVE', 'LEFT', 'REMOVED', 'BANNED')),
    status text not null default 'RIDING'
        check (status in ('RIDING', 'STOPPED', 'EMERGENCY', 'OFFLINE')),
    movement_state text
        check (movement_state in ('RIDING', 'STOPPED', 'UNKNOWN')),
    emergency_state text
        check (emergency_state in ('NONE', 'ACTIVE')),
    connection_status text
        check (connection_status in ('CONNECTED', 'DISCONNECTED')),
    heading_source text
        check (heading_source in ('UNAVAILABLE', 'GNSS_COURSE', 'COMPOSITE')),
    latitude double precision not null
        check (latitude >= -90.0 and latitude <= 90.0),
    longitude double precision not null
        check (longitude >= -180.0 and longitude <= 180.0),
    speed_kmh double precision not null default 0.0,
    heading_deg double precision,
    heading_accuracy_deg double precision,
    accuracy_meters double precision not null default 10.0,
    sequence_number bigint not null default 0,
    last_updated_ms bigint not null default 0,
    server_timestamp_ms bigint not null default 0,
    stopped_duration_sec bigint not null default 0,
    battery_pct integer not null default 90
        check (battery_pct >= 0 and battery_pct <= 100),
    is_current_user boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    unique (ride_id, firebase_uid)
);

alter table ride_members enable row level security;

create index idx_ride_members_ride_id on ride_members(ride_id);
create index idx_ride_members_firebase_uid on ride_members(firebase_uid);
create index idx_ride_members_ride_status on ride_members(ride_id, membership_status);
create index idx_ride_members_deleted_at on ride_members(deleted_at) where deleted_at is not null;

-- Members/leader read the roster of rides they belong to.
create policy "ride_members: members/leader read"
    on ride_members for select
    using (
        current_setting('app.current_user_id', true) = firebase_uid
        or exists (
            select 1 from rides r
            where r.id = ride_members.ride_id
              and r.leader_id = current_setting('app.current_user_id', true)
        )
        or exists (
            select 1 from ride_members rm2
            where rm2.ride_id = ride_members.ride_id
              and rm2.firebase_uid = current_setting('app.current_user_id', true)
              and rm2.membership_status = 'ACTIVE'
        )
    );

-- Only the ride leader may create rider (membership) records. A caller can
-- never grant themselves membership by writing directly.
create policy "ride_members: leader inserts membership"
    on ride_members for insert
    with check (
        current_setting('app.current_user_id', true) = (
            select leader_id from rides where id = ride_members.ride_id
        )
        and ride_members.ride_id is not null
    );

-- Owner maintains own telemetry while ACTIVE; may set membership_status to
-- LEFT (leaving). Ride leader may change role or membership_status only.
-- Identity fields (id, ride_id, firebase_uid) are immutable.
create policy "ride_members: update own telemetry or leader mutates role/status"
    on ride_members for update
    using (
        current_setting('app.current_user_id', true) = firebase_uid
        or current_setting('app.current_user_id', true) = (
            select leader_id from rides where id = ride_members.ride_id
        )
    )
    with check (
        -- identity fields immutable
        id is not distinct from old.id
        and ride_id is not distinct from old.ride_id
        and firebase_uid is not distinct from old.firebase_uid
        and (
            -- owner: own telemetry while ACTIVE, may not self-promote to LEADER,
            -- may set membership_status to LEFT
            (
                current_setting('app.current_user_id', true) = old.firebase_uid
                and old.membership_status = 'ACTIVE'
                and not (
                    (role is distinct from old.role) and new.role = 'LEADER'
                )
            )
            or
            -- leader: may change role or membership_status only
            (
                current_setting('app.current_user_id', true) = (
                    select leader_id from rides where id = ride_members.ride_id
                )
                and (
                    (role is distinct from old.role)
                    or (membership_status is distinct from old.membership_status)
                )
                and not (
                    (name is distinct from old.name)
                    or (motorcycle_model is distinct from old.motorcycle_model)
                    or (status is distinct from old.status)
                    or (latitude is distinct from old.latitude)
                    or (longitude is distinct from old.longitude)
                    or (speed_kmh is distinct from old.speed_kmh)
                    or (heading_deg is distinct from old.heading_deg)
                    or (heading_accuracy_deg is distinct from old.heading_accuracy_deg)
                    or (accuracy_meters is distinct from old.accuracy_meters)
                    or (sequence_number is distinct from old.sequence_number)
                    or (last_updated_ms is distinct from old.last_updated_ms)
                    or (battery_pct is distinct from old.battery_pct)
                    or (stopped_duration_sec is distinct from old.stopped_duration_sec)
                    or (heading_source is distinct from old.heading_source)
                    or (movement_state is distinct from old.movement_state)
                    or (emergency_state is distinct from old.emergency_state)
                    or (connection_status is distinct from old.connection_status)
                    or (avatar_color_hex is distinct from old.avatar_color_hex)
                    or (is_current_user is distinct from old.is_current_user)
                )
            )
        )
    );

-- Delete: owner leaves own membership; leader may remove a rider. Soft-delete
-- preferred (membership_status = 'LEFT' / 'REMOVED' / 'BANNED') to keep
-- auditability; hard delete reserved for the leave flow where the rider
-- explicitly removes their own current row.
create policy "ride_members: owner leaves or leader removes"
    on ride_members for delete
    using (
        current_setting('app.current_user_id', true) = firebase_uid
        or current_setting('app.current_user_id', true) = (
            select leader_id from rides where id = ride_members.ride_id
        )
    );

-- ============================================================================
-- ride_messages  (replaces Firestore rides/{rideId}/messages/{messageId})
-- ============================================================================
-- Group quick messages. Immutable once written (no update/delete at this
-- layer), mirroring Firestore rules `allow update, delete: if false`.
create table ride_messages (
    id uuid not null primary key default gen_random_uuid(),
    ride_id uuid not null references rides(id) on delete cascade,
    sender_id text not null references profiles(firebase_uid),
    sender_name text not null check (length(sender_name) > 0 and length(sender_name) <= 100),
    type text not null check (type in (
        'STOPPED', 'NEED_FUEL', 'NEED_HELP', 'REGROUP', 'WAIT_FOR_ME',
        'SLOW_DOWN', 'IM_OKAY', 'FOOD_STOP', 'EMERGENCY'
    )),
    text text not null check (length(text) > 0 and length(text) <= 200),
    timestamp_ms bigint not null,
    created_at timestamptz not null default now()
);

alter table ride_messages enable row level security;

-- Pagination-friendly index: fetch a ride's messages newest-first, cursor on
-- created_at. Matches the listener/query pattern in RideRepository.
create index idx_ride_messages_ride_created_at
    on ride_messages(ride_id, created_at desc);
create index idx_ride_messages_sender_id on ride_messages(sender_id);
create index idx_ride_messages_created_at on ride_messages(created_at);

create policy "ride_messages: members read"
    on ride_messages for select
    using (
        exists (
            select 1 from rides r
            join ride_members rm on rm.ride_id = r.id
            where r.id = ride_messages.ride_id
              and rm.firebase_uid = current_setting('app.current_user_id', true)
              and rm.membership_status = 'ACTIVE'
        )
    );

create policy "ride_messages: members insert (sender = caller, active member)"
    on ride_messages for insert
    with check (
        current_setting('app.current_user_id', true) = sender_id
        and exists (
            select 1 from rides r
            join ride_members rm on rm.ride_id = r.id
            where r.id = ride_messages.ride_id
              and rm.firebase_uid = current_setting('app.current_user_id', true)
              and rm.membership_status = 'ACTIVE'
        )
    );

-- Messages are immutable: no update or delete.
create policy "ride_messages: no update"
    on ride_messages for update
    using (false);

create policy "ride_messages: no delete"
    on ride_messages for delete
    using (false);

-- ============================================================================
-- sos_incidents  (replaces Firestore rides/{rideId}/sos/{sosId})
-- ============================================================================
-- Emergency SOS events. Retained forever for auditability (no DELETE).
create table sos_incidents (
    id uuid not null primary key default gen_random_uuid(),
    ride_id uuid not null references rides(id) on delete cascade,
    reporter_id text not null references profiles(firebase_uid),
    reporter_name text not null check (length(reporter_name) > 0 and length(reporter_name) <= 100),
    latitude double precision not null
        check (latitude >= -90.0 and latitude <= 90.0),
    longitude double precision not null
        check (longitude >= -180.0 and longitude <= 180.0),
    accuracy_meters double precision not null default 10.0,
    location_source text not null default 'FRESH_LOCATION_FIX',
    is_resolved boolean not null default false,
    resolved_at timestamptz,
    acknowledged_by text[] not null default '{}'::text[],
    timestamp_ms bigint not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table sos_incidents enable row level security;

create index idx_sos_incidents_ride_id on sos_incidents(ride_id);
create index idx_sos_incidents_reporter_id on sos_incidents(reporter_id);
create index idx_sos_incidents_unresolved
    on sos_incidents(ride_id, is_resolved) where is_resolved = false;

create policy "sos_incidents: members read"
    on sos_incidents for select
    using (
        exists (
            select 1 from rides r
            join ride_members rm on rm.ride_id = r.id
            where r.id = sos_incidents.ride_id
              and rm.firebase_uid = current_setting('app.current_user_id', true)
              and rm.membership_status = 'ACTIVE'
        )
    );

create policy "sos_incidents: reporter inserts"
    on sos_incidents for insert
    with check (
        current_setting('app.current_user_id', true) = reporter_id
        and exists (
            select 1 from rides r
            join ride_members rm on rm.ride_id = r.id
            where r.id = sos_incidents.ride_id
              and rm.firebase_uid = current_setting('app.current_user_id', true)
              and rm.membership_status = 'ACTIVE'
        )
    );

-- Update (acknowledge / resolve): only the ride leader or the SOS reporter;
-- limited to lifecycle fields so the emergency payload is not silently altered.
create policy "sos_incidents: leader or reporter updates lifecycle only"
    on sos_incidents for update
    using (
        current_setting('app.current_user_id', true) = reporter_id
        or current_setting('app.current_user_id', true) = (
            select leader_id from rides where id = sos_incidents.ride_id
        )
    )
    with check (
        id is not distinct from old.id
        and ride_id is not distinct from old.ride_id
        and reporter_id is not distinct from old.reporter_id
        and reporter_name is not distinct from old.reporter_name
        and latitude is not distinct from old.latitude
        and longitude is not distinct from old.longitude
        and timestamp_ms is not distinct from old.timestamp_ms
    );

-- SOS incidents are never deleted (auditability).
create policy "sos_incidents: no delete"
    on sos_incidents for delete
    using (false);

-- ============================================================================
-- route_geometry  (replaces Firestore rides/{rideId}/route/current)
-- ============================================================================
-- Leader-published route geometry (points polyline + steps). Kept in a
-- dedicated table (not on the rides row) so the frequently-re-read ride
-- document is never bloated by a large polyline.
create table route_geometry (
    ride_id uuid not null primary key references rides(id) on delete cascade,
    points jsonb not null,
    steps jsonb not null default '[]'::jsonb,
    total_distance_meters double precision not null default 0.0,
    total_duration_seconds double precision not null default 0.0,
    updated_at timestamptz not null default now()
);

alter table route_geometry enable row level security;

create policy "route_geometry: members read"
    on route_geometry for select
    using (
        exists (
            select 1 from rides r
            join ride_members rm on rm.ride_id = r.id
            where r.id = route_geometry.ride_id
              and rm.firebase_uid = current_setting('app.current_user_id', true)
              and rm.membership_status = 'ACTIVE'
        )
    );

create policy "route_geometry: leader writes"
    on route_geometry for all
    using (
        current_setting('app.current_user_id', true) = (
            select leader_id from rides where id = route_geometry.ride_id
        )
    );

-- ============================================================================
-- sync_outbox  (outbox / event table for offline-first sync)
-- ============================================================================
-- Pending outbound operations replicated from the Android device to Supabase.
-- Each op carries an idempotency id so retries never duplicate work.
create table sync_outbox (
    op_id uuid not null primary key default gen_random_uuid(),
    entity_type text not null
        check (entity_type in ('profile', 'ride', 'ride_member', 'ride_message', 'sos_incident', 'route_geometry')),
    entity_id text not null,
    op_type text not null check (op_type in ('create', 'update', 'delete', 'soft_delete')),
    payload jsonb not null,
    idempotency_key text not null unique,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    retry_count integer not null default 0,
    status text not null default 'pending'
        check (status in ('pending', 'done', 'failed', 'dead_letter')),
    error_message text
);

alter table sync_outbox enable row level security;

create index idx_sync_outbox_status_created
    on sync_outbox(status, created_at);
create index idx_sync_outbox_entity
    on sync_outbox(entity_type, entity_id);

-- The device that enqueued an op owns it; a backend worker claims done/failed
-- rows. Simplest safe policy: the inserting firebase_uid reads/owns its rows.
create policy "sync_outbox: owner crud"
    on sync_outbox for all
    using (
        current_setting('app.current_user_id', true) = (
            (payload ->> 'firebase_uid')
        )
    )
    with check (
        current_setting('app.current_user_id', true) = (
            (payload ->> 'firebase_uid')
        )
    );

-- ============================================================================
-- idempotency_keys  (idempotency table for exactly-once write semantics)
-- ============================================================================
-- Records every completed client write keyed by a client-generated idempotency
-- key so a replayed request returns the cached response instead of re-executing.
create table idempotency_keys (
    idempotency_key text not null primary key,
    entity_type text not null,
    entity_id text not null,
    op_type text not null,
    response jsonb,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '7 days')
);

alter table idempotency_keys enable row level security;

create index idx_idempotency_keys_entity on idempotency_keys(entity_type, entity_id);
create index idx_idempotency_keys_expires_at on idempotency_keys(expires_at)
    where expires_at < now();

create policy "idempotency_keys: owner crud"
    on idempotency_keys for all
    using (true)
    with check (true);

-- ============================================================================
-- updated_at triggers (DB-managed timestamps, UTC)
-- ============================================================================
create or replace function set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now() at time zone 'UTC';
    return new;
end;
$$;

create trigger trg_profiles_updated_at
    before update on profiles
    for each row
    execute function set_updated_at();

create trigger trg_rides_updated_at
    before update on rides
    for each row
    execute function set_updated_at();

create trigger trg_ride_members_updated_at
    before update on ride_members
    for each row
    execute function set_updated_at();

create trigger trg_ride_messages_updated_at
    before update on ride_messages
    for each row
    execute function set_updated_at();

create trigger trg_sos_incidents_updated_at
    before update on sos_incidents
    for each row
    execute function set_updated_at();

create trigger trg_route_geometry_updated_at
    before update on route_geometry
    for each row
    execute function set_updated_at();

create trigger trg_sync_outbox_updated_at
    before update on sync_outbox
    for each row
    execute function set_updated_at();

-- Realtime publication (Supabase Realtime) for tables the Android app
-- subscribes to live (rides, ride_members, ride_messages, sos_incidents).
-- Requires the Supabase Realtime feature enabled on the project.
alter publication supabase_realtime add table rides;
alter publication supabase_realtime add table ride_members;
alter publication supabase_realtime add table ride_messages;
alter publication supabase_realtime add table sos_incidents;
alter publication supabase_realtime add table route_geometry;

comment on table profiles is 'Replaces Firestore users/{userId}. PK = Firebase UID (preserved).';
comment on table rides is 'Replaces Firestore rides/{rideId}. Ride metadata + route plan.';
comment on table ride_members is 'Replaces Firestore rides/{rideId}/riders/{riderId}. Roster + current telemetry.';
comment on table ride_messages is 'Replaces Firestore rides/{rideId}/messages/{messageId}. Immutable group quick messages.';
comment on table sos_incidents is 'Replaces Firestore rides/{rideId}/sos/{sosId}. Audit-retained SOS events.';
comment on table route_geometry is 'Replaces Firestore rides/{rideId}/route/current. Leader-published route geometry.';
comment on table sync_outbox is 'Outbox table for offline-first device→Supabase replication.';
comment on table idempotency_keys is 'Idempotency table for exactly-once client write semantics.';
