/*******************************************************************************
 * Copyright (c) 2009, 2018 IBM Corp.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    https://www.eclipse.org/legal/epl-2.0
 * and the Eclipse Distribution License is available at
 *   https://www.eclipse.org/org/documents/edl-v10.php
 *
 * Contributors:
 *    Dave Locke - initial API and implementation and/or initial documentation
 *    Ian Craggs - MQTT 3.1.1 support
 *    Ian Craggs - per subscription message handlers (bug 466579)
 *    Ian Craggs - ack control (bug 472172)
 *    James Sutton - Bug 459142 - WebSocket support for the Java client.
 *    James Sutton - Automatic Reconnect & Offline Buffering.
 */

package org.eclipse.paho.client.mqttv3;

import java.util.Hashtable;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.net.SocketFactory;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;
import org.eclipse.paho.client.mqttv3.internal.ConnectActionListener;
import org.eclipse.paho.client.mqttv3.internal.DisconnectedMessageBuffer;
import org.eclipse.paho.client.mqttv3.internal.ExceptionHelper;
import org.eclipse.paho.client.mqttv3.internal.HighResolutionTimer;
import org.eclipse.paho.client.mqttv3.internal.SystemHighResolutionTimer;
import org.eclipse.paho.client.mqttv3.internal.NetworkModule;
import org.eclipse.paho.client.mqttv3.internal.NetworkModuleService;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttDisconnect;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPublish;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttSubscribe;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttUnsubscribe;
import org.eclipse.paho.client.mqttv3.logging.Logger;
import org.eclipse.paho.client.mqttv3.logging.LoggerFactory;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.client.mqttv3.util.Debug;
import org.eclipse.paho.client.mqttv3.IMqttToken;

/**
 * Lightweight client for talking to an MQTT server using non-blocking methods
 * that allow an operation to run in the background.
 *
 * <p>
 * This class implements the non-blocking {@link IMqttAsyncClient} client
 * interface allowing applications to initiate MQTT actions and then carry on
 * working while the MQTT action completes on a background thread. This
 * implementation is compatible with all Java SE runtimes from 1.7 and up.
 * </p>
 * <p>
 * An application can connect to an MQTT server using:
 * </p>
 * <ul>
 * <li>A plain TCP socket
 * <li>A secure SSL/TLS socket
 * </ul>
 *
 * <p>
 * To enable messages to be delivered even across network and client restarts
 * messages need to be safely stored until the message has been delivered at the
 * requested quality of service. A pluggable persistence mechanism is provided
 * to store the messages.
 * </p>
 * <p>
 * By default {@link MqttDefaultFilePersistence} is used to store messages to a
 * file. If persistence is set to null then messages are stored in memory and
 * hence can be lost if the client, Java runtime or device shuts down.
 * </p>
 * <p>
 * If connecting with {@link MqttConnectOptions#setCleanSession(boolean)} set to
 * true it is safe to use memory persistence as all state is cleared when a
 * client disconnects. If connecting with cleanSession set to false in order to
 * provide reliable message delivery then a persistent message store such as the
 * default one should be used.
 * </p>
 * <p>
 * The message store interface is pluggable. Different stores can be used by
 * implementing the {@link MqttClientPersistence} interface and passing it to
 * the clients constructor.
 * </p>
 *
 * @see IMqttAsyncClient
 */
public class MqttAsyncClient implements IMqttAsyncClient {
	private static final String CLASS_NAME = MqttAsyncClient.class.getName();
	private Logger log = LoggerFactory.getLogger(LoggerFactory.MQTT_CLIENT_MSG_CAT, CLASS_NAME);

	private static final String CLIENT_ID_PREFIX = "paho";
	private static final long QUIESCE_TIMEOUT = 30000; // ms
	private static final long DISCONNECT_TIMEOUT = 10000; // ms
	private static final char MIN_HIGH_SURROGATE = '\uD800';
	private static final char MAX_HIGH_SURROGATE = '\uDBFF';
	private String clientId;
	private String serverURI;
	protected ClientComms comms;
	private Hashtable topics;
	private MqttClientPersistence persistence;
	private MqttCallback mqttCallback;
	private MqttConnectOptions connOpts;
	private Object userContext;
	private Timer reconnectTimer; // Automatic reconnect timer
	private static int reconnectDelay = 1000; // Reconnect delay, starts at 1
												// second
	private boolean reconnecting = false;
	private static final Object clientLock = new Object(); // Simple lock

	private ScheduledExecutorService executorService;

	/**
	 * Create an MqttAsyncClient that is used to communicate with an MQTT
	 * server.
	 * <p>
	 * The address of a server can be specified on the constructor.
	 * Alternatively a list containing one or more servers can be specified
	 * using the {@link MqttConnectOptions#setServerURIs(String[])
	 * setServerURIs} method on MqttConnectOptions.
	 *
	 * <p>
	 * The <code>serverURI</code> parameter is typically used with the the
	 * <code>clientId</code> parameter to form a key. The key is used to store
	 * and reference messages while they are being delivered. Hence the
	 * serverURI specified on the constructor must still be specified even if a
	 * list of servers is specified on an MqttConnectOptions object. The
	 * serverURI on the constructor must remain the same across restarts of the
	 * client for delivery of messages to be maintained from a given client to a
	 * given server or set of servers.
	 *
	 * <p>
	 * The address of the server to connect to is specified as a URI. Two types
	 * of connection are supported <code>tcp://</code> for a TCP connection and
	 * <code>ssl://</code> for a TCP connection secured by SSL/TLS. For example:
	 * </p>
	 * <ul>
	 * <li><code>tcp://localhost:1883</code></li>
	 * <li><code>ssl://localhost:8883</code></li>
	 * </ul>
	 * <p>
	 * If the port is not specified, it will default to 1883 for
	 * <code>tcp://</code>" URIs, and 8883 for <code>ssl://</code> URIs.
	 * </p>
	 *
	 * <p>
	 * A client identifier <code>clientId</code> must be specified and be less
	 * that 65535 characters. It must be unique across all clients connecting to
	 * the same server. The clientId is used by the server to store data related
	 * to the client, hence it is important that the clientId remain the same
	 * when connecting to a server if durable subscriptions or reliable
	 * messaging are required.
	 * <p>
	 * A convenience method is provided to generate a random client id that
	 * should satisfy this criteria - {@link #generateClientId()}. As the client
	 * identifier is used by the server to identify a client when it reconnects,
	 * the client must use the same identifier between connections if durable
	 * subscriptions or reliable delivery of messages is required.
	 * </p>
	 * <p>
	 * In Java SE, SSL can be configured in one of several ways, which the
	 * client will use in the following order:
	 * </p>
	 * <ul>
	 * <li><strong>Supplying an <code>SSLSocketFactory</code></strong> -
	 * applications can use
	 * {@link MqttConnectOptions#setSocketFactory(SocketFactory)} to supply a
	 * factory with the appropriate SSL settings.</li>
	 * <li><strong>SSL Properties</strong> - applications can supply SSL
	 * settings as a simple Java Properties using
	 * {@link MqttConnectOptions#setSSLProperties(Properties)}.</li>
	 * <li><strong>Use JVM settings</strong> - There are a number of standard
	 * Java system properties that can be used to configure key and trust
	 * stores.</li>
	 * </ul>
	 *
	 * <p>
	 * In Java ME, the platform settings are used for SSL connections.
	 * </p>
	 *
	 * <p>
	 * An instance of the default persistence mechanism
	 * {@link MqttDefaultFilePersistence} is used by the client. To specify a
	 * different persistence mechanism or to turn off persistence, use the
	 * {@link #MqttAsyncClient(String, String, MqttClientPersistence)}
	 * constructor.
	 *
	 * @param serverURI
	 *            the address of the server to connect to, specified as a URI.
	 *            Can be overridden using
	 *            {@link MqttConnectOptions#setServerURIs(String[])}
	 * @param clientId
	 *            a client identifier that is unique on the server being
	 *            connected to
	 * @throws IllegalArgumentException
	 *             if the URI does not start with "tcp://", "ssl://" or
	 *             "local://".
	 * @throws IllegalArgumentException
	 *             if the clientId is null or is greater than 65535 characters
	 *             in length
	 * @throws MqttException
	 *             if any other problem was encountered
	 */
	public MqttAsyncClient(String serverURI, String clientId) throws MqttException {
		this(serverURI, clientId, new MqttDefaultFilePersistence());
	}

