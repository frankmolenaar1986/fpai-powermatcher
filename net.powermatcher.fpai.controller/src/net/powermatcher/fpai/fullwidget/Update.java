package net.powermatcher.fpai.fullwidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.powermatcher.core.agent.framework.log.BidLogInfo;
import net.powermatcher.core.agent.framework.log.PriceLogInfo;

public class Update {

    public double marketPrice;
    public List<AgentInfo> agents = new ArrayList<AgentInfo>();

    public Update(double marketPrice, List<AgentInfo> agents) {
        this.marketPrice = marketPrice;
        this.agents = agents;
    }

    public Update(PriceLogInfo lastPrice, Map<String, BidLogInfo> latestBids) {
        if (lastPrice == null) {
            marketPrice = 0;
        } else {
            marketPrice = lastPrice.getCurrentPrice();
        }
        for (BidLogInfo b : latestBids.values()) {
            agents.add(new AgentInfo(b.getAgentId(), b.getBidInfo()));
        }
    }

}
