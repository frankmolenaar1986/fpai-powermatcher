package net.powermatcher.fpai.agents;

import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;
import net.powermatcher.core.configurable.PrefixedConfiguration;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.fpai.controller.AgentTracker;

import org.flexiblepower.messaging.Connection;
import org.flexiblepower.messaging.Port;

public class UncontrolledTest extends TestCase {

    private UncontrolledAgent agent;
    private final Connection resourceManagerConnection = new Connection() {

        @Override
        public void sendMessage(Object message) {
            // TODO Auto-generated method stub

        }

        @Override
        public Port getPort() {
            // TODO Auto-generated method stub
            return null;
        }
    };

    private final AgentTracker agentTracker = new AgentTracker() {

        @Override
        public void unregisterAgent(FpaiAgent agent) {
            // TODO Auto-generated method stub

        }

        @Override
        public void registerAgent(FpaiAgent agent) {
            // TODO Auto-generated method stub

        }
    };

    private ConfigurationService createAgentConfig(int agentId) {
        Map<String, Object> agentProperties = new HashMap<String, Object>();
        String prefix = "agent" + ConfigurationService.SEPARATOR + agentId;
        agentProperties.put(prefix + ".id", String.valueOf(agentId));
        agentProperties.put(prefix + ".agent.bid.log.level", "FULL_LOGGING");
        agentProperties.put(prefix + ".agent.price.log.level", "FULL_LOGGING");
        ConfigurationService agentConfig = new PrefixedConfiguration(agentProperties, prefix);
        return agentConfig;
    }

    @Override
    protected void setUp() throws Exception {
        agent = new UncontrolledAgent(createAgentConfig(1), resourceManagerConnection, null);
    }

    @Override
    protected void tearDown() throws Exception {
    }

    public void testUncontrolled() {
        // TODO
    }

}
