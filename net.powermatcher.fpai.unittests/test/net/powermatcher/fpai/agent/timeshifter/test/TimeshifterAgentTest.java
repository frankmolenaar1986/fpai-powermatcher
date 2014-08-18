package net.powermatcher.fpai.agent.timeshifter.test;

import static javax.measure.unit.NonSI.HOUR;
import static javax.measure.unit.NonSI.MINUTE;
import static javax.measure.unit.SI.JOULE;
import static javax.measure.unit.SI.SECOND;
import static javax.measure.unit.SI.WATT;
import static net.powermatcher.fpai.test.BidAnalyzer.assertFlatBidWithValue;

import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.measure.Measurable;
import javax.measure.Measure;
import javax.measure.quantity.Duration;
import javax.measure.quantity.Energy;
import javax.measure.quantity.Power;

import junit.framework.Assert;
import junit.framework.TestCase;
import net.powermatcher.core.agent.framework.config.AgentConfiguration;
import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.MarketBasis;
import net.powermatcher.core.agent.framework.data.PriceInfo;
import net.powermatcher.core.configurable.PrefixedConfiguration;
import net.powermatcher.core.object.config.ActiveObjectConfiguration;
import net.powermatcher.fpai.agent.timeshifter.TimeshifterAgent;
import net.powermatcher.fpai.test.BidAnalyzer;
import net.powermatcher.fpai.test.MockMatcherService;
import net.powermatcher.fpai.test.MockResourceManager;
import net.powermatcher.fpai.test.MockScheduledExecutor;
import net.powermatcher.fpai.test.MockTimeService;

import org.flexiblepower.rai.Allocation;
import org.flexiblepower.rai.TimeShifterControlSpace;
import org.flexiblepower.rai.values.EnergyProfile;
import org.flexiblepower.time.TimeUtil;

public class TimeshifterAgentTest extends TestCase {
    private static final String RESOURCE_ID = "appliance-id";
    private static final String CFG_PREFIX = "agent.agent1";
    private static final MarketBasis MARKET_BASIS = new MarketBasis("Electricity", "EUR", 100, 0, 50, 1, 0);
    private static final PriceInfo MAX_PRICE = new PriceInfo(MARKET_BASIS, MARKET_BASIS.getMaximumPrice());
    private static final Measurable<Power> ZERO_POWER = Measure.valueOf(0, WATT);
    private static final Measurable<Energy> ZERO_ENERGY = Measure.valueOf(0, JOULE);

    private static final double[] PROFILE_VALUES = { 100, 1000, 500, 1000, 400, 300, 300, 300, 300, 300 };
    private static final Measurable<Duration> PROFILE_ElEMENT_DURATION = Measure.valueOf(10, MINUTE);
    private static final EnergyProfile DEMAND_PROFILE = buildProfile(1, PROFILE_VALUES, PROFILE_ElEMENT_DURATION);
    private static final EnergyProfile SUPPLY_PROFILE = buildProfile(-1, PROFILE_VALUES, PROFILE_ElEMENT_DURATION);

    private static final Measurable<Duration> START_WINDOW = Measure.valueOf(1, HOUR);

    /** the parent matcher of the agent */
    private MockMatcherService parent;
    /** the agent under test */
    private TimeshifterAgent agent;
    /** the manager controlled by the agent */
    private MockResourceManager<TimeShifterControlSpace> manager;

    private MockScheduledExecutor executor;
    private MockTimeService timeService;

    public void testMultipleRuns() {
        EnergyProfile profile = DEMAND_PROFILE;

        timeService.setAbsoluteTime(24 * 60 * 60 * 1000);

        testWaitUntilMustRun(profile);
        timeService.stepInTime(1, TimeUnit.MINUTES);

        testWaitUntilMustRun(profile);
        timeService.stepInTime(1, TimeUnit.MINUTES);

        testWaitUntilMustRun(profile);
        timeService.stepInTime(1, TimeUnit.MINUTES);
    }