	/**
	 * Create an MqttAsyncClient that is used to communicate with an MQTT
	 * server.
	 * <p>
	 * The address of a server can be specified on the constructor.
	 * Alternatively a list containing one or more servers can be specified
	 * using the {@link MqttConnectOptions#setServerURIs(String[])
	 * setServerURIs} method on MqttConnectOptions.
	 *
	 * <p>
	 * The <code>serverURI</code> parameter is typically used with the the
	 * <code>clientId</code> parameter to form a key. The key is used to store
	 * and reference messages while they are being delivered. Hence the
	 * serverURI specified on the constructor must still be specified even if a
	 * list of servers is specified on an MqttConnectOptions object. The
	 * serverURI on the constructor must remain the same across restarts of the
	 * client for delivery of messages to be maintained from a given client to a
	 * given server or set of servers.
	 *
	 * <p>
	 * The address of the server to connect to is specified as a URI. Two types
	 * of connection are supported <code>tcp://</code> for a TCP connection and
	 * <code>ssl://</code> for a TCP connection secured by SSL/TLS. For example:
	 * </p>
	 * <ul>
	 * <li><code>tcp://localhost:1883</code></li>
	 * <li><code>ssl://localhost:8883</code></li>
	 * </ul>
	 * <p>
	 * If the port is not specified, it will default to 1883 for
	 * <code>tcp://</code>" URIs, and 8883 for <code>ssl://</code> URIs.
	 * </p>
	 *
	 * <p>
	 * A client identifier <code>clientId</code> must be specified and be less
	 * that 65535 characters. It must be unique across all clients connecting to
	 * the same server. The clientId is used by the server to store data related
	 * to the client, hence it is important that the clientId remain the same
	 * when connecting to a server if durable subscriptions or reliable
	 * messaging are required.
	 * <p>
	 * A convenience method is provided to generate a random client id that
	 * should satisfy this criteria - {@link #generateClientId()}. As the client
	 * identifier is used by the server to identify a client when it reconnects,
	 * the client must use the same identifier between connections if durable
	 * subscriptions or reliable delivery of messages is required.
	 * </p>
	 * <p>
	 * In Java SE, SSL can be configured in one of several ways, which the
	 * client will use in the following order:
	 * </p>
	 * <ul>
	 * <li><strong>Supplying an <code>SSLSocketFactory</code></strong> -
	 * applications can use
	 * {@link MqttConnectOptions#setSocketFactory(SocketFactory)} to supply a
	 * factory with the appropriate SSL settings.</li>
	 * <li><strong>SSL Properties</strong> - applications can supply SSL
	 * settings as a simple Java Properties using
	 * {@link MqttConnectOptions#setSSLProperties(Properties)}.</li>
	 * <li><strong>Use JVM settings</strong> - There are a number of standard
	 * Java system properties that can be used to configure key and trust
	 * stores.</li>
	 * </ul>
	 *
	 * <p>
	 * In Java ME, the platform settings are used for SSL connections.
	 * </p>
	 * <p>
	 * A persistence mechanism is used to enable reliable messaging. For
	 * messages sent at qualities of service (QoS) 1 or 2 to be reliably
	 * delivered, messages must be stored (on both the client and server) until
	 * the delivery of the message is complete. If messages are not safely
	 * stored when being delivered then a failure in the client or server can
	 * result in lost messages. A pluggable persistence mechanism is supported
	 * via the {@link MqttClientPersistence} interface. An implementer of this
	 * interface that safely stores messages must be specified in order for
	 * delivery of messages to be reliable. In addition
	 * {@link MqttConnectOptions#setCleanSession(boolean)} must be set to false.
	 * In the event that only QoS 0 messages are sent or received or
	 * cleanSession is set to true then a safe store is not needed.
	 * </p>
	 * <p>
	 * An implementation of file-based persistence is provided in class
	 * {@link MqttDefaultFilePersistence} which will work in all Java SE based
	 * systems. If no persistence is needed, the persistence parameter can be
	 * explicitly set to <code>null</code>.
	 * </p>
	 *
	 * @param serverURI
	 *            the address of the server to connect to, specified as a URI.
	 *            Can be overridden using
	 *            {@link MqttConnectOptions#setServerURIs(String[])}
	 * @param clientId
	 *            a client identifier that is unique on the server being
	 *            connected to
	 * @param persistence
	 *            the persistence class to use to store in-flight message. If
	 *            null then the default persistence mechanism is used
	 * @throws MqttException
	 *             if any other problem was encountered
	 */
	public MqttAsyncClient(String serverURI, String clientId, MqttClientPersistence persistence) throws MqttException {
		this(serverURI, clientId, persistence, new TimerPingSender());
	}

	public MqttAsyncClient(String serverURI, String clientId, MqttClientPersistence persistence,
			MqttPingSender pingSender) throws MqttException {
		this(serverURI, clientId, persistence, pingSender, null);
	}

