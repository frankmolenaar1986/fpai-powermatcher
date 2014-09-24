package net.powermatcher.fpai.controller;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import net.powermatcher.core.adapter.Adapter;
import net.powermatcher.core.agent.concentrator.Concentrator;
import net.powermatcher.core.agent.concentrator.framework.AbstractConcentrator;
import net.powermatcher.core.agent.framework.config.AgentConfiguration;
import net.powermatcher.core.agent.framework.service.AgentService;
import net.powermatcher.core.agent.marketbasis.adapter.MarketBasisAdapter;
import net.powermatcher.core.configurable.BaseConfiguration;
import net.powermatcher.core.configurable.PrefixedConfiguration;
import net.powermatcher.core.configurable.service.ConfigurationService;
import net.powermatcher.core.messaging.mqttv3.Mqttv3Connection;
import net.powermatcher.core.messaging.protocol.adapter.AgentProtocolAdapter;
import net.powermatcher.core.messaging.protocol.adapter.MatcherProtocolAdapter;
import net.powermatcher.core.messaging.protocol.adapter.config.ProtocolAdapterConfiguration;
import net.powermatcher.fpai.agents.BufferAgent;
import net.powermatcher.fpai.agents.FpaiAgent;
import net.powermatcher.fpai.agents.TimeshifterAgent;
import net.powermatcher.fpai.agents.UnconstrainedAgent;
import net.powermatcher.fpai.agents.UncontrolledAgent;
import net.powermatcher.fpai.controller.PowerMatcherController.Config;
import net.powermatcher.fpai.fullwidget.PMFullWidget;
import net.powermatcher.fpai.util.PMTimeServiceWrapper;

import org.flexiblepower.efi.EfiControllerManager;
import org.flexiblepower.messaging.Connection;
import org.flexiblepower.messaging.Endpoint;
import org.flexiblepower.messaging.MessageHandler;
import org.flexiblepower.time.TimeService;
import org.flexiblepower.ui.Widget;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.component.Reference;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

@Component(immediate = true, designateFactory = Config.class, provide = { Endpoint.class })
public class PowerMatcherController implements EfiControllerManager, AgentTracker {

    public static interface Config {
        @Meta.AD(deflt = "ExampleCluster")
        String cluster_id();

        @Meta.AD(deflt = "ExampleLocation")
        String location_id();

        @Meta.AD(deflt = "INTERNAL_v1")
        ProtocolAdapterConfiguration.Protocol messaging_protocol();

        @Meta.AD(deflt = "UpdateBid")
        String bid_topic_suffix();

        @Meta.AD(deflt = "UpdatePriceInfo")
        String price_info_topic_suffix();

        @Meta.AD(deflt = "tcp://localhost:1883")
        String broker_uri();

        @Meta.AD(deflt = "1")
        int update_interval();

        @Meta.AD(deflt = "auctioneer1")
        String auctioneer_id();

        @Meta.AD(deflt = "true")
        boolean small_widget();
    }

    private ScheduledExecutorService executorService;

    private TimeService timeService;
    private net.powermatcher.core.scheduler.service.TimeService pmTimeService;

    private final List<FpaiAgent> agents = new ArrayList<FpaiAgent>();

    private Config config;

    private Map<String, Object> properties;

    private AbstractConcentrator concentrator;

    private ServiceRegistration<AgentService> serviceRegistration;

    private PMWidget widget;
    private ServiceRegistration<Widget> widgetRegistration;

    private BaseConfiguration concentratorConfiguration;

    @Activate
    public void activate(BundleContext context, Map<String, Object> properties) throws Exception {
        properties = new Hashtable<String, Object>(properties);
        properties.put("id", "concentrator-" + UUID.randomUUID().toString());

        config = Configurable.createConfigurable(Config.class, properties);
        this.properties = properties;
        this.properties.put(AgentConfiguration.AGENT_BID_LOG_LEVEL_PROPERTY, AgentConfiguration.FULL_LOGGING);

        concentratorConfiguration = new BaseConfiguration(properties);
        concentrator = new Concentrator(concentratorConfiguration);
        concentrator.bind(executorService);
        concentrator.bind(pmTimeService);

        if (config.broker_uri() == null || config.broker_uri().isEmpty()) {
            Dictionary<String, Object> concentratorProperties = new Hashtable<String, Object>();
            concentratorProperties.put("auctioneer.id", "auctioneer1");
            serviceRegistration = context.registerService(AgentService.class, concentrator, concentratorProperties);
        } else {
            createConcentratorUplink(concentrator);
        }

        // Widget
        if (config.small_widget()) {
            widget = new PMWidgetImpl(this);
            widgetRegistration = context.registerService(Widget.class, widget, null);
        } else {
            widget = new PMFullWidget();
            Dictionary<String, Object> p = new Hashtable<String, Object>();
            p.put("widget.type", "full");
            p.put("widget.name", "pmfullwidget");
            widgetRegistration = context.registerService(Widget.class, widget, p);
        }
        concentrator.bind(widget);

    }

