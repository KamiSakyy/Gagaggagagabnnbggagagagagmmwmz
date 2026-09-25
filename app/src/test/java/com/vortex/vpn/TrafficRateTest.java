package com.vortex.vpn;

import static org.junit.Assert.assertEquals;

import com.vortex.vpn.core.TrafficRate;

import org.junit.Test;

/**
 * The dashboard must show a real speed. The user's complaint was "connected but the speed is 0",
 * so the arithmetic that turns the engine counters into bytes per second is pinned here.
 */
public class TrafficRateTest {

    @Test
    public void theFirstSampleDoesNotInventSpeed() {
        TrafficRate rate = new TrafficRate();
        rate.sample(5_000_000L, 9_000_000L, 1_000L);
        assertEquals(0L, rate.uplink());
        assertEquals(0L, rate.downlink());
    }

    @Test
    public void oneMegabyteInOneSecondIsOneMegabytePerSecond() {
        TrafficRate rate = new TrafficRate();
        rate.sample(0L, 0L, 0L);
        rate.sample(1_000_000L, 2_048_000L, 1_000L);
        assertEquals(1_000_000L, rate.uplink());
        assertEquals(2_048_000L, rate.downlink());
    }

    @Test
    public void aSlowerCadenceIsNormalisedToASecond() {
        TrafficRate rate = new TrafficRate();
        rate.sample(1_000L, 1_000L, 0L);
        rate.sample(501_000L, 1_001_000L, 2_000L);
        assertEquals(250_000L, rate.uplink());
        assertEquals(500_000L, rate.downlink());
    }

    @Test
    public void idleTrafficIsZeroNotTheOldValue() {
        TrafficRate rate = new TrafficRate();
        rate.sample(0L, 0L, 0L);
        rate.sample(4_000_000L, 8_000_000L, 1_000L);
        rate.sample(4_000_000L, 8_000_000L, 2_000L);
        assertEquals(0L, rate.uplink());
        assertEquals(0L, rate.downlink());
    }

    @Test
    public void anEngineRestartDoesNotProduceANegativeOrHugeSpeed() {
        TrafficRate rate = new TrafficRate();
        rate.sample(900_000_000L, 900_000_000L, 0L);
        rate.sample(1_000L, 2_000L, 1_000L);   // counters restarted
        assertEquals(0L, rate.uplink());
        assertEquals(0L, rate.downlink());
        rate.sample(101_000L, 202_000L, 2_000L);
        assertEquals(100_000L, rate.uplink());
        assertEquals(200_000L, rate.downlink());
    }

    @Test
    public void samplesCloserThanTheMinimumIntervalAreIgnored() {
        TrafficRate rate = new TrafficRate();
        rate.sample(0L, 0L, 0L);
        rate.sample(1_000L, 1_000L, 50L);      // too soon, ignored
        assertEquals(0L, rate.uplink());
        rate.sample(1_000L, 1_000L, 1_000L);
        assertEquals(1_000L, rate.uplink());
    }

    @Test
    public void resetClearsEverything() {
        TrafficRate rate = new TrafficRate();
        rate.sample(0L, 0L, 0L);
        rate.sample(5_000_000L, 5_000_000L, 1_000L);
        rate.reset();
        assertEquals(0L, rate.uplink());
        assertEquals(0L, rate.downlink());
        rate.sample(7_000_000L, 7_000_000L, 2_000L);
        assertEquals(0L, rate.uplink());
    }
}