	/**
	 * Create an MqttAsyncClient that is used to communicate with an MQTT
	 * server.
	 * <p>
	 * The address of a server can be specified on the constructor.
	 * Alternatively a list containing one or more servers can be specified
	 * using the {@link MqttConnectOptions#setServerURIs(String[])
	 * setServerURIs} method on MqttConnectOptions.
	 *
	 * <p>
	 * The <code>serverURI</code> parameter is typically used with the the
	 * <code>clientId</code> parameter to form a key. The key is used to store
	 * and reference messages while they are being delivered. Hence the
	 * serverURI specified on the constructor must still be specified even if a
	 * list of servers is specified on an MqttConnectOptions object. The
	 * serverURI on the constructor must remain the same across restarts of the
	 * client for delivery of messages to be maintained from a given client to a
	 * given server or set of servers.
	 *
	 * <p>
	 * The address of the server to connect to is specified as a URI. Two types
	 * of connection are supported <code>tcp://</code> for a TCP connection and
	 * <code>ssl://</code> for a TCP connection secured by SSL/TLS. For example:
	 * </p>
	 * <ul>
	 * <li><code>tcp://localhost:1883</code></li>
	 * <li><code>ssl://localhost:8883</code></li>
	 * </ul>
	 * <p>
	 * If the port is not specified, it will default to 1883 for
	 * <code>tcp://</code>" URIs, and 8883 for <code>ssl://</code> URIs.
	 * </p>
	 *
	 * <p>
	 * A client identifier <code>clientId</code> must be specified and be less
	 * that 65535 characters. It must be unique across all clients connecting to
	 * the same server. The clientId is used by the server to store data related
	 * to the client, hence it is important that the clientId remain the same
	 * when connecting to a server if durable subscriptions or reliable
	 * messaging are required.
	 * <p>
	 * A convenience method is provided to generate a random client id that
	 * should satisfy this criteria - {@link #generateClientId()}. As the client
	 * identifier is used by the server to identify a client when it reconnects,
	 * the client must use the same identifier between connections if durable
	 * subscriptions or reliable delivery of messages is required.
	 * </p>
	 * <p>
	 * In Java SE, SSL can be configured in one of several ways, which the
	 * client will use in the following order:
	 * </p>
	 * <ul>
	 * <li><strong>Supplying an <code>SSLSocketFactory</code></strong> -
	 * applications can use
	 * {@link MqttConnectOptions#setSocketFactory(SocketFactory)} to supply a
	 * factory with the appropriate SSL settings.</li>
	 * <li><strong>SSL Properties</strong> - applications can supply SSL
	 * settings as a simple Java Properties using
	 * {@link MqttConnectOptions#setSSLProperties(Properties)}.</li>
	 * <li><strong>Use JVM settings</strong> - There are a number of standard
	 * Java system properties that can be used to configure key and trust
	 * stores.</li>
	 * </ul>
	 *
	 * <p>
	 * In Java ME, the platform settings are used for SSL connections.
	 * </p>
	 * <p>
	 * A persistence mechanism is used to enable reliable messaging. For
	 * messages sent at qualities of service (QoS) 1 or 2 to be reliably
	 * delivered, messages must be stored (on both the client and server) until
	 * the delivery of the message is complete. If messages are not safely
	 * stored when being delivered then a failure in the client or server can
	 * result in lost messages. A pluggable persistence mechanism is supported
	 * via the {@link MqttClientPersistence} interface. An implementer of this
	 * interface that safely stores messages must be specified in order for
	 * delivery of messages to be reliable. In addition
	 * {@link MqttConnectOptions#setCleanSession(boolean)} must be set to false.
	 * In the event that only QoS 0 messages are sent or received or
	 * cleanSession is set to true then a safe store is not needed.
	 * </p>
	 * <p>
	 * An implementation of file-based persistence is provided in class
	 * {@link MqttDefaultFilePersistence} which will work in all Java SE based
	 * systems. If no persistence is needed, the persistence parameter can be
	 * explicitly set to <code>null</code>.
	 * </p>
	 *
	 * @param serverURI
	 *            the address of the server to connect to, specified as a URI.
	 *            Can be overridden using
	 *            {@link MqttConnectOptions#setServerURIs(String[])}
	 * @param clientId
	 *            a client identifier that is unique on the server being
	 *            connected to
	 * @param persistence
	 *            the persistence class to use to store in-flight message. If
	 *            null then the default persistence mechanism is used
	 * @param pingSender
	 *            Custom {@link MqttPingSender} implementation.
	 * @param executorService
	 *            used for managing threads. If null no executor service is used.
	 * @throws IllegalArgumentException
	 *             if the URI does not start with "tcp://", "ssl://" or
	 *             "local://"
	 * @throws IllegalArgumentException
	 *             if the clientId is null or is greater than 65535 characters
	 *             in length
	 * @throws MqttException
	 *             if any other problem was encountered
	 */
	public MqttAsyncClient(String serverURI, String clientId, MqttClientPersistence persistence,
			MqttPingSender pingSender, ScheduledExecutorService executorService) throws MqttException {
		this(serverURI, clientId, persistence, pingSender, executorService, null);
	}
	/**
	 * Create an MqttAsyncClient that is used to communicate with an MQTT
	 * server.
	 * <p>
	 * The address of a server can be specified on the constructor.
	 * Alternatively a list containing one or more servers can be specified
	 * using the {@link MqttConnectOptions#setServerURIs(String[])
	 * setServerURIs} method on MqttConnectOptions.
	 *
	 * <p>
	 * The <code>serverURI</code> parameter is typically used with the the
	 * <code>clientId</code> parameter to form a key. The key is used to store
	 * and reference messages while they are being delivered. Hence the
	 * serverURI specified on the constructor must still be specified even if a
	 * list of servers is specified on an MqttConnectOptions object. The
	 * serverURI on the constructor must remain the same across restarts of the
	 * client for delivery of messages to be maintained from a given client to a
	 * given server or set of servers.
	 *
	 * <p>
	 * The address of the server to connect to is specified as a URI. Two types
	 * of connection are supported <code>tcp://</code> for a TCP connection and
	 * <code>ssl://</code> for a TCP connection secured by SSL/TLS. For example:
	 * </p>
	 * <ul>
	 * <li><code>tcp://localhost:1883</code></li>
	 * <li><code>ssl://localhost:8883</code></li>
	 * </ul>
	 * <p>
	 * If the port is not specified, it will default to 1883 for
	 * <code>tcp://</code>" URIs, and 8883 for <code>ssl://</code> URIs.
	 * </p>
	 *
	 * <p>
	 * A client identifier <code>clientId</code> must be specified and be less
	 * that 65535 characters. It must be unique across all clients connecting to
	 * the same server. The clientId is used by the server to store data related
	 * to the client, hence it is important that the clientId remain the same
	 * when connecting to a server if durable subscriptions or reliable
	 * messaging are required.
	 * <p>
	 * A convenience method is provided to generate a random client id that
	 * should satisfy this criteria - {@link #generateClientId()}. As the client
	 * identifier is used by the server to identify a client when it reconnects,
	 * the client must use the same identifier between connections if durable
	 * subscriptions or reliable delivery of messages is required.
	 * </p>
	 * <p>
	 * In Java SE, SSL can be configured in one of several ways, which the
	 * client will use in the following order:
	 * </p>
	 * <ul>
	 * <li><strong>Supplying an <code>SSLSocketFactory</code></strong> -
	 * applications can use
	 * {@link MqttConnectOptions#setSocketFactory(SocketFactory)} to supply a
	 * factory with the appropriate SSL settings.</li>
	 * <li><strong>SSL Properties</strong> - applications can supply SSL
	 * settings as a simple Java Properties using
	 * {@link MqttConnectOptions#setSSLProperties(Properties)}.</li>
	 * <li><strong>Use JVM settings</strong> - There are a number of standard
	 * Java system properties that can be used to configure key and trust
	 * stores.</li>
	 * </ul>
	 *
	 * <p>
	 * In Java ME, the platform settings are used for SSL connections.
	 * </p>
	 * <p>
	 * A persistence mechanism is used to enable reliable messaging. For
	 * messages sent at qualities of service (QoS) 1 or 2 to be reliably
	 * delivered, messages must be stored (on both the client and server) until
	 * the delivery of the message is complete. If messages are not safely
	 * stored when being delivered then a failure in the client or server can
	 * result in lost messages. A pluggable persistence mechanism is supported
	 * via the {@link MqttClientPersistence} interface. An implementer of this
	 * interface that safely stores messages must be specified in order for
	 * delivery of messages to be reliable. In addition
	 * {@link MqttConnectOptions#setCleanSession(boolean)} must be set to false.
	 * In the event that only QoS 0 messages are sent or received or
	 * cleanSession is set to true then a safe store is not needed.
	 * </p>
	 * <p>
	 * An implementation of file-based persistence is provided in class
	 * {@link MqttDefaultFilePersistence} which will work in all Java SE based
	 * systems. If no persistence is needed, the persistence parameter can be
	 * explicitly set to <code>null</code>.
	 * </p>
	 *
	 * @param serverURI
	 *            the address of the server to connect to, specified as a URI.
	 *            Can be overridden using
	 *            {@link MqttConnectOptions#setServerURIs(String[])}
	 * @param clientId
	 *            a client identifier that is unique on the server being
	 *            connected to
	 * @param persistence
	 *            the persistence class to use to store in-flight message. If
	 *            null then the default persistence mechanism is used
	 * @param pingSender
	 *            Custom {@link MqttPingSender} implementation.
	 * @param executorService
	 *            used for managing threads. If null no executor service is used.
	 * @param highResolutionTimer
	 * 			  used for providing time values for keepalive ping scheduling. If {@code null}, a default timer is used
	 * @throws IllegalArgumentException
	 *             if the URI does not start with "tcp://", "ssl://" or
	 *             "local://"
	 * @throws IllegalArgumentException
	 *             if the clientId is null or is greater than 65535 characters
	 *             in length
	 * @throws MqttException
	 *             if any other problem was encountered
	 */
	public MqttAsyncClient(String serverURI, String clientId, MqttClientPersistence persistence,
						   MqttPingSender pingSender, ScheduledExecutorService executorService,
						   HighResolutionTimer highResolutionTimer) throws MqttException {
		final String methodName = "MqttAsyncClient";

		log.setResourceName(clientId);

		if (clientId == null) { // Support empty client Id, 3.1.1 standard
			throw new IllegalArgumentException("Null clientId");
		}
		// Count characters, surrogate pairs count as one character.
		int clientIdLength = 0;
		for (int i = 0; i < clientId.length() - 1; i++) {
			if (Character_isHighSurrogate(clientId.charAt(i)))
				i++;
			clientIdLength++;
		}
		if (clientIdLength > 65535) {
			throw new IllegalArgumentException("ClientId longer than 65535 characters");
		}

		NetworkModuleService.validateURI(serverURI);

		this.serverURI = serverURI;
		this.clientId = clientId;

		this.persistence = persistence;
		if (this.persistence == null) {
			this.persistence = new MemoryPersistence();
		}

		if (highResolutionTimer == null) {
			highResolutionTimer = new SystemHighResolutionTimer();
		}

		this.executorService = executorService;

		// @TRACE 101=<init> ClientID={0} ServerURI={1} PersistenceType={2}
		log.fine(CLASS_NAME, methodName, "101", new Object[] { clientId, serverURI, persistence });

		this.persistence.open(clientId, serverURI);
		this.comms = new ClientComms(this, this.persistence, pingSender, this.executorService, highResolutionTimer);
		this.persistence.close();
		this.topics = new Hashtable();

	}

