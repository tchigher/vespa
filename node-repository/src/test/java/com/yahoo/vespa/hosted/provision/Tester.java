package com.yahoo.vespa.hosted.provision;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.Cloud;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.SlimeUtils;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.hosted.provision.node.IP;
import com.yahoo.vespa.hosted.provision.provisioning.FatalProvisioningException;
import com.yahoo.vespa.hosted.provision.provisioning.HostProvisioner;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisionedHost;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisioningTester;
import com.yahoo.vespa.hosted.provision.testutils.MockNameResolver;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.yahoo.yolean.Exceptions.uncheck;

/**
 * @author valerijf
 */
public class Tester {
    private static final Logger log = Logger.getLogger(Tester.class.getSimpleName());
    private static final ApplicationId tenantHostAppId = ApplicationId.from("hosted-vespa", "tenant-host", "default");
    private static final ClusterMembership tenantHostMembership = ClusterMembership.from(ClusterSpec.specification(ClusterSpec.Type.container, ClusterSpec.Id.from("tenant-host"))
            .vespaVersion("7")
            .group(ClusterSpec.Group.from(0)).build(), 0);

    @Test
    public void test() {
        MockNameResolver nameResolver = new MockNameResolver();
        MockHostProvisioner hostProvisioner = new MockHostProvisioner(nameResolver);
        ProvisioningTester tester = new ProvisioningTester.Builder()
                .zone(new Zone(Cloud.builder().dynamicProvisioning(true).build(), SystemName.defaultSystem(), Environment.defaultEnvironment(), RegionName.defaultName()))
                .hostProvisioner(hostProvisioner)
                .nameResolver(nameResolver)
                .build();

        List<NodeAllocation> nodes = parse(Paths.get("src/test/resources/nodes.json"));
        activate(tester, hostProvisioner, nodes);


        System.out.println("Done");
    }

//    private static void write(NodeR)

    private static List<NodeAllocation> parse(Path path) {
        byte[] json = uncheck(() -> Files.readAllBytes(path));
        return SlimeUtils.entriesStream(SlimeUtils.jsonToSlime(json).get().field("nodes"))
                .flatMap(i -> NodeAllocation.parse(i).stream())
                .collect(Collectors.toList());
    }

    private static void activate(ProvisioningTester tester, HostProvisioner hostProvisioner, List<NodeAllocation> nodes) {
        Map<ApplicationId, List<NodeAllocation>> applicationNodes = nodes.stream()
                .collect(Collectors.groupingBy(node -> node.applicationId, Collectors.toList()));

        for (Map.Entry<ApplicationId, List<NodeAllocation>> entry : applicationNodes.entrySet()) {
            List<HostSpec> hostSpecs = entry.getValue().stream()
                    .collect(Collectors.groupingBy(
                            node -> node.applicationId.serializedForm() + "|" + node.membership.cluster().id().value() + "|" + node.membership.cluster().type().name(),
                            Collectors.toList()))
                    .values().stream()
                    .flatMap(clusterNodes -> {
                        NodeAllocation node = clusterNodes.get(0);
                        int groups = (int) clusterNodes.stream().map(n -> n.membership.cluster().group().get()).distinct().count();
                        Capacity capacity = Capacity.from(new ClusterResources(clusterNodes.size(), groups, node.nodeResources));
                        ClusterSpec spec = ClusterSpec.request(node.membership.cluster().type(), node.membership.cluster().id()).vespaVersion("7").build();
                        return tester.provisioner().prepare(entry.getKey(), spec, capacity, log::log).stream();
                    })
                    .collect(Collectors.toList());

            provision(tester.nodeRepository(), hostProvisioner);

            NestedTransaction transaction = new NestedTransaction();
            tester.provisioner().activate(transaction, entry.getKey(), hostSpecs);
            transaction.commit();
        }
    }

    private static void provision(NodeRepository nodeRepository, HostProvisioner hostProvisioner) {
        NodeList nodes = nodeRepository.list();
        Map<String, Set<Node>> nodesByProvisionedParentHostname = nodes.nodeType(NodeType.tenant).asList().stream()
                .filter(node -> node.parentHostname().isPresent())
                .collect(Collectors.groupingBy(
                        node -> node.parentHostname().get(),
                        Collectors.toSet()));

        nodes.state(Node.State.provisioned).nodeType(NodeType.host).forEach(host -> {
            Set<Node> children = nodesByProvisionedParentHostname.getOrDefault(host.hostname(), Set.of());
            List<Node> updatedNodes = hostProvisioner.provision(host, children);
            nodeRepository.write(updatedNodes, () -> {});

            Node allocatedHost = nodeRepository.getNode(host.hostname()).get()
                    .allocate(tenantHostAppId, tenantHostMembership, host.resources(), Instant.now());
            NestedTransaction transaction = new NestedTransaction();
            nodeRepository.activate(List.of(allocatedHost), transaction);
            transaction.commit();
        });
    }

