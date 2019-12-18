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

package com.here.xyz.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.here.xyz.hub.auth.Authorization;
import com.here.xyz.hub.cache.CacheClient;
import com.here.xyz.hub.config.ConnectorConfigClient;
import com.here.xyz.hub.config.SpaceConfigClient;
import com.here.xyz.hub.connectors.BurstAndUpdateThread;
import com.here.xyz.hub.util.ARN;
import com.here.xyz.hub.util.ConfigDecryptor;
import com.here.xyz.hub.util.ConfigDecryptor.CryptoException;
import com.here.xyz.hub.util.logging.Logging;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.util.NetUtils;

public class Service implements Logging {

  /**
   * The service start time.
   */
  public static final long START_TIME = System.currentTimeMillis();

  /**
   * The build version.
   */
  public static final String BUILD_VERSION = getBuildProperty("xyzhub.version");
  public static final String XYZ_HUB_USER_AGENT = "XYZ-Hub/" + Service.BUILD_VERSION;

  /**
   * The build time.
   */
  public static final long BUILD_TIME = getBuildTime();

  /**
   * The host ID.
   */
  public static final String HOST_ID = UUID.randomUUID().toString();

  /**
   * The log4J2 console configuration
   */
  private static final String CONSOLE_LOG_CONFIG = "log4j2-console-json.json";

  /**
   * The entry point to the Vert.x core API.
   */
  public static Vertx vertx;

  /**
   * The service configuration.
   */
  public static Config configuration;

  /**
   * The client to access the space configuration.
   */
  public static SpaceConfigClient spaceConfigClient;

  /**
   * The client to access the the connector configuration.
   */
  public static ConnectorConfigClient connectorConfigClient;

  /**
   * A web client to access XYZ Hub nodes and other web resources.
   */
  public static WebClient webClient;

  /**
   * The cache client for the service.
   */
  public static CacheClient cacheClient;

  /**
   * The hostname
   */
  private static String hostname;

  /**
   * The service entry point.
   */
  public static void main(String[] arguments) {
    Configurator.initialize("default", CONSOLE_LOG_CONFIG);
    final ConfigStoreOptions fileStore = new ConfigStoreOptions().setType("file").setConfig(new JsonObject().put("path", "config.json"));
    final ConfigStoreOptions envConfig = new ConfigStoreOptions().setType("env");
    final ConfigStoreOptions sysConfig = new ConfigStoreOptions().setType("sys");
    final ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(fileStore).addStore(envConfig).addStore(sysConfig);
    boolean debug = Arrays.asList(arguments).contains("--debug");

    final VertxOptions vertxOptions = new VertxOptions();

    if (debug) {
      vertxOptions
          .setBlockedThreadCheckInterval(TimeUnit.MINUTES.toMillis(1))
          .setMaxEventLoopExecuteTime(TimeUnit.MINUTES.toMillis(1))
          .setMaxWorkerExecuteTime(TimeUnit.MINUTES.toMillis(1))
          .setWarningExceptionTime(TimeUnit.MINUTES.toMillis(1));
    }

    vertx = Vertx.vertx(vertxOptions);
    webClient = WebClient.create(Service.vertx, new WebClientOptions().setUserAgent(XYZ_HUB_USER_AGENT));
    ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
    retriever.getConfig(Service::onConfigLoaded);
  }

