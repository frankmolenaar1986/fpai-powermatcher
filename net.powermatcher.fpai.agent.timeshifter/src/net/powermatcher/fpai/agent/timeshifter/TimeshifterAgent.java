package net.powermatcher.fpai.agent.timeshifter;

import static javax.measure.unit.SI.WATT;

import java.util.Date;

import javax.measure.Measurable;
import javax.measure.Measure;
import javax.measure.quantity.Duration;
import javax.measure.quantity.Power;

import net.powermatcher.core.agent.framework.config.AgentConfiguration;
import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.MarketBasis;
import net.powermatcher.core.agent.framework.data.PriceInfo;
import net.powermatcher.core.agent.framework.data.PricePoint;
import net.powermatcher.core.agent.framework.log.LoggingConnectorService;
import net.powermatcher.core.agent.framework.service.AgentConnectorService;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.core.object.config.IdentifiableObjectConfiguration;
import net.powermatcher.core.scheduler.service.SchedulerConnectorService;
import net.powermatcher.core.scheduler.service.TimeConnectorService;
import net.powermatcher.fpai.agent.FPAIAgent;
import net.powermatcher.fpai.agent.timeshifter.TimeshifterAgent.Config;

import org.flexiblepower.rai.Allocation;
import org.flexiblepower.rai.TimeShifterControlSpace;
import org.flexiblepower.rai.values.EnergyProfile.Element;
import org.flexiblepower.time.TimeUtil;

import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

