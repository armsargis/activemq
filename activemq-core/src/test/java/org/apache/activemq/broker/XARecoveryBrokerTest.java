/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker;

import java.util.concurrent.TimeUnit;
import javax.jms.JMSException;
import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import junit.framework.Test;

import org.apache.activemq.broker.jmx.RecoveredXATransactionViewMBean;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ConnectionInfo;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.DataArrayResponse;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.command.Response;
import org.apache.activemq.command.SessionInfo;
import org.apache.activemq.command.TransactionId;
import org.apache.activemq.command.TransactionInfo;
import org.apache.activemq.command.XATransactionId;
import org.apache.activemq.util.JMXSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used to simulate the recovery that occurs when a broker shuts down.
 * 
 * 
 */
public class XARecoveryBrokerTest extends BrokerRestartTestSupport {
    protected static final Logger LOG = LoggerFactory.getLogger(XARecoveryBrokerTest.class);
    public void testPreparedJmxView() throws Exception {

        ActiveMQDestination destination = createDestination();

        // Setup the producer and send the message.
        StubConnection connection = createConnection();
        ConnectionInfo connectionInfo = createConnectionInfo();
        SessionInfo sessionInfo = createSessionInfo(connectionInfo);
        ProducerInfo producerInfo = createProducerInfo(sessionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        connection.send(producerInfo);
        ConsumerInfo consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // Prepare 4 message sends.
        for (int i = 0; i < 4; i++) {
            // Begin the transaction.
            XATransactionId txid = createXATransaction(sessionInfo);
            connection.send(createBeginTransaction(connectionInfo, txid));

            Message message = createMessage(producerInfo, destination);
            message.setPersistent(true);
            message.setTransactionId(txid);
            connection.send(message);

            // Prepare
            connection.send(createPrepareTransaction(connectionInfo, txid));
        }

        Response response = connection.request(new TransactionInfo(connectionInfo.getConnectionId(), null, TransactionInfo.RECOVER));
        assertNotNull(response);
        DataArrayResponse dar = (DataArrayResponse)response;
        assertEquals(4, dar.getData().length);

        // restart the broker.
        restartBroker();

        connection = createConnection();
        connectionInfo = createConnectionInfo();
        connection.send(connectionInfo);


        response = connection.request(new TransactionInfo(connectionInfo.getConnectionId(), null, TransactionInfo.RECOVER));
        assertNotNull(response);
        dar = (DataArrayResponse)response;
        assertEquals(4, dar.getData().length);

        TransactionId first = (TransactionId)dar.getData()[0];
        // via jmx, force outcome
        for (int i = 0; i < 4; i++) {
            RecoveredXATransactionViewMBean mbean =  getProxyToPreparedTransactionViewMBean((TransactionId)dar.getData()[i]);
            if (i%2==0) {
                mbean.heuristicCommit();
            } else {
                mbean.heuristicRollback();
            }
        }

        // verify all completed
        response = connection.request(new TransactionInfo(connectionInfo.getConnectionId(), null, TransactionInfo.RECOVER));
        assertNotNull(response);
        dar = (DataArrayResponse)response;
        assertEquals(0, dar.getData().length);

        // verify mbeans gone
        try {
            RecoveredXATransactionViewMBean gone = getProxyToPreparedTransactionViewMBean(first);
            gone.heuristicRollback();
            fail("Excepted not found");
        } catch (InstanceNotFoundException expectedNotfound) {
        }
    }

    private RecoveredXATransactionViewMBean getProxyToPreparedTransactionViewMBean(TransactionId xid) throws MalformedObjectNameException, JMSException {

        ObjectName objectName = new ObjectName("org.apache.activemq:Type=RecoveredXaTransaction,Xid=" +
                JMXSupport.encodeObjectNamePart(xid.toString()) + ",BrokerName=localhost");
        RecoveredXATransactionViewMBean proxy = (RecoveredXATransactionViewMBean) broker.getManagementContext().newProxyInstance(objectName,
                RecoveredXATransactionViewMBean.class, true);
        return proxy;
    }

    public void testPreparedTransactionRecoveredOnRestart() throws Exception {

        ActiveMQDestination destination = createDestination();

        // Setup the producer and send the message.
        StubConnection connection = createConnection();
        ConnectionInfo connectionInfo = createConnectionInfo();
        SessionInfo sessionInfo = createSessionInfo(connectionInfo);
        ProducerInfo producerInfo = createProducerInfo(sessionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        connection.send(producerInfo);
        ConsumerInfo consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // Prepare 4 message sends.
        for (int i = 0; i < 4; i++) {
            // Begin the transaction.
            XATransactionId txid = createXATransaction(sessionInfo);
            connection.send(createBeginTransaction(connectionInfo, txid));

            Message message = createMessage(producerInfo, destination);
            message.setPersistent(true);
            message.setTransactionId(txid);
            connection.send(message);

            // Prepare
            connection.send(createPrepareTransaction(connectionInfo, txid));
        }

        // Since prepared but not committed.. they should not get delivered.
        assertNull(receiveMessage(connection));
        assertNoMessagesLeft(connection);
        connection.request(closeConnectionInfo(connectionInfo));

        // restart the broker.
        restartBroker();

        // Setup the consumer and try receive the message.
        connection = createConnection();
        connectionInfo = createConnectionInfo();
        sessionInfo = createSessionInfo(connectionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // Since prepared but not committed.. they should not get delivered.
        assertNull(receiveMessage(connection));
        assertNoMessagesLeft(connection);

        Response response = connection.request(new TransactionInfo(connectionInfo.getConnectionId(), null, TransactionInfo.RECOVER));
        assertNotNull(response);
        DataArrayResponse dar = (DataArrayResponse)response;
        assertEquals(4, dar.getData().length);

        // ensure we can close a connection with prepared transactions
        connection.request(closeConnectionInfo(connectionInfo));

        // open again  to deliver outcome
        connection = createConnection();
        connectionInfo = createConnectionInfo();
        sessionInfo = createSessionInfo(connectionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // Commit the prepared transactions.
        for (int i = 0; i < dar.getData().length; i++) {
            connection.send(createCommitTransaction2Phase(connectionInfo, (TransactionId)dar.getData()[i]));
        }

        // We should get the committed transactions.
        for (int i = 0; i < expectedMessageCount(4, destination); i++) {
            Message m = receiveMessage(connection, TimeUnit.SECONDS.toMillis(10));
            assertNotNull(m);
        }

        assertNoMessagesLeft(connection);
    }

    public void testQueuePersistentCommitedMessagesNotLostOnRestart() throws Exception {

        ActiveMQDestination destination = createDestination();

        // Setup the producer and send the message.
        StubConnection connection = createConnection();
        ConnectionInfo connectionInfo = createConnectionInfo();
        SessionInfo sessionInfo = createSessionInfo(connectionInfo);
        ProducerInfo producerInfo = createProducerInfo(sessionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        connection.send(producerInfo);

        // Begin the transaction.
        XATransactionId txid = createXATransaction(sessionInfo);
        connection.send(createBeginTransaction(connectionInfo, txid));

        for (int i = 0; i < 4; i++) {
            Message message = createMessage(producerInfo, destination);
            message.setPersistent(true);
            message.setTransactionId(txid);
            connection.send(message);
        }

        // Commit
        connection.send(createCommitTransaction1Phase(connectionInfo, txid));
        connection.request(closeConnectionInfo(connectionInfo));
        // restart the broker.
        restartBroker();

        // Setup the consumer and receive the message.
        connection = createConnection();
        connectionInfo = createConnectionInfo();
        sessionInfo = createSessionInfo(connectionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        ConsumerInfo consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        for (int i = 0; i < expectedMessageCount(4, destination); i++) {
            Message m = receiveMessage(connection);
            assertNotNull(m);
        }

        assertNoMessagesLeft(connection);
    }

    public void testQueuePersistentCommitedAcksNotLostOnRestart() throws Exception {

        ActiveMQDestination destination = createDestination();

        // Setup the producer and send the message.
        StubConnection connection = createConnection();
        ConnectionInfo connectionInfo = createConnectionInfo();
        SessionInfo sessionInfo = createSessionInfo(connectionInfo);
        ProducerInfo producerInfo = createProducerInfo(sessionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        connection.send(producerInfo);

        for (int i = 0; i < 4; i++) {
            Message message = createMessage(producerInfo, destination);
            message.setPersistent(true);
            connection.send(message);
        }

        // Begin the transaction.
        XATransactionId txid = createXATransaction(sessionInfo);
        connection.send(createBeginTransaction(connectionInfo, txid));

        ConsumerInfo consumerInfo;
        Message m = null;
        for (ActiveMQDestination dest : destinationList(destination)) {
            // Setup the consumer and receive the message.
            consumerInfo = createConsumerInfo(sessionInfo, dest);
            connection.send(consumerInfo);

            for (int i = 0; i < 4; i++) {
                m = receiveMessage(connection);
                assertNotNull(m);
            }

            MessageAck ack = createAck(consumerInfo, m, 4, MessageAck.STANDARD_ACK_TYPE);
            ack.setTransactionId(txid);
            connection.send(ack);
        }

        // Commit
        connection.request(createCommitTransaction1Phase(connectionInfo, txid));

        // restart the broker.
        restartBroker();

        // Setup the consumer and receive the message.
        connection = createConnection();
        connectionInfo = createConnectionInfo();
        sessionInfo = createSessionInfo(connectionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // No messages should be delivered.
        assertNoMessagesLeft(connection);

        m = receiveMessage(connection);
        assertNull(m);
    }
    
    public void testQueuePersistentPreparedAcksNotLostOnRestart() throws Exception {

        ActiveMQDestination destination = createDestination();

        // Setup the producer and send the message.
        StubConnection connection = createConnection();
        ConnectionInfo connectionInfo = createConnectionInfo();
        SessionInfo sessionInfo = createSessionInfo(connectionInfo);
        ProducerInfo producerInfo = createProducerInfo(sessionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        connection.send(producerInfo);

        for (int i = 0; i < 4; i++) {
            Message message = createMessage(producerInfo, destination);
            message.setPersistent(true);
            connection.send(message);
        }

        // Begin the transaction.
        XATransactionId txid = createXATransaction(sessionInfo);
        connection.send(createBeginTransaction(connectionInfo, txid));

        ConsumerInfo consumerInfo;
        Message m = null;
        for (ActiveMQDestination dest : destinationList(destination)) {
            // Setup the consumer and receive the message.
            consumerInfo = createConsumerInfo(sessionInfo, dest);
            connection.send(consumerInfo);

            for (int i = 0; i < 4; i++) {
                m = receiveMessage(connection);
                assertNotNull(m);
            }

            // one ack with last received, mimic a beforeEnd synchronization
            MessageAck ack = createAck(consumerInfo, m, 4, MessageAck.STANDARD_ACK_TYPE);
            ack.setTransactionId(txid);
            connection.send(ack);
        }

        connection.request(createPrepareTransaction(connectionInfo, txid));

        // restart the broker.
        restartBroker();

        connection = createConnection();
        connectionInfo = createConnectionInfo();
        connection.send(connectionInfo);

        // validate recovery
        TransactionInfo recoverInfo = new TransactionInfo(connectionInfo.getConnectionId(), null, TransactionInfo.RECOVER);
        DataArrayResponse dataArrayResponse = (DataArrayResponse)connection.request(recoverInfo);

        assertEquals("there is a prepared tx", 1, dataArrayResponse.getData().length);
        assertEquals("it matches", txid, dataArrayResponse.getData()[0]);

        sessionInfo = createSessionInfo(connectionInfo);
        connection.send(sessionInfo);
        consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);
        
        // no redelivery, exactly once semantics unless there is rollback
        m = receiveMessage(connection);
        assertNull(m);
        assertNoMessagesLeft(connection);

        connection.request(createCommitTransaction2Phase(connectionInfo, txid));

        // validate recovery complete
        dataArrayResponse = (DataArrayResponse)connection.request(recoverInfo);
        assertEquals("there are no prepared tx", 0, dataArrayResponse.getData().length);
    }

    public void testQueuePersistentPreparedAcksAvailableAfterRestartAndRollback() throws Exception {

        ActiveMQDestination destination = createDestination();

        // Setup the producer and send the message.
        StubConnection connection = createConnection();
        ConnectionInfo connectionInfo = createConnectionInfo();
        SessionInfo sessionInfo = createSessionInfo(connectionInfo);
        ProducerInfo producerInfo = createProducerInfo(sessionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        connection.send(producerInfo);

        for (int i = 0; i < 4; i++) {
            Message message = createMessage(producerInfo, destination);
            message.setPersistent(true);
            connection.send(message);
        }

        // Begin the transaction.
        XATransactionId txid = createXATransaction(sessionInfo);
        connection.send(createBeginTransaction(connectionInfo, txid));

        ConsumerInfo consumerInfo;
        Message message = null;
        for (ActiveMQDestination dest : destinationList(destination)) {
            // Setup the consumer and receive the message.
            consumerInfo = createConsumerInfo(sessionInfo, dest);
            connection.send(consumerInfo);

            for (int i = 0; i < 4; i++) {
                message = receiveMessage(connection);
                assertNotNull(message);
            }

            // one ack with last received, mimic a beforeEnd synchronization
            MessageAck ack = createAck(consumerInfo, message, 4, MessageAck.STANDARD_ACK_TYPE);
            ack.setTransactionId(txid);
            connection.send(ack);
        }

        connection.request(createPrepareTransaction(connectionInfo, txid));

        // restart the broker.
        restartBroker();

        connection = createConnection();
        connectionInfo = createConnectionInfo();
        connection.send(connectionInfo);

        // validate recovery
        TransactionInfo recoverInfo = new TransactionInfo(connectionInfo.getConnectionId(), null, TransactionInfo.RECOVER);
        DataArrayResponse dataArrayResponse = (DataArrayResponse)connection.request(recoverInfo);

        assertEquals("there is a prepared tx", 1, dataArrayResponse.getData().length);
        assertEquals("it matches", txid, dataArrayResponse.getData()[0]);

        sessionInfo = createSessionInfo(connectionInfo);
        connection.send(sessionInfo);
        consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // no redelivery, exactly once semantics while prepared
        message = receiveMessage(connection);
        assertNull(message);
        assertNoMessagesLeft(connection);

        // rollback so we get redelivery
        connection.request(createRollbackTransaction(connectionInfo, txid));

        // Begin new transaction for redelivery
        txid = createXATransaction(sessionInfo);
        connection.send(createBeginTransaction(connectionInfo, txid));

        for (ActiveMQDestination dest : destinationList(destination)) {
            // Setup the consumer and receive the message.
            consumerInfo = createConsumerInfo(sessionInfo, dest);
            connection.send(consumerInfo);

            for (int i = 0; i < 4; i++) {
                message = receiveMessage(connection);
                assertNotNull(message);
            }
            MessageAck ack = createAck(consumerInfo, message, 4, MessageAck.STANDARD_ACK_TYPE);
            ack.setTransactionId(txid);
            connection.send(ack);
        }

        // Commit
        connection.request(createCommitTransaction1Phase(connectionInfo, txid));

        // validate recovery complete
        dataArrayResponse = (DataArrayResponse)connection.request(recoverInfo);
        assertEquals("there are no prepared tx", 0, dataArrayResponse.getData().length);
    }

    private ActiveMQDestination[] destinationList(ActiveMQDestination dest) {
        return dest.isComposite() ? dest.getCompositeDestinations() : new ActiveMQDestination[]{dest};
    }

    private int expectedMessageCount(int i, ActiveMQDestination destination) {
        return i * (destination.isComposite() ? destination.getCompositeDestinations().length : 1);
    }

    public void testQueuePersistentUncommittedAcksLostOnRestart() throws Exception {

        ActiveMQDestination destination = createDestination();

        // Setup the producer and send the message.
        StubConnection connection = createConnection();
        ConnectionInfo connectionInfo = createConnectionInfo();
        SessionInfo sessionInfo = createSessionInfo(connectionInfo);
        ProducerInfo producerInfo = createProducerInfo(sessionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        connection.send(producerInfo);

        for (int i = 0; i < 4; i++) {
            Message message = createMessage(producerInfo, destination);
            message.setPersistent(true);
            connection.send(message);
        }

        // Setup the consumer and receive the message.
        ConsumerInfo consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // Begin the transaction.
        XATransactionId txid = createXATransaction(sessionInfo);
        connection.send(createBeginTransaction(connectionInfo, txid));
        Message m = null;
        for (int i = 0; i < 4; i++) {
            m = receiveMessage(connection);
            assertNotNull(m);
        }
        MessageAck ack = createAck(consumerInfo, m, 4, MessageAck.STANDARD_ACK_TYPE);
        ack.setTransactionId(txid);
        connection.send(ack);
        // Don't commit

        // restart the broker.
        restartBroker();

        // Setup the consumer and receive the message.
        connection = createConnection();
        connectionInfo = createConnectionInfo();
        sessionInfo = createSessionInfo(connectionInfo);
        connection.send(connectionInfo);
        connection.send(sessionInfo);
        consumerInfo = createConsumerInfo(sessionInfo, destination);
        connection.send(consumerInfo);

        // All messages should be re-delivered.
        for (int i = 0; i < 4; i++) {
            m = receiveMessage(connection);
            assertNotNull(m);
        }

        assertNoMessagesLeft(connection);
    }

    public static Test suite() {
        return suite(XARecoveryBrokerTest.class);
    }

    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }

    protected ActiveMQDestination createDestination() {
        return new ActiveMQQueue(getClass().getName() + "." + getName());
    }

}