  /**
   *
   */
  private static void onConfigLoaded(AsyncResult<JsonObject> ar) {
    final JsonObject config = ar.result();
    configuration = config.mapTo(Config.class);

    initializeLogger(configuration);
    decryptSecrets();

    cacheClient = CacheClient.create();

    spaceConfigClient = SpaceConfigClient.getInstance();
    connectorConfigClient = ConnectorConfigClient.getInstance();

    spaceConfigClient.init(spaceConfigReady -> {
      if (spaceConfigReady.succeeded()) {
        connectorConfigClient.init(connectorConfigReady -> {
          if (connectorConfigReady.succeeded()) {
            if (Service.configuration.INSERT_LOCAL_CONNECTORS) {
              connectorConfigClient.insertLocalConnectors();
            }

            BurstAndUpdateThread.initialize();

            vertx.deployVerticle(XYZHubRESTVerticle.class, new DeploymentOptions().setConfig(config).setWorker(true).setInstances(8));

            Logging.getLogger().info("XYZ Hub " + BUILD_VERSION + " was started at " + new Date().toString());

            Thread.setDefaultUncaughtExceptionHandler((thread, t) -> Logging.getLogger().error("Uncaught exception: ", t));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
              //This may fail, if we are OOM, but lets at least try.
              Logging.getLogger().info("XYZ Service is going down at " + new Date().toString());
            }));
          }
        });
      }
    });
  }

  private static void decryptSecrets() {
    try {
      configuration.STORAGE_DB_PASSWORD = decryptSecret(configuration.STORAGE_DB_PASSWORD);
    } catch (CryptoException e) {
      throw new RuntimeException("Error when trying to decrypt STORAGE_DB_PASSWORD.", e);
    }
    try {
      configuration.ADMIN_MESSAGE_JWT = decryptSecret(configuration.ADMIN_MESSAGE_JWT);
    } catch (CryptoException e) {
      configuration.ADMIN_MESSAGE_JWT = null;
      Logging.getLogger().error("Error when trying to decrypt ADMIN_MESSAGE_JWT. AdminMessaging won't work.", e);
    }
  }

  private static String decryptSecret(String encryptedSecret) throws CryptoException {
    if (ConfigDecryptor.isEncrypted(encryptedSecret)) {
      return ConfigDecryptor.decryptSecret(encryptedSecret);
    }
    return encryptedSecret;
  }

  private static void initializeLogger(Config config) {
    if (!CONSOLE_LOG_CONFIG.equals(config.LOG_CONFIG)) {
      Configurator.reconfigure(NetUtils.toURI(config.LOG_CONFIG));
    }
  }

  public static String getHostname() {
    if (hostname == null) {
      final String hostname = Service.configuration != null ? Service.configuration.HOST_NAME : null;
      if (hostname != null && hostname.length() > 0) {
        Service.hostname = hostname;
      } else {
        try {
          Service.hostname = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
          Logging.getLogger().error("Unable to resolve the hostname using Java's API.", e);
          Service.hostname = "localhost";
        }
      }
    }
    return hostname;
  }

  private static String getBuildProperty(String name) {
    InputStream input = Service.class.getResourceAsStream("/build.properties");

    // load a properties file
    Properties buildProperties = new Properties();
    try {
      buildProperties.load(input);
    } catch (IOException ignored) {
    }

    return buildProperties.getProperty(name);
  }

  private static long getBuildTime() {
    String buildTime = getBuildProperty("xyzhub.buildTime");
    try {
      return new SimpleDateFormat("yyyy.MM.dd-HH:mm").parse(buildTime).getTime();
    } catch (ParseException e) {
      return 0;
    }
  }

  /**
   * The service configuration.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Config {
    public int HTTP_PORT;
    public String XYZ_HUB_REDIS_HOST;
    public int XYZ_HUB_REDIS_PORT;
    public String XYZ_HUB_S3_BUCKET;

    public String JWT_PUB_KEY;
    public Authorization.AuthorizationType XYZ_HUB_AUTH;
    public String LOG_CONFIG;
    public String LOGGING_TYPE;
    public String LOG_PATH;
    public boolean INSERT_LOCAL_CONNECTORS;
    public String PSQL_HOST;

    public String STORAGE_DB_URL;
    public String STORAGE_DB_USER;
    public String STORAGE_DB_PASSWORD;

    public String SPACES_DYNAMODB_TABLE_ARN;
    public String CONNECTORS_DYNAMODB_TABLE_ARN;
    public String PACKAGES_DYNAMODB_TABLE_ARN;

    public int INSTANCE_COUNT;
    public ARN ADMIN_MESSAGE_TOPIC_ARN;
    public String ADMIN_MESSAGE_JWT;
    public int ADMIN_MESSAGE_PORT;

    public String XYZ_HUB_PUBLIC_PROTOCOL;
    public String XYZ_HUB_PUBLIC_HOST;
    public int XYZ_HUB_PUBLIC_PORT;

    public String IAM_SERVICE_ROLE;
    public String HOST_NAME;

    public int GLOBAL_MAX_QUEUE_SIZE; //MB
    public int REMOTE_FUNCTION_REQUEST_TIMEOUT; //seconds

    public String FS_WEB_ROOT;

    public String HEALTH_CHECK_HEADER_NAME;
    public String HEALTH_CHECK_HEADER_VALUE;
  }
}
