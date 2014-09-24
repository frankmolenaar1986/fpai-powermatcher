package net.powermatcher.fpai.agents;

import junit.framework.TestCase;

import org.flexiblepower.messaging.Connection;

public class UncontrolledTest extends TestCase {

    private UncontrolledAgent agent;
    private Connection connection;

    @Override
    protected void setUp() throws Exception {
        agent = new UncontrolledAgent(null, connection, null);
    }

    @Override
    protected void tearDown() throws Exception {
    }

    public void testUncontrolled() {
        // TODO
    }

}
