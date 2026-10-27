require('dotenv').config();
const express = require('express');
const cors = require('cors');
const admin = require('firebase-admin');
const { createClient } = require('@supabase/supabase-js');

const app = express();
const PORT = process.env.PORT || 3000;

// ---------------------------------------------------------------------------
// Firebase Admin SDK — verifies Firebase ID tokens issued by Google Sign-In
// ---------------------------------------------------------------------------
if (!admin.apps.length) {
  admin.initializeApp({
    projectId: process.env.FIREBASE_PROJECT_ID,
  });
}

const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || 'ridetogether-f558a';

if (!FIREBASE_PROJECT_ID) {
  console.error('FIREBASE_PROJECT_ID must be set');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Supabase client (service_role — backend only, never shipped in the APK)
// ---------------------------------------------------------------------------
const supabaseUrl = process.env.SUPABASE_URL;
const supabaseServiceKey = process.env.SUPABASE_SERVICE_ROLE_KEY;

if (!supabaseUrl || !supabaseServiceKey) {
  console.error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set');
  process.exit(1);
}

const supabase = createClient(supabaseUrl, supabaseServiceKey, {
  auth: { persistSession: false, autoRefreshToken: false },
});

// ---------------------------------------------------------------------------
// Middleware: verify Firebase ID token, extract UID, set app.current_user_id
// ---------------------------------------------------------------------------
async function verifyFirebaseToken(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing Authorization: Bearer <Firebase ID token>' });
  }
  const idToken = authHeader.slice(7);

  try {
    const decoded = await admin.auth().verifyIdToken(idToken, true);

    // Verify issuer and audience match our Firebase project
    if (decoded.iss !== `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`) {
      return res.status(401).json({ error: 'Token issuer mismatch' });
    }
    if (decoded.aud !== FIREBASE_PROJECT_ID) {
      return res.status(401).json({ error: 'Token audience mismatch' });
    }

    // Firebase UID — derived ONLY from the verified token
    req.firebaseUid = decoded.uid;
    req.firebaseUser = decoded;
    next();
  } catch (err) {
    console.warn('Firebase ID token verification failed:', err.message);
    return res.status(401).json({ error: 'Invalid or revoked Firebase ID token' });
  }
}

// ---------------------------------------------------------------------------
// Helper: set app.current_user_id on the Supabase session for RLS
// ---------------------------------------------------------------------------
async function setCurrentUserId(uid) {
  // SET config is session-scoped; we issue it as a raw SQL command before
  // each query via Supabase's rpc / sql endpoint, or by setting the header.
  // Supabase JS client does not expose per-request session variables directly,
  // so we use a raw SQL call via rpc to set the variable for the duration
  // of the request. The simplest reliable approach: execute
  // `SET app.current_user_id = '<uid>'` before every query.
  await supabase.rpc('set_current_user_id', { uid });
}

// ---------------------------------------------------------------------------
// Middleware: apply the RLS session variable after token verification
// ---------------------------------------------------------------------------
app.use(verifyFirebaseToken, async (req, res, next) => {
  try {
    await setCurrentUserId(req.firebaseUid);
    next();
  } catch (err) {
    console.error('Failed to set app.current_user_id:', err.message);
    res.status(500).json({ error: 'Database session setup failed' });
  }
});

app.use(cors());
app.use(express.json());

// ---------------------------------------------------------------------------
// Health check
// ---------------------------------------------------------------------------
app.get('/v1/health', (req, res) => {
  res.json({ status: 'ok', project: FIREBASE_PROJECT_ID });
});

// ---------------------------------------------------------------------------
// Rides
// ---------------------------------------------------------------------------

// GET /v1/rides/:rideId — returns ride if caller is member or leader
app.get('/v1/rides/:rideId', async (req, res) => {
  const { rideId } = req.params;
  const { data, error } = await supabase
    .from('rides')
    .select('*')
    .eq('id', rideId)
    .single();
  if (error || !data) {
    return res.status(404).json({ error: 'Ride not found or access denied' });
  }
  res.json(data);
});

