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
package org.apache.activemq.network;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;

import org.apache.activemq.Service;
import org.apache.activemq.advisory.AdvisorySupport;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.BrokerServiceAware;
import org.apache.activemq.broker.TransportConnection;
import org.apache.activemq.broker.region.AbstractRegion;
import org.apache.activemq.broker.region.DurableTopicSubscription;
import org.apache.activemq.broker.region.RegionBroker;
import org.apache.activemq.broker.region.Subscription;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQTempDestination;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.BrokerId;
import org.apache.activemq.command.BrokerInfo;
import org.apache.activemq.command.Command;
import org.apache.activemq.command.ConnectionError;
import org.apache.activemq.command.ConnectionId;
import org.apache.activemq.command.ConnectionInfo;
import org.apache.activemq.command.ConsumerId;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.DataStructure;
import org.apache.activemq.command.DestinationInfo;
import org.apache.activemq.command.ExceptionResponse;
import org.apache.activemq.command.KeepAliveInfo;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageDispatch;
import org.apache.activemq.command.NetworkBridgeFilter;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.command.RemoveInfo;
import org.apache.activemq.command.Response;
import org.apache.activemq.command.SessionInfo;
import org.apache.activemq.command.ShutdownInfo;
import org.apache.activemq.command.WireFormatInfo;
import org.apache.activemq.filter.DestinationFilter;
import org.apache.activemq.filter.MessageEvaluationContext;
import org.apache.activemq.thread.DefaultThreadPools;
import org.apache.activemq.thread.TaskRunnerFactory;
import org.apache.activemq.transport.DefaultTransportListener;
import org.apache.activemq.transport.FutureResponse;
import org.apache.activemq.transport.ResponseCallback;
import org.apache.activemq.transport.Transport;
import org.apache.activemq.transport.TransportDisposedIOException;
import org.apache.activemq.transport.TransportFilter;
import org.apache.activemq.transport.tcp.SslTransport;
import org.apache.activemq.util.IdGenerator;
import org.apache.activemq.util.IntrospectionSupport;
import org.apache.activemq.util.LongSequenceGenerator;
import org.apache.activemq.util.MarshallingSupport;
import org.apache.activemq.util.ServiceStopper;
import org.apache.activemq.util.ServiceSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A useful base class for implementing demand forwarding bridges.
 */
public abstract class DemandForwardingBridgeSupport implements NetworkBridge, BrokerServiceAware {
    private static final Logger LOG = LoggerFactory.getLogger(DemandForwardingBridgeSupport.class);
    private final TaskRunnerFactory asyncTaskRunner = DefaultThreadPools.getDefaultTaskRunnerFactory();
    protected static final String DURABLE_SUB_PREFIX = "NC-DS_";
    protected final Transport localBroker;
    protected final Transport remoteBroker;
    protected final IdGenerator idGenerator = new IdGenerator();
    protected final LongSequenceGenerator consumerIdGenerator = new LongSequenceGenerator();
    protected ConnectionInfo localConnectionInfo;
    protected ConnectionInfo remoteConnectionInfo;
    protected SessionInfo localSessionInfo;
    protected ProducerInfo producerInfo;
    protected String remoteBrokerName = "Unknown";
    protected String localClientId;
    protected ConsumerInfo demandConsumerInfo;
    protected int demandConsumerDispatched;
    protected final AtomicBoolean localBridgeStarted = new AtomicBoolean(false);
    protected final AtomicBoolean remoteBridgeStarted = new AtomicBoolean(false);
    protected AtomicBoolean disposed = new AtomicBoolean();
    protected BrokerId localBrokerId;
    protected ActiveMQDestination[] excludedDestinations;
    protected ActiveMQDestination[] dynamicallyIncludedDestinations;
    protected ActiveMQDestination[] staticallyIncludedDestinations;
    protected ActiveMQDestination[] durableDestinations;
    protected final ConcurrentHashMap<ConsumerId, DemandSubscription> subscriptionMapByLocalId = new ConcurrentHashMap<ConsumerId, DemandSubscription>();
    protected final ConcurrentHashMap<ConsumerId, DemandSubscription> subscriptionMapByRemoteId = new ConcurrentHashMap<ConsumerId, DemandSubscription>();
    protected final BrokerId localBrokerPath[] = new BrokerId[] { null };
    protected CountDownLatch startedLatch = new CountDownLatch(2);
    protected CountDownLatch localStartedLatch = new CountDownLatch(1);
    protected final AtomicBoolean lastConnectSucceeded = new AtomicBoolean(false);
    protected NetworkBridgeConfiguration configuration;
    protected final NetworkBridgeFilterFactory defaultFilterFactory = new DefaultNetworkBridgeFilterFactory();

    protected final BrokerId remoteBrokerPath[] = new BrokerId[] {null};
    protected Object brokerInfoMutex = new Object();
    protected BrokerId remoteBrokerId;

    final AtomicLong enqueueCounter = new AtomicLong();
    final AtomicLong dequeueCounter = new AtomicLong();

    private NetworkBridgeListener networkBridgeListener;
    private boolean createdByDuplex;
    private BrokerInfo localBrokerInfo;
    private BrokerInfo remoteBrokerInfo;

    private final AtomicBoolean started = new AtomicBoolean();
    private TransportConnection duplexInitiatingConnection;
    private BrokerService brokerService = null;
    private ObjectName mbeanObjectName;

    public DemandForwardingBridgeSupport(NetworkBridgeConfiguration configuration, Transport localBroker, Transport remoteBroker) {
        this.configuration = configuration;
        this.localBroker = localBroker;
        this.remoteBroker = remoteBroker;
    }

    public void duplexStart(TransportConnection connection, BrokerInfo localBrokerInfo, BrokerInfo remoteBrokerInfo) throws Exception {
        this.localBrokerInfo = localBrokerInfo;
        this.remoteBrokerInfo = remoteBrokerInfo;
        this.duplexInitiatingConnection = connection;
        start();
        serviceRemoteCommand(remoteBrokerInfo);
    }

