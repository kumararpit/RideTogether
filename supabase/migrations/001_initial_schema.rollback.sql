-- ============================================================================
# RideTogether — Supabase PostgreSQL migration (Firestore → Supabase)
# Migration 001 ROLLBACK
#
# Drops every object created by 001_initial_schema.sql, in reverse order.
# Intended for development / staging only. Production rollback of a schema
# migration should be a forward migration (new version) that restores the
# prior state, not a destructive drop.
#
# WARNING: this is destructive. All data in these tables is lost.
-- ============================================================================

-- Realtime publications first (they reference the tables).
alter publication supabase_realtime drop table if exists route_geometry;
alter publication supabase_realtime drop table if exists sos_incidents;
alter publication supabase_realtime drop table if exists ride_messages;
alter publication supabase_realtime drop table if exists ride_members;
alter publication supabase_realtime drop table if exists rides;

-- Triggers.
drop trigger if exists trg_sync_outbox_updated_at on sync_outbox;
drop trigger if exists trg_route_geometry_updated_at on route_geometry;
drop trigger if exists trg_sos_incidents_updated_at on sos_incidents;
drop trigger if exists trg_ride_messages_updated_at on ride_messages;
drop trigger if exists trg_ride_members_updated_at on ride_members;
drop trigger if exists trg_rides_updated_at on rides;
drop trigger if exists trg_profiles_updated_at on profiles;

drop function if exists set_updated_at() cascade;

-- Tables (reverse creation order; FKs resolved by cascade).
drop table if exists idempotency_keys cascade;
drop table if exists sync_outbox cascade;
drop table if exists route_geometry cascade;
drop table if exists sos_incidents cascade;
drop table if exists ride_messages cascade;
drop table if exists ride_members cascade;
drop table if exists rides cascade;
drop table if exists profiles cascade;
drop table if exists app_config cascade;

-- Extension (only if no other object depends on it).
drop extension if exists "uuid-ossp";
