# RideTogether Trusted Backend

Verifies Firebase ID tokens and proxies authorized requests to Supabase.

## Architecture

Firebase Auth is the sole identity provider. Supabase is the data layer only.
This backend sits between the Android app and Supabase:

```
Android app (HTTPS + Firebase ID token)
        │
        ▼
Trusted backend (this service)
  - verify Firebase ID token via Firebase Admin SDK
  - extract Firebase UID (never trust client-supplied uid)
  - SET app.current_user_id = <Firebase UID> on Supabase session
  - enforce membership/leadership authorization
        │
        ▼
Supabase PostgreSQL (RLS enabled, current_setting('app.current_user_id', true))
```

## Security rules

- **Never embed the Supabase `service_role` key in the APK.** It lives only in this
  backend's runtime environment.
- The Android app sends the Firebase ID token as `Authorization: Bearer <id_token>`
  on every sensitive request. The backend verifies it, extracts the UID, and sets
  `app.current_user_id` before any query.
- RLS policies in `supabase-schema.sql` read `current_setting('app.current_user_id', true)`
  — not `auth.uid()` — because Supabase Auth is not used.

## Getting started

```bash
cd backend
npm install
cp .env.example .env
# edit .env with your Firebase project ID and Supabase credentials
npm start
```

## Environment variables

| Variable | Description |
|----------|-------------|
| `FIREBASE_PROJECT_ID` | Firebase project ID (e.g. `ridetogether-f558a`) |
| `SUPABASE_URL` | Supabase project URL |
| `SUPABASE_SERVICE_ROLE_KEY` | Supabase service_role key (backend only, never in APK) |
| `PORT` | HTTP port (default 3000) |

## Endpoints

All endpoints require `Authorization: Bearer <Firebase ID token>`.

### `GET /v1/rides/:rideId`
Returns ride data if the caller is a member or leader of the ride.

### `POST /v1/rides`
Creates a new ride. Caller becomes the leader.

### `GET /v1/rides/:rideId/riders`
Returns the rider roster for members/leaders.

### `POST /v1/rides/:rideId/riders`
Leader adds a member to the ride (authorized join).

### `PUT /v1/rides/:rideId/riders/:riderId`
Updates a rider's telemetry (owner) or role/membership (leader).

### `POST /v1/rides/:rideId/messages`
Sends a quick message to the ride (members only).

### `POST /v1/rides/:rideId/sos`
Triggers an SOS alert (members only).

### `PUT /v1/rides/:rideId/sos/:sosId`
Acknowledges or resolves an SOS (leader or SOS author).