	/**
	 * @param ch
	 *            the character to check.
	 * @return returns 'true' if the character is a high-surrogate code unit
	 */
	protected static boolean Character_isHighSurrogate(char ch) {
		return (ch >= MIN_HIGH_SURROGATE) && (ch <= MAX_HIGH_SURROGATE);
	}

	/**
	 * Factory method to create an array of network modules, one for each of the
	 * supplied URIs
	 *
	 * @param address
	 *            the URI for the server.
	 * @param options
	 *            the {@link MqttConnectOptions} for the connection.
	 * @return a network module appropriate to the specified address.
	 * @throws MqttException
	 *             if an exception occurs creating the network Modules
	 * @throws MqttSecurityException
	 *             if an issue occurs creating an SSL / TLS Socket
	 */
	protected NetworkModule[] createNetworkModules(String address, MqttConnectOptions options)
			throws MqttException, MqttSecurityException {
		final String methodName = "createNetworkModules";
		// @TRACE 116=URI={0}
		log.fine(CLASS_NAME, methodName, "116", new Object[] { address });

		NetworkModule[] networkModules = null;
		String[] serverURIs = options.getServerURIs();
		String[] array = null;
		if (serverURIs == null) {
			array = new String[] { address };
		} else if (serverURIs.length == 0) {
			array = new String[] { address };
		} else {
			array = serverURIs;
		}

		networkModules = new NetworkModule[array.length];
		for (int i = 0; i < array.length; i++) {
			networkModules[i] = createNetworkModule(array[i], options);
		}

		log.fine(CLASS_NAME, methodName, "108");
		return networkModules;
	}

	/**
	 * Factory method to create the correct network module, based on the
	 * supplied address URI.
	 *
	 * @param address the URI for the server.
	 * @param options Connect options
	 * @return a network module appropriate to the specified address.
	 */
	private NetworkModule createNetworkModule(String address, MqttConnectOptions options) throws MqttException, MqttSecurityException {
		final String methodName = "createNetworkModule";
		// @TRACE 115=URI={0}
		log.fine(CLASS_NAME,methodName, "115", new Object[] {address});


		NetworkModule netModule = NetworkModuleService.createInstance(address, options, clientId);

		return netModule;
	}