    private void testWaitUntilMustRun(EnergyProfile profile) {
        Date from = timeService.getDate();
        Date deadline = TimeUtil.add(from, START_WINDOW);
        Date end = TimeUtil.add(deadline, profile.getDuration());

        TimeShifterControlSpaceBuilder builder = new TimeShifterControlSpaceBuilder();
        TimeShifterControlSpace cs1 = builder.setApplianceId(RESOURCE_ID)
                                             .setValidFrom(from)
                                             .setValidThru(end)
                                             .setExpirationTime(deadline)
                                             .setEnergyProfile(profile)
                                             .setStartAfter(from)
                                             .setStartBefore(deadline)
                                             .build();

        stepInTime(1, TimeUnit.MINUTES);

        // step through time until deadline
        for (; nowIsBefore(deadline); timeService.stepInTime(1, TimeUnit.MINUTES)) {
            updateControlSpaceAndPrice(cs1, MAX_PRICE);

            // assure step bid issued
            assertStepBid(lastBid(), profile);
            // assert the device isn't commanded to start yet
            // (no allocation is sent, ends in the past or its total energy is zero)
            Allocation lastAllocation = lastAllocation();
            assertTrue(lastAllocation == null || getEnd(lastAllocation).before(timeService.getDate())
                       || lastAllocation.getEnergyProfile().getTotalEnergy().equals(ZERO_ENERGY));
        }

        // assert that a bid is issued at/before the deadline
        stepInTime(1, TimeUnit.MINUTES);
        updateControlSpaceAndPrice(cs1, MAX_PRICE);

        Date start = deadline;
        assertStarted(manager.getCurrentControlSpace(), lastAllocation());

        // step through time until end
        for (; nowIsNotAfter(end); stepInTime(1, TimeUnit.MINUTES)) {
            updateControlSpaceAndPrice(cs1, MAX_PRICE);

            // assert profile is reflected in must run bids into the cluster
            assertFlatBidWithValue(lastBid(), getCurrentDemand(timeService.getDate(), start, profile));

            // assert that either no new allocation is issued or the remaining energy in the allocation equals the
            // remaining energy from the original profile
            Allocation lastAllocation = lastAllocation();
            assertTrue("Last allocation is not null and its total energy is incorrect",
                       lastAllocation == null || subProfile(lastAllocation.getEnergyProfile(), deadline, end).getTotalEnergy()
                                                                                                             .equals(subProfile(profile,
                                                                                                                                deadline,
                                                                                                                                end).getTotalEnergy()));
        }

        stepInTime(1, TimeUnit.MINUTES);
        updateControlSpaceAndPrice(null, MAX_PRICE);
        assertFlatBidWithValue(lastBid(), ZERO_POWER);
    }

    public void testMustRun() {
        EnergyProfile profile = DEMAND_PROFILE;

        // start a day after the Unix epoch
        long testStartTime = 24 * 60 * 60 * 1000;
        timeService.setAbsoluteTime(testStartTime);

        Date validFrom = new Date(testStartTime);
        Date deadline = TimeUtil.add(validFrom, START_WINDOW);
        Measurable<Duration> profileDuration = profile.getDuration();
        Date validThru = TimeUtil.add(deadline, profileDuration);

        TimeShifterControlSpaceBuilder builder = new TimeShifterControlSpaceBuilder();
        builder.setApplianceId(RESOURCE_ID);

        builder.setValidFrom(validFrom);
        builder.setValidThru(validThru);
        builder.setExpirationTime(deadline);

        builder.setEnergyProfile(profile);
        builder.setStartAfter(validFrom);
        builder.setStartBefore(deadline);

        TimeShifterControlSpace controlSpace = builder.build();

        // set unattractive price
        // and update the control space
        agent.updatePriceInfo(new PriceInfo(MARKET_BASIS, MARKET_BASIS.getMaximumPrice()));
        manager.updateControlSpace(controlSpace);

        // now < startAfter
        // assert that the resource can not start yet (must-off bid and no allocation yet)
        executor.executePending();
        BidInfo bid = lastBid();
        BidAnalyzer.assertFlatBidWithValue(bid, ZERO_POWER);
        Assert.assertNull(lastAllocation());

        // startBefore == now + 1 minute
        // assert that the resource has started (allocation and must-run bid with initial power exists)
        timeService.setAbsoluteTime(deadline.getTime());
        timeService.stepInTime(Measure.valueOf(1, MINUTE));
        Date start = timeService.getDate();
        executor.executePending();
        bid = lastBid();
        BidAnalyzer.assertFlatBidWithValue(bid, Measure.valueOf(PROFILE_VALUES[0], WATT));
        assertStarted(controlSpace, lastAllocation());

        // We don't check if the device was started in time, because starting at the startBefore time is actually to
        // late. This is acceptable (the resource manager should also act in this situation).

        // check that the agent correctly behaves during the execution of the profile
        assertCorrectMustRunBidding(profile, start, TimeUtil.add(start, profileDuration));
    }

