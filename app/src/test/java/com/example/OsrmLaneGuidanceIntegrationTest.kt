package com.example

import com.example.data.location.OsrmRoutingService
import com.example.data.model.LaneArrow
import com.example.data.model.LatLng
import com.example.data.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end lane guidance through the real service path.
 *
 * The body below is a **verbatim capture** of a live routing-engine response
 * (the steps that carry lane data, with the original overview geometry). It
 * is the response shape the app receives at runtime, so this test exercises
 * the same parse the network path uses.
 *
 * The capture is a European city route, because the engine only reports lane
 * data for roads that are mapped with lane markings. The app's own test
 * route (Pune to Lonavala) returns no lane data at all - the "no data, no
 * strip" case is covered below and in LaneGuidanceParsingTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsrmLaneGuidanceIntegrationTest {

    private val service = OsrmRoutingService()

    private val body = """
{
 "code": "Ok",
 "routes": [
  {
   "distance": 3380.4,
   "duration": 371,
   "geometry": "mfp_Ic_vpAB?ZEA_@?GGyBCeAEaBAOAW?YAGEoBGgBEaBAIE_B?GAKCICICMAKAI?EAG?OIcCGsCAQA_@AUGoCAY?EA_@M_FGgBA]?SIsCCq@AMAOCQEWGYEWIUQo@YgAIWc@{A]mAW_AQo@s@eCUs@O_@_A_CO_@iAoCCGMYP_@BCn@gAb@qALUFGNWVc@T]LSLQ@CVGh@y@DIRYVg@^i@PYP[|@qADGNURMXMZ_@NOLKHEH?F@FBFBDDLPDDTZTq@DSBUBMDu@Fs@Dy@TqC@KDa@BY`@}E@MFs@Dg@?IFq@Jg@BuBGyCCaAA}@AY?MAiA?qBBs@@SHcARqBNgA@KD[Fa@Fa@@E\\aBJc@T_ABI@IHYBIR}@nBeIPu@ZmAFUFUH]BMXoALg@VcAPm@?ABIJWJUW[SYGKcBcCKOIIi@m@UUk@i@c@[YUgAaAu@o@QOB_@?C@K?OJgBL_BPuCBUMICAUGc@KKIa@K@G@O@O",
   "legs": [
    {
     "distance": 3380.4,
     "duration": 371,
     "summary": "Test",
     "steps": [
      {
       "intersections": [
        {
         "out": 1,
         "in": 0,
         "entry": [
          false,
          true,
          true,
          false
         ],
         "bearings": [
          0,
          90,
          180,
          270
         ],
         "location": [
          13.388849,
          52.516865
         ]
        },
        {
         "lanes": [
          {
           "valid": false,
           "indications": [
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "straight",
            "right"
           ]
          },
          {
           "valid": false,
           "indications": [
            "none"
           ]
          }
         ],
         "out": 1,
         "in": 3,
         "entry": [
          true,
          true,
          true,
          false
         ],
         "bearings": [
          0,
          90,
          180,
          270
         ],
         "location": [
          13.390702,
          52.516985
         ]
        },
        {
         "out": 1,
         "in": 2,
         "entry": [
          true,
          true,
          false
         ],
         "bearings": [
          0,
          60,
          240
         ],
         "location": [
          13.393173,
          52.517185
         ]
        },
        {
         "out": 1,
         "in": 3,
         "entry": [
          true,
          true,
          true,
          false
         ],
         "bearings": [
          0,
          90,
          180,
          270
         ],
         "location": [
          13.395146,
          52.517349
         ]
        },
        {
         "out": 1,
         "in": 3,
         "entry": [
          true,
          true,
          false,
          false
         ],
         "bearings": [
          0,
          90,
          180,
          270
         ],
         "location": [
          13.396297,
          52.517422
         ]
        },
        {
         "out": 0,
         "in": 2,
         "entry": [
          true,
          true,
          false
         ],
         "bearings": [
          90,
          180,
          270
         ],
         "location": [
          13.397424,
          52.517494
         ]
        },
        {
         "out": 0,
         "in": 2,
         "entry": [
          true,
          true,
          false
         ],
         "bearings": [
          90,
          165,
          270
         ],
         "location": [
          13.398086,
          52.517537
         ]
        },
        {
         "out": 0,
         "in": 1,
         "entry": [
          true,
          false
         ],
         "bearings": [
          90,
          270
         ],
         "location": [
          13.398189,
          52.517543
         ]
        }
       ],
       "driving_side": "right",
       "maneuver": {
        "bearing_after": 82,
        "bearing_before": 172,
        "location": [
         13.388849,
         52.516865
        ],
        "modifier": "left",
        "type": "turn"
       },
       "geometry": "mep_Ii_vpAA_@?GGyBCeAEaBAOAW?YAGEoBGgBEaBAIE_B?GAKCICICMAKAI?EAG?OIcCGsCAQA_@AUGoCAY?EA_@M_FGgBA]?SIsC",
       "ref": "B 2; B 5",
       "name": "Unter den Linden",
       "mode": "driving",
       "weight": 66,
       "duration": 66,
       "distance": 690
      },
      {
       "intersections": [
        {
         "out": 1,
         "in": 2,
         "entry": [
          true,
          true,
          false,
          false
         ],
         "bearings": [
          45,
          135,
          225,
          315
         ],
         "location": [
          13.404811,
          52.519918
         ]
        },
        {
         "out": 1,
         "in": 3,
         "entry": [
          true,
          true,
          true,
          false
         ],
         "bearings": [
          60,
          135,
          240,
          315
         ],
         "location": [
          13.407084,
          52.518364
         ]
        },
        {
         "lanes": [
          {
           "valid": true,
           "indications": [
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "straight"
           ]
          }
         ],
         "out": 1,
         "in": 2,
         "entry": [
          false,
          true,
          false
         ],
         "bearings": [
          60,
          150,
          315
         ],
         "location": [
          13.408315,
          52.517475
         ]
        },
        {
         "lanes": [
          {
           "valid": false,
           "indications": [
            "left"
           ]
          },
          {
           "valid": false,
           "indications": [
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "straight"
           ]
          }
         ],
         "out": 1,
         "in": 2,
         "entry": [
          true,
          true,
          false
         ],
         "bearings": [
          60,
          150,
          330
         ],
         "location": [
          13.40839,
          52.517377
         ]
        },
        {
         "out": 0,
         "in": 1,
         "entry": [
          true,
          false
         ],
         "bearings": [
          150,
          330
         ],
         "location": [
          13.408621,
          52.517111
         ]
        }
       ],
       "driving_side": "right",
       "geometry": "oxp_IacypAP_@BCn@gAb@qALUFGNWVc@T]LSLQ@CVGh@y@DIRYVg@^i@PYP[|@qADGNURMXMZ_@NOLKHEH?F@FBFBDDLPDDTZ",
       "maneuver": {
        "bearing_after": 133,
        "bearing_before": 49,
        "location": [
         13.404811,
         52.519918
        ],
        "modifier": "right",
        "type": "turn"
       },
       "name": "Spandauer Stra\u00dfe",
       "mode": "driving",
       "weight": 55.1,
       "duration": 55.1,
       "distance": 487.6
      },
      {
       "intersections": [
        {
         "lanes": [
          {
           "valid": true,
           "indications": [
            "left"
           ]
          },
          {
           "valid": false,
           "indications": [
            "straight"
           ]
          },
          {
           "valid": false,
           "indications": [
            "straight"
           ]
          }
         ],
         "out": 1,
         "in": 0,
         "entry": [
          false,
          true,
          true
         ],
         "bearings": [
          30,
          120,
          210
         ],
         "location": [
          13.408452,
          52.516501
         ]
        },
        {
         "out": 1,
         "in": 3,
         "entry": [
          true,
          true,
          false,
          false
         ],
         "bearings": [
          30,
          120,
          210,
          300
         ],
         "location": [
          13.408703,
          52.516391
         ]
        },
        {
         "out": 1,
         "in": 2,
         "entry": [
          true,
          true,
          false
         ],
         "bearings": [
          15,
          105,
          285
         ],
         "location": [
          13.410529,
          52.516107
         ]
        },
        {
         "out": 0,
         "in": 2,
         "entry": [
          true,
          true,
          false
         ],
         "bearings": [
          105,
          195,
          285
         ],
         "location": [
          13.410594,
          52.516097
         ]
        },
        {
         "out": 0,
         "in": 2,
         "entry": [
          true,
          true,
          false,
          true
         ],
         "bearings": [
          105,
          180,
          285,
          345
         ],
         "location": [
          13.412325,
          52.515834
         ]
        },
        {
         "out": 0,
         "in": 2,
         "entry": [
          true,
          true,
          false,
          true
         ],
         "bearings": [
          90,
          165,
          270,
          345
         ],
         "location": [
          13.415027,
          52.515753
         ]
        }
       ],
       "driving_side": "right",
       "geometry": "ccp_IyyypATq@DSBUBMDu@Fs@Dy@TqC@KDa@BY`@}E@MFs@Dg@?IFq@Jg@BuBGyCCaAA}@AY?MAiA?qBBs@@SHcARqBNgA@KD[",
       "maneuver": {
        "bearing_after": 125,
        "bearing_before": 215,
        "location": [
         13.408452,
         52.516501
        ],
        "modifier": "left",
        "type": "turn"
       },
       "name": "Stralauer Stra\u00dfe",
       "mode": "driving",
       "weight": 67.6,
       "duration": 67.6,
       "distance": 667.6
      },
      {
       "intersections": [
        {
         "lanes": [
          {
           "valid": true,
           "indications": [
            "straight",
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "straight"
           ]
          },
          {
           "valid": false,
           "indications": [
            "right"
           ]
          }
         ],
         "out": 1,
         "in": 3,
         "entry": [
          false,
          true,
          true,
          false
         ],
         "bearings": [
          15,
          105,
          195,
          285
         ],
         "location": [
          13.417996,
          52.515468
         ]
        },
        {
         "lanes": [
          {
           "valid": true,
           "indications": [
            "straight",
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "straight"
           ]
          }
         ],
         "out": 1,
         "in": 3,
         "entry": [
          true,
          true,
          false,
          false
         ],
         "bearings": [
          15,
          120,
          195,
          285
         ],
         "location": [
          13.418174,
          52.515428
         ]
        }
       ],
       "driving_side": "right",
       "geometry": "u|o_Iou{pAFa@Fa@@E\\aBJc@T_ABI@IHYBIR}@nBeIPu@ZmAFUFUH]BMXoALg@VcAPm@?ABIJW",
       "maneuver": {
        "bearing_after": 109,
        "bearing_before": 106,
        "location": [
         13.417996,
         52.515468
        ],
        "modifier": "straight",
        "type": "new name"
       },
       "name": "Holzmarktstra\u00dfe",
       "mode": "driving",
       "weight": 46.3,
       "duration": 46.3,
       "distance": 468.4
      },
      {
       "intersections": [
        {
         "lanes": [
          {
           "valid": true,
           "indications": [
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "left"
           ]
          },
          {
           "valid": false,
           "indications": [
            "straight"
           ]
          },
          {
           "valid": false,
           "indications": [
            "straight",
            "right"
           ]
          }
         ],
         "out": 1,
         "in": 3,
         "entry": [
          false,
          true,
          true,
          false
         ],
         "bearings": [
          30,
          135,
          195,
          300
         ],
         "location": [
          13.424029,
          52.513433
         ]
        },
        {
         "lanes": [
          {
           "valid": true,
           "indications": [
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "left"
           ]
          },
          {
           "valid": false,
           "indications": [
            "straight"
           ]
          },
          {
           "valid": false,
           "indications": [
            "straight",
            "right"
           ]
          }
         ],
         "out": 0,
         "in": 3,
         "entry": [
          true,
          true,
          false,
          false
         ],
         "bearings": [
          30,
          135,
          240,
          315
         ],
         "location": [
          13.424138,
          52.513372
         ]
        },
        {
         "lanes": [
          {
           "valid": false,
           "indications": [
            "left"
           ]
          },
          {
           "valid": true,
           "indications": [
            "straight"
           ]
          },
          {
           "valid": true,
           "indications": [
            "none"
           ]
          }
         ],
         "out": 0,
         "in": 2,
         "entry": [
          true,
          false,
          false,
          true
         ],
         "bearings": [
          45,
          135,
          210,
          300
         ],
         "location": [
          13.424283,
          52.513489
         ]
        }
       ],
       "driving_side": "right",
       "geometry": "}oo_Ie{|pAJUW[SYGKcBcCKOIIi@m@UUk@i@c@[YUgAaAu@o@QO",
       "maneuver": {
        "bearing_after": 132,
        "bearing_before": 120,
        "location": [
         13.424029,
         52.513433
        ],
        "modifier": "left",
        "type": "turn"
       },
       "name": "Lichtenberger Stra\u00dfe",
       "mode": "driving",
       "weight": 36.7,
       "duration": 36.7,
       "distance": 334
      }
     ]
    }
   ]
  }
 ]
}
    """

    private fun parsed() = service.parseRouteResponse(
        body = body,
        waypoints = listOf(
            LatLng(52.517037, 13.388860),
            LatLng(52.515941, 13.429707)
        )
    )!!

    @Test
    fun `the richer maneuver reaches the parsed steps`() {
        val route = parsed()
        assertEquals(5, route.steps.size)
        assertEquals(ManeuverType.TURN_LEFT, route.steps[0].maneuver)
        assertEquals(ManeuverType.TURN_RIGHT, route.steps[1].maneuver)
        assertEquals(ManeuverType.TURN_LEFT, route.steps[2].maneuver)
        assertEquals(ManeuverType.NEW_NAME, route.steps[3].maneuver)
        assertEquals(ManeuverType.TURN_LEFT, route.steps[4].maneuver)
        assertTrue(route.steps[2].instruction.startsWith("Turn left onto"))
        assertTrue(route.steps[3].instruction.startsWith("Continue onto"))
    }

    @Test
    fun `lanes are shown only for the manoeuvre they belong to`() {
        val steps = parsed().steps
        // The first two steps carry lane data, but only on a LATER
        // intersection - those lanes belong to the next manoeuvre and must
        // not be shown with this one.
        assertNull(steps[0].lanes)
        assertNull(steps[1].lanes)
        // This turn's own node carries the lanes.
        val lanes = steps[2].lanes
        assertNotNull(lanes)
        assertEquals(3, lanes!!.laneCount)
        assertEquals(LaneArrow.LEFT, lanes.lanes[0].primaryArrow)
        assertTrue(lanes.lanes[0].recommended)
        assertTrue(!lanes.lanes[1].recommended)
    }

    @Test
    fun `a lane that may be used for several purposes keeps all of them`() {
        val lanes = parsed().steps[3].lanes
        assertNotNull(lanes)
        assertEquals(3, lanes!!.laneCount)
        assertEquals(listOf(LaneArrow.STRAIGHT, LaneArrow.LEFT), lanes.lanes[0].arrows)
        assertTrue(lanes.lanes[0].recommended)
        assertTrue(lanes.lanes[1].recommended)
        assertTrue(!lanes.lanes[2].recommended)
    }

    @Test
    fun `the engine's own valid flag decides the highlight`() {
        val lanes = parsed().steps[4].lanes
        assertNotNull(lanes)
        assertEquals(4, lanes!!.laneCount)
        assertEquals(2, lanes.recommendedLanes.size)
        assertTrue(lanes.recommendedLanes.all { it.primaryArrow == LaneArrow.LEFT })
    }

    @Test
    fun `a step without lane data carries no guidance`() {
        // The engine only reports lanes for roads that are mapped with lane
        // markings; a step without them must carry no guidance at all.
        val route = service.parseRouteResponse(
            body = """
                {"code":"Ok","routes":[{"distance":100.0,"duration":60.0,"geometry":"_p~iF~ps|U_ulL",
                "legs":[{"distance":100.0,"duration":60.0,"summary":"x","steps":[
                  {"distance":100.0,"duration":60.0,"name":"Main Road",
                   "maneuver":{"type":"turn","modifier":"left","location":[13.4,52.5]},
                   "intersections":[{"location":[13.4,52.5],"bearings":[30,120],"entry":[false,true]}]}
                ]}]}]}
            """.trimIndent(),
            waypoints = listOf(LatLng(52.5, 13.4), LatLng(52.51, 13.41))
        )!!
        assertNull(route.steps[0].lanes)
        assertEquals(ManeuverType.TURN_LEFT, route.steps[0].maneuver)
    }
}
