package org.bluepowerrobotics.converter.gateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个 JVM 进程内同时运行多个网关（每个网关独立端口 / 后端 / base URL）。
 */
public final class GatewayCluster {

    private final List<GatewayServer> servers;

    private GatewayCluster(List<GatewayServer> servers) {
        this.servers = servers;
    }

    public static GatewayCluster start(List<GatewayConfig> configs) throws IOException {
        List<GatewayServer> servers = new ArrayList<GatewayServer>();
        try {
            for (GatewayConfig config : configs) {
                servers.add(GatewayServer.start(config));
            }
        } catch (IOException e) {
            for (GatewayServer server : servers) {
                server.stop();
            }
            throw e;
        }
        return new GatewayCluster(servers);
    }

    public List<GatewayServer> getServers() {
        return Collections.unmodifiableList(servers);
    }

    public List<String> getAddresses() {
        List<String> out = new ArrayList<String>();
        for (GatewayServer server : servers) {
            out.add(server.getAddress());
        }
        return out;
    }

    public void stop() {
        for (GatewayServer server : servers) {
            server.stop();
        }
    }
}
