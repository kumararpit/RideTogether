# Supabase Migration — Security Architecture

## Purpose
Security design for migrating RideTogether's ride data layer from Firebase Firestore to
Supabase PostgreSQL while preserving Firebase Authentication (Google Sign-In) as the
identity provider. Firebase UIDs are the canonical app identities and are never replaced
by Supabase `auth.users` identities.

This document is security analysis and policy/ test design only. No Kotlin/Java source
or backend implementation is modified here.

---

## 1. Identity model

- Firebase Auth (Google Sign-In via `CredentialManager`) remains the **sole**
  authentication provider. The Android app obtains a Firebase ID token via
  `FirebaseUser.getIdToken(true)`.
- The Firebase UID (`auth.currentUser.uid`) is the canonical app identity. It is stored
  in `profiles.id`, `ride_members.user_id`, `rides.leader_id`,
  `ride_messages.sender_id`, `sos_incidents.rider_id`.
- Supabase Auth is **NOT used**. `auth.users`, `auth.uid()`, `auth.role()` are **not**
  referenced in schema or RLS. The existing `supabase-schema.sql` design that references
  `auth.users`/`auth.uid()` is superseded by this document.

---

## 2. Security findings (current state)

The following gaps were identified in the current codebase and must be closed by the
migration design:

| # | Finding | Severity |
|---|---------|----------|
| F-01 | `SupabaseClient` sends the anon key as `Authorization: Bearer <anon key>` and talks directly to Supabase REST. There is **no backend mediation** and **no Firebase ID token forwarding**. | High |
| F-02 | `SupabaseRideRepository` relies on Supabase RLS in `supabase-schema.sql` which uses `auth.users`/`auth.uid()`/`auth.role()`. This **violates** the requirement to preserve Firebase UIDs and not use Supabase Auth. | High |
| F-03 | No backend Firebase ID token verification exists anywhere in the stack. | High |
| F-04 | The `rides` SELECT policy is `auth.role() = 'authenticated'` — any authenticated Supabase principal can read **all** rides (too broad; not membership-gated). | High |
| F-05 | No session variable / `current_setting` pattern exists for passing the Firebase UID into RLS policies. | Medium |
| F-06 | Invite-code join is performed client-side by querying the `rides` table directly — no server-side atomic validation and no rate limiting. | Medium |

---

## 3. Backend token verification design

The Android app must **not** verify Firebase ID tokens itself, and must **not** send a
raw Firebase UID that the backend trusts without verification.

Trusted backend (Node.js / Go / Python with Firebase Admin SDK or equivalent) verifies
every incoming request:

1. Client → HTTPS POST `/v1/...` with `Authorization: Bearer <Firebase ID token>`.
2. Backend extracts the Bearer token.
3. Backend verifies the Firebase ID token using Firebase Admin SDK
   (`admin.auth().verifyIdToken(idToken, checkRevoked=true)`):
   a. **Signature validation** — RS256, signed by Google's public certs.
   b. **Issuer** (`iss`) == `https://securetoken.google.com/<project-id>` where
      `<project-id>` = `ridetogether-f558a`.
   c. **Audience** (`aud`) == `<project-id>` = `ridetogether-f558a`.
   d. **Expiration** (`exp`) — token not expired.
   e. **Issued-at** (`iat`) — token not in the future.
   f. **Revocation** (`checkRevoked=true`) — token not revoked / user not disabled.
4. On success, Admin SDK returns the decoded token containing `uid` = Firebase UID.
5. Backend derives the Firebase UID **only** from the verified token — never from a
   client-supplied `uid`, `user_id`, or `X-User-Id` header.
6. Backend sets a database session variable `app.current_user_id = <Firebase UID>`
   (via `SET config`) before executing any query, or passes it through a signed
   service-to-DB context.

---

## 4. Backend mediates sensitive reads and ALL writes

```
Android app (HTTPS)
     │
     ▼
Trusted backend API
  - verify Firebase ID token → Firebase UID
  - authorization checks (membership / leadership / status)
     │
     ▼
PostgreSQL (via restricted DB role, RLS enabled)
     ▲
     │
service-role connection (admin/migration only — backend, never APK)
```

Every data access path:
- **Reads** of private ride data (roster, location, messages, SOS): backend checks
  membership before returning data.
- **All writes** (create ride, join, telemetry, message, SOS, end ride, transfer
  leadership, remove member): backend checks authorization (leader vs member vs
  active status) before writing.
- The Android Supabase client (anon key) is used **only** for non-sensitive public
  reads or not at all; sensitive operations go through the backend API.

---

## 5. Database access design

- RLS is **enabled** on all exposed tables: `profiles`, `rides`, `ride_members`,
  `ride_messages`, `sos_incidents`.
- The backend connects to PostgreSQL through a **restricted DB role** (not
  `service_role`) for user-facing requests. Before each query it executes
  `SET app.current_user_id = '<Firebase UID>'`. RLS policies read this value via
  `current_setting('app.current_user_id', true)`.
- A separate **service-role** connection is used only by the trusted backend for
  administrative operations (migrations, seed data, support tools). It is **never**
  embedded in the APK.
- Admin access is isolated: the service-role key lives only in the backend runtime
  environment.

---

## 6. Decision: do NOT use Supabase Firebase third-party auth integration

Supabase offers a "Firebase" third-party auth integration that maps Firebase tokens to
Supabase `auth.users` for direct RLS via `auth.uid()`. This is **not adopted** because:

- It would introduce Supabase Auth identities and break the requirement to preserve
  Firebase UIDs as app identities.
- It couples RLS to Supabase Auth's user model rather than the app's `ride_members`
  table, making ride-membership authorization (ACTIVE/LEFT/REMOVED/BANNED) harder to
  enforce in SQL.
- Integration requirements (custom claims, token revocation propagation, session
  management) have **not** been documented or verified.

**Preferred approach:** backend-mediated access with a restricted DB role + RLS as
defense-in-depth (see sections 3–5).

---

## 7. Secrets management

- The APK must **never** embed: PostgreSQL password, Supabase service-role key/secret,
  or any backend admin credential.
- Android may hold only the Supabase **anon key** (for public REST access) and calls
  the trusted backend over HTTPS for sensitive operations. The Firebase API key is
  already embedded — it is a public client-side identifier, not a secret.
- Service-role key and backend secrets live only in the backend runtime environment.

---

## 8. Authorization boundary summary

| Operation | Android client | Trusted backend API | PostgreSQL RLS |
|-----------|---------------|---------------------|----------------|
| Google Sign-In | CredentialManager → Firebase ID token | — | — |
| Verify identity | — | Firebase Admin SDK `verifyIdToken` | — |
| Read ride data | HTTPS request + ID token | Membership check → query | `app.current_user_id` filter |
| Write ride data | HTTPS request + ID token | Leader/member check → write | `app.current_user_id` filter |
| Admin / migration | — | Service-role (backend only) | Bypasses RLS (admin only) |

---

## 9. Open questions

- Does a trusted backend (Node.js / Go / Python) exist or need to be built? If none,
  an intermediate API layer must be added before any sensitive Supabase access.
- Is the Firebase Admin SDK available in the chosen backend language/runtime?
- Is `checkRevoked=true` acceptable given potential latency, or should revocation be
  checked periodically / cached?
- Will the backend set `app.current_user_id` per request via `SET config`, or pass the
  Firebase UID through a signed JWT claim that Postgres reads?
- Is ride-metadata read (for invite-code discovery) fully membership-gated, or does a
  backend-side lookup endpoint replace the open collection query?