    public void start() throws Exception {
        if (started.compareAndSet(false, true)) {
            localBroker.setTransportListener(new DefaultTransportListener() {

                @Override
                public void onCommand(Object o) {
                    Command command = (Command) o;
                    serviceLocalCommand(command);
                }

                @Override
                public void onException(IOException error) {
                    serviceLocalException(error);
                }
            });
            remoteBroker.setTransportListener(new DefaultTransportListener() {

                public void onCommand(Object o) {
                    Command command = (Command) o;
                    serviceRemoteCommand(command);
                }

                public void onException(IOException error) {
                    serviceRemoteException(error);
                }

            });

            localBroker.start();
            remoteBroker.start();
            if (!disposed.get()) {
                try {
                    triggerRemoteStartBridge();
                } catch (IOException e) {
                    LOG.warn("Caught exception from remote start", e);
                }
            } else {
                LOG.warn ("Bridge was disposed before the start() method was fully executed.");
                throw new TransportDisposedIOException();
            }
        }
    }

    protected void triggerLocalStartBridge() throws IOException {
        asyncTaskRunner.execute(new Runnable() {
            public void run() {
                final String originalName = Thread.currentThread().getName();
                Thread.currentThread().setName("StartLocalBridge: localBroker=" + localBroker);
                try {
                    startLocalBridge();
                } catch (Throwable e) {
                    serviceLocalException(e);
                } finally {
                    Thread.currentThread().setName(originalName);
                }
            }
        });
    }

    protected void triggerRemoteStartBridge() throws IOException {
        asyncTaskRunner.execute(new Runnable() {
            public void run() {
                final String originalName = Thread.currentThread().getName();
                Thread.currentThread().setName("StartRemoteBridge: remoteBroker=" + remoteBroker);
                try {
                    startRemoteBridge();
                } catch (Exception e) {
                    serviceRemoteException(e);
                } finally {
                    Thread.currentThread().setName(originalName);
                }
            }
        });
    }

