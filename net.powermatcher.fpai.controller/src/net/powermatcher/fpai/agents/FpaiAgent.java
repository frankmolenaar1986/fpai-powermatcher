package net.powermatcher.fpai.agents;

import net.powermatcher.core.agent.framework.Agent;
import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.PriceInfo;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.fpai.controller.PowerMatcherController;

import org.flexiblepower.messaging.Connection;
import org.flexiblepower.messaging.MessageHandler;
import org.flexiblepower.rai.comm.Allocation;
import org.flexiblepower.rai.comm.AllocationRevoke;
import org.flexiblepower.rai.comm.AllocationStatusUpdate;
import org.flexiblepower.rai.comm.ControlSpaceRegistration;
import org.flexiblepower.rai.comm.ControlSpaceUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class FpaiAgent extends Agent implements MessageHandler {

    public static final Logger log = LoggerFactory.getLogger(FpaiAgent.class);

    protected final Connection connection;
    protected final PowerMatcherController powerMatcherController;

    public FpaiAgent(ConfigurationService config, Connection connection, PowerMatcherController powerMatcherController) {
        super(config);
        this.connection = connection;
        this.powerMatcherController = powerMatcherController;
    }

    @Override
    public void handleMessage(Object message) {
        if (message instanceof ControlSpaceRegistration) {
            handleControlSpaceRegistration((ControlSpaceRegistration) message);
        } else if (message instanceof ControlSpaceUpdate) {
            handleControlSpaceUpdate((ControlSpaceUpdate) message);
        } else if (message instanceof AllocationStatusUpdate) {
            handleAllocationStatusUpdate((AllocationStatusUpdate) message);
        } else {
            log.error("Received unknown type of message: " + message);
        }
    }

    protected abstract void handleControlSpaceRegistration(ControlSpaceRegistration message);

    protected abstract void handleControlSpaceUpdate(ControlSpaceUpdate message);

    protected abstract void handleAllocationStatusUpdate(AllocationStatusUpdate message);

    /**
     * Construct a new bid based on known information.
     * 
     * This method is usually called after a ControlSpaceUpdate was received. If no bid can be constructed yet (because
     * the agent doesn't have enough information yet) the method should return null.
     * 
     * @return A new {@link BidInfo} or null if no bid could be constructed.
     */
    protected abstract BidInfo constructBid();

    protected abstract void priceUpdated();

    @Override
    public void disconnected() {
        powerMatcherController.removeAgent(this);
    }

    protected void sendAllocation(Allocation allocation) {
        connection.sendMessage(allocation);
    }

    protected void sendAllocationRevoke(AllocationRevoke allocationRevoe) {
        connection.sendMessage(allocationRevoe);
    }

    @Override
    protected void doBidUpdate() {
        BidInfo bid = constructBid();
        if (bid != null) {
            publishBidUpdate(bid);
        }
    }

    @Override
    public void updatePriceInfo(PriceInfo newPriceInfo) {
        super.updatePriceInfo(newPriceInfo);
        priceUpdated();
    }

}
