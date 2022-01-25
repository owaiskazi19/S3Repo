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


import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterModule;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Strings;
import org.opensearch.common.breaker.CircuitBreaker;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.component.AbstractLifecycleComponent;
import org.opensearch.common.component.Lifecycle;
import org.opensearch.common.inject.Injector;
import org.opensearch.common.inject.ModulesBuilder;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkAddress;
import org.opensearch.common.network.NetworkModule;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.*;
import org.opensearch.common.transport.BoundTransportAddress;
import org.opensearch.common.transport.PortsRange;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.unit.ByteSizeValue;
import org.opensearch.common.util.BigArrays;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.indices.IndicesModule;
import org.opensearch.indices.breaker.BreakerSettings;
import org.opensearch.indices.breaker.CircuitBreakerService;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.node.Node;
import org.opensearch.plugins.*;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.fs.FsRepository;
import org.opensearch.rest.RestController;
import org.opensearch.search.SearchModule;
import org.opensearch.tasks.Task;
import org.opensearch.tasks.TaskManager;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.opensearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;
import static org.opensearch.node.Node.createCircuitBreakerService;

public abstract class IndependentPlugin extends  AbstractLifecycleComponent{
    private  static NamedXContentRegistry namedXContentRegistry;
    private  static RecoverySettings recoverySettings;
    private  static ClusterService clusterService;
    private static Settings settings;
    private static S3Service service;
    private static Environment env;
    private static CircuitBreakerService usageService;
    private static RestController restController;
    private static NodeEnvironment nodeEnvironment;
    private static LocalNodeFactory localNodeFactory;
    private static Injector injector;
    private static final AtomicInteger portGenerator = new AtomicInteger();
    protected static TaskManager taskManager;
    private static DiscoveryNode localNode;
    private static Lifecycle lifecycle;
    protected static Set<ProfileSettings> profileSettings;
    private static final Logger logger = LogManager.getLogger(IndependentPlugin.class);
    private static final ReadWriteLock closeLock = new ReentrantReadWriteLock();
    private static final Map<String, List<TcpServerChannel>> serverChannels = newConcurrentMap();
    private static volatile BoundTransportAddress boundAddress;
    private static final ConcurrentMap<String, BoundTransportAddress> profileBoundAddresses = newConcurrentMap();

    public static final class ProfileSettings {
        public final String profileName;
        public final boolean tcpNoDelay;
        public final boolean tcpKeepAlive;
        public final int tcpKeepIdle;
        public final int tcpKeepInterval;
        public final int tcpKeepCount;
        public final boolean reuseAddress;
        public final ByteSizeValue sendBufferSize;
        public final ByteSizeValue receiveBufferSize;
        public final List<String> bindHosts;
        public final List<String> publishHosts;
        public final String portOrRange;
        public final int publishPort;
        public final boolean isDefaultProfile;


        public ProfileSettings(Settings settings, String profileName) {
            this.profileName = profileName;
            isDefaultProfile = TransportSettings.DEFAULT_PROFILE.equals(profileName);
            tcpKeepAlive = TransportSettings.TCP_KEEP_ALIVE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpKeepIdle = TransportSettings.TCP_KEEP_IDLE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpKeepInterval = TransportSettings.TCP_KEEP_INTERVAL_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpKeepCount = TransportSettings.TCP_KEEP_COUNT_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpNoDelay = TransportSettings.TCP_NO_DELAY_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            reuseAddress = TransportSettings.TCP_REUSE_ADDRESS_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            sendBufferSize = TransportSettings.TCP_SEND_BUFFER_SIZE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            receiveBufferSize = TransportSettings.TCP_RECEIVE_BUFFER_SIZE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            List<String> profileBindHosts = TransportSettings.BIND_HOST_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            bindHosts = (profileBindHosts.isEmpty() ? NetworkService.GLOBAL_NETWORK_BIND_HOST_SETTING.get(settings) : profileBindHosts);
            publishHosts = TransportSettings.PUBLISH_HOST_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            Setting<String> concretePort = TransportSettings.PORT_PROFILE.getConcreteSettingForNamespace(profileName);
            if (concretePort.exists(settings) == false && isDefaultProfile == false) {
                throw new IllegalStateException("profile [" + profileName + "] has no port configured");
            }
            portOrRange = TransportSettings.PORT_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            publishPort = isDefaultProfile
                    ? TransportSettings.PUBLISH_PORT.get(settings)
                    : TransportSettings.PUBLISH_PORT_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
        }
    }


