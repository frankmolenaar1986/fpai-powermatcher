package net.powermatcher.fpai.agents;

import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.fpai.controller.PowerMatcherController;

import org.flexiblepower.messaging.Connection;
import org.flexiblepower.rai.comm.Allocation;
import org.flexiblepower.rai.comm.AllocationStatusUpdate;
import org.flexiblepower.rai.comm.ControlSpaceRegistration;
import org.flexiblepower.rai.comm.ControlSpaceUpdate;

public class TimeshifterAgent extends FpaiAgent {

    public TimeshifterAgent(ConfigurationService config,
                            Connection connection,
                            PowerMatcherController powerMatcherController) {
        super(config, connection, powerMatcherController);
    }

    /*
     * AllocationStatusUpdate:
     * 
     * rejected: stop, must not run
     * 
     * accepted: ignore
     * 
     * processing: ignore
     * 
     * starting: profiel afspelen
     * 
     * finished: stop, must not run
     */

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
    protected Allocation constructAllocation() {
        // TODO Auto-generated method stub
        return null;
    }

}