package net.powermatcher.fpai.agents;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.measure.Measurable;
import javax.measure.Measure;
import javax.measure.quantity.Duration;
import javax.measure.quantity.Energy;
import javax.measure.quantity.Power;
import javax.measure.unit.SI;

import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.MarketBasis;
import net.powermatcher.core.agent.framework.data.PricePoint;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.fpai.controller.PowerMatcherController;

import org.flexiblepower.efi.timeshifter.SequentialProfile;
import org.flexiblepower.efi.timeshifter.TimeShifterAllocation;
import org.flexiblepower.efi.timeshifter.TimeShifterRegistration;
import org.flexiblepower.efi.timeshifter.TimeShifterUpdate;
import org.flexiblepower.messaging.Connection;
import org.flexiblepower.rai.comm.AllocationStatusUpdate;
import org.flexiblepower.rai.comm.ControlSpaceRegistration;
import org.flexiblepower.rai.comm.ControlSpaceUpdate;
import org.flexiblepower.rai.values.Commodity;
import org.flexiblepower.rai.values.CommodityForecast;
import org.flexiblepower.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimeshifterAgent extends FpaiAgent {

    private static final Logger log = LoggerFactory.getLogger(TimeshifterAgent.class);
    private static final double EAGERNESS = 1.0;

    private TimeShifterRegistration registration;

    /** Last received {@link TimeShifterUpdate}. Null means no flexibility, must not run bid. */
    private TimeShifterUpdate lastTimeshifterUpdate = null;
    private CommodityForecast<Energy, Power> concatenatedCommodityForecast;

    /** Time when the machine started. Null means it's not runnig. */
    private Date profileStartTime = null;

    public TimeshifterAgent(ConfigurationService config,
                            Connection connection,
                            PowerMatcherController powerMatcherController) {
        super(config, connection, powerMatcherController);
    }

    @Override
    protected void handleControlSpaceRegistration(ControlSpaceRegistration message) {
        if (message instanceof TimeShifterRegistration) {
            if (registration == null) {
                registration = (TimeShifterRegistration) message;
                if (!registration.getSupportedCommodities().contains(Commodity.ELECTRICITY)) {
                    log.error("PowerMatcher cannot support appliances which do not support electricity, removing agent");
                    disconnected();
                }
            } else {
                log.error("Received multiple ControlSpaceRegistrations, ignoring...");
            }
        } else {
            log.error("Received unknown ContorlSpaceRegistration: " + message);
        }

    }

    @Override
    protected void handleControlSpaceUpdate(ControlSpaceUpdate message) {
        if (message instanceof TimeShifterUpdate) {
            lastTimeshifterUpdate = (TimeShifterUpdate) message;
            List<SequentialProfile> timeShifterProfiles = lastTimeshifterUpdate.getTimeShifterProfiles();
            CommodityForecast<Energy, Power> concatenatedCommodityForecast = timeShifterProfiles.get(0)
                                                                                                .getCommodityProfiles()
                                                                                                .get(Commodity.ELECTRICITY);
            for (int i = 1; i < timeShifterProfiles.size(); i++) {
                concatenatedCommodityForecast = concatenatedCommodityForecast.concat(timeShifterProfiles.get(i)
                                                                                                        .getCommodityProfiles()
                                                                                                        .get(Commodity.ELECTRICITY));
            }
            doBidUpdate();
        } else {
            log.error("Received unknown type of ControlSpaceUpdate: " + message);
        }
    }

    @Override
    protected void handleAllocationStatusUpdate(AllocationStatusUpdate message) {
        switch (message.getStatus()) {
        case ACCEPTED:
            // Great! No action.
            break;
        case REJECTED:
            // Go to no-flexibility state
            lastTimeshifterUpdate = null;
            profileStartTime = null;
            break;
        case PROCESSING:
            // Great! No action.
            break;
        case STARTED:
            // Start 'playing' the profile
            profileStartTime = message.getTimestamp();
            break;
        case FINISHED:
            // Great! Go to no-flexibility state
            lastTimeshifterUpdate = null;
            profileStartTime = null;
            break;
        }
        doBidUpdate();
    }

    @Override
    protected BidInfo constructBid() {
        MarketBasis marketBasis = getCurrentMarketBasis();
        if (marketBasis == null) {
            return null;
        }
        if (lastTimeshifterUpdate == null) {
            // No flexibility, must not run bid
            return new BidInfo(marketBasis, new PricePoint(0, 0));
        } else if (profileStartTime != null) {
            // Appliance is currently executing program
            return constructBidForRunningProgram(marketBasis);
        } else {
            // Flexibility bid
            return constructFlexibleBid(marketBasis);
        }

    }

    private BidInfo constructFlexibleBid(MarketBasis marketBasis) {
        // determine how far time has progressed in comparison to the start window (start after until start before)
        long startAfter = lastTimeshifterUpdate.getTimestamp().getTime();
        long endBefore = lastTimeshifterUpdate.getEndBefore().getTime();
        long startBefore = endBefore - concatenatedCommodityForecast.getTotalDuration().longValue(SI.MILLI(SI.SECOND));
        long startWindow = startBefore - startAfter;

        long timeSinceAllowableStart = getCurrentTimeMillis() - startAfter;
        double ratio = Math.pow(timeSinceAllowableStart / startWindow, EAGERNESS);

        double initialDemandWatt = getInitialDemand().doubleValue(SI.WATT);

        // if the initial demand is supply, the ratio flips
        if (initialDemandWatt < 0) {
            ratio = 1 - ratio;
        }

        // calculate the step price
        double priceRange = marketBasis.getMaximumPrice() - marketBasis.getMinimumPrice()
                            - (marketBasis.getPriceIncrement() * 2);
        double stepPrice = priceRange * ratio + marketBasis.getMinimumPrice() + marketBasis.getPriceIncrement();
        int normalizedStepPrice = marketBasis.toNormalizedPrice(stepPrice);

        // the bid depends on whether the initial demand is actually demand or is supply
        if (initialDemandWatt > 0) {
            return new BidInfo(marketBasis,
                               new PricePoint(normalizedStepPrice, initialDemandWatt),
                               new PricePoint(normalizedStepPrice, 0));
        } else {
            return new BidInfo(marketBasis, new PricePoint(normalizedStepPrice, 0), new PricePoint(normalizedStepPrice,
                                                                                                   initialDemandWatt));
        }
    }

    private BidInfo constructBidForRunningProgram(MarketBasis marketBasis) {
        Measurable<Duration> offset = Measure.valueOf(getCurrentTimeMillis() - profileStartTime.getTime(),
                                                      SI.MILLI(SI.SECOND));
        if (offset.longValue(SI.MILLI(SI.SECOND)) >= concatenatedCommodityForecast.getTotalDuration()
                                                                                  .longValue(SI.MILLI(SI.SECOND))) {
            // Program finished
            return new BidInfo(marketBasis, new PricePoint(0, 0));
        } else {
            // Program currently running
            Measurable<Power> demand = concatenatedCommodityForecast.getExpectedAverageAtOffset(offset);
            return new BidInfo(marketBasis, new PricePoint(0, demand.doubleValue(SI.WATT)));
        }
    }

    private Measurable<Power> getInitialDemand() {
        return lastTimeshifterUpdate.getTimeShifterProfiles()
                                    .get(0)
                                    .getCommodityProfiles()
                                    .get(Commodity.ELECTRICITY)
                                    .get(0)
                                    .getExpectedAverage();
    }

    @Override
    protected void priceUpdated() {
        // Do an allocation?
        if (lastTimeshifterUpdate != null && profileStartTime == null) {
            // We're in the flexibility period, the program hasn't started yet
            double demandForCurrentPrice = getLastBid().getDemand(getLastPriceInfo().getCurrentPrice());
            if (demandForCurrentPrice != 0) {
                // Let's start!
                final Date startTime = new Date(getCurrentTimeMillis());
                Date sequentialProfielStartTime = startTime;
                List<TimeShifterAllocation.SequentialProfileAllocation> seqAllocs = new ArrayList<TimeShifterAllocation.SequentialProfileAllocation>(lastTimeshifterUpdate.getTimeShifterProfiles()
                                                                                                                                                                          .size());
                for (SequentialProfile sp : lastTimeshifterUpdate.getTimeShifterProfiles()) {
                    seqAllocs.add(new TimeShifterAllocation.SequentialProfileAllocation(sp.getId(),
                                                                                        sequentialProfielStartTime));
                    sequentialProfielStartTime = TimeUtil.add(sequentialProfielStartTime, sp.getCommodityProfiles()
                                                                                            .get(Commodity.ELECTRICITY)
                                                                                            .getTotalDuration());
                }
                TimeShifterAllocation allocation = new TimeShifterAllocation(lastTimeshifterUpdate.getResourceId(),
                                                                             lastTimeshifterUpdate,
                                                                             startTime,
                                                                             false,
                                                                             seqAllocs);
                sendAllocation(allocation);
                // profileStartTime is set in the handleAllocationStatusUpdate method
            }
        }
    }
}