    public IndependentPlugin(
            Environment env,
            ClusterService clusterService,
            NamedXContentRegistry namedXContentRegistry,
            RecoverySettings recoverySettings,
            Settings settings,
            S3Service service,
            Set<ProfileSettings> profileSettings) {
        this.profileSettings =  getProfileSettings(settings);;
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


    public static Set<ProfileSettings> getProfileSettings(Settings settings) {
        HashSet<ProfileSettings> profiles = new HashSet<>();
        boolean isDefaultSet = false;
        for (String profile : settings.getGroups("transport.profiles.", true).keySet()) {
            profiles.add(new ProfileSettings(settings, profile));
            if (TransportSettings.DEFAULT_PROFILE.equals(profile)) {
                isDefaultSet = true;
            }
        }
        if (isDefaultSet == false) {
            profiles.add(new ProfileSettings(settings, TransportSettings.DEFAULT_PROFILE));
        }
        return Collections.unmodifiableSet(profiles);
    }

    final static NetworkService networkService = new NetworkService(
            getCustomNameResolvers(filterPlugins(DiscoveryPlugin.class))
    );


    protected static TcpServerChannel bind(String name, InetSocketAddress address) throws IOException {
        return null;
    }

    private static InetSocketAddress bindToPort(final String name, final InetAddress hostAddress, String port) {
        PortsRange portsRange = new PortsRange(port);
        final AtomicReference<Exception> lastException = new AtomicReference<>();
        final AtomicReference<InetSocketAddress> boundSocket = new AtomicReference<>();
        closeLock.writeLock().lock();
        try {
            // No need for locking here since Lifecycle objects can't move from STARTED to INITIALIZED
            if (lifecycle.initialized() == false && lifecycle.started() == false) {
                throw new IllegalStateException("transport has been stopped");
            }
            boolean success = portsRange.iterate(portNumber -> {
                try {
                    TcpServerChannel channel = bind(name, new InetSocketAddress(hostAddress, portNumber));
                    serverChannels.computeIfAbsent(name, k -> new ArrayList<>()).add(channel);
                    boundSocket.set(channel.getLocalAddress());
                } catch (Exception e) {
                    lastException.set(e);
                    return false;
                }
                return true;
            });
            if (!success) {
                throw new BindTransportException(
                        "Failed to bind to " + NetworkAddress.format(hostAddress, portsRange),
                        lastException.get()
                );
            }
        } finally {
            closeLock.writeLock().unlock();
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Bound profile [{}] to address {{}}", name, NetworkAddress.format(boundSocket.get()));
        }

        return boundSocket.get();
    }

    static int resolvePublishPort(ProfileSettings profileSettings, List<InetSocketAddress> boundAddresses, InetAddress publishInetAddress) {
        int publishPort = profileSettings.publishPort;

        // if port not explicitly provided, search for port of address in boundAddresses that matches publishInetAddress
        if (publishPort < 0) {
            for (InetSocketAddress boundAddress : boundAddresses) {
                InetAddress boundInetAddress = boundAddress.getAddress();
                if (boundInetAddress.isAnyLocalAddress() || boundInetAddress.equals(publishInetAddress)) {
                    publishPort = boundAddress.getPort();
                    break;
                }
            }
        }

        // if no matching boundAddress found, check if there is a unique port for all bound addresses
        if (publishPort < 0) {
            final IntSet ports = new IntHashSet();
            for (InetSocketAddress boundAddress : boundAddresses) {
                ports.add(boundAddress.getPort());
            }
            if (ports.size() == 1) {
                publishPort = ports.iterator().next().value;
            }
        }

        if (publishPort < 0) {
            String profileExplanation = profileSettings.isDefaultProfile ? "" : " for profile " + profileSettings.profileName;
            throw new BindTransportException(
                    "Failed to auto-resolve publish port"
                            + profileExplanation
                            + ", multiple bound addresses "
                            + boundAddresses
                            + " with distinct ports and none of them matched the publish address ("
                            + publishInetAddress
                            + "). "
                            + "Please specify a unique port by setting "
                            + TransportSettings.PORT.getKey()
                            + " or "
                            + TransportSettings.PUBLISH_PORT.getKey()
            );
        }
        return publishPort;
    }

    private static BoundTransportAddress createBoundTransportAddress(ProfileSettings profileSettings, List<InetSocketAddress> boundAddresses) {
        String[] boundAddressesHostStrings = new String[boundAddresses.size()];
        TransportAddress[] transportBoundAddresses = new TransportAddress[boundAddresses.size()];
        for (int i = 0; i < boundAddresses.size(); i++) {
            InetSocketAddress boundAddress = boundAddresses.get(i);
            boundAddressesHostStrings[i] = boundAddress.getHostString();
            transportBoundAddresses[i] = new TransportAddress(boundAddress);
        }

        List<String> publishHosts = profileSettings.publishHosts;
        if (profileSettings.isDefaultProfile == false && publishHosts.isEmpty()) {
            publishHosts = Arrays.asList(boundAddressesHostStrings);
        }
        if (publishHosts.isEmpty()) {
            publishHosts = NetworkService.GLOBAL_NETWORK_PUBLISH_HOST_SETTING.get(settings);
        }

        final InetAddress publishInetAddress;
        try {
            publishInetAddress = networkService.resolvePublishHostAddresses(publishHosts.toArray(Strings.EMPTY_ARRAY));
        } catch (Exception e) {
            throw new BindTransportException("Failed to resolve publish address", e);
        }

        final int publishPort = resolvePublishPort(profileSettings, boundAddresses, publishInetAddress);
        final TransportAddress publishAddress = new TransportAddress(new InetSocketAddress(publishInetAddress, publishPort));
        return new BoundTransportAddress(transportBoundAddresses, publishAddress);
    }

    protected static void bindServer(ProfileSettings profileSettings) {
        // Bind and start to accept incoming connections.
        InetAddress[] hostAddresses;
        List<String> profileBindHosts = profileSettings.bindHosts;
        try {
            hostAddresses = networkService.resolveBindHostAddresses(profileBindHosts.toArray(Strings.EMPTY_ARRAY));
        } catch (IOException e) {
            throw new BindTransportException("Failed to resolve host " + profileBindHosts, e);
        }
        if (logger.isDebugEnabled()) {
            String[] addresses = new String[hostAddresses.length];
            for (int i = 0; i < hostAddresses.length; i++) {
                addresses[i] = NetworkAddress.format(hostAddresses[i]);
            }
            logger.debug("binding server bootstrap to: {}", (Object) addresses);
        }

        assert hostAddresses.length > 0;

        List<InetSocketAddress> boundAddresses = new ArrayList<>();
        for (InetAddress hostAddress : hostAddresses) {
            boundAddresses.add(bindToPort(profileSettings.profileName, hostAddress, profileSettings.portOrRange));
        }

        final BoundTransportAddress boundTransportAddress = createBoundTransportAddress(profileSettings, boundAddresses);

        if (profileSettings.isDefaultProfile) {
            boundAddress = boundTransportAddress;
        } else {
            profileBoundAddresses.put(profileSettings.profileName, boundTransportAddress);
        }
    }

    public static ClusterSettings getClusterSettings() {
        final Set<SettingUpgrader<?>> clusterSettingUpgraders = new HashSet<>();
        return new ClusterSettings(settings, new HashSet<>(), clusterSettingUpgraders);
    }

    public static TransportAddress buildNewFakeTransportAddress() {
        return new TransportAddress(TransportAddress.META_ADDRESS, portGenerator.incrementAndGet());
    }





    public static <T> List<T> filterPlugins(Class<T> type) {
        final List<Tuple<PluginInfo, Plugin>> plugins = null;
        return plugins.stream().filter(x -> type.isAssignableFrom(x.v2().getClass())).map(p -> ((T) p.v2())).collect(Collectors.toList());
    }

    public static List<String> getPluginSettingsFilter() {
        final List<Tuple<PluginInfo, Plugin>> plugins = null;
        return plugins.stream().flatMap(p -> p.v2().getSettingsFilter().stream()).collect(Collectors.toList());
    }

    public static List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        final List<Tuple<PluginInfo, Plugin>> plugins = null;
        final ArrayList<ExecutorBuilder<?>> builders = new ArrayList<>();
        for (final Tuple<PluginInfo, Plugin> plugin : plugins) {
            builders.addAll(plugin.v2().getExecutorBuilders(settings));
        }
        return builders;
    }