    public void testDemand() throws InterruptedException {
        testNormal(DEMAND_PROFILE, true);
    }

    public void testSupply() throws InterruptedException {
        testNormal(SUPPLY_PROFILE, false);
    }

    private void testNormal(EnergyProfile profile, boolean isDemand) {
        // start a day after the Unix epoch
        long testStartTime = 24 * 60 * 60 * 1000;

        Date validFrom = new Date(testStartTime);
        Date after = validFrom;
        Date before = TimeUtil.add(validFrom, START_WINDOW);
        Measurable<Duration> profileDuration = profile.getDuration();
        Date validThru = TimeUtil.add(before, profileDuration);

        TimeShifterControlSpaceBuilder builder = new TimeShifterControlSpaceBuilder();
        builder.setApplianceId(RESOURCE_ID);

        builder.setValidFrom(validFrom);
        builder.setValidThru(validThru);
        builder.setExpirationTime(before);

        builder.setEnergyProfile(profile);
        builder.setStartAfter(after);
        builder.setStartBefore(before);

        TimeShifterControlSpace controlSpace = builder.build();

        // set unattractive price
        // and update the control space
        double unattractivePrice = isDemand ? MARKET_BASIS.getMaximumPrice() : MARKET_BASIS.getMinimumPrice();
        agent.updatePriceInfo(new PriceInfo(MARKET_BASIS, unattractivePrice));
        manager.updateControlSpace(controlSpace);

        // now < startAfter
        // assert that the resource can not start yet (must-off bid and no allocation yet)
        executor.executePending();
        BidInfo bid = lastBid();
        BidAnalyzer.assertFlatBidWithValue(bid, ZERO_POWER);
        Assert.assertNull(lastAllocation());

        // startAfter = now < startBefore
        // get the bid and assert it's initially still flat and that there is no allocation yet
        timeService.setAbsoluteTime(after.getTime());
        executor.executePending();
        bid = lastBid();
        BidAnalyzer.assertFlatBidWithValue(bid, ZERO_POWER);
        Assert.assertNull(lastAllocation());

        // startAfter < now < startBefore
        // progress time to half-way and assert it's a step and that there is no allocation yet
        timeService.stepInTime(Measure.valueOf(START_WINDOW.doubleValue(SECOND) / 2, SECOND));
        executor.executePending();
        bid = lastBid();
        assertStepBid(bid, profile);
        Assert.assertNull(lastAllocation());
        double stepPrice = BidAnalyzer.getStepPrice(bid);

        // startAfter < now < startBefore
        // progress time to three-quarters and assert it's a step, that there is no allocation yet and that maximum
        // accepted price is moving up or down (depending on whether it is supply or demand
        timeService.stepInTime(Measure.valueOf(START_WINDOW.doubleValue(SECOND) / 4, SECOND));
        executor.executePending();
        bid = lastBid();
        assertStepBid(bid, profile);
        Assert.assertNull(lastAllocation());
        if (isDemand) {
            Assert.assertTrue(stepPrice < BidAnalyzer.getStepPrice(bid));
        } else {
            Assert.assertTrue(stepPrice > BidAnalyzer.getStepPrice(bid));
        }

        // price to high, assert no allocation and still same step bid
        stepPrice = BidAnalyzer.getStepPrice(bid);
        double price = isDemand ? unattractivePrice : MARKET_BASIS.getMinimumPrice();
        for (; isDemand ? price > stepPrice : price < stepPrice; price -= isDemand ? 1 : -1) {
            agent.updatePriceInfo(new PriceInfo(MARKET_BASIS, price));
            executor.executePending();

            bid = lastBid();
            assertStepBid(bid, profile);
            Assert.assertNull(lastAllocation());
        }

        // price low enough, assert started (allocation and must-run bid with initial power exists)
        Date startTime = timeService.getDate();
        Date endTime = TimeUtil.add(startTime, profileDuration);

        // we lower/raise the price by one extra, to give some room for price rounding in the PowerMatcher core
        price = isDemand ? price - 1 : price + 1;
        agent.updatePriceInfo(new PriceInfo(MARKET_BASIS, price));
        executor.executePending();

        Allocation allocation = lastAllocation();
        assertStarted(controlSpace, allocation);
        assertStartedInTime(controlSpace, allocation);
        bid = lastBid();
        double initialDemand = isDemand ? PROFILE_VALUES[0] : -PROFILE_VALUES[0];
        BidAnalyzer.assertFlatBidWithValue(bid, Measure.valueOf(initialDemand, WATT));

        assertCorrectMustRunBidding(profile, startTime, endTime);
    }

