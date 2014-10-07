package net.powermatcher.fpai.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import net.powermatcher.core.agent.framework.data.BidInfo;
import net.powermatcher.core.agent.framework.data.MarketBasis;
import net.powermatcher.core.agent.framework.data.PriceInfo;
import net.powermatcher.core.agent.framework.data.PricePoint;
import net.powermatcher.fpai.agents.BufferBid.BufferBidElement;

public class BufferBid extends TreeSet<BufferBidElement> {

    private static final long serialVersionUID = -8852055330954885453L;

    protected static class BufferBidElement implements Comparable<BufferBidElement> {

        public int actuatorId;
        public int runningModeId;
        public double priority;
        public double demandWatt;

        @Override
        public int compareTo(BufferBidElement o) {
            // Sort from high demand to low demand
            return Double.compare(o.demandWatt, demandWatt);
        }

        public int normalizedPrice(MarketBasis marketBasis) {
            return (int) Math.round(priority * marketBasis.getPriceSteps());
        }
    }

    public BidInfo toBidInfo(MarketBasis marketBasis) {
        if (isEmpty()) {
            throw new IllegalStateException("Cannot construct BidInfo if BufferBid is empty");
        }

        List<PricePoint> pricePoints = new ArrayList<PricePoint>();

        int previousNormalizedPrice = 0;

        BufferBidElement[] sorted = this.toArray(new BufferBidElement[size()]);

        for (BufferBidElement element : sorted) {
            pricePoints.add(new PricePoint(previousNormalizedPrice, element.demandWatt));
            pricePoints.add(new PricePoint(element.normalizedPrice(marketBasis), element.demandWatt));
            previousNormalizedPrice = element.normalizedPrice(marketBasis);
        }

        return new BidInfo(marketBasis, pricePoints.toArray(new PricePoint[pricePoints.size()]));
    }

    /**
     * Set priorities (prices) for the bid (part of the bid strategy)
     * 
     * @param soc
     *            State Of Change as double between 0 and 1
     */
    public void setPriorities(double soc) {
        if (isEmpty()) {
            throw new IllegalStateException("Cannot set priorities for empty BufferBid");
        } else if (size() == 1) {
            first().priority = 1;
        } else {
            int step = size() - 1;
            double cur = 0;
            for (BufferBidElement e : this) {
                e.priority = cur;
                cur += (soc / step);
            }
        }
    }

    public BufferBidElement runningModeForPrice(PriceInfo price) {
        if (isEmpty()) {
            throw new IllegalStateException("Cannot process price for empty BufferBid");
        } else if (size() == 1) {
            return first();
        } else {
            double priority = price.getNormalizedPrice() / (double) price.getMarketBasis().getPriceSteps();
            BufferBidElement pref = null;
            for (BufferBidElement e : this) {
                if (e.priority > priority) {
                    return pref;
                }
                pref = e;
            }
            return pref;
        }
    }
}
