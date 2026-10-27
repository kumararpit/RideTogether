-- Supabase schema for RideTogether
-- Run this in Supabase SQL Editor: https://supabase.com/dashboard/project/_/sql
-- Free tier: 500MB database, 2GB bandwidth, realtime included
--
-- Authorization model: Firebase Auth (Google Sign-In) is the identity provider.
-- Supabase Auth is NOT used. The trusted backend verifies the Firebase ID token
-- via Firebase Admin SDK and sets `SET app.current_user_id = '<Firebase UID>'`
-- on the database session before executing any query. RLS policies reference
-- `current_setting('app.current_user_id', true)` rather than `auth.uid()`.
--
-- The APK sends only the Supabase `anon` key + the Firebase ID token (as Bearer)
-- to the trusted backend. No service_role key or privileged credential ships in
-- the APK.

-- Enable UUID extension
create extension if not exists "uuid-ossp";

-- Session variable for the current Firebase user ID (set by trusted backend).
-- This is a placeholder so the column exists; the backend sets it per request.
do $$
begin
  if not exists (select 1 from pg_settings where name = 'app.current_user_id') then
    alter system set app.current_user_id = '';
  end if;
end
$$;

-- ============================================================================
-- profiles (replaces users/{userId})
-- PK: firebase_uid (text, Firebase UID preserved verbatim)
-- ============================================================================
create table profiles (
  firebase_uid text primary key,
  name text not null,
  email text,
  photo_url text,
  motorcycle_model text,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

alter table profiles enable row level security;

create policy "Owner can read own profile"
  on profiles for select
  using (firebase_uid = current_setting('app.current_user_id', true));

create policy "Owner can insert own profile"
  on profiles for insert
  with check (firebase_uid = current_setting('app.current_user_id', true));

create policy "Owner can update own profile"
  on profiles for update
  using (firebase_uid = current_setting('app.current_user_id', true));

-- ============================================================================
-- rides (replaces rides/{rideId})
-- PK: id (uuid, gen_random_uuid). Firestore doc id is NOT reused as SQL PK.
-- ============================================================================
create table rides (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  start_location_name text not null default 'Start',
  destination_name text not null default 'Destination',
  start_lat double precision not null,
  start_lng double precision not null,
  dest_lat double precision not null,
  dest_lng double precision not null,
  leader_id text not null,
  invite_code text not null unique,
  status text not null default 'LOBBY',
  total_distance_meters double precision default 0,
  total_duration_seconds double precision default 0,
  actual_recorded_distance_meters double precision default 0,
  elapsed_duration_seconds double precision default 0,
  started_at_ms bigint default 0,
  initial_planned_distance_meters double precision default 0,
  route_stops jsonb default '[]'::jsonb,
  route_plan_version bigint default 0,
  selected_route_index int default 0,
  route_options jsonb default '[]'::jsonb,
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  deleted_at timestamptz
);

alter table rides enable row level security;

-- Select: leader or ACTIVE member of the ride (membership-gated, NOT permissive)
create policy "Leader or member can read ride"
  on rides for select
  using (
    leader_id = current_setting('app.current_user_id', true)
    or exists (
      select 1 from ride_members rm
      where rm.ride_id = rides.id
        and rm.firebase_uid = current_setting('app.current_user_id', true)
        and rm.membership_status = 'ACTIVE'
    )
  );

-- Insert: leader only
create policy "Ride leader can insert ride"
  on rides for insert
  with check (leader_id = current_setting('app.current_user_id', true));

-- Update: leader only, immutable keys never change
create policy "Ride leader can update own ride"
  on rides for update
  using (leader_id = current_setting('app.current_user_id', true))
  with check (
    leader_id = current_setting('app.current_user_id', true)
    and id = old.id
    and created_at = old.created_at
    and invite_code = old.invite_code
  );

-- Soft-delete: leader only (sets deleted_at, no hard DELETE)
create policy "Ride leader can soft-delete ride"
  on rides for delete
  using (leader_id = current_setting('app.current_user_id', true));

-- ============================================================================
-- ride_members (replaces rides/{rideId}/riders/{riderId})
-- PK: id (uuid). UNIQUE(ride_id, firebase_uid).
-- ============================================================================
create table ride_members (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null references rides(id) on delete cascade,
  firebase_uid text not null,
  name text not null,
  motorcycle_model text default '',
  role text not null default 'MEMBER',
  membership_status text not null default 'ACTIVE',
  status text not null default 'RIDING',
  movement_state text default 'UNKNOWN',
  emergency_state text default 'NONE',
  connection_status text,
  latitude double precision not null,
  longitude double precision not null,
  speed_kmh double precision default 0,
  heading_deg double precision,
  heading_source text default 'UNAVAILABLE',
  heading_accuracy_deg double precision,
  accuracy_meters double precision default 10,
  sequence_number bigint default 0,
  last_updated_ms bigint not null default 0,
  server_timestamp_ms bigint not null default 0,
  stopped_duration_sec bigint default 0,
  is_current_user boolean not null default false,
  battery_pct integer default 90,
  avatar_color_hex bigint default 4291562752,
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  deleted_at timestamptz,
  unique (ride_id, firebase_uid)
);

alter table ride_members enable row level security;

-- Select: owner, leader, or ACTIVE member of the ride
create policy "Member can read ride roster"
  on ride_members for select
  using (
    firebase_uid = current_setting('app.current_user_id', true)
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

-- Insert: leader only
create policy "Ride leader can insert rider"
  on ride_members for insert
  with check (
    exists (
      select 1 from rides r
      where r.id = ride_members.ride_id
        and r.leader_id = current_setting('app.current_user_id', true)
    )
  );

-- Update: owner telemetry (while ACTIVE, no self-promote) OR leader role/membership
create policy "Member can update own telemetry"
  on ride_members for update
  using (
    firebase_uid = current_setting('app.current_user_id', true)
    and membership_status = 'ACTIVE'
  )
  with check (
    firebase_uid = current_setting('app.current_user_id', true)
    and membership_status = 'ACTIVE'
    and role = old.role
    and not (role = 'LEADER' and old.role <> 'LEADER')
  );

create policy "Leader can update rider membership"
  on ride_members for update
  using (
    exists (
      select 1 from rides r
      where r.id = ride_members.ride_id
        and r.leader_id = current_setting('app.current_user_id', true)
    )
  )
  with check (
    exists (
      select 1 from rides r
      where r.id = ride_members.ride_id
        and r.leader_id = current_setting('app.current_user_id', true)
    )
    and not (
      (name is distinct from old.name)
      or (motorcycle_model is distinct from old.motorcycle_model)
      or (status is distinct from old.status)
      or (latitude is distinct from old.latitude)
      or (longitude is distinct from old.longitude)
      or (speed_kmh is distinct from old.speed_kmh)
      or (heading_deg is distinct from old.heading_deg)
      or (heading_source is distinct from old.heading_source)
      or (heading_accuracy_deg is distinct from old.heading_accuracy_deg)
      or (accuracy_meters is distinct from old.accuracy_meters)
      or (sequence_number is distinct from old.sequence_number)
      or (last_updated_ms is distinct from old.last_updated_ms)
      or (server_timestamp_ms is distinct from old.server_timestamp_ms)
      or (stopped_duration_sec is distinct from old.stopped_duration_sec)
      or (is_current_user is distinct from old.is_current_user)
      or (battery_pct is distinct from old.battery_pct)
      or (avatar_color_hex is distinct from old.avatar_color_hex)
    )
  );

-- Delete: owner leaves own membership, or leader removes a rider
create policy "Member can delete own membership"
  on ride_members for delete
  using (
    firebase_uid = current_setting('app.current_user_id', true)
    or exists (
      select 1 from rides r
      where r.id = ride_members.ride_id
        and r.leader_id = current_setting('app.current_user_id', true)
    )
  );

-- ============================================================================
-- ride_messages (replaces rides/{rideId}/messages/{messageId})
-- PK: id (uuid). Immutable once written.
-- ============================================================================
create table ride_messages (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null references rides(id) on delete cascade,
  sender_id text not null,
  sender_name text not null,
  type text not null,
  text text not null,
  timestamp_ms bigint not null,
  created_at timestamptz default now()
);

alter table ride_messages enable row level security;

-- Select: ACTIVE member of the ride
create policy "Member can read messages"
  on ride_messages for select
  using (
    exists (
      select 1 from rides r
      join ride_members rm2 on rm2.ride_id = r.id
      where r.id = ride_messages.ride_id
        and rm2.firebase_uid = current_setting('app.current_user_id', true)
        and rm2.membership_status = 'ACTIVE'
    )
  );

-- Insert: ACTIVE member, sender_id must equal caller
create policy "Member can insert message"
  on ride_messages for insert
  with check (
    sender_id = current_setting('app.current_user_id', true)
    and exists (
      select 1 from rides r
      join ride_members rm2 on rm2.ride_id = r.id
      where r.id = ride_messages.ride_id
        and rm2.firebase_uid = current_setting('app.current_user_id', true)
        and rm2.membership_status = 'ACTIVE'
    )
  );

-- No update/delete: messages are immutable

-- ============================================================================
-- sos_incidents (replaces rides/{rideId}/sos/{sosId})
-- PK: id (uuid). Never deleted (auditability).
-- ============================================================================
create table sos_incidents (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null references rides(id) on delete cascade,
  reporter_id text not null,
  reporter_name text not null,
  latitude double precision not null,
  longitude double precision not null,
  is_resolved boolean not null default false,
  acknowledged_by text[] not null default '{}',
  timestamp_ms bigint not null,
  accuracy_meters double precision not null default 10,
  location_source text not null default 'FRESH_LOCATION_FIX',
  resolved_at timestamptz,
  created_at timestamptz default now()
);

alter table sos_incidents enable row level security;

-- Select: ACTIVE member of the ride
create policy "Member can read SOS incidents"
  on sos_incidents for select
  using (
    exists (
      select 1 from rides r
      join ride_members rm2 on rm2.ride_id = r.id
      where r.id = sos_incidents.ride_id
        and rm2.firebase_uid = current_setting('app.current_user_id', true)
        and rm2.membership_status = 'ACTIVE'
    )
  );

-- Insert: ACTIVE member, reporter_id must equal caller
create policy "Member can insert SOS incident"
  on sos_incidents for insert
  with check (
    reporter_id = current_setting('app.current_user_id', true)
    and exists (
      select 1 from rides r
      join ride_members rm2 on rm2.ride_id = r.id
      where r.id = sos_incidents.ride_id
        and rm2.firebase_uid = current_setting('app.current_user_id', true)
        and rm2.membership_status = 'ACTIVE'
    )
  );

-- Update: author or leader, lifecycle fields only
create policy "Author or leader can update SOS"
  on sos_incidents for update
  using (
    reporter_id = current_setting('app.current_user_id', true)
    or exists (
      select 1 from rides r
      where r.id = sos_incidents.ride_id
        and r.leader_id = current_setting('app.current_user_id', true)
    )
  )
  with check (
    reporter_id = old.reporter_id
    and ride_id = old.ride_id
    and reporter_name = old.reporter_name
    and latitude = old.latitude
    and longitude = old.longitude
    and timestamp_ms = old.timestamp_ms
  );

-- No delete: SOS incidents are never deleted

-- ============================================================================
-- route_geometry (replaces rides/{rideId}/route/current)
-- PK: ride_id (uuid, FK → rides.id). Leader writes only.
-- ============================================================================
create table route_geometry (
  ride_id uuid primary key references rides(id) on delete cascade,
  points jsonb not null,
  steps jsonb default '[]'::jsonb,
  total_distance_meters double precision default 0,
  total_duration_seconds double precision default 0,
  updated_at timestamptz default now()
);

alter table route_geometry enable row level security;

-- Select: ACTIVE member of the ride
create policy "Member can read route geometry"
  on route_geometry for select
  using (
    exists (
      select 1 from rides r
      join ride_members rm2 on rm2.ride_id = r.id
      where r.id = route_geometry.ride_id
        and rm2.firebase_uid = current_setting('app.current_user_id', true)
        and rm2.membership_status = 'ACTIVE'
    )
  );

-- All writes: leader only
create policy "Leader can write route geometry"
  on route_geometry for all
  using (
    exists (
      select 1 from rides r
      where r.id = route_geometry.ride_id
        and r.leader_id = current_setting('app.current_user_id', true)
    )
  )
  with check (
    exists (
      select 1 from rides r
      where r.id = route_geometry.ride_id
        and r.leader_id = current_setting('app.current_user_id', true)
    )
  );

-- ============================================================================
-- sync_outbox — outbox / event table for offline write buffering
-- ============================================================================
create table sync_outbox (
  id uuid primary key default gen_random_uuid(),
  user_id text not null,
  entity_type text not null,
  entity_id text not null,
  op_type text not null,
  payload jsonb not null,
  idempotency_key text not null unique,
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  retry_count integer default 0,
  status text not null default 'pending',
  error_message text
);

alter table sync_outbox enable row level security;

create policy "Owner can access own outbox entries"
  on sync_outbox for all
  using (user_id = current_setting('app.current_user_id', true))
  with check (user_id = current_setting('app.current_user_id', true));

-- ============================================================================
-- idempotency_keys — deduplication of client writes
-- ============================================================================
create table idempotency_keys (
  idempotency_key text primary key,
  entity_type text not null,
  entity_id text not null,
  response jsonb,
  created_at timestamptz default now(),
  expires_at timestamptz not null default (now() + interval '7 days')
);

alter table idempotency_keys enable row level security;

create policy "Owner can access own idempotency keys"
  on idempotency_keys for all
  using (
    exists (
      select 1 from sync_outbox so
      where so.idempotency_key = idempotency_keys.idempotency_key
        and so.user_id = current_setting('app.current_user_id', true)
    )
  );

-- ============================================================================
-- Indexes
-- ============================================================================
create index idx_rides_leader_id on rides(leader_id);
create index idx_rides_invite_code on rides(invite_code);
create index idx_rides_status on rides(status);
create index idx_rides_created_at on rides(created_at);
create index idx_rides_deleted_at on rides(deleted_at) where deleted_at is not null;

create index idx_ride_members_ride_id on ride_members(ride_id);
create index idx_ride_members_firebase_uid on ride_members(firebase_uid);
create index idx_ride_members_ride_status on ride_members(ride_id, membership_status);
create index idx_ride_members_deleted_at on ride_members(deleted_at) where deleted_at is not null;

create index idx_ride_messages_ride_created_at on ride_messages(ride_id, created_at desc);
create index idx_ride_messages_sender_id on ride_messages(sender_id);
create index idx_ride_messages_created_at on ride_messages(created_at);

create index idx_sos_incidents_ride_id on sos_incidents(ride_id);
create index idx_sos_incidents_reporter_id on sos_incidents(reporter_id);
create index idx_sos_incidents_unresolved on sos_incidents(ride_id, is_resolved) where not is_resolved;

create index idx_sync_outbox_status_created on sync_outbox(status, created_at);
create index idx_sync_outbox_entity on sync_outbox(entity_type, entity_id);

create index idx_idempotency_keys_entity on idempotency_keys(entity_type, entity_id);
create index idx_idempotency_keys_expires_at on idempotency_keys(expires_at) where expires_at < now();

-- ============================================================================
-- Database-managed timestamps (UTC)
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
  before update on profiles for each row
  execute function set_updated_at();

create trigger trg_rides_updated_at
  before update on rides for each row
  execute function set_updated_at();

create trigger trg_ride_members_updated_at
  before update on ride_members for each row
  execute function set_updated_at();

create trigger trg_ride_messages_updated_at
  before update on ride_messages for each row
  execute function set_updated_at();

create trigger trg_sos_incidents_updated_at
  before update on sos_incidents for each row
  execute function set_updated_at();

create trigger trg_route_geometry_updated_at
  before update on route_geometry for each row
  execute function set_updated_at();

create trigger trg_sync_outbox_updated_at
  before update on sync_outbox for each row
  execute function set_updated_at();

-- ============================================================================
-- SECURITY DEFINER admin functions (executed by trusted backend, never from APK)
-- These run with a fixed search_path to prevent search_path injection.
-- ============================================================================

-- Validate an invite code and return limited ride metadata (no membership leak)
create or replace function validate_invite_code(code text)
returns table (ride_id uuid, ride_name text, status text)
language plpgsql security definer
set search_path = 'public'
as $$
begin
  return query
  select id, name, status
  from rides
  where invite_code = code
    and deleted_at is null;
end;
$$;

-- Atomically join a ride (leader creates member row via backend)
create or replace function join_ride(ride_id uuid, member_uid text, member_name text)
returns boolean
language plpgsql security definer
set search_path = 'public'
as $$
declare
  ride_exists boolean;
begin
  select exists (select 1 from rides where id = ride_id and deleted_at is null)
  into ride_exists;
  if not ride_exists then
    return false;
  end if;
  insert into ride_members (ride_id, firebase_uid, name, role, membership_status)
  values (ride_id, member_uid, member_name, 'MEMBER', 'ACTIVE')
  on conflict (ride_id, firebase_uid) do nothing;
  return true;
end;
$$;

-- End/complete a ride (leader only)
create or replace function end_ride(ride_id uuid, leader_uid text, elapsed_seconds double precision)
returns boolean
language plpgsql security definer
set search_path = 'public'
as $$
declare
  is_leader boolean;
begin
  select leader_id = leader_uid into is_leader
  from rides where id = ride_id;
  if not is_leader then
    return false;
  end if;
  update rides
  set status = 'COMPLETED',
      elapsed_duration_seconds = elapsed_seconds,
      updated_at = now() at time zone 'UTC'
  where id = ride_id and status <> 'COMPLETED';
  return true;
end;
$$;

-- Transfer leadership (leader only)
create or replace function transfer_leadership(ride_id uuid, old_leader_uid text, new_leader_uid text)
returns boolean
language plpgsql security definer
set search_path = 'public'
as $$
declare
  is_leader boolean;
  new_member_exists boolean;
begin
  select leader_id = old_leader_uid into is_leader
  from rides where id = ride_id;
  if not is_leader then
    return false;
  end if;
  select exists (
    select 1 from ride_members
    where ride_id = transfer_leadership.ride_id
      and firebase_uid = new_leader_uid
      and membership_status = 'ACTIVE'
  ) into new_member_exists;
  if not new_member_exists then
    return false;
  end if;
  update ride_members
  set role = 'MEMBER'
  where ride_id = transfer_leadership.ride_id
    and firebase_uid = old_leader_uid;
  update ride_members
  set role = 'LEADER'
  where ride_id = transfer_leadership.ride_id
    and firebase_uid = new_leader_uid;
  update rides
  set leader_id = new_leader_uid,
      updated_at = now() at time zone 'UTC'
  where id = ride_id;
  return true;
end;
$$;

-- Remove a member (leader only)
create or replace function remove_member(ride_id uuid, leader_uid text, target_uid text)
returns boolean
language plpgsql security definer
set search_path = 'public'
as $$
declare
  is_leader boolean;
begin
  select leader_id = leader_uid into is_leader
  from rides where id = ride_id;
  if not is_leader then
    return false;
  end if;
  update ride_members
  set membership_status = 'REMOVED',
      deleted_at = now() at time zone 'UTC'
  where ride_id = remove_member.ride_id
    and firebase_uid = target_uid
    and membership_status = 'ACTIVE';
  return true;
end;
$$;

-- ============================================================================
-- Realtime: enable realtime for tables that need live listeners
-- ============================================================================
alter publication supabase_realtime add table rides;
alter publication supabase_realtime add table ride_members;
alter publication supabase_realtime add table ride_messages;
alter publication supabase_realtime add table sos_incidents;
