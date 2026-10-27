// ============================================================================
// DEPRECATED — Firestore has been removed from the RideTogether Android app
// (Phase 5: Firestore dependency removed from RideRepository.kt).
//
// Firebase Auth remains the sole identity provider. Supabase is the data layer.
// This file is kept for historical/reference purposes only. The emulator tests
// here (T-01 through T-21) validated the Firestore security rules that are now
// superseded by Supabase RLS policies in supabase-schema.sql.
//
// See supabase-schema.sql for the replacement authorization model:
// Firebase ID token → trusted backend verifies via Firebase Admin SDK →
// SET app.current_user_id → RLS policies use current_setting('app.current_user_id', true).
// ============================================================================

const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123"; // ride leader
const BOB_UID = "bob_456"; // ride member
const MALLORY_UID = "mallory_789"; // authenticated non-member of any ride
const RIDE_ID = "ride_1";
const RIDE2_ID = "ride_2";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const aliceDb = () => testEnv.authenticatedContext(ALICE_UID).firestore();
const bobDb = () => testEnv.authenticatedContext(BOB_UID).firestore();
const malloryDb = () => testEnv.authenticatedContext(MALLORY_UID).firestore();
const unauthedDb = () => testEnv.unauthenticatedContext().firestore();

const ridePath = (rideId) => `rides/${rideId}`;
const ridersPath = (rideId) => `rides/${rideId}/riders`;
const messagesPath = (rideId) => `rides/${rideId}/messages`;
const sosPath = (rideId) => `rides/${rideId}/sos`;

const now = () => new Date();

const baseRide = (rideId, leaderId) => ({
  id: rideId,
  name: "Morning Ride",
  inviteCode: "RIDE123",
  leaderId,
  status: "LOBBY",
  startLocationName: "Pune",
  destinationName: "Lonavala",
  startLat: 18.52,
  startLng: 73.85,
  destLat: 18.75,
  destLng: 73.40,
  createdAt: now(),
  updatedAt: now(),
});

const baseRider = (riderUid, rideId, role = "MEMBER", status = "RIDING") => ({
  id: riderUid,
  userId: riderUid,
  rideId,
  name: riderUid === ALICE_UID ? "Alice" : "Bob",
  role,
  status,
  membershipStatus: "ACTIVE",
  latitude: 18.53,
  longitude: 73.86,
  lastUpdatedMs: Date.now(),
  updatedAt: now(),
});

const baseMessage = (messageId, senderId, rideId) => ({
  id: messageId,
  rideId,
  senderId,
  senderName: senderId === ALICE_UID ? "Alice" : "Bob",
  type: "GROUP",
  text: "Need help at the next stop",
  timestampMs: Date.now(),
  createdAt: now(),
});

const baseSos = (sosId, riderId, rideId) => ({
  id: sosId,
  rideId,
  riderId,
  riderName: riderId === ALICE_UID ? "Alice" : "Bob",
  latitude: 18.53,
  longitude: 73.86,
  isResolved: false,
  timestampMs: Date.now(),
  createdAt: now(),
});

// Seed ride R with alice (LEADER) and bob (MEMBER), plus alice's user profile.
// Uses alice's authenticated context so the ride-create and leader-created-rider
// paths are exercised against the real rules (not withSecurityRulesDisabled).
async function seedStandardRide() {
  const db = aliceDb();
  await assertSucceeds(db.collection("rides").doc(RIDE_ID).set(baseRide(RIDE_ID, ALICE_UID)));
  await assertSucceeds(
    db.collection("users").doc(ALICE_UID).set({
      id: ALICE_UID,
      name: "Alice",
      email: "alice@example.com",
      createdAt: now(),
    })
  );
  // Leader creates her own LEADER rider doc (authorized at ride creation).
  await assertSucceeds(
    db.collection(ridersPath(RIDE_ID)).doc(ALICE_UID).set(baseRider(ALICE_UID, RIDE_ID, "LEADER"))
  );
  // Leader creates bob's MEMBER rider doc (authorized join / host approval).
  await assertSucceeds(
    db.collection(ridersPath(RIDE_ID)).doc(BOB_UID).set(baseRider(BOB_UID, RIDE_ID, "MEMBER"))
  );
}

