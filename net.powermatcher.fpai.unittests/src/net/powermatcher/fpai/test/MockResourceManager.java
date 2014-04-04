package net.powermatcher.fpai.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.flexiblepower.observation.Observation;
import org.flexiblepower.observation.ObservationProvider;
import org.flexiblepower.rai.Allocation;
import org.flexiblepower.rai.ControlSpace;
import org.flexiblepower.rai.Controller;
import org.flexiblepower.ral.ResourceControlParameters;
import org.flexiblepower.ral.ResourceDriver;
import org.flexiblepower.ral.ResourceManager;
import org.flexiblepower.ral.ResourceState;

public class MockResourceManager<CS extends ControlSpace> implements
                                                          ResourceManager<CS, ResourceState, ResourceControlParameters> {
    public static <CS extends ControlSpace> MockResourceManager<CS> create(String applianceId,
                                                                           Class<CS> controlSpaceType) {
        return new MockResourceManager<CS>(applianceId, controlSpaceType);
    }

    /** the allocations received */
    private final List<Allocation> allAllocations = new ArrayList<Allocation>();

    /** all controllers */
    private final List<Controller<? super CS>> controllers = new CopyOnWriteArrayList<Controller<? super CS>>();

    /** the id of the appliance managed */
    private final String applianceId;

    /** the type of appliance managed */
    private final Class<CS> controlSpaceType;

    /** the last / current control space */
    private CS controlSpace;

    public MockResourceManager(String applianceId, Class<CS> controlSpaceType) {
        this.applianceId = applianceId;
        this.controlSpaceType = controlSpaceType;
    }

    public String getResourceId() {
        return applianceId;
    }

    public CS getCurrentControlSpace() {
        return controlSpace;
    }

    @Override
    public void handleAllocation(Allocation allocation) {
        // add the new allocation and notify any thread waiting on this allocation
        synchronized (allAllocations) {
            allAllocations.add(allocation);
            allAllocations.notifyAll();
        }
    }

    /**
     * Returns the last known allocation. The method blocks until allocation is yet known, or the blocking thread is
     * interrupted (this method will return the last allocation if know or null if not).
     * 
     * @return The last allocation or null if no such allocation is available within the given timeout.
     */
    public Allocation getLastAllocation() {
        return getLastAllocation(0);
    }

    /**
     * Returns the last known allocation. The method blocks for the timeout (in milliseconds) if no allocation is yet
     * known. If the blocking thread is interrupted, this method will return the last allocation if know or null if not.
     * 
     * @param timeout
     *            The maximum number of milliseconds to wait for the allocation.
     * @return
     * @return The last allocation or null if no such allocation is available within the given timeout.
     */
    public Allocation getLastAllocation(long timeout) {
        synchronized (allAllocations) {
            // if empty wait for an allocation to be added (which unblocks the wait)
            // or the timeout expires or this thread is interrupted
            if (allAllocations.isEmpty() && timeout > 0) {
                try {
                    allAllocations.wait(timeout);
                } catch (InterruptedException e) {
                    // swallow
                }
            }

            // if there still are no allocations, return null
            if (allAllocations.isEmpty()) {
                return null;
            }

            // return the last bid and clear the list
            Allocation lastAllocation = allAllocations.remove(allAllocations.size() - 1);
            allAllocations.clear();
            return lastAllocation;
        }
    }

    /**
     * updates the current control space (any invocation on getCurrentControlSpace will return the given control space)
     * and notifies all registered resource manager listeners with the given control space
     */
    public void updateControlSpace(CS controlSpace) {
        this.controlSpace = controlSpace;

        for (Controller<? super CS> controller : controllers) {
            controller.controlSpaceUpdated(this, controlSpace);
        }
    }

    @Override
    public void consume(ObservationProvider<? extends ResourceState> source,
                        Observation<? extends ResourceState> observation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Class<CS> getControlSpaceType() {
        return this.controlSpaceType;
    }

    @Override
    public void setController(Controller<? super CS> controller) {
        controllers.add(controller);
    }

    @Override
    public void unsetController(Controller<? super CS> controller) {
        controllers.remove(controller);
    }

    @Override
    public void registerDriver(ResourceDriver<? extends ResourceState, ? super ResourceControlParameters> driver) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterDriver(ResourceDriver<? extends ResourceState, ? super ResourceControlParameters> driver) {
        throw new UnsupportedOperationException();
    }

}
