package net.powermatcher.fpai.fullwidget;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.powermatcher.core.agent.framework.log.BidLogInfo;
import net.powermatcher.core.agent.framework.log.LogListenerService;
import net.powermatcher.core.agent.framework.log.PriceLogInfo;
import net.powermatcher.fpai.controller.PMController;
import net.powermatcher.fpai.controller.PMWidget;

import org.flexiblepower.ui.Widget;

import aQute.bnd.annotation.component.Component;

@Component(properties = { "widget.type=full", "widget.name=pmfullwidget" }, provide = Widget.class)
public class PMFullWidget implements PMWidget, LogListenerService {

    private PriceLogInfo lastPrice;
    private final Map<String, BidLogInfo> latestBids = new HashMap<String, BidLogInfo>();
    private final PMController controller;

    public PMFullWidget(PMController controller) {
        this.controller = controller;
    }

    @Override
    public String getTitle(Locale locale) {
        return "PowerMatcher";
    }

    @Override
    public void handleBidLogInfo(BidLogInfo bidLogInfo) {
        latestBids.put(bidLogInfo.getAgentId(), bidLogInfo);
    }

    @Override
    public void handlePriceLogInfo(PriceLogInfo priceLogInfo) {
        lastPrice = priceLogInfo;
    }

    public Update update() {
        return new Update(lastPrice, latestBids);
    }

}
