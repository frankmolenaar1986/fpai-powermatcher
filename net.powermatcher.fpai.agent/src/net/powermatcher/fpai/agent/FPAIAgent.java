package net.powermatcher.fpai.agent;

import java.util.Date;

import net.powermatcher.core.agent.framework.Agent;
import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.MarketBasis;
import net.powermatcher.core.agent.framework.data.PriceInfo;
import net.powermatcher.core.agent.framework.data.PricePoint;
import net.powermatcher.core.configurable.service.ConfigurationService;

import org.flexiblepower.rai.Allocation;
import org.flexiblepower.rai.ControlSpace;
import org.flexiblepower.rai.ControllableResource;
import org.flexiblepower.rai.Controller;
import org.flexiblepower.rai.ext.ControlSpaceCache;
import org.flexiblepower.time.TimeService;

/**
 * Abstract class for PowerMatcher agents on the FPAI framework. FPAIAgents are created by the PMController
 *
 * @author TNO
 *
 * @param <CS>
 *            The type of ControlSpace this agent represents
 */
public abstract class FPAIAgent<CS extends ControlSpace> extends Agent implements Controller<CS> {
    /** Last processed ControlSpace */
    private CS lastControlSpace = null;

    /** The last time a price update was received */
    private Date lastPriceDate = null;

    /** The resource this agent is controlling */
    private ControllableResource<? extends CS> controllableResource = null;

    /** The {@link ControlSpaceCache} keeps track of received {@link ControlSpace}s */
    private ControlSpaceCache<CS> controlSpaceCache;

    protected FPAIAgent() {
        super();
    }

    protected FPAIAgent(ConfigurationService configuration) {
        super(configuration);
    }

    protected abstract BidInfo createBid(CS controlSpace, MarketBasis marketBasis);

    protected abstract Allocation createAllocation(BidInfo lastBid, PriceInfo newPriceInfo, CS controlSpace);

    public void setFpaiTimeService(TimeService timeService) {
        this.controlSpaceCache = new ControlSpaceCache<CS>(timeService);
    }

    /**
     * This method is called doBidUpdate, but actually it's just a method called periodically (every
     * {@link #getUpdateInterval()} seconds). {@link FPAIAgent}s update both their bid and allocation
     *
     * @see net.powermatcher.core.agent.framework.Agent#doBidUpdate()
     */
    @Override
    protected synchronized void doBidUpdate() {
        /*
         * Force a bid update based on the current control space
         */
        if (controlSpaceCache != null) {
            publishBidUpdate();
        }
    }

    @Override
    public synchronized void controlSpaceUpdated(ControllableResource<? extends CS> resource, CS controlSpace) {
        assert controllableResource == resource;

        // Add the controlSpace to the cache
        if (controlSpace != null) {
            controlSpaceCache.addNewControlSpace(controlSpace);
        }

        publishBidUpdate();
    }

    private void publishBidUpdate() {
        // Retrieve the controlSpace
        CS activeControlSpace = controlSpaceCache.getActiveControlSpace();

        BidInfo bidInfo;
        if (activeControlSpace == null) {
            // No flexibility available
            bidInfo = BidUtil.zeroBid(getCurrentMarketBasis());
            this.logDebug("No active ControlSpace found, triggering must-off bid");
        } else {
            // remember the updated control space
            lastControlSpace = activeControlSpace;

            // calculate a new bid
            bidInfo = createBid(activeControlSpace, getCurrentMarketBasis());
            this.logDebug("Control space was updated (" + activeControlSpace
                          + "), triggering updating the bid: "
                          + bidInfo);
        }
        // and publish it
        publishBidUpdate(bidInfo);
    }

    @Override
    public void updatePriceInfo(PriceInfo newPriceInfo) {
        super.updatePriceInfo(newPriceInfo);
        this.lastPriceDate = new Date(getTimeSource().currentTimeMillis());

        updateAllocation();
    }

    private void updateAllocation() {
        // check if there is control space information available
        CS lastControlSpace = this.lastControlSpace;
        if (lastControlSpace == null) {
            this.logDebug("Ignoring price update, no control space information available");
            return;
        }

        BidInfo lastBid = getLastBid();
        if (lastBid == null) {
            this.logDebug("Ignoring price update, no bid published yet");
            return;
        }

        PriceInfo lastPrice = getLastPriceInfo();

        // if so, construct a new allocation and send it the ControllableResource
        Allocation allocation = createAllocation(lastBid, lastPrice, lastControlSpace);
        if (allocation != null) {
            controllableResource.handleAllocation(allocation);

            this.logDebug("Price update (" + lastPrice.getCurrentPrice()
                          + ") triggered calculation of new allocation: "
                          + allocation);
        } else {
            this.logDebug("Price update (" + lastPrice.getCurrentPrice()
                          + ") received, but no allocation was calculated");
        }
    }

    /**
     * Bind this controller to a controllableResource
     *
     * @param controllableResource
     */
    public synchronized void bind(ControllableResource<CS> controllableResource) {
        assert this.controllableResource == null;
        this.controllableResource = controllableResource;
        controllableResource.setController(this);
    }

    /**
     * Unbind this controller from a controllableResource
     *
     * @param controllableResource
     */
    public synchronized void unbind(ControllableResource<CS> controllableResource) {
        // send out 0 bid to say bye-bye
        publishBidUpdate(new BidInfo(getCurrentMarketBasis(), new PricePoint(0, 0)));

        assert this.controllableResource == controllableResource;
        controllableResource.unsetController(this);
        lastControlSpace = null;
        controllableResource = null;
    }
}
