/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.admin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.cloud.OverseerSolrResponse;
import org.apache.solr.cloud.OverseerSolrResponseSerializer;
import org.apache.solr.cloud.OverseerTaskQueue.QueueEvent;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.params.ConfigSetParams;
import org.apache.solr.common.params.ConfigSetParams.ConfigSetAction;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.ConfigSetService;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthenticationPlugin;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.cloud.Overseer.QUEUE_OPERATION;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.BASE_CONFIGSET;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.CONFIGSETS_ACTION_PREFIX;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.PROPERTY_PREFIX;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.CREATE;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.DELETE;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.LIST;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.UPLOAD;

/**
 * A {@link org.apache.solr.request.SolrRequestHandler} for ConfigSets API requests.
 */
public class ConfigSetsHandler extends RequestHandlerBase implements PermissionNameProvider {
  final public static Boolean DISABLE_CREATE_AUTH_CHECKS = Boolean.getBoolean("solr.disableConfigSetsCreateAuthChecks"); // this is for back compat only
  final public static String DEFAULT_CONFIGSET_NAME = "_default";
  final public static String AUTOCREATED_CONFIGSET_SUFFIX = ".AUTOCREATED";
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected final CoreContainer coreContainer;
  public static long DEFAULT_ZK_TIMEOUT = 300 * 1000;
  /**
   * Overloaded ctor to inject CoreContainer into the handler.
   *
   * @param coreContainer Core Container of the solr webapp installed.
   */
  public ConfigSetsHandler(final CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
  }

  public static String getSuffixedNameForAutoGeneratedConfigSet(String configName) {
    return configName + AUTOCREATED_CONFIGSET_SUFFIX;
  }

  public static boolean isAutoGeneratedConfigSet(String configName) {
    return configName != null && configName.endsWith(AUTOCREATED_CONFIGSET_SUFFIX);
  }


  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    checkErrors();

    // Pick the action
    SolrParams params = req.getParams();
    String a = params.get(ConfigSetParams.ACTION);
    if (a != null) {
      ConfigSetAction action = ConfigSetAction.get(a);
      if (action == null)
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown action: " + a);
      if (action == ConfigSetAction.UPLOAD) {
        handleConfigUploadRequest(req, rsp);
        return;
      }
      invokeAction(req, rsp, action);
    } else {
      throw new SolrException(ErrorCode.BAD_REQUEST, "action is a required param");
    }

