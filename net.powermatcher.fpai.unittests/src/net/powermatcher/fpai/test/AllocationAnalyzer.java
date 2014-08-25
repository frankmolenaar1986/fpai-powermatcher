package net.powermatcher.fpai.test;

public class AllocationAnalyzer {
    //
    // private static Measurable<Power> getWattFromDemand(Allocation allocation) {
    // return allocation.getEnergyProfile().get(0).getAveragePower();
    // }
    //
    // public static void assertAllocationWithDemand(Allocation allocation,
    // TimeService timeService,
    // Measurable<Power> demand) {
    // assertNotNull(allocation);
    // assertAllocationStartsNow(allocation, timeService);
    // assertEquals(getWattFromDemand(allocation).doubleValue(WATT), demand.doubleValue(WATT), 0.0001);
    // }
    //
    // public static void assertDemandAtMost(Allocation allocation, TimeService timeService, Measurable<Power> demand) {
    // assertNotNull(allocation);
    // assertAllocationStartsNow(allocation, timeService);
    // double watt = getWattFromDemand(allocation).doubleValue(WATT);
    // double demandWatt = demand.doubleValue(WATT);
    // assertTrue("Demand should be at most " + demandWatt + ", was " + watt, watt <= demandWatt);
    // }
    //
    // public static void assertDemandAtLeast(Allocation allocation, TimeService timeService, Measurable<Power> demand)
    // {
    // assertNotNull(allocation);
    // assertAllocationStartsNow(allocation, timeService);
    // double watt = getWattFromDemand(allocation).doubleValue(WATT);
    // double demandWatt = demand.doubleValue(WATT);
    // assertTrue("Demand should be at least " + demandWatt + ", was " + watt, watt >= demandWatt);
    // }
    //
    // public static void assertNotRunningAllocation(Allocation allocation, TimeService timeService) {
    // assertNotNull(allocation);
    // assertAllocationStartsNow(allocation, timeService);
    // Element energy = allocation.getEnergyProfile().get(0);
    // assertEquals("Exected no demand, demand was " + energy.getAveragePower(),
    // 0,
    // energy.getEnergy().doubleValue(JOULE),
    // 0.001);
    // }
    //
    // public static void assertRunningAllocation(Allocation allocation, TimeService timeService) {
    // assertNotNull(allocation);
    // assertAllocationStartsNow(allocation, timeService);
    // assertFalse("Should be running allocation, but allocation has a demand of 0 watt",
    // allocation.getEnergyProfile().get(0).getEnergy().doubleValue(JOULE) == 0);
    // }
    //
    // public static void assertAllocationStartsNow(Allocation allocation, TimeService timeService) {
    // assertNotNull(allocation);
    // assertEquals(timeService.currentTimeMillis(), allocation.getStartTime().getTime());
    // }

}
