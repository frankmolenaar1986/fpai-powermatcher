package net.powermatcher.fpai.agents;

import javax.measure.unit.SI;

import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.MarketBasis;
import net.powermatcher.core.agent.framework.data.PricePoint;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.fpai.controller.PowerMatcherController;

import org.flexiblepower.efi.uncontrolled.UncontrolledForecast;
import org.flexiblepower.efi.uncontrolled.UncontrolledMeasurement;
import org.flexiblepower.efi.uncontrolled.UncontrolledRegistration;
import org.flexiblepower.messaging.Connection;
import org.flexiblepower.rai.comm.AllocationStatusUpdate;
import org.flexiblepower.rai.comm.ControlSpaceRegistration;
import org.flexiblepower.rai.comm.ControlSpaceUpdate;
import org.flexiblepower.rai.values.Commodity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UncontrolledAgent extends FpaiAgent {

    private static final Logger log = LoggerFactory.getLogger(UncontrolledAgent.class);

    private UncontrolledRegistration registration;
    private UncontrolledMeasurement lastUncontrolledMeasurement;

    public UncontrolledAgent(ConfigurationService config,
                             Connection connection,
                             PowerMatcherController powerMatcherController) {
        super(config, connection, powerMatcherController);
    }

    @Override
    protected void handleControlSpaceRegistration(ControlSpaceRegistration message) {
        if (message instanceof UncontrolledRegistration) {
            if (registration == null) {
                registration = (UncontrolledRegistration) message;
                if (!registration.supportsCommodity(Commodity.ELECTRICITY)) {
                    log.error("PowerMatcher cannot support appliances which do not support electricity");
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
        if (message instanceof UncontrolledMeasurement) {
            lastUncontrolledMeasurement = (UncontrolledMeasurement) message;
            doBidUpdate();
        } else if (message instanceof UncontrolledForecast) {
            log.debug("Received UncontrolledForecast, ignoring...");
        } else {
            log.error("Received unknown type of ControlSpaceUpdate: " + message);
        }
    }

    @Override
    protected void handleAllocationStatusUpdate(AllocationStatusUpdate message) {
        log.debug("Received AllocationStatusUpdate, ignoring...");
    }

    @Override
    protected BidInfo constructBid() {
        MarketBasis marketBasis = getCurrentMarketBasis();
        if (lastUncontrolledMeasurement == null || marketBasis == null) {
            return null;
        } else {
            double demandWatt = lastUncontrolledMeasurement.getMeasurements()
                                                           .get(Commodity.ELECTRICITY)
                                                           .doubleValue(SI.WATT);
            return new BidInfo(getCurrentMarketBasis(), new PricePoint(0, demandWatt));
        }

    }

    @Override
    protected void priceUpdated() {
        // This agent doesn't support curtailment
    }
}
