package net.powermatcher.fpai.fullwidget;

import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.PricePoint;

public class AgentInfo {

    public String id;
    public double[][] coordinates;

    public AgentInfo(String id, BidInfo bid) {
        this.id = id;
        if (bid.getPricePoints() == null) {
            double[] demand = bid.getDemand();
            coordinates = new double[demand.length][];
            for (int i = 0; i < demand.length; i++) {
                coordinates[i] = new double[] { bid.getMarketBasis().toPrice(i), demand[i] };
            }
        } else {
            PricePoint[] pricePoints = bid.getPricePoints();
            coordinates = new double[pricePoints.length][];
            for (int i = 0; i < pricePoints.length; i++) {
                PricePoint p = pricePoints[i];
                coordinates[i] = new double[] { p.getNormalizedPrice(), p.getDemand() };
            }
        }
    }
}