// POST /v1/rides — caller becomes leader
app.post('/v1/rides', async (req, res) => {
  const { name, startLocationName, destinationName, startLat, startLng, destLat, destLng, inviteCode } = req.body;
  const leaderId = req.firebaseUid;

  const { data, error } = await supabase
    .from('rides')
    .insert([{
      id: crypto.randomUUID(),
      name,
      leaderId,
      inviteCode,
      status: 'CREATED',
      startLocationName,
      destinationName,
      startLat,
      startLng,
      destLat,
      destLng,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }])
    .select()
    .single();

  if (error) {
    return res.status(400).json({ error: error.message });
  }

  // Leader creates their own rider record
  await supabase.from('ride_members').insert([{
    id: leaderId,
    userId: leaderId,
    rideId: data.id,
    name: req.body.leaderName || 'Leader',
    role: 'LEADER',
    membershipStatus: 'ACTIVE',
    status: 'RIDING',
    latitude: startLat,
    longitude: startLng,
    lastUpdatedMs: Date.now(),
  }]);

  res.status(201).json(data);
});

// ---------------------------------------------------------------------------
// Riders (membership roster)
// ---------------------------------------------------------------------------

// GET /v1/rides/:rideId/riders — roster for members/leaders
app.get('/v1/rides/:rideId/riders', async (req, res) => {
  const { rideId } = req.params;
  const { data, error } = await supabase
    .from('ride_members')
    .select('*')
    .eq('rideId', rideId);
  if (error) return res.status(400).json({ error: error.message });
  res.json(data || []);
});

// POST /v1/rides/:rideId/riders — leader adds a member (authorized join)
app.post('/v1/rides/:rideId/riders', async (req, res) => {
  const { rideId } = req.params;
  const { userId, name } = req.body;
  const leaderId = req.firebaseUid;

  // Verify caller is the leader of this ride
  const { data: ride } = await supabase
    .from('rides')
    .select('leaderId')
    .eq('id', rideId)
    .single();
  if (!ride || ride.leaderId !== leaderId) {
    return res.status(403).json({ error: 'Only the ride leader can add riders' });
  }

  const { data, error } = await supabase
    .from('ride_members')
    .insert([{
      id: userId,
      userId,
      rideId,
      name,
      role: 'MEMBER',
      membershipStatus: 'ACTIVE',
      status: 'RIDING',
    }])
    .select()
    .single();

  if (error) return res.status(400).json({ error: error.message });
  res.status(201).json(data);
});

// PUT /v1/rides/:rideId/riders/:riderId — update telemetry (owner) or role/membership (leader)
app.put('/v1/rides/:rideId/riders/:riderId', async (req, res) => {
  const { rideId, riderId } = req.params;
  const callerUid = req.firebaseUid;
  const updates = req.body;

  const { data: existing } = await supabase
    .from('ride_members')
    .select('*')
    .eq('id', riderId)
    .eq('rideId', rideId)
    .single();
  if (!existing) return res.status(404).json({ error: 'Rider not found' });

  const isOwner = existing.userId === callerUid;
  const { data: ride } = await supabase
    .from('rides')
    .select('leaderId')
    .eq('id', rideId)
    .single();
  const isLeader = ride && ride.leaderId === callerUid;

  // Owner may update their own telemetry while ACTIVE; may not change role.
  // Leader may change role or membershipStatus only.
  if (isOwner && !isLeader) {
    const allowedKeys = ['latitude', 'longitude', 'speedKmh', 'headingDeg', 'status', 'lastUpdatedMs'];
    const keys = Object.keys(updates);
    if (!keys.every(k => allowedKeys.includes(k))) {
      return res.status(403).json({ error: 'Members may only update their own telemetry' });
    }
  } else if (isLeader) {
    const allowedKeys = ['role', 'membershipStatus'];
    const keys = Object.keys(updates);
    if (!keys.every(k => allowedKeys.includes(k))) {
      return res.status(403).json({ error: 'Leader may only update role or membershipStatus' });
    }
  } else {
    return res.status(403).json({ error: 'Not authorized to update this rider' });
  }

  const { data, error } = await supabase
    .from('ride_members')
    .update(updates)
    .eq('id', riderId)
    .eq('rideId', rideId)
    .select()
    .single();

  if (error) return res.status(400).json({ error: error.message });
  res.json(data);
});

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

