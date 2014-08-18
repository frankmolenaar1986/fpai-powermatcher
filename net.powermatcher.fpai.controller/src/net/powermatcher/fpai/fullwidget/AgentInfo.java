package net.powermatcher.fpai.fullwidget;

import net.powermatcher.core.agent.framework.data.BidInfo;

public class AgentInfo {

    public String id;
    public double[][] coordinates;

    public AgentInfo(String id, BidInfo bid) {
        this.id = id;
        double[] demand = bid.getDemand();
        coordinates = new double[demand.length][];
        for (int i = 0; i < demand.length; i++) {
            coordinates[i] = new double[] { bid.getMarketBasis().toPrice(i), demand[i] };
        }
    }
}
