package net.powermatcher.fpai.agents;

import java.util.Collection;
import java.util.Date;

import javax.measure.quantity.Quantity;
import javax.measure.unit.SI;

import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.MarketBasis;
import net.powermatcher.core.agent.framework.data.PricePoint;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.fpai.agents.BufferBid.BufferBidElement;
import net.powermatcher.fpai.controller.AgentTracker;

import org.flexiblepower.api.efi.bufferhelper.Buffer;
import org.flexiblepower.api.efi.bufferhelper.BufferActuator;
import org.flexiblepower.efi.buffer.BufferRegistration;
import org.flexiblepower.efi.buffer.BufferStateUpdate;
import org.flexiblepower.efi.buffer.BufferSystemDescription;
import org.flexiblepower.efi.buffer.RunningModeBehaviour;
import org.flexiblepower.efi.util.FillLevelFunction;
import org.flexiblepower.efi.util.RunningMode;
import org.flexiblepower.messaging.Connection;
import org.flexiblepower.rai.AllocationStatusUpdate;
import org.flexiblepower.rai.ControlSpaceRegistration;
import org.flexiblepower.rai.ControlSpaceUpdate;
import org.flexiblepower.rai.values.Commodity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BufferAgent<Q extends Quantity> extends FpaiAgent {

    private static final Logger log = LoggerFactory.getLogger(BufferAgent.class);

    private BufferRegistration<Q> registration;
    private Buffer<Q> bufferHelper;
    private BufferBid lastBid;

    public BufferAgent(ConfigurationService config, Connection connection, AgentTracker agentTracker) {
        super(config, connection, agentTracker);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void handleControlSpaceRegistration(ControlSpaceRegistration message) {
        if (message instanceof BufferRegistration) {
            if (registration == null) {
                registration = (BufferRegistration<Q>) message;
                bufferHelper = new Buffer<Q>(registration);
            } else {
                log.error("Received multiple ControlSpaceRegistrations, ignoring...");
            }
        } else {
            log.error("Received unknown ContorlSpaceRegistration: " + message);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void handleControlSpaceUpdate(ControlSpaceUpdate message) {
        if (message instanceof BufferSystemDescription) {
            bufferHelper.processSystemDescription((BufferSystemDescription) message);
        } else if (message instanceof BufferStateUpdate) {
            bufferHelper.processStateUpdate((BufferStateUpdate<Q>) message);
        } else {
            log.info("ControlSpaceUpdate not yet supported");
        }
    }

    @Override
    protected void handleAllocationStatusUpdate(AllocationStatusUpdate message) {
        switch (message.getStatus()) {
        case ACCEPTED:
            // TODO
            break;
        case REJECTED:
            // TODO
            break;
        case PROCESSING:
            // TODO
            break;
        case STARTED:
            // TODO
            break;
        case FINISHED:
            // TODO
            break;
        }
        log.info("handleAllocationStatusUpdate not yet implemented");
        doBidUpdate();
    }

    @Override
    protected BidInfo constructBid() {
        MarketBasis marketBasis = getCurrentMarketBasis();
        if (marketBasis == null || registration == null) {
            return null;
        }

        double soc = bufferHelper.getCurrentFillFraction();
        soc = Math.max(0, Math.min(1, soc));

        // TODO for now we only support one buffer
        BufferActuator actuator = bufferHelper.getElectricalActuators().get(0);

        Date now = new Date(getTimeSource().currentTimeMillis());
        Collection<RunningMode<FillLevelFunction<RunningModeBehaviour>>> runningModes = actuator.getReachableRunningModes(now);

        if (runningModes.isEmpty()) {
            log.error("No reachable runningmodel found, sending must off bid");
            return new BidInfo(getCurrentMarketBasis(), new PricePoint(0, 0));
        }

        lastBid = new BufferBid();

        for (RunningMode<FillLevelFunction<RunningModeBehaviour>> rm : runningModes) {
            BufferBidElement bbe = new BufferBidElement();
            bbe.demandWatt = rm.getValue()
                               .getRangeElementForFillLevel(bufferHelper.getCurrentFillLevel()
                                                                        .doubleValue(registration.getFillLevelUnit()))
                               .getValue()
                               .getCommodityConsumption()
                               .get(Commodity.ELECTRICITY)
                               .doubleValue(SI.WATT);

            bbe.actuatorId = actuator.getActuatorId();
            bbe.runningModeId = rm.getId();
        }
        lastBid.setPriorities(soc);
        log.info("Sending bid");
        return lastBid.toBidInfo(marketBasis);
    }

    @Override
    protected void priceUpdated() {
        if (lastBid != null) {
            BufferBidElement runningMode = lastBid.runningModeForPrice(getLastPriceInfo());
            log.info("Allocation runningmode " + runningMode);
        }
    }

}
