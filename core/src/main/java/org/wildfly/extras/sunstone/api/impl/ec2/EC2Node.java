package org.wildfly.extras.sunstone.api.impl.ec2;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.ec2.compute.options.EC2TemplateOptions;
import org.jclouds.ec2.domain.InstanceState;
import org.jclouds.ec2.domain.RunningInstance;
import org.slf4j.Logger;
import org.wildfly.extras.sunstone.api.impl.AbstractJCloudsNode;
import org.wildfly.extras.sunstone.api.impl.SunstoneCoreLogger;
import org.wildfly.extras.sunstone.api.impl.Config;
import org.wildfly.extras.sunstone.api.impl.NodeConfigData;
import org.wildfly.extras.sunstone.api.impl.ObjectProperties;
import org.wildfly.extras.sunstone.api.impl.ResolvedImage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * EC2 implementation of {@link org.wildfly.extras.sunstone.api.Node}. This implementation uses JClouds internally.
 *
 */
public class EC2Node extends AbstractJCloudsNode<EC2CloudProvider> {
    private static final Logger LOGGER = SunstoneCoreLogger.DEFAULT;

    private static final int WAIT_FOR_NODE_STOP_TIMEOUT = 240_000; // milliseconds

    private static final NodeConfigData EC2_NODE_CONFIG_DATA = new NodeConfigData(
            Config.Node.EC2.WAIT_FOR_PORTS,
            Config.Node.EC2.WAIT_FOR_PORTS_TIMEOUT_SEC,
            600
    );

    private final String imageName;
    private final NodeMetadata initialNodeMetadata;

    public EC2Node(EC2CloudProvider ec2CloudProvider, String name, Map<String, String> configOverrides) {
        super(ec2CloudProvider, name, configOverrides, EC2_NODE_CONFIG_DATA);

        EC2TemplateOptions templateOptions = buildTemplateOptions(objectProperties);
        final TemplateBuilder templateBuilder = computeService.templateBuilder();

        final String region = getRegion();
        final String instanceType = getInstanceType();

        if (Strings.isNullOrEmpty(region)) {
            throw new IllegalArgumentException("No region was provided for node " + name);
        }
        if (Strings.isNullOrEmpty(instanceType)) {
            throw new IllegalArgumentException("No instance type was provided for node " + name);
        }

        ResolvedImage resolvedImage = ResolvedImage.fromNameAndId(
                objectProperties.getProperty(Config.Node.EC2.IMAGE),
                objectProperties.getProperty(Config.Node.EC2.IMAGE_ID),
                region,
                computeService
        );

        this.imageName = resolvedImage.humanReadableName;

        final Template template = templateBuilder
                .hardwareId(instanceType)
                .locationId(region)
                .imageId(resolvedImage.fullId)
                .options(templateOptions)
                .build();

        LOGGER.debug("Creating {} node from template: {}",
                cloudProvider.getCloudProviderType().getHumanReadableName(), template);
        try {
            this.initialNodeMetadata = createNode(template);
            String publicAddress = Iterables.getFirst(initialNodeMetadata.getPublicAddresses(), null);
            LOGGER.info("Started {} node '{}' from image {}, its public IP address is {}",
                    cloudProvider.getCloudProviderType().getHumanReadableName(), name, imageName, publicAddress);
            waitForStartPorts();
        } catch (RunNodesException e) {
            throw new RuntimeException("Unable to create " + cloudProvider.getCloudProviderType().getHumanReadableName()
                    + " node from template " + template, e);
        }
    }

    private static EC2TemplateOptions buildTemplateOptions(ObjectProperties objectProperties) {
        EC2TemplateOptions templateOptions = new EC2TemplateOptions();

        final int[] inboundPorts = Pattern.compile(",")
                .splitAsStream(objectProperties.getProperty(Config.Node.EC2.INBOUND_PORTS, "")).mapToInt(Integer::parseInt)
                .toArray();
        if (inboundPorts.length > 0) {
            templateOptions.inboundPorts(inboundPorts);
        }

        final String userName = objectProperties.getProperty(Config.Node.EC2.SSH_USER);
        if (!Strings.isNullOrEmpty(userName)) {
            templateOptions.overrideLoginUser(userName);
        } else {
            templateOptions.runAsRoot(true);
        }

        final Path sshPrivateKeyFile = objectProperties.getPropertyAsPath(Config.Node.EC2.SSH_PRIVATE_KEY_FILE, null);
        if (sshPrivateKeyFile != null) {
            try {
                String sshPrivateKey = new String(Files.readAllBytes(sshPrivateKeyFile), StandardCharsets.UTF_8);
                templateOptions.overrideLoginPrivateKey(sshPrivateKey);
            } catch (IOException e) {
                LOGGER.error("Unable to read EC2 SSH private key", e);
            }
        }

        final String keyPair = objectProperties.getProperty(Config.Node.EC2.KEY_PAIR);
        if (!Strings.isNullOrEmpty(keyPair)) {
            templateOptions.keyPair(keyPair);
        }

        final String securityGroups = objectProperties.getProperty(Config.Node.EC2.SECURITY_GROUPS);
        if (!Strings.isNullOrEmpty(securityGroups)) {
            templateOptions.securityGroups(securityGroups.split(","));
        }

        byte[] userData = null;
        final String userDataStr = objectProperties.getProperty(Config.Node.EC2.USER_DATA);
        final Path userDataFile = objectProperties.getPropertyAsPath(Config.Node.EC2.USER_DATA_FILE, null);
        if (!Strings.isNullOrEmpty(userDataStr)) {
            userData = userDataStr.getBytes(StandardCharsets.UTF_8);
        } else if (userDataFile != null) {
            if (Files.isReadable(userDataFile)) {
                try {
                    userData = Files.readAllBytes(userDataFile);
                } catch (IOException e) {
                    LOGGER.error("Unable to read EC2 User Data file", e);
                }
            } else {
                LOGGER.error("EC2 User Data file location is specified ({}), but it doesn't contain readable data.",
                        userDataFile);
            }
        }
        if (userData != null) {
            templateOptions.userData(userData);
        }

        return templateOptions;
    }

