package org.testcontainers.utility;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.PruneType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Component that responsible for container removal and automatic cleanup of dead containers at JVM shutdown.
 */
@Slf4j
public final class ResourceReaper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceReaper.class);

    static final List<List<Map.Entry<String, String>>> DEATH_NOTE = new ArrayList<>(
        Arrays.asList(
            DockerClientFactory.DEFAULT_LABELS.entrySet().stream()
                .<Map.Entry<String, String>>map(it -> new SimpleEntry<>("label", it.getKey() + "=" + it.getValue()))
                .collect(Collectors.toList())
        )
    );

    private static ResourceReaper instance;
    private static AtomicBoolean ryukStarted = new AtomicBoolean(false);
    private final DockerClient dockerClient = DockerClientFactory.lazyClient();
    private Map<String, String> registeredContainers = new ConcurrentHashMap<>();
    private Set<String> registeredNetworks = Sets.newConcurrentHashSet();
    private Set<String> registeredImages = Sets.newConcurrentHashSet();
    private AtomicBoolean hookIsSet = new AtomicBoolean(false);


    /**
     *
     * @deprecated internal API
     */
    @Deprecated
    public static String start(String hostIpAddress, DockerClient client) {
        return start(client);
    }

    /**
     *
     * @deprecated internal API
     */
    @Deprecated
    public static String start(DockerClient client) {
        RyukResourceReaper ryuk = new RyukResourceReaper(client);
        String containerId = ryuk.getContainerId();
        ryukStarted.set(true);
        return containerId;
    }

    public synchronized static ResourceReaper instance() {
        if (instance == null) {
            instance = new ResourceReaper();
        }

        return instance;
    }

    /**
     * Perform a cleanup.
     */
    public synchronized void performCleanup() {
        registeredContainers.forEach(this::removeContainer);
        registeredNetworks.forEach(this::removeNetwork);
        registeredImages.forEach(this::removeImage);
    }

    /**
     * Register a filter to be cleaned up.
     *
     * @param filter the filter
     * @deprecated only label filter is supported by the prune API, use {@link #registerLabelsFilterForCleanup(Map)}
     */
    @Deprecated
    public void registerFilterForCleanup(List<Map.Entry<String, String>> filter) {
        synchronized (DEATH_NOTE) {
            DEATH_NOTE.add(filter);
            DEATH_NOTE.notifyAll();
        }
    }

    /**
     * Register a label to be cleaned up.
     *
     * @param labels the filter
     */
    public void registerLabelsFilterForCleanup(Map<String, String> labels) {
        registerFilterForCleanup(
            labels.entrySet().stream()
                .map(it -> new SimpleEntry<>("label", it.getKey() + "=" + it.getValue()))
                .collect(Collectors.toList())
        );
    }

    /**
     * Register a container to be cleaned up, either on explicit call to stopAndRemoveContainer, or at JVM shutdown.
     *
     * @param containerId the ID of the container
     * @param imageName   the image name of the container (used for logging)
     */
    public void registerContainerForCleanup(String containerId, String imageName) {
        setHook();
        registeredContainers.put(containerId, imageName);
    }

    /**
     * Stop a potentially running container and remove it, including associated volumes.
     *
     * @param containerId the ID of the container
     */
    public void stopAndRemoveContainer(String containerId) {
        removeContainer(containerId, registeredContainers.get(containerId));

        registeredContainers.remove(containerId);
    }

    /**
     * Stop a potentially running container and remove it, including associated volumes.
     *
     * @param containerId the ID of the container
     * @param imageName   the image name of the container (used for logging)
     */
    public void stopAndRemoveContainer(String containerId, String imageName) {
        removeContainer(containerId, imageName);

        registeredContainers.remove(containerId);
    }

    private void removeContainer(String containerId, String imageName) {
        boolean running;
        try {
            InspectContainerResponse containerInfo = dockerClient.inspectContainerCmd(containerId).exec();
            running = containerInfo.getState() != null && Boolean.TRUE.equals(containerInfo.getState().getRunning());
        } catch (NotFoundException e) {
            LOGGER.trace("Was going to stop container but it apparently no longer exists: {}", containerId);
            return;
        } catch (Exception e) {
            LOGGER.trace("Error encountered when checking container for shutdown (ID: {}) - it may not have been stopped, or may already be stopped. Root cause: {}",
                containerId,
                Throwables.getRootCause(e).getMessage());
            return;
        }

        if (running) {
            try {
                LOGGER.trace("Stopping container: {}", containerId);
                dockerClient.killContainerCmd(containerId).exec();
                LOGGER.trace("Stopped container: {}", imageName);
            } catch (Exception e) {
                LOGGER.trace("Error encountered shutting down container (ID: {}) - it may not have been stopped, or may already be stopped. Root cause: {}",
                    containerId,
                    Throwables.getRootCause(e).getMessage());
            }
        }

        try {
            dockerClient.inspectContainerCmd(containerId).exec();
        } catch (Exception e) {
            LOGGER.trace("Was going to remove container but it apparently no longer exists: {}", containerId);
            return;
        }

        try {
            LOGGER.trace("Removing container: {}", containerId);
            dockerClient.removeContainerCmd(containerId).withRemoveVolumes(true).withForce(true).exec();
            LOGGER.debug("Removed container and associated volume(s): {}", imageName);
        } catch (Exception e) {
            LOGGER.trace("Error encountered shutting down container (ID: {}) - it may not have been stopped, or may already be stopped. Root cause: {}",
                containerId,
                Throwables.getRootCause(e).getMessage());
        }
    }

    /**
     * Register a network to be cleaned up at JVM shutdown.
     *
     * @param id   the ID of the network
     */
    public void registerNetworkIdForCleanup(String id) {
        setHook();
        registeredNetworks.add(id);
    }

    /**
     * @param networkName   the name of the network
     * @deprecated see {@link ResourceReaper#registerNetworkIdForCleanup(String)}
     */
    @Deprecated
    public void registerNetworkForCleanup(String networkName) {
        try {
            // Try to find the network by name, so that we can register its ID for later deletion
            dockerClient.listNetworksCmd()
                    .withNameFilter(networkName)
                    .exec()
            .forEach(network -> registerNetworkIdForCleanup(network.getId()));
        } catch (Exception e) {
            LOGGER.trace("Error encountered when looking up network (name: {})", networkName);
        }
    }

    /**
     * Removes a network by ID.
     * @param id
     */
    public void removeNetworkById(String id) {
      removeNetwork(id);
    }

    /**
     * Removes a network by ID.
     * @param identifier
     * @deprecated see {@link ResourceReaper#removeNetworkById(String)}
     */
    @Deprecated
    public void removeNetworks(String identifier) {
        removeNetworkById(identifier);
    }

    private void removeNetwork(String id) {
        try {
            List<Network> networks;
            try {
                // Try to find the network if it still exists
                // Listing by ID first prevents docker-java logging an error if we just go blindly into removeNetworkCmd
                networks = dockerClient.listNetworksCmd().withIdFilter(id).exec();
            } catch (Exception e) {
                LOGGER.trace("Error encountered when looking up network for removal (name: {}) - it may not have been removed", id);
                return;
            }

            // at this point networks should contain either 0 or 1 entries, depending on whether the network exists
            // using a for loop we essentially treat the network like an optional, only applying the removal if it exists
            for (Network network : networks) {
                try {
                    dockerClient.removeNetworkCmd(network.getId()).exec();
                    registeredNetworks.remove(network.getId());
                    LOGGER.debug("Removed network: {}", id);
                } catch (Exception e) {
                    LOGGER.trace("Error encountered removing network (name: {}) - it may not have been removed", network.getName());
                }
            }
        } finally {
            registeredNetworks.remove(id);
        }
    }

    public void unregisterNetwork(String identifier) {
        registeredNetworks.remove(identifier);
    }

    public void unregisterContainer(String identifier) {
        registeredContainers.remove(identifier);
    }

    public void registerImageForCleanup(String dockerImageName) {
        setHook();
        registeredImages.add(dockerImageName);
    }

    private void removeImage(String dockerImageName) {
        LOGGER.trace("Removing image tagged {}", dockerImageName);
        try {
            dockerClient.removeImageCmd(dockerImageName).withForce(true).exec();
        } catch (Throwable e) {
            LOGGER.warn("Unable to delete image " + dockerImageName, e);
        }
    }

    private void prune(PruneType pruneType, List<Map.Entry<String, String>> filters) {
        String[] labels = filters.stream()
            .filter(it -> "label".equals(it.getKey()))
            .map(Map.Entry::getValue)
            .toArray(String[]::new);
        switch (pruneType) {
            // Docker only prunes stopped containers, so we have to do it manually
            case CONTAINERS:
                List<Container> containers = dockerClient.listContainersCmd()
                    .withFilter("label", Arrays.asList(labels))
                    .withShowAll(true)
                    .exec();

                containers.parallelStream().forEach(container -> {
                    removeContainer(container.getId(), container.getImage());
                });
                break;
            default:
                dockerClient.pruneCmd(pruneType).withLabelFilter(labels).exec();
                break;
        }
    }

    /**
     * @deprecated internal API, not intended for public usage
     */
    @Deprecated
    public void setHook() {
        if (hookIsSet.compareAndSet(false, true)) {
            // If the JVM stops without containers being stopped, try and stop the container.
            Runtime.getRuntime().addShutdownHook(
                new Thread(DockerClientFactory.TESTCONTAINERS_THREAD_GROUP,
                    () -> {
                        performCleanup();

                        if (!ryukStarted.get()) {
                            synchronized (DEATH_NOTE) {
                                DEATH_NOTE.forEach(filters -> prune(PruneType.CONTAINERS, filters));
                                DEATH_NOTE.forEach(filters -> prune(PruneType.NETWORKS, filters));
                                DEATH_NOTE.forEach(filters -> prune(PruneType.VOLUMES, filters));
                                DEATH_NOTE.forEach(filters -> prune(PruneType.IMAGES, filters));
                            }
                        }
                    }
                )
            );
        }
    }

    static class FilterRegistry {

        @VisibleForTesting
        static final String ACKNOWLEDGMENT = "ACK";

        private final BufferedReader in;
        private final OutputStream out;

        FilterRegistry(InputStream ryukInputStream, OutputStream ryukOutputStream) {
            this.in = new BufferedReader(new InputStreamReader(ryukInputStream));
            this.out = ryukOutputStream;
        }

        /**
         * Registers the given filters with Ryuk
         *
         * @param filters the filter to register
         * @return true if the filters have been registered successfuly, false otherwise
         * @throws IOException if communication with Ryuk fails
         */
        protected boolean register(List<Map.Entry<String, String>> filters) throws IOException {
            String query = filters.stream()
                .map(it -> {
                    try {
                        return URLEncoder.encode(it.getKey(), "UTF-8") + "=" + URLEncoder.encode(it.getValue(), "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.joining("&"));

            log.debug("Sending '{}' to Ryuk", query);
            out.write(query.getBytes());
            out.write('\n');
            out.flush();

            return waitForAcknowledgment(in);
        }

        private static boolean waitForAcknowledgment(BufferedReader in) throws IOException {
            String line = in.readLine();
            while (line != null && !ACKNOWLEDGMENT.equalsIgnoreCase(line)) {
                line = in.readLine();
            }
            return ACKNOWLEDGMENT.equalsIgnoreCase(line);
        }

    }
}
