package net.powermatcher.fpai.util;

import net.powermatcher.core.scheduler.service.TimeService;

public class PMTimeServiceWrapper implements TimeService {

    private final org.flexiblepower.time.TimeService fpaiTimeSerice;

    public PMTimeServiceWrapper(org.flexiblepower.time.TimeService timeService) {
        fpaiTimeSerice = timeService;
    }

    @Override
    public long currentTimeMillis() {
        return fpaiTimeSerice.getCurrentTimeMillis();
    }

    @Override
    public int getRate() {
        // We don't know this... Does anyone use this by the way?
        return 0;
    }

}