    public static Settings settings() {
        return env.settings();
    }

    private static List<NetworkService.CustomNameResolver> getCustomNameResolvers(List<DiscoveryPlugin> discoveryPlugins) {
        List<NetworkService.CustomNameResolver> customNameResolvers = new ArrayList<>();
        for (DiscoveryPlugin discoveryPlugin : discoveryPlugins) {
            NetworkService.CustomNameResolver customNameResolver = discoveryPlugin.getCustomNameResolver(settings());
            if (customNameResolver != null) {
                customNameResolvers.add(customNameResolver);
            }
        }
        return customNameResolvers;
    }

    static BigArrays createBigArrays(PageCacheRecycler pageCacheRecycler, CircuitBreakerService circuitBreakerService) {
        return new BigArrays(pageCacheRecycler, circuitBreakerService, CircuitBreaker.REQUEST);
    }

    static PageCacheRecycler createPageCacheRecycler(Settings settings) {
        return new PageCacheRecycler(settings);
    }

    public static RestController getRestController() {
        return restController;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }



    public static void main(String[] args) {
        //System.out.println("Hello from main");
        logger.info("From main");

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

        final List<Setting<?>> additionalSettings = new ArrayList<>();
        //register the node.data, node.ingest, node.master, node.remote_cluster_client settings here so we can mark them private
//        additionalSettings.add(NODE_DATA_SETTING);
//        additionalSettings.add(NODE_INGEST_SETTING);
//        additionalSettings.add(NODE_MASTER_SETTING);
//        additionalSettings.add(NODE_REMOTE_CLUSTER_CLIENT);


        //----- Dependency on PluginsService ---
        final Set<SettingUpgrader<?>> settingsUpgraders = filterPlugins(Plugin.class)
                .stream()
                .map(Plugin::getSettingUpgraders)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        // ----- Dependency on PluginsService ---
        final List<String> additionalSettingsFilter = new ArrayList<>(getPluginSettingsFilter());

        final SettingsModule settingsModule = new SettingsModule(
                settings,
                additionalSettings,
                additionalSettingsFilter,
                settingsUpgraders
        );




        final RecoverySettings recoverySettings = new RecoverySettings(settings, getClusterSettings());

        //----- Dependency on PluginsService ---
        SearchModule searchModule = new SearchModule(settings, filterPlugins(SearchPlugin.class));

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
//        IndependentPlugin Indplug = new IndependentPlugin(environment, clusterService, xContentRegistry, recoverySettings, settings, service, profileSettings) {
//            @Override
//            protected void doStart() {
//
//            }
//
//            @Override
//            protected void doStop() {
//
//            }
//
//            @Override
//            protected void doClose() throws IOException {
//
//            }
//        };


        //Transport Service

        //----- Dependency on PluginsService ---
        final List<ExecutorBuilder<?>> executorBuilders = getExecutorBuilders(settings);
        final ThreadPool threadPool = new ThreadPool(settings, executorBuilders.toArray(new ExecutorBuilder[0]));
        List<BreakerSettings> pluginCircuitBreakers = filterPlugins(CircuitBreakerPlugin.class)
                .stream()
                .map(plugin -> plugin.getCircuitBreaker(settings))
                .collect(toList());

        final CircuitBreakerService circuitBreakerService = createCircuitBreakerService(
                settingsModule.getSettings(),
                pluginCircuitBreakers,
                settingsModule.getClusterSettings()
        );

        PageCacheRecycler pageCacheRecycler = createPageCacheRecycler(settings);
        BigArrays bigArrays = createBigArrays(pageCacheRecycler, circuitBreakerService);
        IndicesModule indicesModule = new IndicesModule(filterPlugins(MapperPlugin.class));

        List<NamedWriteableRegistry.Entry> namedWriteables = Stream.of(
                NetworkModule.getNamedWriteables().stream(),
                indicesModule.getNamedWriteables().stream(),
                searchModule.getNamedWriteables().stream(),
                filterPlugins(Plugin.class).stream().flatMap(p -> p.getNamedWriteables().stream()),
                ClusterModule.getNamedWriteables().stream()
        ).flatMap(Function.identity()).collect(toList());

        final NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(namedWriteables);

        final NetworkService networkService = new NetworkService(
                getCustomNameResolvers(filterPlugins(DiscoveryPlugin.class))
        );



        final RestController restController = getRestController();


        final NetworkModule networkModule = new NetworkModule(
                settings,
                filterPlugins(NetworkPlugin.class),
                threadPool,
                bigArrays,
                pageCacheRecycler,
                circuitBreakerService,
                namedWriteableRegistry,
                xContentRegistry,
                networkService,
                restController,
                clusterService.getClusterSettings()
        );

        final Transport transport = networkModule.getTransportSupplier().get();
        Set<String> taskHeaders = Stream.concat(
                filterPlugins(ActionPlugin.class).stream().flatMap(p -> p.getTaskHeaders().stream()),
                Stream.of(Task.X_OPAQUE_ID)
        ).collect(Collectors.toSet());

        localNodeFactory = new LocalNodeFactory(settings, nodeEnvironment.nodeId());
        
        final TransportService transportService = newTransportService(
                settings,
                transport,
                threadPool,
                networkModule.getTransportInterceptor(),
                localNodeFactory,
                settingsModule.getClusterSettings(),
                taskHeaders
        );
        transportService.start();
        transportService.acceptIncomingRequests();


        for (ProfileSettings profileSettings : profileSettings) {
            bindServer(profileSettings);
        }

       // doStart();



    }

