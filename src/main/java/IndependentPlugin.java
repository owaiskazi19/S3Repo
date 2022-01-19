/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */


import org.opensearch.Version;
import org.opensearch.cluster.ClusterModule;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.inject.Injector;
import org.opensearch.common.inject.ModulesBuilder;
import org.opensearch.common.network.NetworkModule;
import org.opensearch.common.settings.*;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.indices.IndicesModule;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.node.Node;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.fs.FsRepository;
import org.opensearch.search.SearchModule;
import org.opensearch.transport.RemoteClusterService;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class IndependentPlugin {
    private  static NamedXContentRegistry namedXContentRegistry;
    private  static RecoverySettings recoverySettings;
    private  static ClusterService clusterService;
    private static Settings settings;
    private static S3Service service;
    private static Environment env;


    public IndependentPlugin(
            Environment env,
            ClusterService clusterService,
            NamedXContentRegistry namedXContentRegistry,
            RecoverySettings recoverySettings,
            Settings settings,
            S3Service service
    ) {
        Map<String, Repository.Factory> factories = new HashMap<>();
        factories.put(
                FsRepository.TYPE,
                metadata -> new FsRepository(metadata, env, namedXContentRegistry, clusterService, recoverySettings)
        );
        S3RepositoryPlugin s3repo = new S3RepositoryPlugin(settings, service);
        Map<String, Repository.Factory> newRepoTypes = s3repo.getRepositories(
                env,
                namedXContentRegistry,
                clusterService,
                recoverySettings
        );
        for (Map.Entry<String, Repository.Factory> entry : newRepoTypes.entrySet()) {
            if (factories.put(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("Repository type [" + entry.getKey() + "] is already registered");
            }
        }
    }



    public static ClusterSettings getClusterSettings() {
        final Set<SettingUpgrader<?>> clusterSettingUpgraders = new HashSet<>();
        return new ClusterSettings(settings, new HashSet<>(), clusterSettingUpgraders);
    }





    public static <T> List<T> filterPlugins(Class<T> type) {
        final List<Tuple<PluginInfo, Plugin>> plugins = null;
        return plugins.stream().filter(x -> type.isAssignableFrom(x.v2().getClass())).map(p -> ((T) p.v2())).collect(Collectors.toList());
    }

    public static void main(String[] args) {

        // ----- Dependency on PluginsService ---
        //final Settings settings = pluginsService.updatedSettings();

        // final Setting<Boolean> NODE_DATA_SETTING = Setting.boolSetting(
        //         "node.data",
        //         true,
        //         Setting.Property.Deprecated,
        //         Setting.Property.NodeScope
        // );

        // final Setting<Boolean> NODE_MASTER_SETTING = Setting.boolSetting(
        //         "node.master",
        //         true,
        //         Setting.Property.Deprecated,
        //         Setting.Property.NodeScope
        // );

        // final Setting<Boolean> NODE_INGEST_SETTING = Setting.boolSetting(
        //         "node.ingest",
        //         true,
        //         Setting.Property.Deprecated,
        //         Setting.Property.NodeScope
        // );

        // final Setting<Boolean> NODE_REMOTE_CLUSTER_CLIENT = Setting.boolSetting(
        //         "node.remote_cluster_client",
        //         RemoteClusterService.ENABLE_REMOTE_CLUSTERS,
        //         Setting.Property.Deprecated,
        //         Setting.Property.NodeScope
        // );

//        final List<Setting<?>> additionalSettings = new ArrayList<>();
//        //register the node.data, node.ingest, node.master, node.remote_cluster_client settings here so we can mark them private
//        additionalSettings.add(NODE_DATA_SETTING);
//        additionalSettings.add(NODE_INGEST_SETTING);
//        additionalSettings.add(NODE_MASTER_SETTING);
//        additionalSettings.add(NODE_REMOTE_CLUSTER_CLIENT);


         //----- Dependency on PluginsService ---
//        final Set<SettingUpgrader<?>> settingsUpgraders = pluginsService.filterPlugins(Plugin.class)
//                .stream()
//                .map(Plugin::getSettingUpgraders)
//                .flatMap(List::stream)
//                .collect(Collectors.toSet());

        // ----- Dependency on PluginsService ---
        //final List<String> additionalSettingsFilter = new ArrayList<>(pluginsService.getPluginSettingsFilter());

//        final SettingsModule settingsModule = new SettingsModule(
//                settings,
//                additionalSettings,
//                additionalSettingsFilter,
//                settingsUpgraders
//        );




        final RecoverySettings recoverySettings = new RecoverySettings(settings, getClusterSettings());

         //----- Dependency on PluginsService ---
        SearchModule searchModule = new SearchModule(settings, false, filterPlugins(SearchPlugin.class));

        // ----- Dependency on PluginsService ---
        NamedXContentRegistry xContentRegistry = new NamedXContentRegistry(
                Stream.of(
                        NetworkModule.getNamedXContents().stream(),
                        IndicesModule.getNamedXContents().stream(),
                        searchModule.getNamedXContents().stream(),
                        filterPlugins(Plugin.class).stream().flatMap(p -> p.getNamedXContent().stream()),
                        ClusterModule.getNamedXWriteables().stream()
                ).flatMap(Function.identity()).collect(toList())
        );

        final Injector injector;
        ModulesBuilder modules = new ModulesBuilder();
        injector = modules.createInjector();
        final ClusterService clusterService = injector.getInstance(ClusterService.class);

        final Environment initialEnvironment = (Environment) System.getenv();;
        Environment environment = new Environment(settings, initialEnvironment.configFile(), Node.NODE_LOCAL_STORAGE_SETTING.get(settings));

        S3Service service = new S3Service();
        IndependentPlugin Indplug = new IndependentPlugin(environment, clusterService, xContentRegistry, recoverySettings, settings, service);

    }

//    public static void main(String[] args ) {
//        S3RepositoryPlugin s3repo = new S3RepositoryPlugin(settings, service);
//        Map<String, Repository.Factory> newRepoTypes = s3repo.getRepositories(
//                env,
//                namedXContentRegistry,
//                clusterService,
//                recoverySettings
//        );
//    }
}
