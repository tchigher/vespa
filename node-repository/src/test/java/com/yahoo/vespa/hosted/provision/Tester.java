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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.yahoo.config.provision.NodeResources.DiskSpeed.fast;
import static com.yahoo.config.provision.NodeResources.DiskSpeed.slow;
import static com.yahoo.config.provision.NodeResources.StorageType.local;
import static com.yahoo.config.provision.NodeResources.StorageType.remote;
import static com.yahoo.vespa.hosted.provision.Tester.VespaFlavor.DiskType.EBS_GP2;
import static com.yahoo.vespa.hosted.provision.Tester.VespaFlavor.DiskType.NVMe;
import static com.yahoo.vespa.hosted.provision.Tester.VespaFlavor.Environment.VIRTUAL_MACHINE;
import static com.yahoo.yolean.Exceptions.uncheck;

/**
 * @author valerijf
 */
public class Tester {
    private static final Random rnd = new Random(5);
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
        System.out.println(tester.nodeRepository().getNodes().stream()
                .collect(Collectors.groupingBy(Node::type,
                        Collectors.mapping(Node::resources, Collectors.reducing(NodeResources.unspecified(), NodeResources::add)))));
    }

    private static void naive(List<NodeAllocation> nodes) {
        Map<NodeType, NodeResources> map = new HashMap<>(Map.of(NodeType.host, NodeResources.unspecified(), NodeType.tenant, NodeResources.unspecified()));
        double cost = nodes.stream()
                .mapToDouble(na -> {
                    VespaFlavor vf = findNearestResources(na.nodeResources);
                    NodeResources hostResources = vf.advertisedResources()
                            .withDiskGb(vf.disk().type().isRemote() ? na.nodeResources.diskGb() : vf.disk().sizeInBase10Gb);
                    System.out.println(toString(na.nodeResources) + " " + toString(hostResources));
                    map.computeIfPresent(NodeType.tenant, (k, nr) -> nr.justNumbers().add(na.nodeResources));
                    map.computeIfPresent(NodeType.host, (k, nr) -> nr.justNumbers().add(hostResources));
                    return vf.cost + (vf.disk().type().isRemote() ? na.nodeResources.diskGb() * 0.1 : 0);
                })
                .sum();

        System.out.println(cost);
        System.out.println(map);
    }

    private static String toString(NodeResources nr) {
        return nr.vcpu() + "-" + nr.memoryGb() + "-" + nr.diskGb() + "-" + nr.storageType();
    }

    private static VespaFlavor findNearestResources(NodeResources requestedResources) {
        return Arrays.stream(VespaFlavor.values())
                .filter(v -> v.advertisedResources().satisfies(requestedResources.with(rnd.nextBoolean() ? local : remote)))
                .min(Comparator.comparingDouble(VespaFlavor::cost))
                .orElseThrow(() -> new IllegalArgumentException("For " + requestedResources));
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

    public enum VespaFlavor {

        aws_t3_nano(     "aws-t3.nano",      "t3.nano",       0.5, 0.45,   0.5,                4),
        aws_t3_micro(    "aws-t3.micro",     "t3.micro",      0.5, 0.94,   1,                  7),
        aws_t3_small(    "aws-t3.small",     "t3.small",      0.5, 1.80,   2,                 15),
        aws_t3_medium(   "aws-t3.medium",    "t3.medium",     0.5, 3.60,   4,                 30),
        aws_t3_large(    "aws-t3.large",     "t3.large",      0.5, 7.50,   8,                 60),
        aws_t3_xlarge(   "aws-t3.xlarge",    "t3.xlarge",     0.5,15.10,  16,                120),
        aws_t3_2xlarge(  "aws-t3.2xlarge",   "t3.2xlarge",    0.5,30.50,  32,                240),

        aws_m5_large(    "aws-m5.large",     "m5.large",      2,   7.30,   8,                 69),
        aws_m5d_large(   "aws-m5d.large",    "m5d.large",     2,   7.30,   8,   NVMe.GB(75),  81),
        aws_m5_xlarge(   "aws-m5.xlarge",    "m5.xlarge",     4,  15.10,  16,                138),
        aws_m5d_xlarge(  "aws-m5d.xlarge",   "m5d.xlarge",    4,  15.10,  16,  NVMe.GB(150), 162),
        aws_m5_2xlarge(  "aws-m5.2xlarge",   "m5.2xlarge",    8,  30.50,  32,                276),
        aws_m5d_2xlarge( "aws-m5d.2xlarge",  "m5d.2xlarge",   8,  30.50,  32,  NVMe.GB(300), 324),
        aws_m5_4xlarge(  "aws-m5.4xlarge",   "m5.4xlarge",   16,  61.30,  64,                553),
        aws_m5d_4xlarge( "aws-m5d.4xlarge",  "m5d.4xlarge",  16,  61.30,  64,  NVMe.GB(600), 648),
        aws_m5_8xlarge(  "aws-m5.8xlarge",   "m5.8xlarge",   32, 123.00, 128,               1106),
        aws_m5d_8xlarge( "aws-m5d.8xlarge",  "m5d.8xlarge",  32, 123.00, 128, NVMe.GB(1200),1296),
        aws_m5_12xlarge( "aws-m5.12xlarge",  "m5.12xlarge",  48, 185.00, 192,               1659),
        aws_m5d_12xlarge("aws-m5d.12xlarge", "m5d.12xlarge", 48, 185.00, 192, NVMe.GB(1800),1953),
        aws_m5_16xlarge( "aws-m5.16xlarge",  "m5.16xlarge",  64, 246.00, 256,               2212),
        aws_m5d_16xlarge("aws-m5d.16xlarge", "m5d.16xlarge", 64, 246.00, 256, NVMe.GB(2400),2604),
        aws_m5_24xlarge( "aws-m5.24xlarge",  "m5.24xlarge",  96, 370.00, 384,               3318),
        aws_m5d_24xlarge("aws-m5d.24xlarge", "m5d.24xlarge", 96, 370.00, 384, NVMe.GB(3600),3905),

        aws_c5_large(    "aws-c5.large",     "c5.large",      2,   3.50,   4,                 61),
        aws_c5d_large(   "aws-c5d.large",    "c5d.large",     2,   3.50,   4,   NVMe.GB(50),  69),
        aws_c5_xlarge(   "aws-c5.xlarge",    "c5.xlarge",     4,   7.30,   8,             122),
        aws_c5d_xlarge(  "aws-c5d.xlarge",   "c5d.xlarge",    4,   7.30,   8,  NVMe.GB(100), 138),
        aws_c5_2xlarge(  "aws-c5.2xlarge",   "c5.2xlarge",    8,  15.00,  16,                245),
        aws_c5d_2xlarge( "aws-c5d.2xlarge",  "c5d.2xlarge",   8,  15.00,  16,  NVMe.GB(200), 276),
        aws_c5_4xlarge(  "aws-c5.4xlarge",   "c5.4xlarge",   16,  30.30,  32,                490),
        aws_c5d_4xlarge( "aws-c5d.4xlarge",  "c5d.4xlarge",  16,  30.30,  32,  NVMe.GB(400), 553),
        aws_c5_9xlarge(  "aws-c5.9xlarge",   "c5.9xlarge",   36,  68.50,  72,               1102),
        aws_c5d_9xlarge( "aws-c5d.9xlarge",  "c5d.9xlarge",  36,  68.50,  72,  NVMe.GB(900),1244),
        aws_c5_12xlarge( "aws-c5.12xlarge",  "c5.12xlarge",  48,  92.20,  96,               1469),
        aws_c5d_12xlarge("aws-c5d.12xlarge", "c5d.12xlarge", 48,  92.20,  96, NVMe.GB(1800),1659),
        aws_c5_18xlarge( "aws-c5.18xlarge",  "c5.18xlarge",  72, 137.00, 144,               2203),
        aws_c5d_18xlarge("aws-c5d.18xlarge", "c5d.18xlarge", 72, 137.00, 144, NVMe.GB(1800),2488),
        aws_c5_24xlarge( "aws-c5.24xlarge",  "c5.24xlarge",  96, 185.00, 192,               2938),
        aws_c5d_24xlarge("aws-c5d.24xlarge", "c5d.24xlarge", 96, 185.00, 192, NVMe.GB(3600),3318),

        aws_r5_large(    "aws-r5.large",     "r5.large",      2,  15.20,  16,                 91),
        aws_r5d_large(   "aws-r5d.large",    "r5d.large",     2,  15.20,  16,   NVMe.GB(75), 103),
        aws_r5_xlarge(   "aws-r5.xlarge",    "r5.xlarge",     4,  30.80,  32,                181),
        aws_r5d_xlarge(  "aws-r5d.xlarge",   "r5d.xlarge",    4,  30.80,  32,  NVMe.GB(150), 206),
        aws_r5_2xlarge(  "aws-r5.2xlarge",   "r5.2xlarge",    8,  62.00,  64,                363),
        aws_r5d_2xlarge( "aws-r5d.2xlarge",  "r5d.2xlarge",   8,  62.00,  64,  NVMe.GB(300), 412),
        aws_r5_4xlarge(  "aws-r5.4xlarge",   "r5.4xlarge",   16, 124.00, 128,                726),
        aws_r5d_4xlarge( "aws-r5d.4xlarge",  "r5d.4xlarge",  16, 124.00, 128,  NVMe.GB(600), 824),
        aws_r5_8xlarge(  "aws-r5.8xlarge",   "r5.8xlarge",   32, 249.00, 256,               1452),
        aws_r5d_8xlarge( "aws-r5d.8xlarge",  "r5d.8xlarge",  32, 249.00, 256, NVMe.GB(1200),1648),
        aws_r5_12xlarge( "aws-r5.12xlarge",  "r5.12xlarge",  48, 374.00, 384,               2177),
        aws_r5d_12xlarge("aws-r5d.12xlarge", "r5d.12xlarge", 48, 374.00, 384, NVMe.GB(1800),2488),
        aws_r5_16xlarge( "aws-r5.16xlarge",  "r5.16xlarge",  64, 498.00, 512,               2903),
        aws_r5d_16xlarge("aws-r5d.16xlarge", "r5d.16xlarge", 64, 498.00, 512, NVMe.GB(2400),3318),
        aws_r5_24xlarge( "aws-r5.24xlarge",  "r5.24xlarge",  96, 748.00, 768,               4355),
        aws_r5d_24xlarge("aws-r5d.24xlarge", "r5d.24xlarge", 96, 748.00, 768, NVMe.GB(3600),4977),

        aws_z1d_6xlarge( "aws-z1d.6xlarge",  "z1d.6xlarge",  24, 185.00, 192, NVMe.GB(900), 1607);

        private final String name;
        private final Environment environment;
        private final double cpus;
        private final double cpuSpeedup;
        private final double memory;
        private final Optional<Double> advertisedMemory;
        private final Disk disk;
        private final Bandwidth bandwidth;
        private final int cost;
        private final Optional<String> awsInstanceType;

        /** For flavors used in AWS */
        VespaFlavor(String name, String awsInstanceType, double cpus, double memory, double advertisedMemory, int cost) {
            // Max General Purpose SSD (gp2) EBS volume type in AWS is 16 TB
            // https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EBSVolumeTypes.html
            // https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/ec2/model/EbsBlockDevice.html#withVolumeSize-java.lang.Integer-
            this(name, awsInstanceType, cpus, memory, advertisedMemory, EBS_GP2.GB(16_384), cost);
        }

        /** For flavors used in AWS */
        VespaFlavor(String name, String awsInstanceType, double cpus, double memory, double advertisedMemory, Disk disk, int cost) {
            this(name, VIRTUAL_MACHINE, cpus, 1.0, memory, disk, Bandwidth.NET_10GBIT /* TODO: Find the actual value */,
                    cost, Optional.of(advertisedMemory), Optional.of(awsInstanceType));
        }


        VespaFlavor(String name, Environment environment, double cpus, double cpuSpeedup,
                    double memory, Disk disk, Bandwidth bandwidth, int cost,
                    Optional<Double> advertisedMemory, Optional<String> awsInstanceType) {
            this.name = name;
            this.environment = environment;
            this.cpus = cpus;
            this.cpuSpeedup = cpuSpeedup;
            this.memory = memory;
            this.advertisedMemory = advertisedMemory;
            this.disk = disk;
            this.bandwidth = bandwidth;
            this.cost = cost;
            this.awsInstanceType = awsInstanceType;
        }

        public static Optional<VespaFlavor> fromVespaFlavorName(String flavorName) {
            return Stream.of(VespaFlavor.values())
                    .filter(f -> f.name.equals(flavorName))
                    .findFirst();
        }

        public Environment environment() { return environment; }
        public double memoryGb() { return memory; }
        public Disk disk() { return disk; }

        // Cap reported network bandwidth for host to 10Gbps until we have switches that can handle more
        public Bandwidth bandwidth() { return (Double.compare(bandwidth.gbits(),
                Bandwidth.NET_10GBIT.gbits()) > 0) ? Bandwidth.NET_10GBIT : bandwidth; }

        public NodeResources resources() {
            return new NodeResources(cpus,
                    memory,
                    disk.sizeInBase10Gb(),
                    bandwidth.gbits(),
                    disk().type().isFast() ? fast : slow,
                    disk().type().isRemote() ? remote : local);
        }

        /** Returns the resources for this flavor as advertised by the cloud provider */
        public NodeResources advertisedResources() {
            return new NodeResources(cpus * cpuSpeedup,
                    advertisedMemory.orElse(memory),
                    disk.sizeInBase10Gb(),
                    bandwidth.gbits(),
                    disk().type().isFast() ? fast : slow,
                    disk().type().isRemote() ? remote : local);
        }

        /** Returns the cost of this node in USD per month */
        public int cost() { return cost; }

        public Optional<String> awsInstanceType() {
            return awsInstanceType;
        }

        public enum Environment { BARE_METAL, VIRTUAL_MACHINE }


        public enum DiskType {
            DISK, SSD, NVMe, EBS_GP2;

            public boolean isFast() {
                return this != DISK;
            }

            public boolean isRemote() {
                return this == EBS_GP2;
            }

            private Disk GB(double sizeInBase10Gb) {
                return new Disk(this, sizeInBase10Gb);
            }
        }

        public static class Disk {

            private final DiskType diskType;
            private final double sizeInBase10Gb;

            private Disk(DiskType diskType, double sizeInBase10Gb) {
                this.diskType = diskType;
                this.sizeInBase10Gb = sizeInBase10Gb;
            }

            public DiskType type() {
                return diskType;
            }

            /** Returns disk size in GB in base 2 (1GB = 10^9 bytes) */
            public double sizeInBase10Gb() {
                return sizeInBase10Gb;
            }

            /** Returns disk size in GB in base 2, also known as GiB (1GiB = 2^30 bytes), rounded to nearest integer value */
            public double sizeInBase2Gb() {
                return Math.round(sizeInBase10Gb / Math.pow(1.024, 3));
            }
        }

        public enum Bandwidth {

            NET_1GBIT(  1),
            NET_10GBIT(10),
            NET_25GBIT(25);

            /* Gbit/s of network bandwidth */
            private final double gbits;

            Bandwidth(double gbits) {
                this.gbits = gbits;
            }

            public double mbits() {
                return gbits * 1000;
            }

            public double gbits() {
                return gbits;
            }

        }

    }

}

