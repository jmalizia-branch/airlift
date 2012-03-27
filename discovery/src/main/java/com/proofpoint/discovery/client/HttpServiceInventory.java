package com.proofpoint.discovery.client;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.proofpoint.http.client.HttpClient;
import com.proofpoint.http.client.RequestBuilder;
import com.proofpoint.json.JsonCodec;
import com.proofpoint.log.Logger;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.units.Duration;
import org.weakref.jmx.Managed;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.collect.Lists.newArrayList;
import static com.proofpoint.http.client.JsonResponseHandler.createJsonResponseHandler;
import static com.proofpoint.http.client.RequestBuilder.prepareGet;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public class HttpServiceInventory
{
    private static final Logger log = Logger.get(HttpServiceInventory.class);

    private final String environment;
    private final URI serviceInventoryUri;
    private final Duration updateRate;
    private final NodeInfo nodeInfo;
    private final JsonCodec<ServiceDescriptorsRepresentation> serviceDescriptorsCodec;
    private final HttpClient httpClient;

    private final AtomicReference<List<ServiceDescriptor>> serviceDescriptors = new AtomicReference<List<ServiceDescriptor>>(ImmutableList.<ServiceDescriptor>of());
    private final ScheduledExecutorService executorService = newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("service-inventory-%s").setDaemon(true).build());
    private final AtomicBoolean serverUp = new AtomicBoolean(true);
    private ScheduledFuture<?> scheduledFuture;

    @Inject
    public HttpServiceInventory(ServiceInventoryConfig config,
            NodeInfo nodeInfo,
            JsonCodec<ServiceDescriptorsRepresentation> serviceDescriptorsCodec,
            @ForDiscoveryClient HttpClient httpClient)
    {
        Preconditions.checkNotNull(config, "config is null");
        Preconditions.checkNotNull(nodeInfo, "nodeInfo is null");
        Preconditions.checkNotNull(serviceDescriptorsCodec, "serviceDescriptorsCodec is null");
        Preconditions.checkNotNull(httpClient, "httpClient is null");

        this.nodeInfo = nodeInfo;
        this.environment = nodeInfo.getEnvironment();
        this.serviceInventoryUri = config.getServiceInventoryUri();
        updateRate = config.getUpdateRate();
        this.serviceDescriptorsCodec = serviceDescriptorsCodec;
        this.httpClient = httpClient;

        if (serviceInventoryUri != null) {
            String scheme = serviceInventoryUri.getScheme().toLowerCase();
            Preconditions.checkArgument(scheme.equals("http") || scheme.equals("https"), "Service inventory uri must have a http or https scheme");

            try {
                updateServiceInventory();
            }
            catch (Exception ignored) {
            }
        }
    }

    @PostConstruct
    public synchronized void start()
    {
        if (serviceInventoryUri == null || scheduledFuture != null) {
            return;
        }
        scheduledFuture = executorService.scheduleAtFixedRate(new Runnable()
        {
            @Override
            public void run()
            {
                try {
                    updateServiceInventory();
                }
                catch (Throwable e) {
                    log.error(e, "Unexpected exception from service inventory update");
                }
            }
        }, 0, (long) updateRate.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PostConstruct
    public synchronized void stop()
    {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFuture = null;
        }
    }

    public Iterable<ServiceDescriptor> getServiceDescriptors()
    {
        return serviceDescriptors.get();
    }

    public Iterable<ServiceDescriptor> getServiceDescriptors(final String type)
    {
        return Iterables.filter(getServiceDescriptors(), new Predicate<ServiceDescriptor>()
        {
            @Override
            public boolean apply(ServiceDescriptor serviceDescriptor)
            {
                return serviceDescriptor.getType().equals(type);
            }
        });
    }

    public Iterable<ServiceDescriptor> getServiceDescriptors(final String type, final String pool)
    {
        return Iterables.filter(getServiceDescriptors(), new Predicate<ServiceDescriptor>()
        {
            @Override
            public boolean apply(ServiceDescriptor serviceDescriptor)
            {
                return serviceDescriptor.getType().equals(type) &&
                        serviceDescriptor.getPool().equals(pool);
            }
        });
    }

    @Managed
    public void updateServiceInventory()
    {
        RequestBuilder requestBuilder = prepareGet()
                .setUri(serviceInventoryUri)
                .setHeader("User-Agent", nodeInfo.getNodeId());
        ServiceDescriptorsRepresentation serviceDescriptorsRepresentation = httpClient.execute(requestBuilder.build(), createJsonResponseHandler(serviceDescriptorsCodec));

        if (!environment.equals(serviceDescriptorsRepresentation.getEnvironment())) {
            logServerError("Expected environment to be %s, but was %s", environment, serviceDescriptorsRepresentation.getEnvironment());
        }

        List<ServiceDescriptor> descriptors = newArrayList(serviceDescriptorsRepresentation.getServiceDescriptors());
        Collections.shuffle(descriptors);
        serviceDescriptors.set(ImmutableList.copyOf(descriptors));

        if (serverUp.compareAndSet(false, true)) {
            log.info("ServiceInventory connect succeeded");
        }
    }

    private void logServerError(String message, Object... args)
    {
        if (serverUp.compareAndSet(true, false)) {
            log.error(message, args);
        }
    }
}