    rsp.setHttpCaching(false);
  }

  protected void checkErrors() {
    if (coreContainer == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Core container instance missing");
    }

    // Make sure that the core is ZKAware
    if (!coreContainer.isZooKeeperAware()) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Solr instance is not running in SolrCloud mode.");
    }
  }

  void invokeAction(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetAction action) throws Exception {
    ConfigSetOperation operation = ConfigSetOperation.get(action);
    if (log.isInfoEnabled()) {
      log.info("Invoked ConfigSet Action :{} with params {} ", action.toLower(), req.getParamString());
    }
    Map<String, Object> result = operation.call(req, rsp, this);
    sendToZk(rsp, operation, result);
  }

  protected void sendToZk(SolrQueryResponse rsp, ConfigSetOperation operation, Map<String, Object> result)
      throws KeeperException, InterruptedException {
    if (result != null) {
      // We need to differentiate between collection and configsets actions since they currently
      // use the same underlying queue.
      result.put(QUEUE_OPERATION, CONFIGSETS_ACTION_PREFIX + operation.action.toLower());
      ZkNodeProps props = new ZkNodeProps(result);
      handleResponse(operation.action.toLower(), props, rsp, DEFAULT_ZK_TIMEOUT);
    }
  }

  private void handleConfigUploadRequest(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    if (!"true".equals(System.getProperty("configset.upload.enabled", "true"))) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Configset upload feature is disabled. To enable this, start Solr with '-Dconfigset.upload.enabled=true'.");
    }

    String configSetName = req.getParams().get(NAME);
    if (StringUtils.isBlank(configSetName)) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "The configuration name should be provided in the \"name\" parameter");
    }

    ConfigSetService configSetService = coreContainer.getConfigSetService();

    boolean overwritesExisting = configSetService.checkConfigExists(configSetName);

    boolean requestIsTrusted = isTrusted(req, coreContainer.getAuthenticationPlugin());

    // Get upload parameters
    String singleFilePath = req.getParams().get(ConfigSetParams.FILE_PATH, "");
    boolean allowOverwrite = req.getParams().getBool(ConfigSetParams.OVERWRITE, false);
    boolean cleanup = req.getParams().getBool(ConfigSetParams.CLEANUP, false);

    Iterator<ContentStream> contentStreamsIterator = req.getContentStreams().iterator();

    if (!contentStreamsIterator.hasNext()) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
              "No stream found for the config data to be uploaded");
    }

    InputStream inputStream = contentStreamsIterator.next().getStream();

    // Only Upload a single file
    if (!singleFilePath.isEmpty()) {
      String fixedSingleFilePath = singleFilePath;
      if (fixedSingleFilePath.charAt(0) == '/') {
        fixedSingleFilePath = fixedSingleFilePath.substring(1);
      }
      if (fixedSingleFilePath.isEmpty()) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "The file path provided for upload, '" + singleFilePath + "', is not valid.");
      } else if (cleanup) {
        // Cleanup is not allowed while using singleFilePath upload
        throw new SolrException(ErrorCode.BAD_REQUEST, "ConfigSet uploads do not allow cleanup=true when file path is used.");
      } else {
        // Create a node for the configuration in config
        // For creating the baseNode, the cleanup parameter is only allowed to be true when singleFilePath is not passed.
        createBaseNode(configSetService, overwritesExisting, requestIsTrusted, configSetName);
        configSetService.uploadFileToConfig(configSetName, fixedSingleFilePath, IOUtils.toByteArray(inputStream), allowOverwrite);
      }
      return;
    }

    if (overwritesExisting && !allowOverwrite) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
              "The configuration " + configSetName + " already exists in zookeeper");
    }

    List<String> filesToDelete;
    if (overwritesExisting && cleanup) {
      filesToDelete = configSetService.getAllConfigFiles(configSetName);
    } else {
      filesToDelete = Collections.emptyList();
    }

    // Create a node for the configuration in zookeeper
    // For creating the baseZnode, the cleanup parameter is only allowed to be true when singleFilePath is not passed.
    createBaseNode(configSetService, overwritesExisting, requestIsTrusted, configSetName);

    ZipInputStream zis = new ZipInputStream(inputStream, StandardCharsets.UTF_8);
    ZipEntry zipEntry = null;
    boolean hasEntry = false;
    while ((zipEntry = zis.getNextEntry()) != null) {
      hasEntry = true;
      String filePath = zipEntry.getName();
      if (filePath.endsWith("/")) {
        filesToDelete.remove(filePath.substring(0, filePath.length() - 1));
      } else {
        filesToDelete.remove(filePath);
      }
      if (!zipEntry.isDirectory()) {
        configSetService.uploadFileToConfig(configSetName, zipEntry.getName(), IOUtils.toByteArray(zis), true);
      }
    }
    zis.close();
    if (!hasEntry) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
              "Either empty zipped data, or non-zipped data was uploaded. In order to upload a configSet, you must zip a non-empty directory to upload.");
    }
    deleteUnusedFiles(configSetService, configSetName, filesToDelete);

    // If the request is doing a full trusted overwrite of an untrusted configSet (overwrite=true, cleanup=true), then trust the configSet.
    if (cleanup && requestIsTrusted && overwritesExisting && !isCurrentlyTrusted(configSetService, configSetName)) {
      Map<String, Object> metadata = Collections.singletonMap("trusted", true);
      configSetService.setConfigMetadata(configSetName, metadata);
    }
  }

  private void createBaseNode(ConfigSetService configSetService, boolean overwritesExisting, boolean requestIsTrusted, String configName) throws IOException {
    Map<String, Object> metadata = Collections.singletonMap("trusted", requestIsTrusted);

    if (overwritesExisting) {
      if (!requestIsTrusted) {
        ensureOverwritingUntrustedConfigSet(configSetService, configName);
      }
      // If the request is trusted and cleanup=true, then the configSet will be set to trusted after the overwriting has been done.
    } else {
      configSetService.setConfigMetadata(configName, metadata);
    }
  }

  private void deleteUnusedFiles(ConfigSetService configSetService, String configName, List<String> filesToDelete) throws IOException {
    if (!filesToDelete.isEmpty()) {
      if (log.isInfoEnabled()) {
        log.info("Cleaning up {} unused files", filesToDelete.size());
      }
      if (log.isDebugEnabled()) {
        log.debug("Cleaning up unused files: {}", filesToDelete);
      }
      configSetService.deleteFilesFromConfig(configName, filesToDelete);
    }
  }

  /*
   * Fail if an untrusted request tries to update a trusted ConfigSet
   */
  private void ensureOverwritingUntrustedConfigSet(ConfigSetService configSetService, String configName) throws IOException {
    boolean isCurrentlyTrusted = isCurrentlyTrusted(configSetService, configName);
    if (isCurrentlyTrusted) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Trying to make an untrusted ConfigSet update on a trusted configSet");
    }
  }

  private static boolean isCurrentlyTrusted(ConfigSetService configSetService, String configName) throws IOException {
    Map<String, Object> contentMap = configSetService.getConfigMetadata(configName);
    if (contentMap == null || contentMap.size() == 0) {
      return true;
    }
    return (boolean) contentMap.getOrDefault("trusted", true);
  }

  static boolean isTrusted(SolrQueryRequest req, AuthenticationPlugin authPlugin) {
    if (authPlugin != null && req.getUserPrincipal() != null) {
      log.debug("Trusted configset request");
      return true;
    }
    log.debug("Untrusted configset request");
    return false;
  }

  @SuppressWarnings({"unchecked"})
  private void handleResponse(String operation, ZkNodeProps m,
                              SolrQueryResponse rsp, long timeout) throws KeeperException, InterruptedException {
    long time = System.nanoTime();

    QueueEvent event = coreContainer.getZkController()
        .getOverseerConfigSetQueue()
        .offer(Utils.toJSON(m), timeout);
    if (event.getBytes() != null) {
      SolrResponse response = OverseerSolrResponseSerializer.deserialize(event.getBytes());
      rsp.getValues().addAll(response.getResponse());
      @SuppressWarnings({"rawtypes"})
      SimpleOrderedMap exp = (SimpleOrderedMap) response.getResponse().get("exception");
      if (exp != null) {
        Integer code = (Integer) exp.get("rspCode");
        rsp.setException(new SolrException(code != null && code != -1 ? ErrorCode.getErrorCode(code) : ErrorCode.SERVER_ERROR, (String) exp.get("msg")));
      }
    } else {
      if (System.nanoTime() - time >= TimeUnit.NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS)) {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset time out:" + timeout / 1000 + "s");
      } else if (event.getWatchedEvent() != null) {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset error [Watcher fired on path: "
            + event.getWatchedEvent().getPath() + " state: "
            + event.getWatchedEvent().getState() + " type "
            + event.getWatchedEvent().getType() + "]");
      } else {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset unknown case");
      }
    }
  }

  private static Map<String, Object> copyPropertiesWithPrefix(SolrParams params, Map<String, Object> props, String prefix) {
    Iterator<String> iter = params.getParameterNamesIterator();
    while (iter.hasNext()) {
      String param = iter.next();
      if (param.startsWith(prefix)) {
        props.put(param, params.get(param));
      }
    }

    // The configset created via an API should be mutable.
    props.put("immutable", "false");

    return props;
  }

  @Override
  public String getDescription() {
    return "Manage SolrCloud ConfigSets";
  }

  @Override
  public Category getCategory() {
    return Category.ADMIN;
  }

  public enum ConfigSetOperation {
    UPLOAD_OP(UPLOAD) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        h.handleConfigUploadRequest(req, rsp);
        return null;
      }
    },
    CREATE_OP(CREATE) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        String baseConfigSetName = req.getParams().get(BASE_CONFIGSET, DEFAULT_CONFIGSET_NAME);
        String newConfigSetName = req.getParams().get(NAME);
        if (newConfigSetName == null || newConfigSetName.length() == 0) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "ConfigSet name not specified");
        }

        if (h.coreContainer.getConfigSetService().checkConfigExists(newConfigSetName)) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "ConfigSet already exists: " + newConfigSetName);
        }

        // is there a base config that already exists
        if (!h.coreContainer.getConfigSetService().checkConfigExists(baseConfigSetName)) {
          throw new SolrException(ErrorCode.BAD_REQUEST,
                  "Base ConfigSet does not exist: " + baseConfigSetName);
        }

        Map<String, Object> props = CollectionsHandler.copy(req.getParams().required(), null, NAME);
        props.put(BASE_CONFIGSET, baseConfigSetName);
        if (!DISABLE_CREATE_AUTH_CHECKS &&
                !isTrusted(req, h.coreContainer.getAuthenticationPlugin()) &&
                isCurrentlyTrusted(h.coreContainer.getConfigSetService(), baseConfigSetName)) {
          throw new SolrException(ErrorCode.UNAUTHORIZED, "Can't create a configset with an unauthenticated request from a trusted " + BASE_CONFIGSET);
        }
        return copyPropertiesWithPrefix(req.getParams(), props, PROPERTY_PREFIX + ".");
      }
    },
    DELETE_OP(DELETE) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        return CollectionsHandler.copy(req.getParams().required(), null, NAME);
      }
    },
    @SuppressWarnings({"unchecked"})
    LIST_OP(LIST) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        NamedList<Object> results = new NamedList<>();
        List<String> configSetsList = h.coreContainer.getConfigSetService().listConfigs();
        results.add("configSets", configSetsList);
        SolrResponse response = new OverseerSolrResponse(results);
        rsp.getValues().addAll(response.getResponse());
        return null;
      }
    };

    ConfigSetAction action;

    ConfigSetOperation(ConfigSetAction action) {
      this.action = action;
    }

    public abstract Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception;

    public static ConfigSetOperation get(ConfigSetAction action) {
      for (ConfigSetOperation op : values()) {
        if (op.action == action) return op;
      }
      throw new SolrException(ErrorCode.SERVER_ERROR, "No such action" + action);
    }
  }

  @Override
  public Name getPermissionName(AuthorizationContext ctx) {
    String a = ctx.getParams().get(ConfigSetParams.ACTION);
    if (a != null) {
      ConfigSetAction action = ConfigSetAction.get(a);
      if (action == ConfigSetAction.CREATE || action == ConfigSetAction.DELETE || action == ConfigSetAction.UPLOAD) {
        return Name.CONFIG_EDIT_PERM;
      } else if (action == ConfigSetAction.LIST) {
        return Name.CONFIG_READ_PERM;
      }
    }
    return null;
  }
}