    private void assertStepBid(BidInfo bid, EnergyProfile profile) {
        if (profile.getTotalEnergy().doubleValue(JOULE) > 0) {
            BidAnalyzer.assertStepBid(bid, Measure.valueOf(PROFILE_VALUES[0], WATT), ZERO_POWER, null);
        } else {
            BidAnalyzer.assertStepBid(bid, ZERO_POWER, Measure.valueOf(-PROFILE_VALUES[0], WATT), null);
        }
    }

    private void assertCorrectMustRunBidding(EnergyProfile profile, Date startTime, Date endTime) {
        // progress time and expose the agent to various prices
        // and assert no new allocations are sent and the must-run bid follows the profile
        for (; nowIsBefore(endTime); timeService.stepInTime(1, TimeUnit.MINUTES)) {
            for (double p = MARKET_BASIS.getMinimumPrice(); p <= MARKET_BASIS.getMaximumPrice(); p += 1.5) {
                agent.updatePriceInfo(new PriceInfo(MARKET_BASIS, p));
                executor.executePending();

                assertStarted(lastAllocation());

                BidAnalyzer.assertFlatBidWithValue(lastBid(),
                                                   getCurrentDemand(timeService.getDate(), startTime, profile));
            }
        }

        // progress time even further and assert the bid ends with a must-off bid
        timeService.stepInTime(1, TimeUnit.MINUTES);
        executor.executePending();
        BidAnalyzer.assertFlatBidWithValue(lastBid(), ZERO_POWER);
        assertStopped(lastAllocation());
    }

    private void assertStarted(Allocation allocation) {
        // assert an allocation exists
        Assert.assertNotNull(allocation);

        // assert the time is the current time
        Date start = allocation.getStartTime();
        Date end = getEnd(allocation);
        Date now = timeService.getDate();
        Assert.assertTrue("Allocation starts in the future", now.getTime() >= start.getTime());
        Assert.assertTrue("Allocation ends in the past", now.getTime() <= end.getTime());
    }

    private void assertStarted(TimeShifterControlSpace controlSpace, Allocation allocation) {
        assertStarted(allocation);

        // assert the appliance id and control space are there
        Assert.assertEquals(manager.getResourceId(), allocation.getResourceId());
        Assert.assertEquals(controlSpace.getId(), allocation.getControlSpaceId());

        // assert the energy profile is exactly as in the control space
        Assert.assertEquals(controlSpace.getEnergyProfile(), allocation.getEnergyProfile());
    }