    @Override
    public NodeMetadata getInitialNodeMetadata() {
        return initialNodeMetadata;
    }

    @Override
    public NodeMetadata getFreshNodeMetadata() {
        return computeService.getNodeMetadata(initialNodeMetadata.getId());
    }

    @Override
    public String getImageName() {
        return imageName;
    }

    /**
     * Returns Amazon instance type as defined in object properties at the time of node creation.
     * Instance type is one of types provided by Amazon. It looks like "m1.large" (without quotes).
     *
     * @return Amazon instance type
     */
    public String getInstanceType() {
        return objectProperties.getProperty(Config.Node.EC2.INSTANCE_TYPE);
    }

    /**
     * Returns Amazon EC2 region as defined in object properties at the time of node creation.
     * It looks like "us-east-1". For full reference, see documentation for {@link org.jclouds.aws.domain.Region}.
     *
     * @return Amazon Region (location) for this node.
     */
    public String getRegion() {
        return cloudProvider.getObjectProperties().getProperty(Config.CloudProvider.EC2.REGION);
    }

    /**
     * Stops the instance. ("Stop" in the EC2 context - the instance can later be restarted)
     *
     * @see org.wildfly.extras.sunstone.api.Node#stop()
     */
    @Override
    public void stop() {
        LOGGER.info("Stopping {} node '{}'", cloudProvider.getCloudProviderType().getHumanReadableName(), getName());
        LOGGER.debug("Stopping instance: {}, instance state: {}", initialNodeMetadata.getId().split("/")[1],  getInstance().getInstanceState());
        doLifecycle(InstanceState.STOPPED, false);
        LOGGER.info("Stopped {} node '{}'", cloudProvider.getCloudProviderType().getHumanReadableName(), getName());
    }

    /**
     * Starts/restarts the instance.
     *
     * @see org.wildfly.extras.sunstone.api.Node#start()
     */
    @Override
    public void start() {
        LOGGER.info("Starting {} node '{}'", cloudProvider.getCloudProviderType().getHumanReadableName(), getName());
        LOGGER.debug("Starting instance: {}, instance state: {}", initialNodeMetadata.getId().split("/")[1],  getInstance().getInstanceState());
        doLifecycle(InstanceState.RUNNING, false); // force (second) parameter does not matter
        LOGGER.info("Started {} node '{}'", cloudProvider.getCloudProviderType().getHumanReadableName(), getName());
    }

    /**
     * Stops the instance. You can also destroy the instance, but that
     * is something different (the instance cannot be restarted then - it is entirely gone).
     *
     * @see org.wildfly.extras.sunstone.api.Node#kill()
     * @see org.wildfly.extras.sunstone.api.impl.ec2.EC2CloudProvider#destroyNode
     */
    @Override
    public void kill() {
        LOGGER.info("Killing {} node '{}'", cloudProvider.getCloudProviderType().getHumanReadableName(), getName());
        LOGGER.debug("Killing instance: {}, instance state: {}", initialNodeMetadata.getId().split("/")[1],  getInstance().getInstanceState());
        doLifecycle(InstanceState.STOPPED, true);
        LOGGER.info("Killed {} node '{}'", cloudProvider.getCloudProviderType().getHumanReadableName(), getName());
    }

    private void doLifecycle(InstanceState targetInstanceState, boolean force) {
        if (targetInstanceState.equals(InstanceState.STOPPED)) {
            cloudProvider.getInstanceAPI().stopInstancesInRegion(getRegion(), force, initialNodeMetadata.getId().split("/")[1]);
        } else {
            cloudProvider.getInstanceAPI().startInstancesInRegion(getRegion(), initialNodeMetadata.getId().split("/")[1]);
        }

        long timeout = System.currentTimeMillis() + WAIT_FOR_NODE_STOP_TIMEOUT;
        while (System.currentTimeMillis() < timeout) {
            if (getInstance().getInstanceState().equals(targetInstanceState)) {
                break;
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                LOGGER.warn("Waiting for instance to switch state was interrupted: ", e);
                break;
            }
        }

        if (timeout <= System.currentTimeMillis()) {
            LOGGER.warn("Instance {} hasn't switched state to {} in time: {} seconds. Current instance state is: {}",
                    initialNodeMetadata.getId().split("/")[1], targetInstanceState, WAIT_FOR_NODE_STOP_TIMEOUT / 1000, getInstance().getInstanceState());
        }
    }

    /**
     * Checks if this Node is running and reachable.
     */
    @Override
    public boolean isRunning() throws NullPointerException {
        RunningInstance runningInstance = getInstance();
        if (runningInstance == null) {
            throw new IllegalStateException("There is no instance that corresponds to this node! This should never happen and is almost definitely a bug!");
        }
        return runningInstance.getInstanceState().equals(InstanceState.RUNNING);
    }

    private RunningInstance getInstance() {
        return cloudProvider.getInstanceAPI().describeInstancesInRegion(getRegion(), initialNodeMetadata.getId().split("/")[1])
                .iterator().next().iterator().next();
    }
}