    @Deactivate
    public void deactivate() {
        if (serviceRegistration != null) {
            serviceRegistration.unregister();
        } else {
            deactivateConcentratorUplink(concentrator);
        }

        // remove all agents
        for (FpaiAgent agent : agents.toArray(new FpaiAgent[agents.size()])) {
            unregisterAgent(agent);
        }

        concentrator.unbind(executorService);
        concentrator.unbind(pmTimeService);
        concentrator.unbind(widget);
        concentrator = null;
        widgetRegistration.unregister();
    }

    // These represent the default uplink for the concentrator
    private MarketBasisAdapter marketBasisAdapter;
    private AgentProtocolAdapter agentProtocolAdapter;
    private MatcherProtocolAdapter matcherProtocolAdapter;
    private Mqttv3Connection mqttv3Connection;

    private int agentId = 0;

    protected void createConcentratorUplink(AbstractConcentrator concentrator) throws Exception {
        marketBasisAdapter = new MarketBasisAdapter(concentratorConfiguration);
        marketBasisAdapter.setAgentConnector(concentrator);

        agentProtocolAdapter = new AgentProtocolAdapter(concentratorConfiguration);
        agentProtocolAdapter.setAgentConnector(concentrator);
        agentProtocolAdapter.setParentMatcherId(config.auctioneer_id());

        matcherProtocolAdapter = new MatcherProtocolAdapter(concentratorConfiguration);
        matcherProtocolAdapter.setMatcherConnector(concentrator);

        mqttv3Connection = new Mqttv3Connection();
        mqttv3Connection.setConfiguration(concentratorConfiguration);
        mqttv3Connection.addConnector(agentProtocolAdapter);
        mqttv3Connection.addConnector(matcherProtocolAdapter);

        for (Adapter a : new Adapter[] { marketBasisAdapter,
                                        matcherProtocolAdapter,
                                        agentProtocolAdapter,
                                        mqttv3Connection }) {
            a.bind(executorService);
            a.bind(pmTimeService);
            a.bind();
        }
    }

    protected void deactivateConcentratorUplink(AbstractConcentrator concentrator) {
        for (Adapter a : new Adapter[] { mqttv3Connection,
                                        matcherProtocolAdapter,
                                        agentProtocolAdapter,
                                        marketBasisAdapter }) {
            a.unbind();
            a.unbind(pmTimeService);
            a.unbind(executorService);
        }
    }

    @Override
    public MessageHandler onConnect(Connection connection) {
        FpaiAgent newAgent;

        ConfigurationService agentConfig = createAgentConfig(agentId++);

        if ("buffer".equals(connection.getPort().name())) {
            newAgent = new BufferAgent(agentConfig, connection, this);
        } else if ("timeshifter".equals(connection.getPort().name())) {
            newAgent = new TimeshifterAgent(agentConfig, connection, this);
        } else if ("unconstrained".equals(connection.getPort().name())) {
            newAgent = new UnconstrainedAgent(agentConfig, connection, this);
        } else if ("uncontrolled".equals(connection.getPort().name())) {
            newAgent = new UncontrolledAgent(agentConfig, connection, this);
        } else {
            // Wut?
            throw new IllegalArgumentException("Unknown type of connection");
        }
        registerAgent(newAgent);
        return newAgent;
    }

    private ConfigurationService createAgentConfig(int agentId) {
        Map<String, Object> agentProperties = new HashMap<String, Object>(properties);
        String prefix = "agent" + ConfigurationService.SEPARATOR + agentId;
        agentProperties.put(prefix + ".id", String.valueOf(agentId));
        agentProperties.put(prefix + ".matcher.id", concentrator.getId());
        agentProperties.put(prefix + ".agent.bid.log.level", "FULL_LOGGING");
        agentProperties.put(prefix + ".agent.price.log.level", "FULL_LOGGING");
        ConfigurationService agentConfig = new PrefixedConfiguration(agentProperties, prefix);
        return agentConfig;
    }

    @Reference
    public void setTimeService(TimeService timeService) {
        this.timeService = timeService;
        pmTimeService = new PMTimeServiceWrapper(timeService);
    }

    @Reference
    public void setScheduledExecutorService(ScheduledExecutorService executorService) {
        this.executorService = executorService;
    }

    public TimeService getTimeService() {
        return timeService;
    }

    public net.powermatcher.core.scheduler.service.TimeService getPMTimeService() {
        return pmTimeService;
    }

    public List<FpaiAgent> getAgentList() {
        return agents;
    }

    @Override
    public void registerAgent(FpaiAgent agent) {
        agent.bind(executorService);
        agent.bind(pmTimeService);

        if (widget != null) {
            agent.bind(widget);
        }

        agent.bind(concentrator);
        concentrator.bind(agent);

        agents.add(agent);
    }

    @Override
    public void unregisterAgent(FpaiAgent agent) {
        // unbind the agent from the concentrator and vice versa
        concentrator.unbind(agent);
        agent.unbind(concentrator);

        // unbind the executor service
        agent.unbind(executorService);
        agent.unbind(pmTimeService);
        agents.remove(agent);
    }

}