    private static class NodeAllocation {
        private final String hostname;
        private final ApplicationId applicationId;
        private final ClusterMembership membership;
        private final NodeResources nodeResources;

        private NodeAllocation(String hostname, ApplicationId applicationId, ClusterMembership clusterMembership, NodeResources nodeResources) {
            this.hostname = hostname;
            this.applicationId = applicationId;
            this.membership = clusterMembership;
            this.nodeResources = nodeResources;
        }

        private static Optional<NodeAllocation> parse(Inspector inspector) {
            if (!inspector.field("type").asString().equals("tenant")) return Optional.empty();
            if (!inspector.field("state").asString().equals("active")) return Optional.empty();

            Inspector owner = inspector.field("owner");
            Inspector membership = inspector.field("membership");
            Inspector resources = inspector.field("requestedResources");

            ClusterSpec clusterSpec = ClusterSpec.specification(
                    fromString(membership.field("clustertype").asString()),
                    ClusterSpec.Id.from(membership.field("clusterid").asString())
            )
                    .group(ClusterSpec.Group.from(Integer.parseInt(membership.field("group").asString())))
                    .vespaVersion(inspector.field("vespaVersion").asString())
                    .build();
            int index = (int) membership.field("index").asLong();

            return Optional.of(
                    new NodeAllocation(
                            inspector.field("type").asString(),
                            ApplicationId.from(owner.field("tenant").asString(), owner.field("application").asString(), owner.field("instance").asString()),
                            membership.field("retired").asBool() ?
                                    ClusterMembership.retiredFrom(clusterSpec, index): ClusterMembership.from(clusterSpec, index),
                            new NodeResources(
                                    resources.field("vcpu").asDouble(),
                                    resources.field("memoryGb").asDouble(),
                                    resources.field("diskGb").asDouble(),
                                    resources.field("bandwidthGbps").asDouble(),
                                    NodeResources.DiskSpeed.valueOf(resources.field("diskSpeed").asString()),
                                    NodeResources.StorageType.valueOf(resources.field("storageType").asString())                            ))
            );
        }

        static ClusterSpec.Type fromString(String type) {
            switch (type) {
                case "container": return ClusterSpec.Type.container;
                case "admin": return ClusterSpec.Type.admin;
                case "content":
                case "combined": return ClusterSpec.Type.content;
                default: throw new IllegalArgumentException();
            }
        }
    }

    static class MockHostProvisioner implements HostProvisioner {

        private static final NodeResources hostResources = new NodeResources(72, 144, 1800, 25).justNumbers();

        private final MockNameResolver nameResolver;

        public MockHostProvisioner(MockNameResolver nameResolver) {
            this.nameResolver = nameResolver;
        }

        @Override
        public List<ProvisionedHost> provisionHosts(List<Integer> provisionIndexes, NodeResources resources, ApplicationId applicationId, Version osVersion) {
            Flavor hostFlavor = new Flavor(hostResources.with(resources.diskSpeed()).with(resources.storageType()));
            return provisionIndexes.stream()
                    .map(index -> new ProvisionedHost(
                            "host" + index, "host" + index, hostFlavor, "host" + index + "-1", resources, osVersion))
                    .collect(Collectors.toList());
        }

        @Override
        public List<Node> provision(Node host, Set<Node> children) throws FatalProvisioningException {
            List<Node> result = new ArrayList<>();
            result.add(withIpAssigned(host));
            for (var child : children) {
                result.add(withIpAssigned(child));
            }
            return result;
        }

        private Node withIpAssigned(Node node) {
            int hostIndex = Integer.parseInt(node.hostname().replaceAll("^[a-z]+|-\\d+$", ""));
            if (node.type() == NodeType.tenant) {
                int nodeIndex = Integer.parseInt(node.hostname().replaceAll("^[a-z]+\\d+-", ""));
                return node.with(node.ipConfig().with(Set.of("::" + hostIndex + ":" + nodeIndex)));
            }

            Set<String> addresses = Set.of("::" + hostIndex + ":0");
            nameResolver.addRecord(node.hostname(), addresses.iterator().next());
            Set<String> pool = new HashSet<>();
            for (int i = 1; i <= 32; i++) {
                String ip = "::" + hostIndex + ":" + i;
                pool.add(ip);
                nameResolver.addRecord(node.hostname() + "-" + i, ip);
            }
            return node.with(node.ipConfig().with(addresses).with(IP.Pool.of(pool)));
        }

        @Override
        public void deprovision(Node host) {
            throw new UnsupportedOperationException("Not implemented");
        }


    }
}

