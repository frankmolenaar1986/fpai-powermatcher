package net.powermatcher.fpai.agent.uncontrolled.test;

import junit.framework.TestCase;

/** Unit test for {@link net.powermatcher.fpai.agent.uncontrolled.UncontrolledAgent}. */
public class UncontrolledAgentTest extends TestCase {
    // private static final String RESOURCE_ID = "appliance-id";
    // private static final String CFG_PREFIX = "agent.agent1";
    // private static final MarketBasis MARKET_BASIS = new MarketBasis("Electricity", "EUR", 101, 0, 50, 1, 0);
    //
    // private UncontrolledAgent agent;
    // private ScheduledExecutorService executor;
    // SystemTimeService timeService;
    // private MockMatcherService parent;
    // private MockResourceManager<UncontrolledControlSpace> resourceManager;
    //
    // public void testControlSpaceUpdated() {
    // double[] durationValues = { 0.1, 0.5, 1.0, 2.0 };
    //
    // double[] measurementValueNumbers = { Double.MIN_VALUE,
    // Long.MIN_VALUE,
    // Integer.MIN_VALUE,
    // -1000,
    // -1,
    // -0.1,
    // 0,
    // 0.1,
    // 1,
    // 1000,
    // Integer.MAX_VALUE,
    // Long.MAX_VALUE,
    // Double.MAX_VALUE };
    //
    // // test the combination of the duration values, time units, measurement value numbers, and measurement units
    // for (double durationValue : durationValues) {
    // Measurable<Duration> measurementDuration = Measure.valueOf(durationValue, SECOND);
    //
    // for (double measurementValueNumber : measurementValueNumbers) {
    // // create a measurement value from the variations
    // Measurable<Energy> measurementValue = Measure.valueOf(measurementValueNumber, JOULE);
    //
    // // perform the actual test
    // testControlSpaceUpdated(measurementDuration, measurementValue);
    // }
    // }
    // }
    //
    // /**
    // * method performs the actual test of the uncontrolled agent with an energy profile based on the given measurement
    // * duration and measurement value
    // */
    // private void testControlSpaceUpdated(Measurable<Duration> measurementDuration, Measurable<Energy>
    // measurementValue) {
    // // send the control space and get the bid
    // BidInfo bid = sendControlSpace(measurementDuration, measurementValue);
    //
    // double expectedPowerWatt = measurementValue.doubleValue(JOULE) / measurementDuration.doubleValue(SECOND);
    // Measurable<Power> expectedPower = Measure.valueOf(expectedPowerWatt, WATT);
    //
    // // if the expected power can't be computed with double precision, we don't expect a bid
    // if (Double.isInfinite(expectedPowerWatt) || Double.isNaN(expectedPowerWatt)) {
    // // what do we expect?
    // } else {
    // // perform the normal test
    // Assert.assertNotNull("No bid received for energy profile with duration " + measurementDuration
    // + " with value"
    // + measurementValue, bid);
    //
    // double[] demand = bid.getDemand();
    //
    // // check the number of elements in the demand array
    // Assert.assertEquals("Bid does not span the market basis", MARKET_BASIS.getPriceSteps(), demand.length);
    //
    // // check the first power value to be equal to the expected power
    // BidAnalyzer.assertFlatBidWithValue(bid, expectedPower);
    // }
    // }
    //
    // /**
    // * sends a control space via the resource manager to the agent based on the given measurement duration and value
    // and
    // * returns the bid emitted based on that
    // */
    // private BidInfo sendControlSpace(Measurable<Duration> measurementDuration, Measurable<Energy> measurementValue) {
    // // the control space is valid from now and the profile ends now
    // Date startTime = new Date();
    //
    // // construct the profile and control space
    // EnergyProfile energyProfile = EnergyProfile.create().add(measurementDuration, measurementValue).build();
    // UncontrolledControlSpace controlSpace = new UncontrolledControlSpace(RESOURCE_ID, startTime, energyProfile);
    //
    // // send the control space
    // return sendControlSpace(controlSpace);
    // }
    //
    // private BidInfo sendControlSpace(UncontrolledControlSpace controlSpace) {
    // // let the resource manager send the new control space to the agent
    // resourceManager.updateControlSpace(controlSpace);
    // // then retreive the last bid from the agent
    // return parent.getLastBid(agent.getId(), 1000);
    // }
    //
    // public void testControlSpaceUpdatedGarbadgeIn() {
    // Date startTime = new Date(System.currentTimeMillis() - 1500);
    //
    // // test with Double.MAX_VALUE kWh for a second which can't be expressed in watts
    // Measurable<Energy> maxkWh = Measure.valueOf(Double.MAX_VALUE, KWH);
    // EnergyProfile energyProfile = EnergyProfile.create().add(Measure.valueOf(1, SECOND), maxkWh).build();
    // UncontrolledControlSpace controlSpace = new UncontrolledControlSpace(RESOURCE_ID, startTime, energyProfile);
    // BidInfo received = sendControlSpace(controlSpace);
    // // Invalid bid should return must-not-run bid
    // BidAnalyzer.assertFlatBidWithValue(received, Measure.valueOf(0, SI.WATT));
    //
    // // test with expired control space
    // BidInfo bid = sendControlSpace(new UncontrolledControlSpace(RESOURCE_ID,
    // startTime,
    // EnergyProfile.create()
    // .add(Measure.valueOf(1, SECOND),
    // Measure.valueOf(1, JOULE))
    // .build()));
    // // Should be flat bid
    // BidAnalyzer.assertFlatBidWithValue(bid, Measure.valueOf(0, WATT));
    // }
    //
    // public void testUpdatePriceInfo() {
    // agent.updatePriceInfo(new PriceInfo(MARKET_BASIS, 1));
    // Assert.assertNull("Allocation generated for uncontrollable resource", resourceManager.getLastAllocation(10));
    // }
    //
    // @Override
    // public void setUp() throws Exception {
    // Properties cfg = new Properties();
    // cfg.put(CFG_PREFIX + ".id", "agent1");
    // cfg.put(CFG_PREFIX + ".matcher.id", "concentrator1");
    // cfg.put(CFG_PREFIX + ".agent.bid.log.level", AgentConfiguration.FULL_LOGGING);
    // cfg.put(CFG_PREFIX + ".agent.price.log.level", AgentConfiguration.FULL_LOGGING);
    //
    // agent = new UncontrolledAgent(new PrefixedConfiguration(cfg, CFG_PREFIX));
    //
    // timeService = new SystemTimeService();
    // agent.setFpaiTimeService(new TimeService() {
    // @Override
    // public Date getTime() {
    // return new Date(timeService.currentTimeMillis());
    // }
    //
    // @Override
    // public long getCurrentTimeMillis() {
    // return timeService.currentTimeMillis();
    // }
    // });
    // agent.bind(timeService);
    //
    // executor = new MockScheduledExecutor(new PowerMatcherToFPAITimeService(timeService));
    // agent.bind(executor);
    //
    // resourceManager = MockResourceManager.create(RESOURCE_ID, UncontrolledControlSpace.class);
    // agent.bind(resourceManager);
    //
    // parent = new MockMatcherService();
    // agent.bind(parent);
    //
    // agent.updateMarketBasis(MARKET_BASIS);
    // }
    //
    // @Override
    // public void tearDown() throws Exception {
    // agent.unbind(executor);
    // agent.unbind(timeService);
    // agent.unbind(parent);
    //
    // executor.shutdown();
    // executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
    // }
}