@Component(designateFactory = Config.class)
public class TimeshifterAgent extends FPAIAgent<TimeShifterControlSpace> implements
                                                                        AgentConnectorService,
                                                                        LoggingConnectorService,
                                                                        TimeConnectorService,
                                                                        SchedulerConnectorService {

    private static final double DEFAULT_EAGERNESS = 1.0 / 0.3;

    /**
     * Specifies how eager an agent is to get started. A low value indicates that the agent is eager to get started, and
     * vice versa. An eagerness of 1 will result in a linearly decreasing price for which the resource will be turned
     * on.
     */
    private double eagerness = DEFAULT_EAGERNESS;

    private TimeShifterControlSpace lastControlSpace;
    private Allocation lastAllocation;

    public TimeshifterAgent() {
    }

    public TimeshifterAgent(ConfigurationService configuration) {
        super(configuration);

        Config config = Configurable.createConfigurable(Config.class, configuration.getProperties());
        eagerness = config.eagerness();
    }

    @Override
    public Allocation createAllocation(BidInfo lastBid, PriceInfo price, TimeShifterControlSpace controlSpace) {
        Date now = new Date(getTimeSource().currentTimeMillis());

        // if the control space is updated consider the previous shiftable process preempted, start afresh
        if (!controlSpace.equals(lastControlSpace)) {
            lastAllocation = null;
            lastControlSpace = controlSpace;
        }

        // if the lastAllocation is finished (its end time is before now) consider the shiftable process finished
        if ((lastAllocation != null && now.after(TimeUtil.add(lastAllocation.getStartTime(),
                                                              lastAllocation.getEnergyProfile().getDuration())))) {
            lastAllocation = null;
        }
        // if no allocation has been determined yet (the shiftable process hasn't been started)
        // and the price given the last bid dictates that we should consume / supply
        // create an allocation for the shiftable process starting now
        else if (lastAllocation == null && lastBid.getDemand(price.getCurrentPrice()) != 0) {
            lastAllocation = new Allocation(controlSpace, now, controlSpace.getEnergyProfile());
        }

        return lastAllocation;
    }

    // @Override
    // protected synchronized void doBidUpdate() {
    // super.doBidUpdate();
    //
    // // if (lastAllocation != null) {
    // publishBidUpdate(createBid(lastControlSpace, getCurrentMarketBasis()));
    // // }
    // }

    // /**
    // * Publish regularly a new bid when the device is started, even if there was no new ControlSpace. The
    // EnergyProfile
    // * used to create a must-run bid can change over time.
    // *
    // * Overrides the run in FPAIAgent
    // */
    // @Override
    // public void run() {
    // super.run();
    //
    // // if (lastAllocation != null) {
    // publishBidUpdate(createBid(lastControlSpace, getCurrentMarketBasis()));
    // // }
    // }

    @Override
    public BidInfo createBid(TimeShifterControlSpace controlSpace, MarketBasis marketBasis) {
        assert controlSpace != null;
        assert marketBasis != null;

        // the device hasn't started yet
        if (lastAllocation == null) {
            Date now = new Date(getTimeSource().currentTimeMillis());

            // we're (still) at the very start or before of the flexibility, so we're still in a must-off situation
            if (controlSpace.getStartAfter().after(now) || now.equals(controlSpace.getStartAfter())) {
                return new BidInfo(marketBasis, new PricePoint(0, 0));
            }

            // we're at the very end of or after the flexibility so we're in a must run situation
            else if (now.after(controlSpace.getStartBefore()) || now.equals(controlSpace.getStartBefore())) {
                return new BidInfo(marketBasis, new PricePoint(0, getInitialDemand(controlSpace).doubleValue(WATT)));
            }

            // determine step price and create a step bid with the initial consumption before the step price
            else {
                return calculateFlexibleBid(controlSpace, marketBasis);
            }
        }

        // The time-shifter is turned on, send must-run bid with current demand
        else {
            return new BidInfo(marketBasis, new PricePoint(0, getCurrentDemand().doubleValue(WATT)));
        }
    }

    private BidInfo calculateFlexibleBid(TimeShifterControlSpace controlSpace, MarketBasis marketBasis) {
        assert controlSpace != null;
        assert marketBasis != null;

        // determine how far time has progressed in comparison to the start window (start after until start before)
        long startAfter = controlSpace.getStartAfter().getTime();
        long startBefore = controlSpace.getStartBefore().getTime();
        double startWindow = (startBefore - startAfter);

        double timeSinceAllowableStart = (getTimeSource().currentTimeMillis() - startAfter);
        double ratio = Math.pow(timeSinceAllowableStart / startWindow, eagerness);

        double initialDemandWatt = getInitialDemand(controlSpace).doubleValue(WATT);

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

    private Measurable<Power> getInitialDemand(TimeShifterControlSpace controlSpace) {
        assert controlSpace != null;
        assert !controlSpace.getEnergyProfile().isEmpty();

        Element initialElement = controlSpace.getEnergyProfile().get(0);
        Measurable<Power> initialDemand = initialElement.getAveragePower();
        assert initialDemand.doubleValue(WATT) != 0;
        return initialDemand;
    }

    private Measurable<Power> getCurrentDemand() {
        Allocation allocation = lastAllocation;

        if (allocation == null) {
            return Measure.valueOf(0, WATT);
        }

        Date now = new Date(getTimeSource().currentTimeMillis());
        Date start = allocation.getStartTime();
        Measurable<Duration> offset = TimeUtil.difference(start, now);
        Element value = allocation.getEnergyProfile().getElementForOffset(offset);

        if (value == null) {
            return Measure.valueOf(0, WATT);
        } else {
            return value.getAveragePower();
        }
    }

    public static interface Config extends AgentConfiguration {
        @Override
        @Meta.AD(required = false, deflt = CLUSTER_ID_DEFAULT)
        public String cluster_id();

        @Override
        public String id();

        @Override
        @Meta.AD(required = false, deflt = IdentifiableObjectConfiguration.ENABLED_DEFAULT_STR)
        public boolean enabled();

        @Override
        @Meta.AD(required = false, deflt = UPDATE_INTERVAL_DEFAULT_STR)
        public int update_interval();

        @Override
        @Meta.AD(required = false,
                 deflt = AGENT_BID_LOG_LEVEL_DEFAULT,
                 optionValues = { NO_LOGGING, PARTIAL_LOGGING, FULL_LOGGING },
                 optionLabels = { NO_LOGGING_LABEL, PARTIAL_LOGGING_LABEL, FULL_LOGGING_LABEL })
        public String agent_bid_log_level();

        @Override
        @Meta.AD(required = false,
                 deflt = AGENT_PRICE_LOG_LEVEL_DEFAULT,
                 optionValues = { NO_LOGGING, FULL_LOGGING },
                 optionLabels = { NO_LOGGING_LABEL, FULL_LOGGING_LABEL })
        public String agent_price_log_level();

        @Meta.AD(required = false, deflt = "" + DEFAULT_EAGERNESS)
        public double eagerness();
    }
}
