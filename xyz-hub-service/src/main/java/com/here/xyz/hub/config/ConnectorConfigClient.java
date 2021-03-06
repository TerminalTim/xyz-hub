/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.xyz.hub.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.here.xyz.hub.Service;
import com.here.xyz.hub.connectors.models.Connector;
import com.here.xyz.hub.connectors.models.Connector.RemoteFunctionConfig.Embedded;
import com.here.xyz.hub.rest.admin.AdminMessage;
import com.here.xyz.hub.util.logging.Logging;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Marker;

public abstract class ConnectorConfigClient implements Initializable, Logging {

  public static final ExpiringMap<String, Connector> cache = ExpiringMap.builder()
      .expirationPolicy(ExpirationPolicy.CREATED)
      .expiration(3, TimeUnit.MINUTES)
      .build();

  public static ConnectorConfigClient getInstance() {
    // TODO remove the below comments when it's time to move to dynamo
//    if (Service.configuration.CONNECTORS_DYNAMODB_TABLE_ARN != null) {
//      if (Service.configuration.STORAGE_DB_URL != null) {
//        //We're in the migration phase
//        return MigratingSpaceConnectorConfigClient.getInstance();
//      }
//      else
//        return new DynamoConnectorConfigClient(Service.configuration.CONNECTORS_DYNAMODB_TABLE_ARN);
//    }
//    else
    return JDBCConnectorConfigClient.getInstance();
  }


  public void get(Marker marker, String connectorId, Handler<AsyncResult<Connector>> handler) {
    final Connector connectorFromCache = cache.get(connectorId);

    if (connectorFromCache != null) {
      logger().info(marker, "storageId: {} - The connector was loaded from cache", connectorId);
      handler.handle(Future.succeededFuture(connectorFromCache));
      return;
    }

    getConnector(marker, connectorId, ar -> {
      if (ar.succeeded()) {
        final Connector connector = ar.result();
        cache.put(connectorId, connector);
        handler.handle(Future.succeededFuture(connector));
      } else {
        logger().info(marker, "storageId[{}]: Connector not found", connectorId);
        handler.handle(Future.failedFuture(ar.cause()));
      }
    });
  }

  public void store(Marker marker, Connector connector, Handler<AsyncResult<Connector>> handler) {
    store(marker, connector, handler, true);
  }

  private void store(Marker marker, Connector connector, Handler<AsyncResult<Connector>> handler, boolean withInvalidation) {
    if (connector.id == null) {
      connector.id = RandomStringUtils.randomAlphanumeric(10);
    }

    storeConnector(marker, connector, ar -> {
      if (ar.succeeded()) {
        final Connector connectorResult = ar.result();
        if (withInvalidation)
          invalidateCache(connector.id);
        handler.handle(Future.succeededFuture(connectorResult));
      } else {
        logger().info(marker, "storageId[{}]: Failed to store connector configuration, reason: ", connector.id, ar.cause());
        handler.handle(Future.failedFuture(ar.cause()));
      }
    });
  }

  public void delete(Marker marker, String connectorId, Handler<AsyncResult<Connector>> handler) {
    deleteConnector(marker, connectorId, ar -> {
      if (ar.succeeded()) {
        final Connector connectorResult = ar.result();
        invalidateCache(connectorId);
        handler.handle(Future.succeededFuture(connectorResult));
      } else {
        logger().info(marker, "storageId[{}]: Failed to store connector configuration, reason: ", connectorId, ar.cause());
        handler.handle(Future.failedFuture(ar.cause()));
      }
    });
  }

  public void getAll(Marker marker, Handler<AsyncResult<List<Connector>>> handler) {
    getAllConnectors(marker, ar -> {
      if (ar.succeeded()) {
        final List<Connector> connectors = ar.result();
        connectors.forEach(c -> cache.put(c.id, c));
        handler.handle(Future.succeededFuture(connectors));
      } else {
        logger().info(marker, "Failed to load connectors, reason: ", ar.cause());
        handler.handle(Future.failedFuture(ar.cause()));
      }
    });
  }

  public void insertLocalConnectors() {
    InputStream input = ConnectorConfigClient.class.getResourceAsStream("/connectors.json");
    try (BufferedReader buffer = new BufferedReader(new InputStreamReader(input))) {
      String connectorsFile = buffer.lines().collect(Collectors.joining("\n"));
      List<Connector> connectors = Json.decodeValue(connectorsFile, new TypeReference<List<Connector>>() {
      });
      connectors.forEach(c -> {
        replaceEnvVars(c);

        storeConnectorIfNotExists(null, c, r -> {
          if (r.failed()) {
            logger().info(r.toString());
          }
        });
      });
    } catch (IOException e) {
      logger().info("Unable to insert the local connectors.");
    }
  }

  private void replaceEnvVars(final Connector connector) {
    if (connector == null) return;

    if (connector.remoteFunction instanceof Embedded) {
      final Map<String, String> map = new HashMap<String, String>() {{
        put("PSQL_HOST", Service.configuration.PSQL_HOST);
      }};

      final Embedded embedded = (Embedded) connector.remoteFunction;
      final Map<String, String> replacement = new HashMap<>();

      embedded.env.entrySet().stream()
        .filter(e->StringUtils.startsWith(e.getValue(), "${") && StringUtils.endsWith(e.getValue(), "}"))
        .forEach(e-> {
          final String placeholder = StringUtils.substringBetween(e.getValue(), "${", "}");
          if (map.containsKey(placeholder)) {
            replacement.put(e.getKey(), map.get(placeholder));
          }
        });

      embedded.env.putAll(replacement);
    }
  }

  private void storeConnectorIfNotExists(Marker marker, Connector connector, Handler<AsyncResult<Connector>> handler) {
    get(marker, connector.id, r -> {
      if (r.failed()) {
        logger().info("Connector with ID {} does not exist. Creating it ...", connector.id);
        store(marker, connector, handler, false);
      } else {
        handler.handle(Future.failedFuture("Connector with ID " + connector.id + " already exists."));
      }
    });
  }


  protected abstract void getConnector(Marker marker, String connectorId, Handler<AsyncResult<Connector>> handler);

  protected abstract void storeConnector(Marker marker, Connector connector, Handler<AsyncResult<Connector>> handler);

  protected abstract void deleteConnector(Marker marker, String connectorId, Handler<AsyncResult<Connector>> handler);

  protected abstract void getAllConnectors(Marker marker, Handler<AsyncResult<List<Connector>>> handler);

  public void invalidateCache(String id) {
    new InvalidateConnectorCacheMessage().withId(id).withBroadcastIncludeLocalNode(true).broadcast();
  }

  public static class InvalidateConnectorCacheMessage extends AdminMessage {

    String id;

    public InvalidateConnectorCacheMessage withId(String id) {
      this.id = id;
      return this;
    }

    @Override
    protected void handle() {
      cache.remove(id);
    }
  }
}