    private static TransportService newTransportService(Settings settings, Transport transport, ThreadPool threadPool, TransportInterceptor transportInterceptor, LocalNodeFactory localNodeFactory, ClusterSettings clusterSettings, Set<String> taskHeaders) {
        return new TransportService(settings, transport, threadPool, transportInterceptor, localNodeFactory, clusterSettings, taskHeaders);
    }




    // Start the transport service now so the publish address will be added to the local disco node in ClusterService
//    TransportService transportService = injector.getInstance(TransportService.class);
//    transportService.start();


//    public static void main(String[] args ) {
//        S3RepositoryPlugin s3repo = new S3RepositoryPlugin(settings, service);
//        Map<String, Repository.Factory> newRepoTypes = s3repo.getRepositories(
//                env,
//                namedXContentRegistry,
//                clusterService,
//                recoverySettings
//        );
//    }

    private static class LocalNodeFactory implements Function<BoundTransportAddress, DiscoveryNode> {
        private final SetOnce<DiscoveryNode> localNode = new SetOnce<>();
        private final String persistentNodeId;
        private final Settings settings;

        private LocalNodeFactory(Settings settings, String persistentNodeId) {
            this.persistentNodeId = persistentNodeId;
            this.settings = settings;
        }

        @Override
        public DiscoveryNode apply(BoundTransportAddress boundTransportAddress) {
            localNode.set(DiscoveryNode.createLocal(settings, boundTransportAddress.publishAddress(), persistentNodeId));
            return localNode.get();
        }

        DiscoveryNode getNode() {
            assert localNode.get() != null;
            return localNode.get();
        }
    }
}
