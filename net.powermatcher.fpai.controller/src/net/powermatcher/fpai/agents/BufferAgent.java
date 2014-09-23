package net.powermatcher.fpai.agents;

import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.fpai.controller.PowerMatcherController;

import org.flexiblepower.messaging.Connection;
import org.flexiblepower.rai.AllocationStatusUpdate;
import org.flexiblepower.rai.ControlSpaceRegistration;
import org.flexiblepower.rai.ControlSpaceUpdate;

public class BufferAgent extends FpaiAgent {

    public BufferAgent(ConfigurationService config, Connection connection, PowerMatcherController powerMatcherController) {
        super(config, connection, powerMatcherController);
    }

    @Override
    protected void handleControlSpaceRegistration(ControlSpaceRegistration message) {
        // TODO Auto-generated method stub

    }

    @Override
    protected void handleControlSpaceUpdate(ControlSpaceUpdate message) {
        // TODO Auto-generated method stub

    }

    @Override
    protected void handleAllocationStatusUpdate(AllocationStatusUpdate message) {
        // TODO Auto-generated method stub

    }

    @Override
    protected BidInfo constructBid() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected void priceUpdated() {
        // TODO Auto-generated method stub
    }

}
