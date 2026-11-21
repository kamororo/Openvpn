package com.renamer

/** 1.75 (worker C): MAP FIRST, THEN TEST — the speed-test
 *  sequencing gate, pure logic (no Android imports) so it is
 *  JVM-testable standalone (see TestMain175C.kt).
 *
 *  The panel used to run the geo/map fetch and the ping/speed/
 *  upload measurement SIMULTANEOUSLY, and the user reports the
 *  ping and upload/download speeds may then not show accurately.
 *  The order is now sequential:
 *
 *      geo lookup -> map tiles loaded -> THEN the speed test
 *
 *  The measurement must not start until the map has finished
 *  loading — or the map load has definitively failed and the
 *  placeholder is shown (a failed map never blocks the test:
 *  no map anchor at all, or a tile failure, both open the gate).
 *
 *  SpeedTestPanel.Panel drives one Sequence per run and starts
 *  its measurement ONLY through tryStartTest(): the tested code
 *  IS the shipped gate. All gate calls happen on the main thread
 *  (handler.post / the panel's own calls), so no synchronisation
 *  is needed; a stale settle (zoom/pan after the test started,
 *  or a cancelled run) is a no-op because tryStartTest/mapLoaded/
 *  mapFailed only fire from their exact phase, exactly once. */
object SpeedTestSequencePure {

    enum class Phase { IDLE, GEO_LOADING, MAP_LOADING, TEST_RUNNING, DONE }

    class Sequence {
        var phase: Phase = Phase.IDLE
            private set
        var testStarted: Boolean = false
            private set
        val events = mutableListOf<String>()

        /** Opening the panel (or refresh): the geo lookup starts;
         *  no test runs yet. */
        fun openPanel() {
            phase = Phase.GEO_LOADING
            testStarted = false
            events.clear()
            events += "geo-start"
        }

        /** Geo arrived. With a map anchor the map loads next
         *  (test must NOT start — returns false); without one
         *  there is no map to wait for, so the test may start
         *  (returns true). */
        fun geoArrived(hasMap: Boolean): Boolean {
            if (phase != Phase.GEO_LOADING) return false
            if (hasMap) {
                phase = Phase.MAP_LOADING
                events += "map-start"
                return false
            }
            phase = Phase.TEST_RUNNING
            events += "map-skipped"
            return true
        }

        /** Map tiles landed: the test may start now. */
        fun mapLoaded(): Boolean {
            if (phase != Phase.MAP_LOADING) return false
            phase = Phase.TEST_RUNNING
            events += "map-loaded"
            return true
        }

        /** Map load definitively failed (placeholder shown): the
         *  test may start now — a failed map never blocks it. */
        fun mapFailed(): Boolean {
            if (phase != Phase.MAP_LOADING) return false
            phase = Phase.TEST_RUNNING
            events += "map-failed"
            return true
        }

        /** The single gate the panel calls before starting the
         *  measurement: true exactly once, and only once the
         *  phase is TEST_RUNNING (geo done AND map settled or
         *  skipped). */
        fun tryStartTest(): Boolean {
            if (phase != Phase.TEST_RUNNING || testStarted) return false
            testStarted = true
            events += "test-start"
            return true
        }

        fun finishTest() {
            if (phase == Phase.TEST_RUNNING) {
                phase = Phase.DONE
                events += "test-done"
            }
        }

        fun cancel() {
            phase = Phase.IDLE
            testStarted = false
            events += "cancel"
        }
    }
}
