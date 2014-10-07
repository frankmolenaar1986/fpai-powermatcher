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
        public double priceFraction;
        public double demandWatt;

        @Override
        public int compareTo(BufferBidElement o) {
            // Sort from high demand to low demand
            return Double.compare(o.demandWatt, demandWatt);
        }

        public int normalizedPrice(MarketBasis marketBasis) {
            return (int) Math.round(priceFraction * marketBasis.getPriceSteps());
        }
    }

    public BidInfo toBidInfo(MarketBasis marketBasis) {
        if (isEmpty()) {
            throw new IllegalStateException("Cannot construct BidInfo if BufferBid is empty");
        }

        List<PricePoint> pricePoints = new ArrayList<PricePoint>();

        BufferBidElement[] sorted = this.toArray(new BufferBidElement[size()]);

        for (int i = 0; i < sorted.length - 1; i++) {
            // Loop doesn't visit last index
            int normalizedPrice = sorted[i].normalizedPrice(marketBasis);
            pricePoints.add(new PricePoint(normalizedPrice, sorted[i].demandWatt));
            pricePoints.add(new PricePoint(normalizedPrice, sorted[i + 1].demandWatt));
        }

        return new BidInfo(marketBasis, pricePoints.toArray(new PricePoint[pricePoints.size()]));
    }

    /**
     * Set price fractions for the bid (part of the bid strategy)
     * 
     * @param soc
     *            State Of Change as double between 0 and 1
     */
    public void setPriceFractions(double soc) {
        if (isEmpty()) {
            throw new IllegalStateException("Cannot set priorities for empty BufferBid");
        } else if (size() == 1) {
            first().priceFraction = 1;
        } else {
            double step = soc / (size() - 1);
            double cur = step;
            int cnt = 0;
            for (BufferBidElement e : this) {
                e.priceFraction = cur;
                if (cnt == size() - 1) {
                    // last element, should be 1
                    e.priceFraction = 1;
                    break;
                }
                cur += step;
                cnt++;
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
                if (e.priceFraction > priority) {
                    return pref;
                }
                pref = e;
            }
            return pref;
        }
    }
}
