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
package org.apache.solr.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Solr standalone version of File System ConfigSetService.
 * <p>
 * Loads a ConfigSet defined by the core's configSet property,
 * looking for a directory named for the configSet property value underneath
 * a base directory.  If no configSet property is set, loads the ConfigSet
 * instead from the core's instance directory.
 */
public class FileSystemConfigSetService extends ConfigSetService {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final Path configSetBase;

    public FileSystemConfigSetService(SolrResourceLoader loader, boolean shareSchema, Path configSetBase) {
        super(loader, shareSchema);
        this.configSetBase = configSetBase;
    }

    @Override
    public SolrResourceLoader createCoreResourceLoader(CoreDescriptor cd) {
        Path instanceDir = locateInstanceDir(cd);
        SolrResourceLoader solrResourceLoader = new SolrResourceLoader(instanceDir, parentLoader.getClassLoader());
        return solrResourceLoader;
    }

    @Override
    public String configSetName(CoreDescriptor cd) {
        return (cd.getConfigSet() == null ? "instancedir " : "configset ") + locateInstanceDir(cd);
    }

    @Override
    public Boolean configExists(String configName) throws IOException {
        return null;
    }

    @Override
    public void deleteConfigDir(String configName) throws IOException {

    }

    @Override
    public void copyConfigDir(String fromConfig, String toConfig) throws IOException {

    }

    @Override
    public void copyConfigDir(String fromConfig, String toConfig, Set<String> copiedToZkPaths) throws IOException {

    }

    @Override
    public void uploadConfigDir(Path dir, String configName) throws IOException {

    }

    @Override
    public void uploadConfigDir(Path dir, String configName, Pattern filenameExclusions) throws IOException {

    }

    @Override
    public void downloadConfigDir(String configName, Path dir) throws IOException {

    }

    @Override
    public List<String> listConfigs() throws IOException {
        return null;
    }

    protected Path locateInstanceDir(CoreDescriptor cd) {
        String configSet = cd.getConfigSet();
        if (configSet == null)
            return cd.getInstanceDir();
        Path configSetDirectory = configSetBase.resolve(configSet);
        if (!Files.isDirectory(configSetDirectory))
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "Could not load configuration from directory " + configSetDirectory);
        return configSetDirectory;
    }

    @Override
    protected Long getCurrentSchemaModificationVersion(String configSet, SolrConfig solrConfig, String schemaFileName) {
        Path schemaFile = Paths.get(solrConfig.getResourceLoader().getConfigDir()).resolve(schemaFileName);
        try {
            return Files.getLastModifiedTime(schemaFile).toMillis();
        } catch (FileNotFoundException e) {
            return null; // acceptable
        } catch (IOException e) {
            log.warn("Unexpected exception when getting modification time of {}", schemaFile, e);
            return null; // debatable; we'll see an error soon if there's a real problem
        }
    }

}
