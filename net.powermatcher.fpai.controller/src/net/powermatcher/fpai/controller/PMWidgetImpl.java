package net.powermatcher.fpai.controller;

import java.text.DateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import net.powermatcher.core.agent.framework.Agent;
import net.powermatcher.core.agent.framework.log.BidLogInfo;
import net.powermatcher.core.agent.framework.log.PriceLogInfo;

public class PMWidgetImpl implements PMWidget {
    private final PowerMatcherController controller;

    private final Map<String, BidLogInfo> latestBids;
    private volatile PriceLogInfo latestPrice;

    public PMWidgetImpl(PowerMatcherController powerMatcherController) {
        controller = powerMatcherController;
        latestBids = new ConcurrentHashMap<String, BidLogInfo>();
        latestPrice = null;
    }

    @Override
    public void handleBidLogInfo(BidLogInfo bidLogInfo) {
        latestBids.put(bidLogInfo.getAgentId(), bidLogInfo);
    }

    @Override
    public void handlePriceLogInfo(PriceLogInfo priceLogInfo) {
        latestPrice = priceLogInfo;
    }

    public Update update(Locale locale) {
        String price = latestPrice == null ? "no price yet" : String.format("%1.2f", latestPrice.getCurrentPrice());
        String timestamp = latestPrice == null ? "" : DateFormat.getTimeInstance(DateFormat.LONG, locale)
                                                                .format(latestPrice.getTimestamp());
        Update update = new Update(price, timestamp);

        for (Agent agent : controller.getAgentList()) {
            BidLogInfo lastBid = latestBids.get(agent.getId());
            String type = getAgentLabel(agent);
            String demands = lastBid == null ? "no bid yet" : getDemands(lastBid);
            update.addAgent(type, agent.getId(), demands);
        }

        return update;
    }

    private String getDemands(BidLogInfo bid) {
        double[] demand = bid.getBidInfo().getDemand();
        double first = demand[0] / 1000;
        double last = demand[demand.length - 1] / 1000;

        if (Math.abs(first - last) < .0001) {
            return String.format("%.2f kW", first);
        } else {
            return String.format("%.2f - %.2f kW", last, first);
        }
    }

    private String getAgentLabel(Agent agent) {
        String name = agent.getClass().getSimpleName();
        return name.replaceAll("(.)(\\p{Upper})", "$1 $2");
    }

    @Override
    public String getTitle(Locale locale) {
        return "PowerMatcher Controller";
    }

    public static class Update {
        private final SortedMap<String, SortedMap<String, String>> demands;
        private final String marketPrice;
        private final String timestamp;

        public Update(String marketPrice, String timestamp) {
            this.marketPrice = marketPrice;
            this.timestamp = timestamp;
            demands = new TreeMap<String, SortedMap<String, String>>();
        }

        public void addAgent(String type, String id, String demand) {
            SortedMap<String, String> d = demands.get(type);

            if (d == null) {
                demands.put(type, d = new TreeMap<String, String>());
            }

            d.put(id, demand);
        }

        public SortedMap<String, SortedMap<String, String>> getDemands() {
            return demands;
        }

        // public List<String> getAgentTypes() {
        // return agentTypes;
        // }
        //
        // public List<String> getDemands() {
        // return demands;
        // }

        public String getMarketPrice() {
            return marketPrice;
        }

        public String getTimestamp() {
            return timestamp;
        }
    }
}
