package io.annopick.gateway.autodns.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annopick.gateway.autodns.config.AutoDnsProperties;
import io.annopick.gateway.autodns.model.ApisixOperation;
import io.annopick.gateway.autodns.repository.ApisixOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApisixService {

    private final AutoDnsProperties properties;
    private final ApisixOperationRepository operationRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void addRoute(String externalHost, String upstreamHost, Integer nodePort) {
        try {
            String routeId = externalHost.replace(".", "-");
            Map<String, Object> route = buildRouteConfig(externalHost, upstreamHost, nodePort);
            String requestBody = objectMapper.writeValueAsString(route);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", properties.getApisix().getAdminKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            String url = properties.getApisix().getAdminUrl() + "/apisix/admin/routes/" + routeId;

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);

            logOperation("ADD", upstreamHost, externalHost, nodePort, requestBody, response.getBody(), "SUCCESS", null);
            log.info("Added APISIX route: {} -> {}:{}", externalHost, upstreamHost, nodePort);
        } catch (Exception e) {
            log.error("Failed to add APISIX route", e);
            logOperation("ADD", upstreamHost, externalHost, nodePort, null, null, "FAILED", e.getMessage());
        }
    }

    public void updateRoute(String externalHost, String upstreamHost, Integer nodePort) {
        addRoute(externalHost, upstreamHost, nodePort);
    }

    public void deleteRoute(String externalHost) {
        try {
            String routeId = externalHost.replace(".", "-");
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", properties.getApisix().getAdminKey());

            HttpEntity<String> entity = new HttpEntity<>(headers);
            String url = properties.getApisix().getAdminUrl() + "/apisix/admin/routes/" + routeId;

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);

            logOperation("DELETE", null, externalHost, null, null, response.getBody(), "SUCCESS", null);
            log.info("Deleted APISIX route: {}", externalHost);
        } catch (Exception e) {
            log.error("Failed to delete APISIX route", e);
            logOperation("DELETE", null, externalHost, null, null, null, "FAILED", e.getMessage());
        }
    }

    private Map<String, Object> buildRouteConfig(String externalHost, String upstreamHost, Integer nodePort) {
        Map<String, Object> route = new HashMap<>();
        route.put("uri", "/*");
        route.put("host", externalHost);
        route.put("enable_websocket", true);

        Map<String, Object> upstream = new HashMap<>();
        upstream.put("type", "roundrobin");
        upstream.put("scheme", "http");
        upstream.put("pass_host", "rewrite");
        upstream.put("upstream_host", upstreamHost);

        Map<String, Object> nodes = new HashMap<>();
        nodes.put(upstreamHost + ":" + nodePort, 1);
        upstream.put("nodes", nodes);

        route.put("upstream", upstream);

        // Add real-ip plugin to forward client IP via X-Forwarded-For
        Map<String, Object> plugins = new HashMap<>();
        Map<String, Object> realIp = new HashMap<>();
        realIp.put("source", "http_x_forwarded_for");
        realIp.put("trusted_addresses", new String[]{"0.0.0.0/0", "::/0"});
        plugins.put("real-ip", realIp);
        route.put("plugins", plugins);

        return route;
    }

    private void logOperation(String operation, String upstreamHost, String externalHost, Integer nodePort,
                              String requestBody, String responseBody, String status, String errorMessage) {
        ApisixOperation op = new ApisixOperation();
        op.setOperation(operation);
        op.setUpstreamHost(upstreamHost);
        op.setExternalHost(externalHost);
        op.setNodePort(nodePort);
        op.setRequestBody(requestBody);
        op.setResponseBody(responseBody);
        op.setStatus(status);
        op.setErrorMessage(errorMessage);
        operationRepository.save(op);
    }
}
