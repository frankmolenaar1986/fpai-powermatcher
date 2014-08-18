package net.powermatcher.fpai.test;

import static javax.measure.unit.SI.MILLI;
import static javax.measure.unit.SI.SECOND;

import java.util.Date;

import javax.measure.Measurable;
import javax.measure.quantity.Duration;

import net.powermatcher.core.scheduler.service.TimeService;

/**
 * Implementation of a TimeService which can be controlled programmatically
 */
public class MockTimeService implements TimeService, org.flexiblepower.time.TimeService {
    private long currentTime;

    public MockTimeService() {
        currentTime = System.currentTimeMillis();
    }

    public MockTimeService(long initialTime) {
        currentTime = initialTime;
    }

    public MockTimeService(Date initialTime) {
        currentTime = initialTime.getTime();
    }

    public void setAbsoluteTime(long time) {
        currentTime = time;
    }

    public void setAbsoluteTime(Date from) {
        setAbsoluteTime(from.getTime());
    }

    public void stepInTime(long stepMs) {
        currentTime += stepMs;
    }

    public void stepInTime(long value, java.util.concurrent.TimeUnit unit) {
        currentTime += java.util.concurrent.TimeUnit.MILLISECONDS.convert(value, unit);
    }

    public void stepInTime(Measurable<Duration> duration) {
        currentTime += duration.doubleValue(MILLI(SECOND));
    }

    @Override
    public long currentTimeMillis() {
        return currentTime;
    }

    @Override
    public int getRate() {
        return 0;
    }

    public Date getDate() {
        return new Date(currentTimeMillis());
    }

    @Override
    public String toString() {
        return "MockTimeService [currentTime=" + new Date(currentTime) + "]";
    }

    @Override
    public Date getTime() {
        return new Date(currentTime);
    }

    @Override
    public long getCurrentTimeMillis() {
        return currentTime;
    }

}
