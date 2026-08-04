package org.bluepowerrobotics.converter.gateway.endpoints;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bluepowerrobotics.converter.gateway.GatewayConfig;
import org.bluepowerrobotics.converter.gateway.HttpSupport;
import org.bluepowerrobotics.converter.util.Json;

/** GET /v1/models：返回后端配置的模型。 */
public final class ModelsEndpoint implements HttpHandler {

    private final GatewayConfig config;

    public ModelsEndpoint(GatewayConfig config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpSupport.sendError(exchange, 405, "invalid_request_error",
                    "Only GET is supported");
            return;
        }
        ArrayNode data = Json.MAPPER.createArrayNode();
        Set<String> seen = new LinkedHashSet<String>();
        for (org.bluepowerrobotics.converter.provider.ProviderConfig backend : config.allBackends()) {
            String model = backend.getModel();
            if (model == null || seen.contains(model)) {
                continue;
            }
            seen.add(model);
            ObjectNode m = Json.MAPPER.createObjectNode();
            m.put("id", model);
            m.put("object", "model");
            m.put("created", 0);
            m.put("owned_by", backend.getType().id());
            data.add(m);
        }
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("object", "list");
        root.set("data", data);
        HttpSupport.sendJson(exchange, 200, root);
    }
}