    private void startLocalBridge() throws Throwable {
        if (localBridgeStarted.compareAndSet(false, true)) {
            synchronized (this) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace(configuration.getBrokerName() + " starting local Bridge, localBroker=" + localBroker);
                }
                if (!disposed.get()) {
                    localConnectionInfo = new ConnectionInfo();
                    localConnectionInfo.setConnectionId(new ConnectionId(idGenerator.generateId()));
                    localClientId = configuration.getName() + "_" + remoteBrokerName + "_inbound_" + configuration.getBrokerName();
                    localConnectionInfo.setClientId(localClientId);
                    localConnectionInfo.setUserName(configuration.getUserName());
                    localConnectionInfo.setPassword(configuration.getPassword());
                    Transport originalTransport = remoteBroker;
                    while (originalTransport instanceof TransportFilter) {
                        originalTransport = ((TransportFilter) originalTransport).getNext();
                    }
                    if (originalTransport instanceof SslTransport) {
                        X509Certificate[] peerCerts = ((SslTransport) originalTransport).getPeerCertificates();
                        localConnectionInfo.setTransportContext(peerCerts);
                    }
                    // sync requests that may fail
                    Object resp = localBroker.request(localConnectionInfo);
                    if (resp instanceof ExceptionResponse) {
                        throw ((ExceptionResponse)resp).getException();
                    }
                    localSessionInfo = new SessionInfo(localConnectionInfo, 1);
                    localBroker.oneway(localSessionInfo);

                    brokerService.getBroker().networkBridgeStarted(remoteBrokerInfo, this.createdByDuplex, remoteBroker.toString());
                    NetworkBridgeListener l = this.networkBridgeListener;
                    if (l != null) {
                        l.onStart(this);
                    }
                    LOG.info("Network connection between " + localBroker + " and " + remoteBroker + "(" + remoteBrokerName + ") has been established.");

                } else {
                    LOG.warn ("Bridge was disposed before the startLocalBridge() method was fully executed.");
                }
                startedLatch.countDown();
                localStartedLatch.countDown();
                if (!disposed.get()) {
                    setupStaticDestinations();
                } else {
                    LOG.warn("Network connection between " + localBroker + " and " + remoteBroker + "(" + remoteBrokerName + ") was interrupted during establishment.");
                }
            }
        }
    }

    protected void startRemoteBridge() throws Exception {
        if (remoteBridgeStarted.compareAndSet(false, true)) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(configuration.getBrokerName() + " starting remote Bridge, remoteBroker=" + remoteBroker);
            }
            synchronized (this) {
                if (!isCreatedByDuplex()) {
                    BrokerInfo brokerInfo = new BrokerInfo();
                    brokerInfo.setBrokerName(configuration.getBrokerName());
                    brokerInfo.setBrokerURL(configuration.getBrokerURL());
                    brokerInfo.setNetworkConnection(true);
                    brokerInfo.setDuplexConnection(configuration.isDuplex());
                    // set our properties
                    Properties props = new Properties();
                    IntrospectionSupport.getProperties(configuration, props, null);
                    String str = MarshallingSupport.propertiesToString(props);
                    brokerInfo.setNetworkProperties(str);
                    brokerInfo.setBrokerId(this.localBrokerId);
                    remoteBroker.oneway(brokerInfo);
                }
                if (remoteConnectionInfo != null) {
                    remoteBroker.oneway(remoteConnectionInfo.createRemoveCommand());
                }
                remoteConnectionInfo = new ConnectionInfo();
                remoteConnectionInfo.setConnectionId(new ConnectionId(idGenerator.generateId()));
                remoteConnectionInfo.setClientId(configuration.getName() + "_" + configuration.getBrokerName() + "_outbound");
                remoteConnectionInfo.setUserName(configuration.getUserName());
                remoteConnectionInfo.setPassword(configuration.getPassword());
                remoteBroker.oneway(remoteConnectionInfo);

                SessionInfo remoteSessionInfo = new SessionInfo(remoteConnectionInfo, 1);
                remoteBroker.oneway(remoteSessionInfo);
                producerInfo = new ProducerInfo(remoteSessionInfo, 1);
                producerInfo.setResponseRequired(false);
                remoteBroker.oneway(producerInfo);
                // Listen to consumer advisory messages on the remote broker to
                // determine demand.
                if (!configuration.isStaticBridge()) {
                    demandConsumerInfo = new ConsumerInfo(remoteSessionInfo, 1);
                    demandConsumerInfo.setDispatchAsync(configuration.isDispatchAsync());
                    String advisoryTopic = configuration.getDestinationFilter();
                    if (configuration.isBridgeTempDestinations()) {
                        advisoryTopic += "," + AdvisorySupport.TEMP_DESTINATION_COMPOSITE_ADVISORY_TOPIC;
                    }
                    demandConsumerInfo.setDestination(new ActiveMQTopic(advisoryTopic));
                    demandConsumerInfo.setPrefetchSize(configuration.getPrefetchSize());
                    remoteBroker.oneway(demandConsumerInfo);
                }
                startedLatch.countDown();
            }
        }
    }

    public void stop() throws Exception {
        if (started.compareAndSet(true, false)) {
            if (disposed.compareAndSet(false, true)) {
                LOG.debug(" stopping " + configuration.getBrokerName() + " bridge to " + remoteBrokerName);
                NetworkBridgeListener l = this.networkBridgeListener;
                if (l != null) {
                    l.onStop(this);
                }
                try {
                    remoteBridgeStarted.set(false);
                    final CountDownLatch sendShutdown = new CountDownLatch(1);
                    asyncTaskRunner.execute(new Runnable() {
                        public void run() {
                            try {
                                localBroker.oneway(new ShutdownInfo());
                                sendShutdown.countDown();
                                remoteBroker.oneway(new ShutdownInfo());
                            } catch (Throwable e) {
                                LOG.debug("Caught exception sending shutdown", e);
                            } finally {
                                sendShutdown.countDown();
                            }

                        }
                    });
                    if (!sendShutdown.await(10, TimeUnit.SECONDS)) {
                        LOG.info("Network Could not shutdown in a timely manner");
                    }
                } finally {
                    ServiceStopper ss = new ServiceStopper();
                    ss.stop(remoteBroker);
                    ss.stop(localBroker);
                    // Release the started Latch since another thread could be
                    // stuck waiting for it to start up.
                    startedLatch.countDown();
                    startedLatch.countDown();
                    localStartedLatch.countDown();
                    ss.throwFirstException();
                }
            }
            if (remoteBrokerInfo != null) {
                brokerService.getBroker().removeBroker(null, remoteBrokerInfo);
                brokerService.getBroker().networkBridgeStopped(remoteBrokerInfo);
                LOG.info(configuration.getBrokerName() + " bridge to " + remoteBrokerName + " stopped");
            }
        }
    }

    public void serviceRemoteException(Throwable error) {
        if (!disposed.get()) {
            if (error instanceof SecurityException || error instanceof GeneralSecurityException) {
                LOG.error("Network connection between " + localBroker + " and " + remoteBroker + " shutdown due to a remote error: " + error);
            } else {
                LOG.warn("Network connection between " + localBroker + " and " + remoteBroker + " shutdown due to a remote error: " + error);
            }
            LOG.debug("The remote Exception was: " + error, error);
            asyncTaskRunner.execute(new Runnable() {
                public void run() {
                    ServiceSupport.dispose(getControllingService());
                }
            });
            fireBridgeFailed();
        }
    }

    protected void serviceRemoteCommand(Command command) {
        if (!disposed.get()) {
            try {
                if (command.isMessageDispatch()) {
                    waitStarted();
                    MessageDispatch md = (MessageDispatch) command;
                    serviceRemoteConsumerAdvisory(md.getMessage().getDataStructure());
                    ackAdvisory(md.getMessage());
                } else if (command.isBrokerInfo()) {
                    lastConnectSucceeded.set(true);
                    remoteBrokerInfo = (BrokerInfo) command;
                    Properties props = MarshallingSupport.stringToProperties(remoteBrokerInfo.getNetworkProperties());
                    try {
                        IntrospectionSupport.getProperties(configuration, props, null);
                        if (configuration.getExcludedDestinations() != null) {
                            excludedDestinations = configuration.getExcludedDestinations().toArray(
                                    new ActiveMQDestination[configuration.getExcludedDestinations().size()]);
                        }
                        if (configuration.getStaticallyIncludedDestinations() != null) {
                            staticallyIncludedDestinations = configuration.getStaticallyIncludedDestinations().toArray(
                                    new ActiveMQDestination[configuration.getStaticallyIncludedDestinations().size()]);
                        }
                        if (configuration.getDynamicallyIncludedDestinations() != null) {
                            dynamicallyIncludedDestinations = configuration.getDynamicallyIncludedDestinations()
                                    .toArray(
                                            new ActiveMQDestination[configuration.getDynamicallyIncludedDestinations()
                                                    .size()]);
                        }
                    } catch (Throwable t) {
                        LOG.error("Error mapping remote destinations", t);
                    }
                    serviceRemoteBrokerInfo(command);
                    // Let the local broker know the remote broker's ID.
                    localBroker.oneway(command);
                    // new peer broker (a consumer can work with remote broker also)
                    brokerService.getBroker().addBroker(null, remoteBrokerInfo);
                } else if (command.getClass() == ConnectionError.class) {
                    ConnectionError ce = (ConnectionError) command;
                    serviceRemoteException(ce.getException());
                } else {
                    if (isDuplex()) {
                        if (command.isMessage()) {
                            ActiveMQMessage message = (ActiveMQMessage) command;
                            if (AdvisorySupport.isConsumerAdvisoryTopic(message.getDestination())
                                || AdvisorySupport.isDestinationAdvisoryTopic(message.getDestination())) {
                                serviceRemoteConsumerAdvisory(message.getDataStructure());
                                ackAdvisory(message);
                            } else {
                                if (!isPermissableDestination(message.getDestination(), true)) {
                                    return;
                                }
                                if (message.isResponseRequired()) {
                                    Response reply = new Response();
                                    reply.setCorrelationId(message.getCommandId());
                                    localBroker.oneway(message);
                                    remoteBroker.oneway(reply);
                                } else {
                                    localBroker.oneway(message);
                                }
                            }
                        } else {
                            switch (command.getDataStructureType()) {
                            case ConnectionInfo.DATA_STRUCTURE_TYPE:
                            case SessionInfo.DATA_STRUCTURE_TYPE:
                            case ProducerInfo.DATA_STRUCTURE_TYPE:
                                localBroker.oneway(command);
                                break;
                            case MessageAck.DATA_STRUCTURE_TYPE:
                                MessageAck ack = (MessageAck) command;
                                DemandSubscription localSub = subscriptionMapByRemoteId.get(ack.getConsumerId());
                                if (localSub != null) {
                                    ack.setConsumerId(localSub.getLocalInfo().getConsumerId());
                                    localBroker.oneway(ack);
                                } else {
                                    LOG.warn("Matching local subscription not found for ack: " + ack);
                                }
                                break;
                            case ConsumerInfo.DATA_STRUCTURE_TYPE:
                                localStartedLatch.await();
                                if (started.get()) {
                                    if (!addConsumerInfo((ConsumerInfo) command)) {
                                        if (LOG.isDebugEnabled()) {
                                            LOG.debug("Ignoring ConsumerInfo: " + command);
                                        }
                                    } else {
                                        if (LOG.isTraceEnabled()) {
                                            LOG.trace("Adding ConsumerInfo: " + command);
                                        }
                                    }
                                } else {
                                    // received a subscription whilst stopping
                                    LOG.warn("Stopping - ignoring ConsumerInfo: " + command);
                                }
                                break;
                            case ShutdownInfo.DATA_STRUCTURE_TYPE:
                                // initiator is shutting down, controlled case
                                // abortive close dealt with by inactivity monitor
                                LOG.info("Stopping network bridge on shutdown of remote broker");
                                serviceRemoteException(new IOException(command.toString()));
                                break;
                            default:
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("Ignoring remote command: " + command);
                                }
                            }
                        }
                    } else {
                        switch (command.getDataStructureType()) {
                        case KeepAliveInfo.DATA_STRUCTURE_TYPE:
                        case WireFormatInfo.DATA_STRUCTURE_TYPE:
                        case ShutdownInfo.DATA_STRUCTURE_TYPE:
                            break;
                        default:
                            LOG.warn("Unexpected remote command: " + command);
                        }
                    }
                }
            } catch (Throwable e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Exception processing remote command: " + command, e);
                }
                serviceRemoteException(e);
            }
        }
    }

    private void ackAdvisory(Message message) throws IOException {
        demandConsumerDispatched++;
        if (demandConsumerDispatched > (demandConsumerInfo.getPrefetchSize() * .75)) {
            MessageAck ack = new MessageAck(message, MessageAck.STANDARD_ACK_TYPE, demandConsumerDispatched);
            ack.setConsumerId(demandConsumerInfo.getConsumerId());
            remoteBroker.oneway(ack);
            demandConsumerDispatched = 0;
        }
    }

    private void serviceRemoteConsumerAdvisory(DataStructure data) throws IOException {
        final int networkTTL = configuration.getNetworkTTL();
        if (data.getClass() == ConsumerInfo.class) {
            // Create a new local subscription
            ConsumerInfo info = (ConsumerInfo) data;
            BrokerId[] path = info.getBrokerPath();

            if (info.isBrowser()) {
                if (LOG.isDebugEnabled()) {
                    LOG.info(configuration.getBrokerName() + " Ignoring sub from " + remoteBrokerName + ", browsers explicitly suppressed");
                }
                return;
            }

            if (path != null && path.length >= networkTTL) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getBrokerName() + " Ignoring sub from " + remoteBrokerName + ", restricted to " + networkTTL + " network hops only : " + info);
                }
                return;
            }
            if (contains(path, localBrokerPath[0])) {
                // Ignore this consumer as it's a consumer we locally sent to the broker.
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getBrokerName() + " Ignoring sub from " + remoteBrokerName + ", already routed through this broker once : " + info);
                }
                return;
            }
            if (!isPermissableDestination(info.getDestination())) {
                // ignore if not in the permitted or in the excluded list
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getBrokerName() + " Ignoring sub from " + remoteBrokerName + ", destination " + info.getDestination() + " is not permiited :" + info);
                }
                return;
            }

            // in a cyclic network there can be multiple bridges per broker that can propagate
            // a network subscription so there is a need to synchronise on a shared entity
            synchronized (brokerService.getVmConnectorURI()) {
                if (addConsumerInfo(info)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(configuration.getBrokerName() + " bridged sub on " + localBroker + " from " + remoteBrokerName + " : " + info);
                    }
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(configuration.getBrokerName() + " Ignoring sub from " + remoteBrokerName + " as already subscribed to matching destination : " + info);
                    }
                }
            }
        } else if (data.getClass() == DestinationInfo.class) {
            // It's a destination info - we want to pass up
            // information about temporary destinations
            DestinationInfo destInfo = (DestinationInfo) data;
            BrokerId[] path = destInfo.getBrokerPath();
            if (path != null && path.length >= networkTTL) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getBrokerName() + " Ignoring destination " + destInfo + " restricted to " + networkTTL + " network hops only");
                }
                return;
            }
            if (contains(destInfo.getBrokerPath(), localBrokerPath[0])) {
                // Ignore this consumer as it's a consumer we locally sent to
                // the broker.
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getBrokerName() + " Ignoring destination " + destInfo + " already routed through this broker once");
                }
                return;
            }
            destInfo.setConnectionId(localConnectionInfo.getConnectionId());
            if (destInfo.getDestination() instanceof ActiveMQTempDestination) {
                // re-set connection id so comes from here
                ActiveMQTempDestination tempDest = (ActiveMQTempDestination) destInfo.getDestination();
                tempDest.setConnectionId(localSessionInfo.getSessionId().getConnectionId());
            }
            destInfo.setBrokerPath(appendToBrokerPath(destInfo.getBrokerPath(), getRemoteBrokerPath()));
            if (LOG.isTraceEnabled()) {
                LOG.trace(configuration.getBrokerName() + " bridging " + (destInfo.isAddOperation() ? "add" : "remove") + " destination on " + localBroker + " from " + remoteBrokerName + ", destination: " + destInfo);
            }
            localBroker.oneway(destInfo);
        } else if (data.getClass() == RemoveInfo.class) {
            ConsumerId id = (ConsumerId) ((RemoveInfo) data).getObjectId();
            removeDemandSubscription(id);
        }
    }

    public void serviceLocalException(Throwable error) {
        if (!disposed.get()) {
            LOG.info("Network connection between " + localBroker + " and " + remoteBroker + " shutdown due to a local error: " + error);
            LOG.debug("The local Exception was:" + error, error);
            asyncTaskRunner.execute(new Runnable() {
                public void run() {
                    ServiceSupport.dispose(getControllingService());
                }
            });
            fireBridgeFailed();
        }
    }

    protected Service getControllingService() {
        return duplexInitiatingConnection != null ? duplexInitiatingConnection : DemandForwardingBridgeSupport.this;
    }

    protected void addSubscription(DemandSubscription sub) throws IOException {
        if (sub != null) {
            localBroker.oneway(sub.getLocalInfo());
        }
    }

    protected void removeSubscription(final DemandSubscription sub) throws IOException {
        if (sub != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(configuration.getBrokerName() + " remove local subscription for remote " + sub.getRemoteInfo().getConsumerId());
            }
            subscriptionMapByLocalId.remove(sub.getLocalInfo().getConsumerId());
            subscriptionMapByRemoteId.remove(sub.getRemoteInfo().getConsumerId());

            // continue removal in separate thread to free up this thread for outstanding responses
            asyncTaskRunner.execute(new Runnable() {
                public void run() {
                    sub.waitForCompletion();
                    try {
                        localBroker.oneway(sub.getLocalInfo().createRemoveCommand());
                    } catch (IOException e) {
                        LOG.warn("failed to deliver remove command for local subscription, for remote " + sub.getRemoteInfo().getConsumerId(), e);
                    }
                }
            });
        }
    }

    protected Message configureMessage(MessageDispatch md) {
        Message message = md.getMessage().copy();
        // Update the packet to show where it came from.
        message.setBrokerPath(appendToBrokerPath(message.getBrokerPath(), localBrokerPath));
        message.setProducerId(producerInfo.getProducerId());
        message.setDestination(md.getDestination());
        if (message.getOriginalTransactionId() == null) {
            message.setOriginalTransactionId(message.getTransactionId());
        }
        message.setTransactionId(null);
        return message;
    }

    protected void serviceLocalCommand(Command command) {
        if (!disposed.get()) {
            try {
                if (command.isMessageDispatch()) {
                    enqueueCounter.incrementAndGet();
                    final MessageDispatch md = (MessageDispatch) command;
                    final DemandSubscription sub = subscriptionMapByLocalId.get(md.getConsumerId());
                    if (sub != null && md.getMessage() != null && sub.incrementOutstandingResponses()) {

                        if (suppressMessageDispatch(md, sub)) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(configuration.getBrokerName() + " message not forwarded to " + remoteBrokerName + " because message came from there or fails networkTTL, brokerPath: " + Arrays.toString(md.getMessage().getBrokerPath()) + ", message: " + md.getMessage());
                            }
                            // still ack as it may be durable
                            try {
                                localBroker.oneway(new MessageAck(md, MessageAck.INDIVIDUAL_ACK_TYPE, 1));
                            } finally {
                                sub.decrementOutstandingResponses();
                            }
                            return;
                        }

                        Message message = configureMessage(md);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("bridging (" + configuration.getBrokerName() + " -> " + remoteBrokerName + ") " + (LOG.isTraceEnabled() ? message : message.getMessageId()) + ", consumer: " + md.getConsumerId() + ", destination " + message.getDestination() + ", brokerPath: " + Arrays.toString(message.getBrokerPath()) + ", message: " + message);
                        }

                        if (!configuration.isAlwaysSyncSend() && !message.isPersistent()) {

                            // If the message was originally sent using async
                            // send, we will preserve that QOS
                            // by bridging it using an async send (small chance
                            // of message loss).
                            try {
                                remoteBroker.oneway(message);
                                localBroker.oneway(new MessageAck(md, MessageAck.INDIVIDUAL_ACK_TYPE, 1));
                                dequeueCounter.incrementAndGet();
                            } finally {
                                sub.decrementOutstandingResponses();
                            }

                        } else {

                            // The message was not sent using async send, so we
                            // should only ack the local
                            // broker when we get confirmation that the remote
                            // broker has received the message.
                            ResponseCallback callback = new ResponseCallback() {
                                public void onCompletion(FutureResponse future) {
                                    try {
                                        Response response = future.getResult();
                                        if (response.isException()) {
                                            ExceptionResponse er = (ExceptionResponse) response;
                                            serviceLocalException(er.getException());
                                        } else {
                                            localBroker.oneway(new MessageAck(md, MessageAck.INDIVIDUAL_ACK_TYPE, 1));
                                            dequeueCounter.incrementAndGet();
                                        }
                                    } catch (IOException e) {
                                        serviceLocalException(e);
                                    } finally {
                                        sub.decrementOutstandingResponses();
                                    }
                                }
                            };

                            remoteBroker.asyncRequest(message, callback);

                        }
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("No subscription registered with this network bridge for consumerId " + md.getConsumerId() + " for message: " + md.getMessage());
                        }
                    }
                } else if (command.isBrokerInfo()) {
                    localBrokerInfo = (BrokerInfo) command;
                    serviceLocalBrokerInfo(command);
                } else if (command.isShutdownInfo()) {
                    LOG.info(configuration.getBrokerName() + " Shutting down");
                    stop();
                } else if (command.getClass() == ConnectionError.class) {
                    ConnectionError ce = (ConnectionError) command;
                    serviceLocalException(ce.getException());
                } else {
                    switch (command.getDataStructureType()) {
                    case WireFormatInfo.DATA_STRUCTURE_TYPE:
                        break;
                    default:
                        LOG.warn("Unexpected local command: " + command);
                    }
                }
            } catch (Throwable e) {
                LOG.warn("Caught an exception processing local command", e);
                serviceLocalException(e);
            }
        }
    }

    private boolean suppressMessageDispatch(MessageDispatch md, DemandSubscription sub) throws Exception {
        boolean suppress = false;
        // for durable subs, suppression via filter leaves dangling acks so we need to
        // check here and allow the ack irrespective
        if (sub.getLocalInfo().isDurable()) {
            MessageEvaluationContext messageEvalContext = new MessageEvaluationContext();
            messageEvalContext.setMessageReference(md.getMessage());
            messageEvalContext.setDestination(md.getDestination());
            suppress = !sub.getNetworkBridgeFilter().matches(messageEvalContext);
        }
        return suppress;
    }

    /**
     * @return Returns the dynamicallyIncludedDestinations.
     */
    public ActiveMQDestination[] getDynamicallyIncludedDestinations() {
        return dynamicallyIncludedDestinations;
    }

    /**
     * @param dynamicallyIncludedDestinations The
     *            dynamicallyIncludedDestinations to set.
     */
    public void setDynamicallyIncludedDestinations(ActiveMQDestination[] dynamicallyIncludedDestinations) {
        this.dynamicallyIncludedDestinations = dynamicallyIncludedDestinations;
    }

    /**
     * @return Returns the excludedDestinations.
     */
    public ActiveMQDestination[] getExcludedDestinations() {
        return excludedDestinations;
    }

    /**
     * @param excludedDestinations The excludedDestinations to set.
     */
    public void setExcludedDestinations(ActiveMQDestination[] excludedDestinations) {
        this.excludedDestinations = excludedDestinations;
    }

    /**
     * @return Returns the staticallyIncludedDestinations.
     */
    public ActiveMQDestination[] getStaticallyIncludedDestinations() {
        return staticallyIncludedDestinations;
    }

    /**
     * @param staticallyIncludedDestinations The staticallyIncludedDestinations
     *            to set.
     */
    public void setStaticallyIncludedDestinations(ActiveMQDestination[] staticallyIncludedDestinations) {
        this.staticallyIncludedDestinations = staticallyIncludedDestinations;
    }

    /**
     * @return Returns the durableDestinations.
     */
    public ActiveMQDestination[] getDurableDestinations() {
        return durableDestinations;
    }

    /**
     * @param durableDestinations The durableDestinations to set.
     */
    public void setDurableDestinations(ActiveMQDestination[] durableDestinations) {
        this.durableDestinations = durableDestinations;
    }

    /**
     * @return Returns the localBroker.
     */
    public Transport getLocalBroker() {
        return localBroker;
    }

    /**
     * @return Returns the remoteBroker.
     */
    public Transport getRemoteBroker() {
        return remoteBroker;
    }

    /**
     * @return the createdByDuplex
     */
    public boolean isCreatedByDuplex() {
        return this.createdByDuplex;
    }

    /**
     * @param createdByDuplex the createdByDuplex to set
     */
    public void setCreatedByDuplex(boolean createdByDuplex) {
        this.createdByDuplex = createdByDuplex;
    }

    public static boolean contains(BrokerId[] brokerPath, BrokerId brokerId) {
        if (brokerPath != null) {
            for (int i = 0; i < brokerPath.length; i++) {
                if (brokerId.equals(brokerPath[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    protected BrokerId[] appendToBrokerPath(BrokerId[] brokerPath, BrokerId[] pathsToAppend) {
        if (brokerPath == null || brokerPath.length == 0) {
            return pathsToAppend;
        }
        BrokerId rc[] = new BrokerId[brokerPath.length + pathsToAppend.length];
        System.arraycopy(brokerPath, 0, rc, 0, brokerPath.length);
        System.arraycopy(pathsToAppend, 0, rc, brokerPath.length, pathsToAppend.length);
        return rc;
    }

    protected BrokerId[] appendToBrokerPath(BrokerId[] brokerPath, BrokerId idToAppend) {
        if (brokerPath == null || brokerPath.length == 0) {
            return new BrokerId[] { idToAppend };
        }
        BrokerId rc[] = new BrokerId[brokerPath.length + 1];
        System.arraycopy(brokerPath, 0, rc, 0, brokerPath.length);
        rc[brokerPath.length] = idToAppend;
        return rc;
    }

    protected boolean isPermissableDestination(ActiveMQDestination destination) {
        return isPermissableDestination(destination, false);
    }

    protected boolean isPermissableDestination(ActiveMQDestination destination, boolean allowTemporary) {
        // Are we not bridging temp destinations?
        if (destination.isTemporary()) {
            if (allowTemporary) {
                return true;
            } else {
                return configuration.isBridgeTempDestinations();
            }
        }

        ActiveMQDestination[] dests = staticallyIncludedDestinations;
        if (dests != null && dests.length > 0) {
            for (int i = 0; i < dests.length; i++) {
                ActiveMQDestination match = dests[i];
                DestinationFilter inclusionFilter = DestinationFilter.parseFilter(match);
                if (match != null && inclusionFilter.matches(destination) && dests[i].getDestinationType() == destination.getDestinationType()) {
                    return true;
                }
            }
        }

        dests = excludedDestinations;
        if (dests != null && dests.length > 0) {
            for (int i = 0; i < dests.length; i++) {
                ActiveMQDestination match = dests[i];
                DestinationFilter exclusionFilter = DestinationFilter.parseFilter(match);
                if (match != null && exclusionFilter.matches(destination) && dests[i].getDestinationType() == destination.getDestinationType()) {
                    return false;
                }
            }
        }

        dests = dynamicallyIncludedDestinations;
        if (dests != null && dests.length > 0) {
            for (int i = 0; i < dests.length; i++) {
                ActiveMQDestination match = dests[i];
                DestinationFilter inclusionFilter = DestinationFilter.parseFilter(match);
                if (match != null && inclusionFilter.matches(destination) && dests[i].getDestinationType() == destination.getDestinationType()) {
                    return true;
                }
            }

            return false;
        }
        return true;
    }

    /**
     * Subscriptions for these destinations are always created
     */
    protected void setupStaticDestinations() {
        ActiveMQDestination[] dests = staticallyIncludedDestinations;
        if (dests != null) {
            for (int i = 0; i < dests.length; i++) {
                ActiveMQDestination dest = dests[i];
                DemandSubscription sub = createDemandSubscription(dest);
                try {
                    addSubscription(sub);
                } catch (IOException e) {
                    LOG.error("Failed to add static destination " + dest, e);
                }
                if (LOG.isTraceEnabled()) {
                    LOG.trace("bridging messages for static destination: " + dest);
                }
            }
        }
    }

    protected boolean addConsumerInfo(final ConsumerInfo consumerInfo) throws IOException {
        boolean consumerAdded = false;
        ConsumerInfo info = consumerInfo.copy();
        addRemoteBrokerToBrokerPath(info);
        DemandSubscription sub = createDemandSubscription(info);
        if (sub != null) {
            if (duplicateSuppressionIsRequired(sub)) {
                undoMapRegistration(sub);
            } else {
                addSubscription(sub);
                consumerAdded = true;
            }
        }
        return consumerAdded;
    }

    private void undoMapRegistration(DemandSubscription sub) {
        subscriptionMapByLocalId.remove(sub.getLocalInfo().getConsumerId());
        subscriptionMapByRemoteId.remove(sub.getRemoteInfo().getConsumerId());
    }

    /*
     * check our existing subs networkConsumerIds against the list of network ids in this subscription
     * A match means a duplicate which we suppress for topics and maybe for queues
     */
    private boolean duplicateSuppressionIsRequired(DemandSubscription candidate) {
        final ConsumerInfo consumerInfo = candidate.getRemoteInfo();
        boolean suppress = false;

        if (consumerInfo.getDestination().isQueue() && !configuration.isSuppressDuplicateQueueSubscriptions() ||
                consumerInfo.getDestination().isTopic() && !configuration.isSuppressDuplicateTopicSubscriptions()) {
            return suppress;
        }

        List<ConsumerId> candidateConsumers = consumerInfo.getNetworkConsumerIds();
        Collection<Subscription> currentSubs =
            getRegionSubscriptions(consumerInfo.getDestination().isTopic());
        for (Subscription sub : currentSubs) {
            List<ConsumerId> networkConsumers = sub.getConsumerInfo().getNetworkConsumerIds();
            if (!networkConsumers.isEmpty()) {
                if (matchFound(candidateConsumers, networkConsumers)) {
                    if (isInActiveDurableSub(sub)) {
                        suppress = false;
                    } else {
                        suppress = hasLowerPriority(sub, candidate.getLocalInfo());
                    }
                    break;
                }
            }
        }
        return suppress;
    }

    private boolean isInActiveDurableSub(Subscription sub) {
        return  (sub.getConsumerInfo().isDurable() && sub instanceof DurableTopicSubscription && !((DurableTopicSubscription)sub).isActive());
    }

    private boolean hasLowerPriority(Subscription existingSub, ConsumerInfo candidateInfo) {
        boolean suppress = false;

        if (existingSub.getConsumerInfo().getPriority() >= candidateInfo.getPriority()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(configuration.getBrokerName() + " Ignoring duplicate subscription from " + remoteBrokerName
                        + ", sub: " + candidateInfo + " is duplicated by network subscription with equal or higher network priority: "
                        + existingSub  + ", networkConsumerIds: " + existingSub.getConsumerInfo().getNetworkConsumerIds());
            }
            suppress = true;
        } else {
            // remove the existing lower priority duplicate and allow this candidate
            try {
                removeDuplicateSubscription(existingSub);

                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getBrokerName() + " Replacing duplicate subscription " + existingSub.getConsumerInfo()
                            + " with sub from " + remoteBrokerName
                            + ", which has a higher priority, new sub: " + candidateInfo + ", networkComsumerIds: "
                            + candidateInfo.getNetworkConsumerIds());
                }
            } catch (IOException e) {
                LOG.error("Failed to remove duplicated sub as a result of sub with higher priority, sub: " + existingSub, e);
            }
        }
        return suppress;
    }

    private void removeDuplicateSubscription(Subscription existingSub) throws IOException {
        for (NetworkConnector connector : brokerService.getNetworkConnectors()) {
            if (connector.removeDemandSubscription(existingSub.getConsumerInfo().getConsumerId())) {
                break;
            }
        }
    }

    private boolean matchFound(List<ConsumerId> candidateConsumers, List<ConsumerId> networkConsumers) {
        boolean found = false;
        for (ConsumerId aliasConsumer : networkConsumers) {
            if (candidateConsumers.contains(aliasConsumer)) {
                found = true;
                break;
            }
        }
        return found;
    }

    private final Collection<Subscription> getRegionSubscriptions(boolean isTopic) {
        RegionBroker region = (RegionBroker) brokerService.getRegionBroker();
        AbstractRegion abstractRegion = (AbstractRegion)
            (isTopic ? region.getTopicRegion() : region.getQueueRegion());
        return abstractRegion.getSubscriptions().values();
    }

    protected DemandSubscription createDemandSubscription(ConsumerInfo info) throws IOException {
        //add our original id to ourselves
        info.addNetworkConsumerId(info.getConsumerId());
        return doCreateDemandSubscription(info);
    }

    protected DemandSubscription doCreateDemandSubscription(ConsumerInfo info) throws IOException {
        DemandSubscription result = new DemandSubscription(info);
        result.getLocalInfo().setConsumerId(new ConsumerId(localSessionInfo.getSessionId(), consumerIdGenerator.getNextSequenceId()));
        if (info.getDestination().isTemporary()) {
            // reset the local connection Id

            ActiveMQTempDestination dest = (ActiveMQTempDestination) result.getLocalInfo().getDestination();
            dest.setConnectionId(localConnectionInfo.getConnectionId().toString());
        }

        if (configuration.isDecreaseNetworkConsumerPriority()) {
            byte priority = (byte) configuration.getConsumerPriorityBase();
            if (info.getBrokerPath() != null && info.getBrokerPath().length > 1) {
                // The longer the path to the consumer, the less it's consumer priority.
                priority -= info.getBrokerPath().length + 1;
            }
            result.getLocalInfo().setPriority(priority);
            if (LOG.isDebugEnabled()) {
                LOG.debug(configuration.getBrokerName() + " using priority :" + priority + " for subscription: " + info);
            }
        }
        configureDemandSubscription(info, result);
        return result;
    }

    final protected DemandSubscription createDemandSubscription(ActiveMQDestination destination) {
        ConsumerInfo info = new ConsumerInfo();
        info.setDestination(destination);
        // the remote info held by the DemandSubscription holds the original
        // consumerId,
        // the local info get's overwritten

        info.setConsumerId(new ConsumerId(localSessionInfo.getSessionId(), consumerIdGenerator.getNextSequenceId()));
        DemandSubscription result = null;
        try {
            result = createDemandSubscription(info);
        } catch (IOException e) {
            LOG.error("Failed to create DemandSubscription ", e);
        }
        return result;
    }

    protected void configureDemandSubscription(ConsumerInfo info, DemandSubscription sub) throws IOException {
        sub.getLocalInfo().setDispatchAsync(configuration.isDispatchAsync());
        sub.getLocalInfo().setPrefetchSize(configuration.getPrefetchSize());
        subscriptionMapByLocalId.put(sub.getLocalInfo().getConsumerId(), sub);
        subscriptionMapByRemoteId.put(sub.getRemoteInfo().getConsumerId(), sub);

        sub.setNetworkBridgeFilter(createNetworkBridgeFilter(info));
        if (!info.isDurable()) {
            // This works for now since we use a VM connection to the local broker.
            // may need to change if we ever subscribe to a remote broker.
            sub.getLocalInfo().setAdditionalPredicate(sub.getNetworkBridgeFilter());
        } else  {
            // need to ack this message if it is ignored as it is durable so
            // we check before we send. see: suppressMessageDispatch()
        }
    }

    protected void removeDemandSubscription(ConsumerId id) throws IOException {
        DemandSubscription sub = subscriptionMapByRemoteId.remove(id);
        if (LOG.isDebugEnabled()) {
            LOG.debug(configuration.getBrokerName() + " remove request on " + localBroker + " from " + remoteBrokerName + " , consumer id: " + id + ", matching sub: " + sub);
        }
        if (sub != null) {
            removeSubscription(sub);
            if (LOG.isDebugEnabled()) {
                LOG.debug(configuration.getBrokerName() + " removed sub on " + localBroker + " from " + remoteBrokerName + " :  " + sub.getRemoteInfo());
            }
        }
    }

    protected boolean removeDemandSubscriptionByLocalId(ConsumerId consumerId) {
        boolean removeDone = false;
        DemandSubscription sub = subscriptionMapByLocalId.get(consumerId);
        if (sub != null) {
            try {
                removeDemandSubscription(sub.getRemoteInfo().getConsumerId());
                removeDone = true;
            } catch (IOException e) {
                LOG.debug("removeDemandSubscriptionByLocalId failed for localId: " + consumerId, e);
            }
        }
        return removeDone;
    }

    protected void waitStarted() throws InterruptedException {
        startedLatch.await();
    }

    protected void clearDownSubscriptions() {
        subscriptionMapByLocalId.clear();
        subscriptionMapByRemoteId.clear();
    }

    protected NetworkBridgeFilter createNetworkBridgeFilter(ConsumerInfo info) throws IOException {
        NetworkBridgeFilterFactory filterFactory = defaultFilterFactory;
        if (brokerService != null && brokerService.getDestinationPolicy() != null) {
            PolicyEntry entry = brokerService.getDestinationPolicy().getEntryFor(info.getDestination());
            if (entry != null && entry.getNetworkBridgeFilterFactory() != null) {
                filterFactory = entry.getNetworkBridgeFilterFactory();
            }
        }
        return filterFactory.create(info, getRemoteBrokerPath(), configuration.getNetworkTTL());
    }

    protected void serviceLocalBrokerInfo(Command command) throws InterruptedException {
        synchronized (brokerInfoMutex) {
            if (remoteBrokerId != null) {
                if (remoteBrokerId.equals(localBrokerId)) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace(configuration.getBrokerName() + " disconnecting local loop back connection for: " + remoteBrokerName + ", with id:" + remoteBrokerId);
                    }
                    waitStarted();
                    ServiceSupport.dispose(this);
                }
            }
        }
    }

    protected void addRemoteBrokerToBrokerPath(ConsumerInfo info) throws IOException {
        info.setBrokerPath(appendToBrokerPath(info.getBrokerPath(), getRemoteBrokerPath()));
    }

    protected void serviceRemoteBrokerInfo(Command command) throws IOException {
        synchronized (brokerInfoMutex) {
            BrokerInfo remoteBrokerInfo = (BrokerInfo)command;
            remoteBrokerId = remoteBrokerInfo.getBrokerId();
            remoteBrokerPath[0] = remoteBrokerId;
            remoteBrokerName = remoteBrokerInfo.getBrokerName();
            if (localBrokerId != null) {
                if (localBrokerId.equals(remoteBrokerId)) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace(configuration.getBrokerName() + " disconnecting remote loop back connection for: " + remoteBrokerName + ", with id:" + remoteBrokerId);
                    }
                    ServiceSupport.dispose(this);
                }
            }
            if (!disposed.get()) {
                triggerLocalStartBridge();
            }
        }
    }

    protected  BrokerId[] getRemoteBrokerPath() {
        return remoteBrokerPath;
    }

    public void setNetworkBridgeListener(NetworkBridgeListener listener) {
        this.networkBridgeListener = listener;
    }

    private void fireBridgeFailed() {
        NetworkBridgeListener l = this.networkBridgeListener;
        if (l != null) {
            l.bridgeFailed();
        }
    }

    public String getRemoteAddress() {
        return remoteBroker.getRemoteAddress();
    }

    public String getLocalAddress() {
        return localBroker.getRemoteAddress();
    }

    public String getRemoteBrokerName() {
        return remoteBrokerInfo == null ? null : remoteBrokerInfo.getBrokerName();
    }

    public String getLocalBrokerName() {
        return localBrokerInfo == null ? null : localBrokerInfo.getBrokerName();
    }

    public long getDequeueCounter() {
        return dequeueCounter.get();
    }

    public long getEnqueueCounter() {
        return enqueueCounter.get();
    }

    protected boolean isDuplex() {
        return configuration.isDuplex() || createdByDuplex;
    }

    public ConcurrentHashMap<ConsumerId, DemandSubscription> getLocalSubscriptionMap() {
        return subscriptionMapByRemoteId;
    }

    public void setBrokerService(BrokerService brokerService) {
        this.brokerService = brokerService;
        this.localBrokerId = brokerService.getRegionBroker().getBrokerId();
        localBrokerPath[0] = localBrokerId;
    }

    public void setMbeanObjectName(ObjectName objectName) {
        this.mbeanObjectName = objectName;
    }

    public ObjectName getMbeanObjectName() {
        return mbeanObjectName;
    }
}
