package net.powermatcher.fpai.agent.buffer;

import static javax.measure.unit.SI.JOULE;
import static javax.measure.unit.SI.MILLI;
import static javax.measure.unit.SI.SECOND;
import static javax.measure.unit.SI.WATT;

import java.util.Date;

import javax.measure.Measurable;
import javax.measure.Measure;
import javax.measure.quantity.Duration;
import javax.measure.quantity.Energy;
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
import net.powermatcher.fpai.agent.BidUtil;
import net.powermatcher.fpai.agent.FPAIAgent;
import net.powermatcher.fpai.agent.buffer.BufferAgent.Config;

import org.flexiblepower.rai.Allocation;
import org.flexiblepower.rai.BufferControlSpace;
import org.flexiblepower.rai.values.Constraint;
import org.flexiblepower.rai.values.ConstraintList;
import org.flexiblepower.rai.values.EnergyProfile;
import org.flexiblepower.rai.values.EnergyProfile.Element;
import org.flexiblepower.time.TimeUtil;

import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.metatype.Meta;

@Component(designateFactory = Config.class)
public class BufferAgent extends FPAIAgent<BufferControlSpace> implements
                                                              AgentConnectorService,
                                                              LoggingConnectorService,
                                                              TimeConnectorService,
                                                              SchedulerConnectorService {
    public BufferAgent() {
        super();
    }

    public BufferAgent(ConfigurationService configuration) {
        super(configuration);
    }

    /** This value influences the bid strategy */
    private final double bidBandWidth = 0.25; // TODO analyze this value

    /** The last allocation given */
    private Allocation lastAllocation;

    /** The time until the device must run (or null if not applicable) */
    private Date mustRunUntil = null;

    /** The time until the device must not run (or null if not applicable) */
    private Date mustNotRunUntil = null;

    /**
     * Main function that creates the bid based on the ControlSpace.
     */
    @Override
    protected BidInfo createBid(BufferControlSpace controlSpace, MarketBasis marketBasis) {
        // if the buffer can't turn on, return a flat bid with power value 0
        if (isInMustNotRunState() || (isOffByAllocation() && !canTurnOn(controlSpace))) {
            return BidUtil.zeroBid(marketBasis);
        }

        // perform the basic bidding strategy (either with the SoC delta from the target as 'driver' or the SoC itself)
        BidInfo bid;
        if (hasTarget(controlSpace)) {
            bid = createBidByTargetSoCDelta(marketBasis, controlSpace);
        } else {
            bid = createBidBySoC(marketBasis, controlSpace);
        }

        // check if there is a minimum charge speed and apply it
        Measurable<Power> minimumChargeSpeed = calculateMinimumChargeSpeed(controlSpace);
        if (isConsumingBuffer(controlSpace) && minimumChargeSpeed.doubleValue(WATT) > 0) {
            bid = BidUtil.setMinimumDemand(bid, minimumChargeSpeed);
        }
        if (isProducingBuffer(controlSpace) && minimumChargeSpeed.doubleValue(WATT) < 0) {
            bid = BidUtil.setMaximumDemand(bid, minimumChargeSpeed);
        }

        // return the bid;
        return bid;
    }

    /**
     * The default bid strategy.
     *
     * @param marketBasis
     *            The market basis to use for creating the bid.
     * @param controlSpace
     *            The control space which expresses the flexibility.
     * @return The bid based on the given flexibility.
     */
    private BidInfo createBidBySoC(MarketBasis marketBasis, BufferControlSpace controlSpace) {
        double bandWidthCenter;
        if (isConsumingBuffer(controlSpace)) {
            // Determine the 'decision-price' in a range of [0,1]
            bandWidthCenter = 1 - controlSpace.getStateOfCharge();
        } else {
            // Determine the 'decision-price' in a range of [0,1]
            bandWidthCenter = controlSpace.getStateOfCharge();
        }
        return createSlopedBid(marketBasis, controlSpace, bandWidthCenter);
    }

    /**
     * The default bid strategy.
     *
     * @param marketBasis
     *            The market basis to use for creating the bid.
     * @param controlSpace
     *            The control space which expresses the flexibility.
     * @return The bid based on the given flexibility.
     */
    private BidInfo createBidByTargetSoCDelta(MarketBasis marketBasis, BufferControlSpace controlSpace) {
        // calculate the amount of energy required to achieve the target state of charge
        double deltaSoC = controlSpace.getTargetStateOfCharge() - controlSpace.getStateOfCharge();

        // use normal bidding strategy if target already achieved
        if (deltaSoC < 0) {
            return createBidBySoC(marketBasis, controlSpace);
        }

        ConstraintList<Power> chargeSpeed = controlSpace.getChargeSpeed();

        // calculate the amount to charge
        double deltaEnergyJoule = deltaSoC * controlSpace.getTotalCapacity().doubleValue(JOULE);

        // calculate the time frame available
        double timeToDeadlineMS = controlSpace.getTargetTime().getTime() - getTimeSource().currentTimeMillis();

        // if past the deadline ...
        if (timeToDeadlineMS <= 0) {
            if (deltaSoC > 0) {
                // and target SoC not yet achieved, charge as fast as possible (minimize 'damage')
                if (isProducingBuffer(controlSpace)) {
                    return BidUtil.createFlatBid(marketBasis, chargeSpeed.getMinimum());
                } else {
                    return BidUtil.createFlatBid(marketBasis, chargeSpeed.getMaximum());
                }
            } else {
                // otherwise use normal bidding strategy
                return createBidBySoC(marketBasis, controlSpace);
            }
        }

        // the price for which the maximum demand is desirable is inversely proportional to the ratio between the
        // minimum time required to cover the delta SoC (at max power) and the time until the target deadline.
        // I.e. the closer to a must-run situation, the higher the accepted price.
        double maxChargePowerWatt;
        double dischargeWatt = controlSpace.getSelfDischarge().doubleValue(WATT);
        // calculate the time required to charge to the target state of charge at maximum power
        if (isConsumingBuffer(controlSpace)) {
            maxChargePowerWatt = chargeSpeed.getMaximum().doubleValue(WATT);
        } else {
            maxChargePowerWatt = -chargeSpeed.getMinimum().doubleValue(WATT);
        }
        double fastestChargeTimeSecond = deltaEnergyJoule / (maxChargePowerWatt - dischargeWatt);
        double fastestChargeTimeMS = fastestChargeTimeSecond * 1000;
        double relativeTurnOnPrice = fastestChargeTimeMS / timeToDeadlineMS;
        if (isProducingBuffer(controlSpace)) {
            relativeTurnOnPrice = 1 - relativeTurnOnPrice;
        }
        // make sure the relativeTurnOnPrice is in the range [0,1]
        relativeTurnOnPrice = Math.max(0, Math.min(1, relativeTurnOnPrice));

        // create the bid respecting the bounds of the market basis and control space
        return createSlopedBid(marketBasis, controlSpace, relativeTurnOnPrice);
    }

    /**
     * Create a sloped bid based on a relative turn on price. The bidBandWidth field is used to determine the width of
     * the 'flexible range' inside the bid.
     *
     * @param marketBasis
     *            The market basis by which the price indices will be bounded.
     * @param controlSpace
     *            The buffer control space which
     * @param relativeTurnOnPrice
     *            The price where device should turn on or off in the range [0,1]
     * @return The sloped bid
     */
    private BidInfo
            createSlopedBid(MarketBasis marketBasis, BufferControlSpace controlSpace, double relativeTurnOnPrice) {
        // Determine the local bandwidth. The bandwidth shrinks when the relativeTurnOnPrice comes close to the border
        // of the bid.
        double bandWidth = Math.min(relativeTurnOnPrice * 2, Math.min((1 - relativeTurnOnPrice) * 2, bidBandWidth));
        int rangeLowerIdx = (int) ((relativeTurnOnPrice - (bandWidth / 2)) * (marketBasis.getPriceSteps() - 1));
        int rangeUpperIdx = (int) ((relativeTurnOnPrice + (bandWidth / 2)) * (marketBasis.getPriceSteps() - 1));

        // create the bid respecting the bounds of the market basis and control space
        return createSlopedBid(marketBasis, controlSpace, rangeLowerIdx, rangeUpperIdx);
    }

    /**
     * Create a sloped bid determined by the indices of the 'flexible range' of the bid. The 'flexible range' is the
     * part of the bid where the device can be between full-power and off.
     *
     * @param marketBasis
     *            The market basis by which the price indices will be bounded.
     * @param controlSpace
     *            The buffer control space which
     * @param rangeLowerIdx
     *            The index where the flexible range starts
     * @param rangeUpperIdx
     *            The index where the flexible range ends
     * @return The sloped bid
     */
    private BidInfo createSlopedBid(MarketBasis marketBasis,
                                    BufferControlSpace controlSpace,
                                    int rangeLowerIdx,
                                    int rangeUpperIdx) {
        assert rangeLowerIdx <= rangeUpperIdx;
        assert rangeLowerIdx >= 0;
        assert rangeUpperIdx < marketBasis.getPriceSteps();

        ConstraintList<Power> chargeSpeed = controlSpace.getChargeSpeed();

        // get the max and min demand possible
        double maxDemand = chargeSpeed.getMaximum().doubleValue(WATT);
        double minDemand = chargeSpeed.getMinimum().doubleValue(WATT);

        // Add the option of being off
        if (isProducingBuffer(controlSpace)) {
            maxDemand = 0;
        } else {
            minDemand = 0;
        }
        // construct the bid via price points
        PricePoint[] pricePoints;
        if (rangeLowerIdx == marketBasis.getPriceSteps() - 1) {
            // Without this check, the last element of the demand array would get the value minDemand
            pricePoints = new PricePoint[] { new PricePoint(0, maxDemand) };
        } else {
            pricePoints = new PricePoint[] { new PricePoint(rangeLowerIdx, maxDemand),
                                            new PricePoint(rangeUpperIdx, minDemand) };
        }

        BidInfo bid = new BidInfo(marketBasis, pricePoints);

        // constrain the bid to the possibilities of the buffer to be charged with
        return BidUtil.roundBidToPowerConstraintList(bid, chargeSpeed, false);
    }

    /**
     * Calculate the minimum charge speed for must-run situations
     */
    private Measurable<Power> calculateMinimumChargeSpeed(BufferControlSpace bufferControlSpace) {
        if (isInMustRunState() || (isOnByAllocation() && !canTurnOff(bufferControlSpace))) {
            // determined by the lowest power which is > 0
            for (Constraint<Power> c : bufferControlSpace.getChargeSpeed()) {
                Measurable<Power> lowerBound = c.getLowerBound();
                Measurable<Power> upperBound = c.getUpperBound();
                if (isConsumingBuffer(bufferControlSpace) && lowerBound.doubleValue(WATT) > 0) {
                    return lowerBound;
                }
                if (isProducingBuffer(bufferControlSpace) && upperBound.doubleValue(WATT) < 0) {
                    return upperBound;
                }
            }
        }

        // There is currently no minimum charge speed
        return Measure.valueOf(0, WATT);
    }

    /**
     * Check if the control space has a target
     */
    private static boolean hasTarget(BufferControlSpace bufferControlSpace) {
        return !(bufferControlSpace.getTargetStateOfCharge() == null || bufferControlSpace.getTargetTime() == null);
    }

    /**
     * Check if the device can turn off now in order to prevent drainage to SoC below 0
     */
    private boolean canTurnOff(BufferControlSpace bufferControlSpace) {
        // device IS off
        if (isOffByAllocation()) {
            return false;
        } else {
            double selfDischargeWatt = bufferControlSpace.getSelfDischarge().doubleValue(WATT);
            double minOffSecond = bufferControlSpace.getMinOffPeriod().doubleValue(SECOND);
            double minChargeEnergyJoule = selfDischargeWatt * minOffSecond;
            double totalCapacityJoule = bufferControlSpace.getTotalCapacity().doubleValue(JOULE);
            double minSOC = minChargeEnergyJoule / totalCapacityJoule;
            return bufferControlSpace.getStateOfCharge() >= minSOC;
        }
    }

    /**
     * Check if the device can turn on now in order to prevent over-charging to SoC above 1
     */
    private boolean canTurnOn(BufferControlSpace bufferControlSpace) {
        // device IS on
        if (isOnByAllocation()) {
            return false;
        } else {
            double minDemandWatt = bufferControlSpace.getChargeSpeed().getMinimum().doubleValue(WATT);
            double netDemandWatt = minDemandWatt - bufferControlSpace.getSelfDischarge().doubleValue(WATT);
            double minOnSecond = bufferControlSpace.getMinOnPeriod().doubleValue(SECOND);
            double minChargeEnergyJoule = minOnSecond * netDemandWatt;
            double totalCapacityJoule = bufferControlSpace.getTotalCapacity().doubleValue(JOULE);
            double maxSOC = 1 - (minChargeEnergyJoule / totalCapacityJoule);
            return bufferControlSpace.getStateOfCharge() < maxSOC;
        }
    }

    /**
     * Create an Allocation. This is done by looking at the latest bid.
     */
    @Override
    protected Allocation createAllocation(BidInfo lastBid, PriceInfo newPriceInfo, BufferControlSpace controlSpace) {
        if (controlSpace == null) {
            return null;
        }

        // calculate the target power given the last bid (if any in that case power is 0)
        double targetPower = getTargetPower(lastBid, newPriceInfo);

        // calculate the currently applicable target power given the last allocation
        double currentTargetPower = getCurrentlyAllocatedPower();

        Date now = new Date(getTimeSource().currentTimeMillis());

        // ignore deviations from the current target below the threshold (as ratio of the max charge speed, e.g. 1�)
        // but only if we have an allocation for the current point in time
        if (getCurrentlyAllocatedPowerOrNull() != null) {
            double updateThreadholdRatio = getProperty("allocation.update.threshold", 0.001d);
            double threshold = controlSpace.getChargeSpeed().getMaximum().doubleValue(WATT) * updateThreadholdRatio;
            if (Math.abs(currentTargetPower - targetPower) < threshold) {
                return null;
            }
        }

        // if we're turning on or off, calculate the time at which we can switch again
        if (currentTargetPower < 0.01 && currentTargetPower > -0.01 && targetPower != 0) {
            logDebug("Turning device ON for at least " + controlSpace.getMinOnPeriod());
            mustRunUntil = TimeUtil.add(now, controlSpace.getMinOnPeriod());
        } else if (currentTargetPower != 0 && targetPower == 0) {
            logDebug("Turning device OFF for at least " + controlSpace.getMinOffPeriod());
            mustNotRunUntil = TimeUtil.add(now, controlSpace.getMinOffPeriod());
        }

        // Construct allocation object
        Date allocationEnd = controlSpace.getValidThru();
        Measurable<Duration> duration = Measure.valueOf(allocationEnd.getTime(), MILLI(SECOND));
        Measurable<Energy> targetEnergyVolume = Measure.valueOf(targetPower * (duration.doubleValue(SECOND)), JOULE);

        // return the allocation and remember it
        EnergyProfile energyProfile = EnergyProfile.create().add(duration, targetEnergyVolume).build();
        return lastAllocation = new Allocation(controlSpace, now, energyProfile);
    }

    private double getTargetPower(BidInfo bid, PriceInfo price) {
        if (bid == null) {
            return 0;
        }

        double[] demand = bid.getDemand();

        MarketBasis market = price.getMarketBasis();
        double index = ((price.getCurrentPrice() - market.getMinimumPrice()) / (market.getMaximumPrice() - market.getMinimumPrice())) * (market.getPriceSteps() - 1);

        int highDemandIndex = (int) Math.floor(index);
        int lowDemandIndex = (int) Math.ceil(index);

        double highDemand = demand[highDemandIndex];
        double lowDemand = demand[lowDemandIndex];

        double weight = index - highDemandIndex;

        double targetDemand = lowDemand + (highDemand - lowDemand) * weight;

        return targetDemand;
    }

    private boolean isOnByAllocation() {
        return getCurrentlyAllocatedPower() != 0;
    }

    private boolean isOffByAllocation() {
        return getCurrentlyAllocatedPower() == 0;
    }

    /** @return true if the resource is in a must-run state */
    private boolean isInMustRunState() {
        if (mustRunUntil == null) {
            return false;
        } else if (getTimeSource().currentTimeMillis() > mustRunUntil.getTime()) {
            mustRunUntil = null;
            return false;
        } else {
            return true;
        }
    }

    /** @return true if the resource is in a must-not-run state */
    private boolean isInMustNotRunState() {
        if (mustNotRunUntil == null) {
            return false;
        } else if (getTimeSource().currentTimeMillis() > mustNotRunUntil.getTime()) {
            mustNotRunUntil = null;
            return false;
        } else {
            return true;
        }
    }

    /**
     * @return The currently applicable target power given the last allocation
     */
    private double getCurrentlyAllocatedPower() {
        // if we haven't allocated a profile yet, but (for code stability ... other code depends on this behavior ...
        // maybe fix at some point in time), return 0 as the default
        if (lastAllocation == null) {
            return 0;
        }

        Double currentlyAllocatedPower = getCurrentlyAllocatedPowerOrNull();

        // there is no actual allocation, but (for code stability ... other code depends on this behavior ... maybe fix
        // at some point in time), so indicate 0 as default allocated power
        if (currentlyAllocatedPower == null) {
            return 0;
        }

        return currentlyAllocatedPower;
    }

    /**
     * @return The currently applicable target power given the last allocation, or null if there is no currently
     *         applicable allocation
     */
    private Double getCurrentlyAllocatedPowerOrNull() {
        if (lastAllocation == null) {
            return null;
        }

        // determine position in current allocation
        Date now = new Date(getTimeSource().currentTimeMillis());
        Measurable<Duration> offsetInAllocation = TimeUtil.difference(lastAllocation.getStartTime(), now);
        // pick the currently applicable element from the allocation
        Element currentAllocElement = lastAllocation.getEnergyProfile().getElementForOffset(offsetInAllocation);

        // allocation not yet active (starts in the future) or already completed (ends in the past)
        if (currentAllocElement == null) {
            return null;
        }

        // return the power we have allocated for the current point in time
        return currentAllocElement.getAveragePower().doubleValue(WATT);
    }

    protected static boolean isConsumingBuffer(BufferControlSpace controlSpace) {
        for (Constraint<Power> c : controlSpace.getChargeSpeed()) {
            double upperWatt = c.getUpperBound().doubleValue(WATT);
            if (upperWatt > 0) {
                return true;
            }
            double lowerWatt = c.getLowerBound().doubleValue(WATT);
            if (lowerWatt < 0) {
                return false;
            }
        }
        // should not get here
        return true;
    }

    protected static boolean isProducingBuffer(BufferControlSpace controlSpace) {
        return !isConsumingBuffer(controlSpace);
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

        @Meta.AD(required = false,
                 deflt = "0.001",
                 description = "The threshold for updating the allocation (expressed as the minimum change in requested power level as factor of the maximum chargespeed, e.g. 0.001 for one per mille of the maximum charge speed)")
        public double
                allocation_update_threshold();
    }
}
