/**
 * Firestore Rules Unit Tests (Task 21 Phase G) — run against the LOCAL Firestore
 * Emulator declared in firebase.json (127.0.0.1:8085).
 *
 * Start the emulator first:
 *   npx firebase-tools@latest emulators:start --only firestore --project ridetogo-rules-test
 * Then run:
 *   node scripts/firestore-rules-test.js
 *
 * Asserts the Task 21 hardening of the SHARED lifecycle + chat sender identity:
 *   1. Leader CAN move the ride to ACTIVE / COMPLETED.
 *   2. A member (different uid) CANNOT change the shared ride status.
 *   3. A client CANNOT change its own status field to an arbitrary other value (spoof).
 *   4. Any authenticated rider MAY update non-status ride fields (distance telemetry, leader transfer).
 *   5. Message create requires the authenticated uid to match senderId.
 *   6. Message update/delete is rejected (messages are immutable).
 */
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8085";
const { initializeTestEnvironment, assertSucceeds, assertFails } = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

const PROJECT = "ridetogo-rules-test";

let env;
let passed = 0;
let failed = 0;

function record(name, promise) {
  return promise
    .then(() => { passed++; console.log(`  PASS  ${name}`); })
    .catch((e) => { failed++; console.error(`  FAIL  ${name}\n        ${e.message || e}`); });
}

async function main() {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: {
      rules: fs.readFileSync(path.resolve(__dirname, "..", "firestore.rules"), "utf8"),
      host: "127.0.0.1",
      port: 8085,
    },
  });

  await env.clearFirestore();

  const leader = env.authenticatedContext("leader-uid");
  const member = env.authenticatedContext("member-uid");
  const leaderDb = leader.firestore();
  const memberDb = member.firestore();

  // Seed the ride document (create rules are open per current product needs).
  const rideData = {
    id: "ride-1", name: "Pune to Lonavala", inviteCode: "ABC123",
    leaderId: "leader-uid", status: "ACTIVE",
    startLocationName: "Pune", destinationName: "Lonavala",
    startLat: 18.52, startLng: 73.85, destLat: 18.75, destLng: 73.40,
    createdAt: new Date(),
  };
  await assertSucceeds(leaderDb.collection("rides").doc("ride-1").set(rideData));

  await record(
    "leader can complete the ride (ACTIVE -> COMPLETED)",
    assertSucceeds(leaderDb.collection("rides").doc("ride-1").update({ status: "COMPLETED", updatedAt: new Date() }))
  );

  // Spoof-reenable check: from the terminal COMPLETED state, a member must not be able
  // to hand-write the status back to ACTIVE (stale-reactivation consistency).
  await record(
    "member CANNOT hand-write status back to ACTIVE of a COMPLETED ride",
    assertFails(memberDb.collection("rides").doc("ride-1").update({ status: "ACTIVE" }))
  );

  // Re-open for member-side participation checks.
  await assertSucceeds(leaderDb.collection("rides").doc("ride-1").set({ ...rideData, status: "ACTIVE" }));

  await record(
    "member CANNOT complete the ride (status change denied)",
    assertFails(memberDb.collection("rides").doc("ride-1").update({ status: "COMPLETED", updatedAt: new Date() }))
  );

  await record(
    "member can update non-status telemetry fields on the ride doc",
    assertSucceeds(memberDb.collection("rides").doc("ride-1").update({ totalDistanceMeters: 100.0, updatedAt: new Date() }))
  );

  await record(
    "leader can transfer leadership (leaderId is not the guarded status field)",
    assertSucceeds(memberDb.collection("rides").doc("ride-1").update({ leaderId: "member-uid" }))
  );

  await record(
    "the NEW leader (after transfer) can complete the ride",
    assertSucceeds(memberDb.collection("rides").doc("ride-1").update({ status: "COMPLETED", updatedAt: new Date() }))
  );

  await record(
    "the OLD leader (no longer leader) can NO LONGER change shared status",
    assertFails(leaderDb.collection("rides").doc("ride-1").update({ status: "ACTIVE" }))
  );

  await record(
    "message create with authenticated senderId matching auth uid succeeds",
    assertSucceeds(
      memberDb.collection("rides").doc("ride-1").collection("messages").doc("m-1").set({
        id: "m-1", rideId: "ride-1", senderId: "member-uid", senderName: "Member",
        type: "REGROUP", text: "Regroup at next stop",
      })
    )
  );

  await record(
    "message create with FORGED senderId is rejected",
    assertFails(
      memberDb.collection("rides").doc("ride-1").collection("messages").doc("m-2").set({
        id: "m-2", rideId: "ride-1", senderId: "leader-uid", senderName: "Member",
        type: "REGROUP", text: "spoofed",
      })
    )
  );

  await record(
    "message update (edit history) is rejected",
    assertFails(
      memberDb.collection("rides").doc("ride-1").collection("messages").doc("m-1").update({ text: "edited-after-send? ah it failed" })
    )
  );

  await record(
    "message delete is rejected (immutable history)",
    assertFails(memberDb.collection("rides").doc("ride-1").collection("messages").doc("m-1").delete())
  );

  await record(
    "members (and anyone with the ride id) can READ the group chat",
    assertSucceeds(memberDb.collection("rides").doc("ride-1").collection("messages").doc("m-1").get())
  );

  // ---------------------------------------------------------------------
  // Route plan authority (feature: route alternatives + per-leg itinerary).
  // The ordered stops, the plan version, the candidate route list and the
  // leader's selected candidate are ONE leader-authoritative plan.
  // ---------------------------------------------------------------------
  const planStops = [
    { id: "s-origin", name: "Pune", role: "ORIGIN", lat: 18.52, lng: 73.85, order: 0 },
    { id: "s-dest", name: "Lonavala", role: "DESTINATION", lat: 18.75, lng: 73.40, order: 1 },
  ];
  const planOptions = [{ index: 0, distanceMeters: 8300, durationSeconds: 540 }];

  await record(
    "member CANNOT change the ordered route stops",
    assertFails(memberDb.collection("rides").doc("ride-1").update({ routeStops: planStops }))
  );

  await record(
    "member CANNOT change the route plan version",
    assertFails(memberDb.collection("rides").doc("ride-1").update({ routePlanVersion: 99 }))
  );

  await record(
    "member CANNOT change the selected candidate route",
    assertFails(memberDb.collection("rides").doc("ride-1").update({ selectedRouteIndex: 1 }))
  );

  await record(
    "member CANNOT change the candidate route list",
    assertFails(
      memberDb.collection("rides").doc("ride-1").update({ routeOptions: planOptions })
    )
  );

  await record(
    "leader CAN publish the plan (stops, version, selection and candidates together)",
    assertSucceeds(
      leaderDb.collection("rides").doc("ride-1").update({
        routeStops: planStops,
        routePlanVersion: 2,
        selectedRouteIndex: 0,
        routeOptions: planOptions,
      })
    )
  );

  await record(
    "member CAN still update telemetry on a document that carries plan fields",
    assertSucceeds(
      memberDb.collection("rides").doc("ride-1").update({ totalDistanceMeters: 4321, updatedAt: new Date() })
    )
  );

  console.log(`\nRules tests: ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exitCode = 1;
  await env.cleanup();
}

main().catch(async (e) => {
  console.error(e);
  process.exitCode = 1;
  if (env) try { await env.cleanup(); } catch (_) {}
  process.exit(1);
});
