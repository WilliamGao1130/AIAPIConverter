package org.bluepowerrobotics.converter.gateway.endpoints;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import org.bluepowerrobotics.converter.gateway.HttpSupport;
import org.bluepowerrobotics.converter.util.Json;

/** GET /health：存活探针。 */
public final class HealthEndpoint implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("status", "ok");
        root.put("service", "aiapiconverter");
        HttpSupport.sendJson(exchange, 200, root);
    }
}
