const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

test("Unauthenticated user cannot read or write user profiles", async () => {
  const unauthedDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthedDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthedDb.collection("users").doc(ALICE_UID).set({
      id: ALICE_UID,
      name: "Alice",
      email: "alice@example.com",
      createdAt: new Date(),
    })
  );
});

test("Authenticated user can write and read own profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      id: ALICE_UID,
      name: "Alice",
      email: "alice@example.com",
      createdAt: new Date(),
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
});

test("User cannot write profile for another user", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).set({
      id: BOB_UID,
      name: "Bob",
      email: "bob@example.com",
      createdAt: new Date(),
    })
  );
});

test("Authenticated user can create a ride as leader", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const rideRef = aliceDb.collection("rides").doc("ride_1");
  await assertSucceeds(
    rideRef.set({
      id: "ride_1",
      name: "Morning Ride",
      inviteCode: "RIDE123",
      leaderId: ALICE_UID,
      status: "LOBBY",
      startLocationName: "Pune",
      destinationName: "Lonavala",
      startLat: 18.52,
      startLng: 73.85,
      destLat: 18.75,
      destLng: 73.40,
      createdAt: new Date(),
    })
  );
});

test("Rider can join ride and read pack members", async () => {
  // Setup ride
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("rides").doc("ride_1").set({
      id: "ride_1",
      name: "Morning Ride",
      inviteCode: "RIDE123",
      leaderId: ALICE_UID,
      status: "LOBBY",
      startLocationName: "Pune",
      destinationName: "Lonavala",
      startLat: 18.52,
      startLng: 73.85,
      destLat: 18.75,
      destLng: 73.40,
      createdAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  // Bob joins as rider
  await assertSucceeds(
    bobDb.collection("rides").doc("ride_1").collection("riders").doc(BOB_UID).set({
      id: BOB_UID,
      userId: BOB_UID,
      rideId: "ride_1",
      name: "Bob",
      role: "MEMBER",
      status: "RIDING",
      latitude: 18.53,
      longitude: 73.86,
    })
  );

  // Bob can read riders list
  await assertSucceeds(
    bobDb.collection("rides").doc("ride_1").collection("riders").get()
  );
});
