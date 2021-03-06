/*********************************************************************
 * Copyright (c) 2019, 2020 Bosch.IO GmbH [and others]
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 **********************************************************************/
package org.eclipse.kuksa.honoConnector;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonClientOptions;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.ApplicationClientFactory;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.config.ClientConfigProperties;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.kuksa.honoConnector.config.ConnectionConfig;
import org.eclipse.kuksa.honoConnector.influxdb.InfluxDBClient;
import org.eclipse.kuksa.honoConnector.message.MessageDTO;
import org.eclipse.kuksa.honoConnector.message.MessageHandler;
import org.slf4j.Logger;

import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.util.function.Function;

import static org.slf4j.LoggerFactory.getLogger;

public class HonoInfluxConnection {

    /** standard logger for information and error output */
    private static final Logger LOGGER = getLogger(HonoInfluxConnection.class);

    private final String tenantId;
    
    /** vertx instance opened to connect to Hono Messaging, needs to be closed */
    private final Vertx vertx;

    /** connection used to connect to the Hono Messaging Service to receive new messages */
    private final HonoConnection honoConnection;

    /** connection options for the connection to the Hono Messaging Service */
    private final ProtonClientOptions options;

    /** message handler forwarding the messages to their final destination */
    private final MessageHandler messageHandler;

    /** handler of the outcome of a connection attempt to Hono Messaging */
    private final Handler<AsyncResult<HonoConnection>> connectionHandler;
    
    public HonoInfluxConnection(final ClientConfigProperties honoConfig,
    		final String influxURL, final ConnectionConfig config) throws MalformedURLException {
    	this(honoConfig, influxURL, config.getTenantId(), config.getInfluxDatabaseName());
    }
    
    public HonoInfluxConnection(final ClientConfigProperties honoConfig,
    		final String influxURL, final String tenantId, final String dbName) throws MalformedURLException {
    	this.tenantId = tenantId;
        vertx = Vertx.vertx();

        honoConnection = HonoConnection.newConnection(vertx, honoConfig);
        ApplicationClientFactory clientFactory = ApplicationClientFactory.create(honoConnection);

        options = new ProtonClientOptions();
        options.setConnectTimeout(10000);

        messageHandler = new InfluxDBClient(influxURL, dbName);

        // try to reconnect if link is closed by Hono
        final Handler<Void> closeHandler = unused -> {
        	// The close handler is invoked when the Hono dispatch router
        	// closes the telemetry receiver link.
        	LOGGER.info("Telemetry receiver link was closed.");
        	reconnect();
        };

        // on connection established create a new telemetry consumer to handle incoming messages
        connectionHandler = result -> {
            if (result.succeeded()) {
                LOGGER.info("Connected to Hono at {}:{} for tenantId {} and Influx database {}", honoConfig.getHost(),
                        honoConfig.getPort(), tenantId, dbName);
                clientFactory.createTelemetryConsumer(tenantId, this::handleTelemetryMessage, closeHandler);
            } else {
                LOGGER.error("Failed to connect to Hono for tenantId {}.", result.cause(), tenantId);
                disconnect();
            }
        };
    }

    /**
     * Connects to the Hono based on the options defined in {@link #options}.
     * The {@link #connectionHandler} is used as a callback for the connection attempt.
     */
	public void connectToHono() {
        try {
            LOGGER.info("Connect to Hono at host {}:{}, with user: {}", honoConnection.getConfig().getHost(),
                    honoConnection.getConfig().getPort(), honoConnection.getConfig().getUsername());
            Future<HonoConnection> future = honoConnection.connect(options);
            LOGGER.info("Started connection attempt to Hono for tenantId {}.", tenantId);
            // set handler to react to the outcome of the connection attempt
            future.setHandler(connectionHandler);
        } catch (Exception e) {
            LOGGER.error("Failed to connect to Hono for tenantId {}.", e, tenantId);
        }
	}

    /**
     * Reconnects to Hono using the {@link #connectToHono()} function.
     */
    private void reconnect() {
        LOGGER.info("Reconnecting to Hono for tenantId {}", tenantId);
        connectToHono();
    }

    /**
     * Handles the incoming message and forwards it using the {@code messageHandler}.
     * If the message is {@code null} it will drop the message.
     *
     * @param msg message to forward
     */
    private void handleTelemetryMessage(final Message msg) {
        if (msg == null) {
            LOGGER.debug("The received message from Hono is null.");
            return;
        }

        final MessageDTO messageDTO = createMessageDTO(msg);
        LOGGER.debug(messageDTO.toString());

        // forward created message dto
        messageHandler.process(messageDTO);
    }

    /**
     * Creates a new dto of the given message object.
     *
     * @param msg message to transform
     * @return the dto of the input
     */
    private static MessageDTO createMessageDTO(final Message msg) {
        final String deviceId = MessageHelper.getDeviceId(msg);

        JsonObject json = new JsonObject();
        try {
            json = MessageHelper.getJsonPayload(msg);
        } catch (DecodeException e) {
            LOGGER.warn("Failed to parse the message body to JSON.", e);
        }

        return new MessageDTO(deviceId, json.getMap());
    }

    /**
     * Disconnects from Hono and the message handler.
     */
	public void disconnect() {
        LOGGER.warn("Hono connector for tenantId {} is closing connections to Hono and InfluxDB.", tenantId);
        messageHandler.close();
        vertx.close();
        LOGGER.info("Hono connector for tenantId {} closed all connections.", tenantId);
	}
	
	public static Function<ConnectionConfig, HonoInfluxConnection> toConnection(final ClientConfigProperties honoConfig,
    		final String influxURL) {
		return connectionConfig -> {
			try {
				return new HonoInfluxConnection(honoConfig, influxURL, connectionConfig);
			} catch (final MalformedURLException e) {
				throw new UncheckedIOException(e);
			}
		};
	}
}