    // assert the start time is in the allowed window
    private void assertStartedInTime(TimeShifterControlSpace controlSpace, Allocation allocation) {
        Date startTime = allocation.getStartTime();
        Date startAfter = controlSpace.getStartAfter();
        Date startBefore = controlSpace.getStartBefore();

        Assert.assertTrue(startAfter.before(startTime) || startAfter.equals(startTime));
        Assert.assertTrue(startBefore.after(startTime) || startBefore.equals(startTime));
    }

    private void assertStopped(Allocation allocation) {
        if (allocation == null) {
            return;
        }

        // assert the allocation ends in the past
        Assert.assertTrue(TimeUtil.add(allocation.getStartTime(), allocation.getEnergyProfile().getDuration())
                                  .before(timeService.getDate()));
    }

    private void stepInTime(long value, TimeUnit unit) {
        executor.executePending();
        timeService.stepInTime(1, unit);
        executor.executePending();
    }

    private void updateControlSpaceAndPrice(TimeShifterControlSpace controlSpace, PriceInfo price) {
        executor.executePending();

        manager.updateControlSpace(controlSpace);
        agent.updatePriceInfo(price);

        executor.executePending();
    }

    private boolean nowIsNotAfter(Date date) {
        return nowIsBefore(date) || timeService.getDate().equals(date);
    }

    private boolean nowIsBefore(Date date) {
        return timeService.getDate().before(date);
    }

    private Allocation lastAllocation() {
        return manager.getLastAllocation();
    }

    private BidInfo lastBid() {
        return parent.getLastBid(agent.getId());
    }

    private Date getEnd(Allocation allocation) {
        return TimeUtil.add(allocation.getStartTime(), allocation.getEnergyProfile().getDuration());
    }

    private EnergyProfile subProfile(EnergyProfile profile, Date deadline, Date end) {
        return profile.subprofile(TimeUtil.difference(deadline, timeService.getDate()),
                                  TimeUtil.difference(timeService.getDate(), end));
    }

    private Measurable<Power> getCurrentDemand(Date now, Date profileStart, EnergyProfile profile) {
        Measurable<Duration> offset = TimeUtil.difference(profileStart, now);
        return profile.getElementForOffset(offset).getAveragePower();
    }

    @Override
    public void setUp() throws Exception {
        Properties cfg = new Properties();
        cfg.put(CFG_PREFIX + ".id", "agent1");
        cfg.put(CFG_PREFIX + ".matcher.id", "concentrator1");
        cfg.put(CFG_PREFIX + ".agent.bid.log.level", AgentConfiguration.FULL_LOGGING);
        cfg.put(CFG_PREFIX + ".agent.price.log.level", AgentConfiguration.FULL_LOGGING);
        cfg.put(CFG_PREFIX + "." + ActiveObjectConfiguration.UPDATE_INTERVAL_PROPERTY, "1");

        agent = new TimeshifterAgent(new PrefixedConfiguration(cfg, CFG_PREFIX));

        timeService = new MockTimeService(new Date(0));
        agent.setFpaiTimeService(timeService);
        agent.bind(timeService);

        executor = new MockScheduledExecutor(timeService);
        agent.bind(executor);

        manager = MockResourceManager.create(RESOURCE_ID, TimeShifterControlSpace.class);
        agent.bind(manager);

        parent = new MockMatcherService();
        agent.bind(parent);

        agent.updateMarketBasis(MARKET_BASIS);
    }

    @Override
    public void tearDown() throws Exception {
        agent.unbind(executor);
        agent.unbind(timeService);
        agent.unbind(parent);

        executor.shutdown();
        executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static EnergyProfile
            buildProfile(int multiplier, double[] powerValues, Measurable<Duration> elementDuration) {
        EnergyProfile.Builder builder = EnergyProfile.create().setDuration(elementDuration);

        for (double profileValue : powerValues) {
            builder.add(Measure.valueOf(profileValue * multiplier * elementDuration.doubleValue(SECOND), JOULE));
        }

        return builder.build();
    }
}