	private String getHostName(String uri) {
		int portIndex = uri.indexOf(':');
		if (portIndex == -1) {
			portIndex = uri.indexOf('/');
		}
		if (portIndex == -1) {
			portIndex = uri.length();
		}
		return uri.substring(0, portIndex);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect(java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken connect(Object userContext, IMqttActionListener callback)
			throws MqttException, MqttSecurityException {
		return this.connect(new MqttConnectOptions(), userContext, callback);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect()
	 */
	public IMqttToken connect() throws MqttException, MqttSecurityException {
		return this.connect(null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect(org.eclipse.paho.
	 * client.mqttv3.MqttConnectOptions)
	 */
	public IMqttToken connect(MqttConnectOptions options) throws MqttException, MqttSecurityException {
		return this.connect(options, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#connect(org.eclipse.paho.
	 * client.mqttv3.MqttConnectOptions, java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken connect(MqttConnectOptions options, Object userContext, IMqttActionListener callback)
			throws MqttException, MqttSecurityException {
		final String methodName = "connect";
		if (comms.isConnected()) {
			throw ExceptionHelper.createMqttException(MqttException.REASON_CODE_CLIENT_CONNECTED);
		}
		if (comms.isConnecting()) {
			throw new MqttException(MqttException.REASON_CODE_CONNECT_IN_PROGRESS);
		}
		if (comms.isDisconnecting()) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_DISCONNECTING);
		}
		if (comms.isClosed()) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_CLOSED);
		}
		if (options == null) {
			options = new MqttConnectOptions();
		}
		this.connOpts = options;
		this.userContext = userContext;
		final boolean automaticReconnect = options.isAutomaticReconnect();

		// @TRACE 103=cleanSession={0} connectionTimeout={1} TimekeepAlive={2}
		// userName={3} password={4} will={5} userContext={6} callback={7}
		log.fine(CLASS_NAME, methodName, "103",
				new Object[] { Boolean.valueOf(options.isCleanSession()), Integer.valueOf(options.getConnectionTimeout()),
						Integer.valueOf(options.getKeepAliveInterval()), options.getUserName(),
						((null == options.getPassword()) ? "[null]" : "[notnull]"),
						((null == options.getWillMessage()) ? "[null]" : "[notnull]"), userContext, callback });
		comms.setNetworkModules(createNetworkModules(serverURI, options));
		comms.setReconnectCallback(new MqttReconnectCallback(automaticReconnect));

		// Insert our own callback to iterate through the URIs till the connect
		// succeeds
		MqttToken userToken = new MqttToken(getClientId());
		ConnectActionListener connectActionListener = new ConnectActionListener(this, persistence, comms, options,
				userToken, userContext, callback, reconnecting);
		userToken.setActionCallback(connectActionListener);
		userToken.setUserContext(this);

		// If we are using the MqttCallbackExtended, set it on the
		// connectActionListener
		if (this.mqttCallback instanceof MqttCallbackExtended) {
			connectActionListener.setMqttCallbackExtended((MqttCallbackExtended) this.mqttCallback);
		}

		comms.setNetworkModuleIndex(0);
		connectActionListener.connect();

		return userToken;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect(java.lang.
	 * Object, org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken disconnect(Object userContext, IMqttActionListener callback) throws MqttException {
		return this.disconnect(QUIESCE_TIMEOUT, userContext, callback);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect()
	 */
	public IMqttToken disconnect() throws MqttException {
		return this.disconnect(null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect(long)
	 */
	public IMqttToken disconnect(long quiesceTimeout) throws MqttException {
		return this.disconnect(quiesceTimeout, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnect(long,
	 * java.lang.Object, org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken disconnect(long quiesceTimeout, Object userContext, IMqttActionListener callback)
			throws MqttException {
		final String methodName = "disconnect";
		// @TRACE 104=> quiesceTimeout={0} userContext={1} callback={2}
		log.fine(CLASS_NAME, methodName, "104", new Object[] { Long.valueOf(quiesceTimeout), userContext, callback });

		MqttToken token = new MqttToken(getClientId());
		token.setActionCallback(callback);
		token.setUserContext(userContext);

		MqttDisconnect disconnect = new MqttDisconnect();
		try {
			comms.disconnect(disconnect, quiesceTimeout, token);
		} catch (MqttException ex) {
			// @TRACE 105=< exception
			log.fine(CLASS_NAME, methodName, "105", null, ex);
			throw ex;
		}
		// @TRACE 108=<
		log.fine(CLASS_NAME, methodName, "108");

		return token;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnectForcibly()
	 */
	public void disconnectForcibly() throws MqttException {
		disconnectForcibly(QUIESCE_TIMEOUT, DISCONNECT_TIMEOUT);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnectForcibly(long)
	 */
	public void disconnectForcibly(long disconnectTimeout) throws MqttException {
		disconnectForcibly(QUIESCE_TIMEOUT, disconnectTimeout);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#disconnectForcibly(long,
	 * long)
	 */
	public void disconnectForcibly(long quiesceTimeout, long disconnectTimeout) throws MqttException {
		comms.disconnectForcibly(quiesceTimeout, disconnectTimeout);
	}

	/**
	 * Disconnects from the server forcibly to reset all the states. Could be
	 * useful when disconnect attempt failed.
	 * <p>
	 * Because the client is able to establish the TCP/IP connection to a none
	 * MQTT server and it will certainly fail to send the disconnect packet.
	 *
	 * @param quiesceTimeout
	 *            the amount of time in milliseconds to allow for existing work
	 *            to finish before disconnecting. A value of zero or less means
	 *            the client will not quiesce.
	 * @param disconnectTimeout
	 *            the amount of time in milliseconds to allow send disconnect
	 *            packet to server.
	 * @param sendDisconnectPacket
	 *            if true, will send the disconnect packet to the server
	 * @throws MqttException
	 *             if any unexpected error
	 */
	public void disconnectForcibly(long quiesceTimeout, long disconnectTimeout, boolean sendDisconnectPacket)
			throws MqttException {
		comms.disconnectForcibly(quiesceTimeout, disconnectTimeout, sendDisconnectPacket);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#isConnected()
	 */
	public boolean isConnected() {
		return comms.isConnected();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#isConnecting()
	 */
	public boolean isConnecting() {
		return comms.isConnecting();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#getClientId()
	 */
	public String getClientId() {
		return clientId;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#getServerURI()
	 */
	public String getServerURI() {
		return serverURI;
	}

	/**
	 * Returns the currently connected Server URI Implemented due to:
	 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=481097
	 *
	 * Where getServerURI only returns the URI that was provided in
	 * MqttAsyncClient's constructor, getCurrentServerURI returns the URI of the
	 * Server that the client is currently connected to. This would be different
	 * in scenarios where multiple server URIs have been provided to the
	 * MqttConnectOptions.
	 *
	 * @return the currently connected server URI
	 */
	public String getCurrentServerURI() {
		return comms.getNetworkModules()[comms.getNetworkModuleIndex()].getServerURI();
	}

	/**
	 * Get a topic object which can be used to publish messages.
	 * <p>
	 * There are two alternative methods that should be used in preference to
	 * this one when publishing a message:
	 * </p>
	 * <ul>
	 * <li>{@link MqttAsyncClient#publish(String, MqttMessage)} to publish a
	 * message in a non-blocking manner or</li>
	 * <li>{@link MqttClient#publish(String, MqttMessage)} to publish a message
	 * in a blocking manner</li>
	 * </ul>
	 * <p>
	 * When you build an application, the design of the topic tree should take
	 * into account the following principles of topic name syntax and semantics:
	 * </p>
	 *
	 * <ul>
	 * <li>A topic must be at least one character long.</li>
	 * <li>Topic names are case sensitive. For example, <em>ACCOUNTS</em> and
	 * <em>Accounts</em> are two different topics.</li>
	 * <li>Topic names can include the space character. For example,
	 * <em>Accounts payable</em> is a valid topic.</li>
	 * <li>A leading "/" creates a distinct topic. For example,
	 * <em>/finance</em> is different from <em>finance</em>. <em>/finance</em>
	 * matches "+/+" and "/+", but not "+".</li>
	 * <li>Do not include the null character (Unicode \x0000) in any topic.</li>
	 * </ul>
	 *
	 * <p>
	 * The following principles apply to the construction and content of a topic
	 * tree:
	 * </p>
	 *
	 * <ul>
	 * <li>The length is limited to 64k but within that there are no limits to
	 * the number of levels in a topic tree.</li>
	 * <li>There can be any number of root nodes; that is, there can be any
	 * number of topic trees.</li>
	 * </ul>
	 *
	 * @param topic
	 *            the topic to use, for example "finance/stock/ibm".
	 * @return an MqttTopic object, which can be used to publish messages to the
	 *         topic.
	 * @throws IllegalArgumentException
	 *             if the topic contains a '+' or '#' wildcard character.
	 */
	protected MqttTopic getTopic(String topic) {
		MqttTopic.validate(topic, false/* wildcards NOT allowed */);

		MqttTopic result = (MqttTopic) topics.get(topic);
		if (result == null) {
			result = new MqttTopic(topic, comms);
			topics.put(topic, result);
		}
		return result;
	}

	/*
	 * (non-Javadoc) Check and send a ping if needed. <p>By default, client
	 * sends PingReq to server to keep the connection to server. For some
	 * platforms which cannot use this mechanism, such as Android, developer
	 * needs to handle the ping request manually with this method. </p>
	 *
	 * @throws MqttException for other errors encountered while publishing the
	 * message.
	 */
	public IMqttToken checkPing(Object userContext, IMqttActionListener callback) throws MqttException {
		final String methodName = "ping";
		MqttToken token;
		// @TRACE 117=>
		log.fine(CLASS_NAME, methodName, "117");

		token = comms.checkForActivity(callback);
		// @TRACE 118=<
		log.fine(CLASS_NAME, methodName, "118");

		return token;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(java.lang.
	 * String, int, java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken subscribe(String topicFilter, int qos, Object userContext, IMqttActionListener callback)
			throws MqttException {
		return this.subscribe(new String[] { topicFilter }, new int[] { qos }, userContext, callback);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(java.lang.
	 * String, int)
	 */
	public IMqttToken subscribe(String topicFilter, int qos) throws MqttException {
		return this.subscribe(new String[] { topicFilter }, new int[] { qos }, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(java.lang.
	 * String[], int[])
	 */
	public IMqttToken subscribe(String[] topicFilters, int[] qos) throws MqttException {
		return this.subscribe(topicFilters, qos, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(java.lang.
	 * String[], int[], java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken subscribe(String[] topicFilters, int[] qos, Object userContext, IMqttActionListener callback)
			throws MqttException {

		if (topicFilters.length != qos.length) {
			throw new IllegalArgumentException();
		}

		// remove any message handlers for individual topics and validate topicFilter
		for (String topicFilter : topicFilters) {
			// Check if the topic filter is valid before subscribing
			MqttTopic.validate(topicFilter, true/* allow wildcards */);
			this.comms.removeMessageListener(topicFilter);
		}
		
		return this.subscribeBase(topicFilters, qos, userContext, callback);
	}

	private IMqttToken subscribeBase(String[] topicFilters, int[] qos, Object userContext, IMqttActionListener callback)
			throws MqttException {
		final String methodName = "subscribe";
				
		// Only Generate Log string if we are logging at FINE level
		if (log.isLoggable(Logger.FINE)) {
			StringBuffer subs = new StringBuffer();
			for (int i = 0; i < topicFilters.length; i++) {
				if (i > 0) {
					subs.append(", ");
				}
				subs.append("topic=").append(topicFilters[i]).append(" qos=").append(qos[i]);			
			}
			// @TRACE 106=Subscribe topicFilter={0} userContext={1} callback={2}
			log.fine(CLASS_NAME, methodName, "106", new Object[] { subs.toString(), userContext, callback });
		}

		MqttToken token = new MqttToken(getClientId());
		token.setActionCallback(callback);
		token.setUserContext(userContext);
		token.internalTok.setTopics(topicFilters);

		MqttSubscribe register = new MqttSubscribe(topicFilters, qos);

		comms.sendNoWait(register, token);
		// @TRACE 109=<
		log.fine(CLASS_NAME, methodName, "109");

		return token;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(java.lang.
	 * String, int, java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken subscribe(String topicFilter, int qos, Object userContext, IMqttActionListener callback,
			IMqttMessageListener messageListener) throws MqttException {

		return this.subscribe(new String[] { topicFilter }, new int[] { qos }, userContext, callback,
				new IMqttMessageListener[] { messageListener });
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(java.lang.
	 * String, int)
	 */
	public IMqttToken subscribe(String topicFilter, int qos, IMqttMessageListener messageListener)
			throws MqttException {
		return this.subscribe(new String[] { topicFilter }, new int[] { qos }, null, null,
				new IMqttMessageListener[] { messageListener });
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#subscribe(java.lang.
	 * String[], int[])
	 */
	public IMqttToken subscribe(String[] topicFilters, int[] qos, IMqttMessageListener[] messageListeners)
			throws MqttException {
		return this.subscribe(topicFilters, qos, null, null, messageListeners);
	}

	public IMqttToken subscribe(String[] topicFilters, int[] qos, Object userContext, IMqttActionListener callback,
			IMqttMessageListener[] messageListeners) throws MqttException {

		if (messageListeners != null && (messageListeners.length != qos.length) || (qos.length != topicFilters.length)) {
			throw new IllegalArgumentException();
		}

		// add or remove message handlers to the list for this client
		for (int i = 0; i < topicFilters.length; ++i) {
			MqttTopic.validate(topicFilters[i], true/* allow wildcards */);
            if (messageListeners == null || messageListeners[i] == null) {
                this.comms.removeMessageListener(topicFilters[i]);
            }
            else {
                this.comms.setMessageListener(topicFilters[i], messageListeners[i]);
            }
		}

		IMqttToken token = null;
		try 	{
			token = this.subscribeBase(topicFilters, qos, userContext, callback);
		} catch(Exception e) {
			// if the subscribe fails, then we have to remove the message handlers
			for (String topicFilter : topicFilters) {
				this.comms.removeMessageListener(topicFilter);
			}
			throw e;
		}
		return token;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(java.lang.
	 * String, java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken unsubscribe(String topicFilter, Object userContext, IMqttActionListener callback)
			throws MqttException {
		return unsubscribe(new String[] { topicFilter }, userContext, callback);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(java.lang.
	 * String)
	 */
	public IMqttToken unsubscribe(String topicFilter) throws MqttException {
		return unsubscribe(new String[] { topicFilter }, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(java.lang.
	 * String[])
	 */
	public IMqttToken unsubscribe(String[] topicFilters) throws MqttException {
		return unsubscribe(topicFilters, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#unsubscribe(java.lang.
	 * String[], java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttToken unsubscribe(String[] topicFilters, Object userContext, IMqttActionListener callback)
			throws MqttException {
		final String methodName = "unsubscribe";

		// Only Generate Log string if we are logging at FINE level
		if (log.isLoggable(Logger.FINE)) {
			String subs = "";
			for (int i = 0; i < topicFilters.length; i++) {
				if (i > 0) {
					subs += ", ";
				}
				subs += topicFilters[i];
			}

			// @TRACE 107=Unsubscribe topic={0} userContext={1} callback={2}
			log.fine(CLASS_NAME, methodName, "107", new Object[] { subs, userContext, callback });
		}

		for (String topicFilter : topicFilters) {
			// Check if the topic filter is valid before unsubscribing
			// Although we already checked when subscribing, but invalid
			// topic filter is meanless for unsubscribing, just prohibit it
			// to reduce unnecessary control packet send to broker.
			MqttTopic.validate(topicFilter, true/* allow wildcards */);
		}

		// remove message handlers from the list for this client
		for (String topicFilter : topicFilters) {
			this.comms.removeMessageListener(topicFilter);
		}

		MqttToken token = new MqttToken(getClientId());
		token.setActionCallback(callback);
		token.setUserContext(userContext);
		token.internalTok.setTopics(topicFilters);

		MqttUnsubscribe unregister = new MqttUnsubscribe(topicFilters);

		comms.sendNoWait(unregister, token);
		// @TRACE 110=<
		log.fine(CLASS_NAME, methodName, "110");

		return token;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#removeMessage(IMqttDeliveryToken)
	 */
	public boolean removeMessage(IMqttDeliveryToken token) throws MqttException {
		return comms.removeMessage(token);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#setCallback(MqttCallback)
	 */
	public void setCallback(MqttCallback callback) {
		this.mqttCallback = callback;
		comms.setCallback(callback);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#setManualAcks(manualAcks)
	 */
	public void setManualAcks(boolean manualAcks) {
		comms.setManualAcks(manualAcks);
	}

	public void messageArrivedComplete(int messageId, int qos) throws MqttException {
		comms.messageArrivedComplete(messageId, qos);
	}

	/**
	 * Returns a randomly generated client identifier based on the the fixed
	 * prefix (paho) and the system time.
	 * <p>
	 * When cleanSession is set to false, an application must ensure it uses the
	 * same client identifier when it reconnects to the server to resume state
	 * and maintain assured message delivery.
	 * </p>
	 * 
	 * @return a generated client identifier
	 * @see MqttConnectOptions#setCleanSession(boolean)
	 */
	public static String generateClientId() {
		// length of nanoTime = 15, so total length = 19 < 65535(defined in
		// spec)
		return CLIENT_ID_PREFIX + System.nanoTime();

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see IMqttAsyncClient#getPendingDeliveryTokens()
	 */
	public IMqttDeliveryToken[] getPendingDeliveryTokens() {
		return comms.getPendingDeliveryTokens();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(java.lang.String,
	 * byte[], int, boolean, java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttDeliveryToken publish(String topic, byte[] payload, int qos, boolean retained, Object userContext,
			IMqttActionListener callback) throws MqttException, MqttPersistenceException {
		MqttMessage message = new MqttMessage(payload);
		message.setQos(qos);
		message.setRetained(retained);
		return this.publish(topic, message, userContext, callback);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(java.lang.String,
	 * byte[], int, boolean)
	 */
	public IMqttDeliveryToken publish(String topic, byte[] payload, int qos, boolean retained)
			throws MqttException, MqttPersistenceException {
		return this.publish(topic, payload, qos, retained, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(java.lang.String,
	 * org.eclipse.paho.client.mqttv3.MqttMessage)
	 */
	public IMqttDeliveryToken publish(String topic, MqttMessage message)
			throws MqttException, MqttPersistenceException {
		return this.publish(topic, message, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.paho.client.mqttv3.IMqttAsyncClient#publish(java.lang.String,
	 * org.eclipse.paho.client.mqttv3.MqttMessage, java.lang.Object,
	 * org.eclipse.paho.client.mqttv3.IMqttActionListener)
	 */
	public IMqttDeliveryToken publish(String topic, MqttMessage message, Object userContext,
			IMqttActionListener callback) throws MqttException, MqttPersistenceException {
		final String methodName = "publish";
		// @TRACE 111=< topic={0} message={1}userContext={1} callback={2}
		log.fine(CLASS_NAME, methodName, "111", new Object[] { topic, userContext, callback });

		// Checks if a topic is valid when publishing a message.
		MqttTopic.validate(topic, false/* wildcards NOT allowed */);

		MqttDeliveryToken token = new MqttDeliveryToken(getClientId());
		token.setActionCallback(callback);
		token.setUserContext(userContext);
		token.setMessage(message);
		token.internalTok.setTopics(new String[] { topic });

		MqttPublish pubMsg = new MqttPublish(topic, message);
		comms.sendNoWait(pubMsg, token);

		// @TRACE 112=<
		log.fine(CLASS_NAME, methodName, "112");

		return token;
	}

	/**
	 * User triggered attempt to reconnect
	 * 
	 * @throws MqttException
	 *             if there is an issue with reconnecting
	 */
	public void reconnect() throws MqttException {
		final String methodName = "reconnect";
		// @Trace 500=Attempting to reconnect client: {0}
		log.fine(CLASS_NAME, methodName, "500", new Object[] { this.clientId });
		// Some checks to make sure that we're not attempting to reconnect an
		// already connected client
		if (comms.isConnected()) {
			throw ExceptionHelper.createMqttException(MqttException.REASON_CODE_CLIENT_CONNECTED);
		}
		if (comms.isConnecting()) {
			throw new MqttException(MqttException.REASON_CODE_CONNECT_IN_PROGRESS);
		}
		if (comms.isDisconnecting()) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_DISCONNECTING);
		}
		if (comms.isClosed()) {
			throw new MqttException(MqttException.REASON_CODE_CLIENT_CLOSED);
		}
		// We don't want to spam the server
		stopReconnectCycle();

		attemptReconnect();
	}

	/**
	 * Attempts to reconnect the client to the server. If successful it will
	 * make sure that there are no further reconnects scheduled. However if the
	 * connect fails, the delay will double up to 128 seconds and will
	 * re-schedule the reconnect for after the delay.
	 *
	 * Any thrown exceptions are logged but not acted upon as it is assumed that
	 * they are being thrown due to the server being offline and so reconnect
	 * attempts will continue.
	 */
	private void attemptReconnect() {
		final String methodName = "attemptReconnect";
		// @Trace 500=Attempting to reconnect client: {0}
		log.fine(CLASS_NAME, methodName, "500", new Object[] { this.clientId });
		try {
			connect(this.connOpts, this.userContext, new MqttReconnectActionListener(methodName));
		} catch (MqttSecurityException ex) {
			// @TRACE 804=exception
			log.fine(CLASS_NAME, methodName, "804", null, ex);
		} catch (MqttException ex) {
			// @TRACE 804=exception
			log.fine(CLASS_NAME, methodName, "804", null, ex);
		}
	}

	private void startReconnectCycle() {
		String methodName = "startReconnectCycle";
		// @Trace 503=Start reconnect timer for client: {0}, delay: {1}
		log.fine(CLASS_NAME, methodName, "503", new Object[] { this.clientId, Long.valueOf(reconnectDelay) });
		reconnectTimer = new Timer("MQTT Reconnect: " + clientId);
		reconnectTimer.schedule(new ReconnectTask(), reconnectDelay);
	}

	private void stopReconnectCycle() {
		String methodName = "stopReconnectCycle";
		// @Trace 504=Stop reconnect timer for client: {0}
		log.fine(CLASS_NAME, methodName, "504", new Object[] { this.clientId });
		synchronized (clientLock) {
			if (this.connOpts.isAutomaticReconnect()) {
				if (reconnectTimer != null) {
					reconnectTimer.cancel();
					reconnectTimer = null;
				}
				reconnectDelay = 1000; // Reset Delay Timer
			}
		}
	}

	private class ReconnectTask extends TimerTask {
		private static final String methodName = "ReconnectTask.run";

		public void run() {
			// @Trace 506=Triggering Automatic Reconnect attempt.
			log.fine(CLASS_NAME, methodName, "506");
			attemptReconnect();
		}
	}

	class MqttReconnectCallback implements MqttCallbackExtended {

		final boolean automaticReconnect;

		MqttReconnectCallback(boolean isAutomaticReconnect) {
			automaticReconnect = isAutomaticReconnect;
		}

		public void connectionLost(Throwable cause) {
			if (automaticReconnect) {
				// Automatic reconnect is set so make sure comms is in resting
				// state
				comms.setRestingState(true);
				reconnecting = true;
				startReconnectCycle();
			}
		}

		public void messageArrived(String topic, MqttMessage message) throws Exception {
		}

		public void deliveryComplete(IMqttDeliveryToken token) {
		}

		public void connectComplete(boolean reconnect, String serverURI) {
		}

	}

	class MqttReconnectActionListener implements IMqttActionListener {

		final String methodName;

		MqttReconnectActionListener(String methodName) {
			this.methodName = methodName;
		}

		public void onSuccess(IMqttToken asyncActionToken) {
			// @Trace 501=Automatic Reconnect Successful: {0}
			log.fine(CLASS_NAME, methodName, "501", new Object[] { asyncActionToken.getClient().getClientId() });
			comms.setRestingState(false);
			stopReconnectCycle();
		}

		public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
			// @Trace 502=Automatic Reconnect failed, rescheduling: {0}
			log.fine(CLASS_NAME, methodName, "502", new Object[] { asyncActionToken.getClient().getClientId() });
			if (reconnectDelay < connOpts.getMaxReconnectDelay()) {
				reconnectDelay = reconnectDelay * 2;
			}
			rescheduleReconnectCycle(reconnectDelay);
		}

		private void rescheduleReconnectCycle(int delay) {
			String reschedulemethodName = methodName + ":rescheduleReconnectCycle";
			// @Trace 505=Rescheduling reconnect timer for client: {0}, delay:
			// {1}
			log.fine(CLASS_NAME, reschedulemethodName, "505",
					new Object[] { MqttAsyncClient.this.clientId, String.valueOf(reconnectDelay) });
			synchronized (clientLock) {
				if (MqttAsyncClient.this.connOpts.isAutomaticReconnect()) {
					if (reconnectTimer != null) {
						reconnectTimer.schedule(new ReconnectTask(), delay);
					} else {
						// The previous reconnect timer was cancelled
						reconnectDelay = delay;
						startReconnectCycle();
					}
				}
			}
		}

	}

	/**
	 * Sets the DisconnectedBufferOptions for this client
	 * 
	 * @param bufferOpts
	 *            the {@link DisconnectedBufferOptions}
	 */
	public void setBufferOpts(DisconnectedBufferOptions bufferOpts) {
		this.comms.setDisconnectedMessageBuffer(new DisconnectedMessageBuffer(bufferOpts));
	}

	/**
	 * Returns the number of messages in the Disconnected Message Buffer
	 * 
	 * @return Count of messages in the buffer
	 */
	public int getBufferedMessageCount() {
		return this.comms.getBufferedMessageCount();
	}

	/**
	 * Returns a message from the Disconnected Message Buffer
	 * 
	 * @param bufferIndex
	 *            the index of the message to be retrieved.
	 * @return the message located at the bufferIndex
	 */
	public MqttMessage getBufferedMessage(int bufferIndex) {
		return this.comms.getBufferedMessage(bufferIndex);
	}

	/**
	 * Deletes a message from the Disconnected Message Buffer
	 * 
	 * @param bufferIndex
	 *            the index of the message to be deleted.
	 */
	public void deleteBufferedMessage(int bufferIndex) {
		this.comms.deleteBufferedMessage(bufferIndex);
	}

	/**
	 * Returns the current number of outgoing in-flight messages being sent by
	 * the client. Note that this number cannot be guaranteed to be 100%
	 * accurate as some messages may have been sent or queued in the time taken
	 * for this method to return.
	 * 
	 * @return the current number of in-flight messages.
	 */
	public int getInFlightMessageCount() {
		return this.comms.getActualInFlight();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#close()
	 */
	public void close() throws MqttException {
		close(false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.paho.client.mqttv3.IMqttAsyncClient#close()
	 */
	public void close(boolean force) throws MqttException {
		final String methodName = "close";
		// @TRACE 113=<
		log.fine(CLASS_NAME, methodName, "113");
		comms.close(force);
		// @TRACE 114=>
		log.fine(CLASS_NAME, methodName, "114");

	}

	/**
	 * Return a debug object that can be used to help solve problems.
	 * 
	 * @return the {@link Debug} object
	 */
	public Debug getDebug() {
		return new Debug(clientId, comms);
	}

}