// Seed ride R2 with only alice as leader (no members yet) for join tests.
async function seedRide2() {
  const db = aliceDb();
  await assertSucceeds(db.collection("rides").doc(RIDE2_ID).set(baseRide(RIDE2_ID, ALICE_UID)));
  await assertSucceeds(
    db.collection(ridersPath(RIDE2_ID)).doc(ALICE_UID).set(baseRider(ALICE_UID, RIDE2_ID, "LEADER"))
  );
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await seedStandardRide();
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

// ---------- T-01 Ride read ----------
test("T-01(+) leader can read her own ride", async () => {
  await assertSucceeds(aliceDb().collection("rides").doc(RIDE_ID).get());
});
test("T-01(−) nonmember cannot read a ride by ID", async () => {
  await assertFails(malloryDb().collection("rides").doc(RIDE_ID).get());
});
test("T-01(−) unauthenticated user cannot read a ride", async () => {
  await assertFails(unauthedDb().collection("rides").doc(RIDE_ID).get());
});

// ---------- T-02 Rider read ----------
test("T-02(+) member can read the rider roster of her ride", async () => {
  await assertSucceeds(bobDb().collection(ridersPath(RIDE_ID)).get());
});
test("T-02(−) nonmember cannot read a ride's rider roster", async () => {
  await assertFails(malloryDb().collection(ridersPath(RIDE_ID)).get());
});

// ---------- T-03 Message read ----------
test("T-03(+) member can read messages in her ride", async () => {
  await assertSucceeds(bobDb().collection(messagesPath(RIDE_ID)).get());
});
test("T-03(−) nonmember cannot read a ride's messages", async () => {
  await assertFails(malloryDb().collection(messagesPath(RIDE_ID)).get());
});

// ---------- T-04 SOS read ----------
test("T-04(+) member can read SOS events in her ride", async () => {
  await assertSucceeds(bobDb().collection(sosPath(RIDE_ID)).get());
});
test("T-04(−) nonmember cannot read a ride's SOS events", async () => {
  await assertFails(malloryDb().collection(sosPath(RIDE_ID)).get());
});

// ---------- T-05 Profile read ----------
test("T-05(+) user can read her own profile", async () => {
  await assertSucceeds(aliceDb().collection("users").doc(ALICE_UID).get());
});
test("T-05(−) nonmember cannot read another user's profile", async () => {
  await assertFails(malloryDb().collection("users").doc(ALICE_UID).get());
});

// ---------- T-06 Message send (member) ----------
test("T-06(+) member can send a quick message to her ride", async () => {
  await assertSucceeds(
    bobDb()
      .collection(messagesPath(RIDE_ID))
      .doc("msg_bob_1")
      .set(baseMessage("msg_bob_1", BOB_UID, RIDE_ID))
  );
});

// ---------- T-07 Message send (nonmember) ----------
test("T-07(−) nonmember cannot send a message into a ride she is not a member of", async () => {
  await assertFails(
    malloryDb()
      .collection(messagesPath(RIDE_ID))
      .doc("msg_mallory_1")
      .set(baseMessage("msg_mallory_1", MALLORY_UID, RIDE_ID))
  );
});

// ---------- T-08 Message send (impersonation) ----------
test("T-08(−) member cannot impersonate another rider (senderId != auth.uid)", async () => {
  await assertFails(
    bobDb()
      .collection(messagesPath(RIDE_ID))
      .doc("msg_impersonate_1")
      .set(baseMessage("msg_impersonate_1", ALICE_UID, RIDE_ID))
  );
});

// ---------- T-09 Rider write (self: maintain own telemetry) ----------
test("T-09(+) member can update her own rider doc (telemetry, no role change)", async () => {
  await assertSucceeds(
    bobDb()
      .collection(ridersPath(RIDE_ID))
      .doc(BOB_UID)
      .update({ latitude: 18.6, longitude: 73.9, status: "STOPPED", lastUpdatedMs: Date.now() })
  );
});

// ---------- T-10 Rider write (other: tamper / self-promote) ----------
test("T-10(−) member cannot write another rider's doc (role escalation to LEADER)", async () => {
  await assertFails(
    bobDb()
      .collection(ridersPath(RIDE_ID))
      .doc(ALICE_UID)
      .update({ role: "LEADER" })
  );
});
test("T-10(−) member cannot create another rider's doc", async () => {
  await assertFails(
    bobDb()
      .collection(ridersPath(RIDE_ID))
      .doc("eve_999")
      .set({ id: "eve_999", userId: "eve_999", rideId: RIDE_ID, name: "Eve", role: "MEMBER", status: "RIDING", latitude: 0, longitude: 0 })
  );
});

// ---------- T-11 Rider join ----------
test("T-11(+) leader can add a member to her ride (authorized join)", async () => {
  await seedRide2();
  await assertSucceeds(
    aliceDb()
      .collection(ridersPath(RIDE2_ID))
      .doc(BOB_UID)
      .set(baseRider(BOB_UID, RIDE2_ID, "MEMBER"))
  );
});
test("T-11(−) nonmember cannot self-register as a rider in a ride", async () => {
  await seedRide2();
  await assertFails(
    malloryDb()
      .collection(ridersPath(RIDE2_ID))
      .doc(MALLORY_UID)
      .set(baseRider(MALLORY_UID, RIDE2_ID, "MEMBER"))
  );
});

// ---------- T-12 Leave + post-leave read (revocation) ----------
test("T-12(+) member can delete her own rider doc (leave)", async () => {
  await assertSucceeds(bobDb().collection(ridersPath(RIDE_ID)).doc(BOB_UID).delete());
});
test("T-12(−) former member cannot read the ride after leaving (membership revoked)", async () => {
  await bobDb().collection(ridersPath(RIDE_ID)).doc(BOB_UID).delete();
  await assertFails(bobDb().collection("rides").doc(RIDE_ID).get());
  await assertFails(bobDb().collection(ridersPath(RIDE_ID)).get());
  await assertFails(bobDb().collection(messagesPath(RIDE_ID)).get());
});

// ---------- T-13 SOS write (member) ----------
test("T-13(+) member can trigger an SOS in her ride", async () => {
  await assertSucceeds(
    bobDb().collection(sosPath(RIDE_ID)).doc("sos_bob_1").set(baseSos("sos_bob_1", BOB_UID, RIDE_ID))
  );
});

// ---------- T-14 SOS write (nonmember) ----------
test("T-14(−) nonmember cannot trigger an SOS in a ride", async () => {
  await assertFails(
    malloryDb().collection(sosPath(RIDE_ID)).doc("sos_mallory_1").set(baseSos("sos_mallory_1", MALLORY_UID, RIDE_ID))
  );
});

// ---------- T-15 Ride status change (leader) ----------
test("T-15(+) leader can start her ride (status change)", async () => {
  await assertSucceeds(aliceDb().collection("rides").doc(RIDE_ID).update({ status: "ACTIVE", updatedAt: now() }));
});

// ---------- T-16 Ride status change (non-leader) ----------
test("T-16(−) member cannot change ride status", async () => {
  await assertFails(bobDb().collection("rides").doc(RIDE_ID).update({ status: "ACTIVE", updatedAt: now() }));
});

// ---------- T-17 Ride field edit (nonmember) ----------
test("T-17(−) nonmember cannot edit a ride's fields (inviteCode)", async () => {
  await assertFails(
    malloryDb().collection("rides").doc(RIDE_ID).update({ inviteCode: "HACKED", updatedAt: now() })
  );
});

// ---------- T-18 Mesh flush (member sender allowed, nonmember denied) ----------
test("T-18(+) bridge flush for a member sender writes a message to the ride", async () => {
  await assertSucceeds(
    aliceDb()
      .collection(messagesPath(RIDE_ID))
      .doc("msg_bridge_alice")
      .set(baseMessage("msg_bridge_alice", ALICE_UID, RIDE_ID))
  );
});
test("T-18(−) bridge flush for a nonmember sender is denied", async () => {
  await assertFails(
    malloryDb()
      .collection(messagesPath(RIDE_ID))
      .doc("msg_bridge_mallory")
      .set(baseMessage("msg_bridge_mallory", MALLORY_UID, RIDE_ID))
  );
});

// ---------- T-19 Active-ride restore ----------
test("T-19(+) leader can restore her active ride by ID at startup", async () => {
  await assertSucceeds(aliceDb().collection("rides").doc(RIDE_ID).get());
});
test("T-19(−) nonmember cannot restore a ride by ID at startup", async () => {
  await assertFails(malloryDb().collection("rides").doc(RIDE_ID).get());
});

// ---------- T-20 Tombstone leave (UPDATE membershipStatus to LEFT) ----------
test("T-20(+) member can tombstone-leave her ride (update membershipStatus to LEFT)", async () => {
  await assertSucceeds(
    bobDb()
      .collection(ridersPath(RIDE_ID))
      .doc(BOB_UID)
      .update({ membershipStatus: "LEFT", updatedAt: now() })
  );
});
test("T-20(−) former member cannot read after tombstone leave (membershipStatus LEFT → denied)", async () => {
  await bobDb()
    .collection(ridersPath(RIDE_ID))
    .doc(BOB_UID)
    .update({ membershipStatus: "LEFT", updatedAt: now() });
  await assertFails(bobDb().collection("rides").doc(RIDE_ID).get());
  await assertFails(bobDb().collection(ridersPath(RIDE_ID)).get());
  await assertFails(bobDb().collection(messagesPath(RIDE_ID)).get());
});

// ---------- T-21 Leader removal (UPDATE membershipStatus to REMOVED) ----------
test("T-21(+) leader can remove a member (set membershipStatus to REMOVED)", async () => {
  await assertSucceeds(
    aliceDb()
      .collection(ridersPath(RIDE_ID))
      .doc(BOB_UID)
      .update({ membershipStatus: "REMOVED", updatedAt: now() })
  );
});
test("T-21(−) removed member cannot read after removal (membershipStatus REMOVED → denied)", async () => {
  await aliceDb()
    .collection(ridersPath(RIDE_ID))
    .doc(BOB_UID)
    .update({ membershipStatus: "REMOVED", updatedAt: now() });
  await assertFails(bobDb().collection("rides").doc(RIDE_ID).get());
  await assertFails(bobDb().collection(ridersPath(RIDE_ID)).get());
  await assertFails(bobDb().collection(messagesPath(RIDE_ID)).get());
});
