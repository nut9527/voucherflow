package com.example.voucherflow.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** 为每次实验创建独立的消息代理存储与消费进度，隔离不同测试的状态。 */
public final class IsolatedRocketMq implements AutoCloseable {
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> namesrv;
    private final GenericContainer<?> broker;
    private final int port;
    public IsolatedRocketMq() {
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        namesrv = new GenericContainer<>("apache/rocketmq:5.3.2").withNetwork(network).withNetworkAliases("namesrv")
                .withEnv("JAVA_OPT_EXT", "-Xms128m -Xmx128m -Xmn64m")
                .withCommand("sh", "mqnamesrv").waitingFor(Wait.forLogMessage(".*Name Server boot success.*\\n", 1));
        String config = "brokerClusterName=VoucherFlowTest\nbrokerName=voucherflow-test\nbrokerId=0\nbrokerIP1=127.0.0.1\n"
                + "brokerRole=ASYNC_MASTER\nflushDiskType=SYNC_FLUSH\nautoCreateTopicEnable=false\nautoCreateSubscriptionGroup=false\n";
        broker = new GenericContainer<>("apache/rocketmq:5.3.2").withNetwork(network).withExposedPorts(port)
                .withEnv("JAVA_OPT_EXT", "-Xms384m -Xmx384m -Xmn128m")
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8), 0644), "/home/rocketmq/test-broker.conf")
                .withCopyToContainer(Transferable.of(("{\"grpcServerPort\":" + port + ",\"rocketMQClusterName\":\"VoucherFlowTest\"}")
                        .getBytes(StandardCharsets.UTF_8), 0644), "/home/rocketmq/test-proxy.json")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", port), new ExposedPort(port))))
                .withCommand("sh", "mqbroker", "--enable-proxy", "-n", "namesrv:9876", "-c", "/home/rocketmq/test-broker.conf",
                        "-pc", "/home/rocketmq/test-proxy.json")
                .waitingFor(Wait.forLogMessage(".*rocketmq-proxy startup successfully.*\\n", 1).withStartupTimeout(Duration.ofSeconds(60)));
    }
    public void start() {
        startWithTopics("vf_fulfillment", "vf_notification");
    }
    public void startWithTopics(String... topics) {
        try {
            namesrv.start(); broker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!admin("clusterList", "-n", "namesrv:9876").contains("voucherflow-test")) {
                if (System.nanoTime() >= deadline) { throw new IllegalStateException("Broker did not register with NameServer"); }
                try { Thread.sleep(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
            for (String topic : topics) {
                admin("updateTopic", "-n", "namesrv:9876", "-b", "127.0.0.1:10911", "-t", topic, "-a", "+message.type=NORMAL");
            }
            for (String group : new String[]{"vf_fulfillment_worker", "vf_notification_worker"}) {
                admin("updateSubGroup", "-n", "namesrv:9876", "-b", "127.0.0.1:10911", "-g", group, "-m", "true", "-r", "2");
            }
        } catch (RuntimeException ex) { close(); throw ex; }
    }
    public String admin(String... args) {
        var command = new String[args.length + 2]; command[0] = "sh"; command[1] = "mqadmin";
        System.arraycopy(args, 0, command, 2, args.length);
        try {
            var result = broker.execInContainer(command);
            if (result.getExitCode() != 0 || result.getStderr().contains("Exception")) {
                throw new IllegalStateException("Broker administration failed: " + result.getStderr());
            }
            return result.getStdout();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public String endpoint() { return "127.0.0.1:" + port; }
    public void pauseBroker() { broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec(); }
    public void resumeBroker() { broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec(); }
    @Override public void close() { broker.close(); namesrv.close(); network.close(); }
}