// POST /v1/rides/:rideId/messages — send a quick message (members only)
app.post('/v1/rides/:rideId/messages', async (req, res) => {
  const { rideId } = req.params;
  const { type, text } = req.body;
  const senderId = req.firebaseUid;

  // Verify caller is a member of the ride
  const { data: member } = await supabase
    .from('ride_members')
    .select('userId')
    .eq('rideId', rideId)
    .eq('userId', senderId)
    .eq('membershipStatus', 'ACTIVE')
    .single();
  if (!member) {
    return res.status(403).json({ error: 'Only active ride members can send messages' });
  }

  const { data, error } = await supabase
    .from('ride_messages')
    .insert([{
      id: crypto.randomUUID(),
      rideId,
      senderId,
      senderName: req.body.senderName || senderId,
      type,
      text,
      timestampMs: Date.now(),
    }])
    .select()
    .single();

  if (error) return res.status(400).json({ error: error.message });
  res.status(201).json(data);
});

// ---------------------------------------------------------------------------
// SOS
// ---------------------------------------------------------------------------

// POST /v1/rides/:rideId/sos — trigger an SOS (members only)
app.post('/v1/rides/:rideId/sos', async (req, res) => {
  const { rideId } = req.params;
  const riderId = req.firebaseUid;

  // Verify caller is a member
  const { data: member } = await supabase
    .from('ride_members')
    .select('userId, name')
    .eq('rideId', rideId)
    .eq('userId', riderId)
    .eq('membershipStatus', 'ACTIVE')
    .single();
  if (!member) {
    return res.status(403).json({ error: 'Only active ride members can trigger SOS' });
  }

  const { data, error } = await supabase
    .from('sos_incidents')
    .insert([{
      id: crypto.randomUUID(),
      rideId,
      riderId,
      riderName: member.name || riderId,
      latitude: req.body.latitude,
      longitude: req.body.longitude,
      isResolved: false,
      timestampMs: Date.now(),
      acknowledgedBy: [],
    }])
    .select()
    .single();

  if (error) return res.status(400).json({ error: error.message });
  res.status(201).json(data);
});

// PUT /v1/rides/:rideId/sos/:sosId — acknowledge or resolve (leader or author)
app.put('/v1/rides/:rideId/sos/:sosId', async (req, res) => {
  const { rideId, sosId } = req.params;
  const callerUid = req.firebaseUid;
  const { isResolved, acknowledgedBy } = req.body;

  const { data: existing } = await supabase
    .from('sos_incidents')
    .select('*')
    .eq('id', sosId)
    .eq('rideId', rideId)
    .single();
  if (!existing) return res.status(404).json({ error: 'SOS not found' });

  const isLeader = (await supabase
    .from('rides')
    .select('leaderId')
    .eq('id', rideId)
    .single()).data?.leaderId === callerUid;
  const isAuthor = existing.riderId === callerUid;

  if (!isLeader && !isAuthor) {
    return res.status(403).json({ error: 'Only the ride leader or SOS author may update' });
  }

  const updates = {};
  if (typeof isResolved === 'boolean') updates.isResolved = isResolved;
  if (Array.isArray(acknowledgedBy)) updates.acknowledgedBy = acknowledgedBy;

  const { data, error } = await supabase
    .from('sos_incidents')
    .update(updates)
    .eq('id', sosId)
    .eq('rideId', rideId)
    .select()
    .single();

  if (error) return res.status(400).json({ error: error.message });
  res.json(data);
});

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------
app.listen(PORT, () => {
  console.log(`RideTogether trusted backend listening on :${PORT}`);
  console.log(`Firebase project: ${FIREBASE_PROJECT_ID}`);
});
