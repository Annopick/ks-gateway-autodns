package io.annopick.gateway.autodns.service;

import io.annopick.gateway.autodns.config.AutoDnsProperties;
import io.annopick.gateway.autodns.model.DnsRecord;
import io.annopick.gateway.autodns.repository.DnsRecordRepository;
import io.annopick.gateway.autodns.repository.PublicIpRecordRepository;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngressWatcher {

    private final KubernetesClient kubernetesClient;
    private final AutoDnsProperties properties;
    private final AliyunDnsService aliyunDnsService;
    private final ApisixService apisixService;
    private final DnsRecordRepository dnsRecordRepository;
    private final PublicIpRecordRepository publicIpRecordRepository;

    @PostConstruct
    public void startWatching() {
        SharedIndexInformer<Ingress> informer = kubernetesClient
            .network()
            .v1()
            .ingresses()
            .inAnyNamespace()
            .inform();

        informer.addEventHandler(new ResourceEventHandler<Ingress>() {
            @Override
            public void onAdd(Ingress ingress) {
                processIngress(ingress, null, "ADD");
            }

            @Override
            public void onUpdate(Ingress oldIngress, Ingress newIngress) {
                processIngress(newIngress, oldIngress, "UPDATE");
            }

            @Override
            public void onDelete(Ingress ingress, boolean deletedFinalStateUnknown) {
                processIngress(ingress, null, "DELETE");
            }
        });

        log.info("Ingress watcher started");
    }

    @Transactional
    public void processIngress(Ingress ingress, Ingress oldIngress, String action) {
        try {
            String ingressClassName = ingress.getSpec().getIngressClassName();
            if (ingressClassName == null || !ingressClassName.startsWith(properties.getIngressClassPrefix())) {
                return;
            }

            List<String> hosts = ingress.getSpec().getRules().stream()
                .map(rule -> rule.getHost())
                .filter(host -> host != null && host.endsWith(properties.getHostSuffix()))
                .collect(Collectors.toList());

            // Handle UPDATE: detect removed hosts
            if ("UPDATE".equals(action) && oldIngress != null) {
                List<String> oldHosts = oldIngress.getSpec().getRules().stream()
                    .map(rule -> rule.getHost())
                    .filter(host -> host != null && host.endsWith(properties.getHostSuffix()))
                    .collect(Collectors.toList());
                
                // Find hosts that were removed in the update
                for (String oldHost : oldHosts) {
                    if (!hosts.contains(oldHost)) {
                        log.info("Detected host removal in UPDATE: {}", oldHost);
                        handleDelete(oldHost);
                    }
                }
            }

            if (hosts.isEmpty()) {
                return;
            }

            for (String host : hosts) {
                if ("DELETE".equals(action)) {
                    handleDelete(host);
                } else {
                    handleAddOrUpdate(host, ingressClassName);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process ingress: {}", ingress.getMetadata().getName(), e);
        }
    }

    private void handleAddOrUpdate(String host, String ingressClassName) {
        try {
            String rr = host.replace("." + properties.getAliyun().getDomain(), "");
            String svcName = ingressClassName.replace(properties.getIngressClassSuffix(), "");
            
            Service service = kubernetesClient.services()
                .inNamespace(properties.getKubesphereNamespace())
                .withName(svcName)
                .get();

            if (service == null) {
                log.warn("Service {} not found in namespace {}", svcName, properties.getKubesphereNamespace());
                return;
            }

            Integer nodePort = service.getSpec().getPorts().stream()
                .filter(port -> "http".equals(port.getName()) || port.getPort() == 80)
                .map(port -> port.getNodePort())
                .findFirst()
                .orElse(null);

            if (nodePort == null) {
                log.warn("NodePort not found for service {}", svcName);
                return;
            }

            List<String> nodeIps = getNodeIps();
            String ipAddresses = String.join(",", nodeIps);

            Optional<DnsRecord> existingRecord = dnsRecordRepository.findByHost(host);

            if (existingRecord.isPresent()) {
                DnsRecord record = existingRecord.get();
                if (!record.getIpAddresses().equals(ipAddresses) || !record.getNodePort().equals(nodePort)) {
                    boolean dnsUpdateSuccess = true;
                    for (String ip : nodeIps) {
                        boolean success = aliyunDnsService.updateDomainRecord(record.getRecordId(), rr, "A", ip);
                        if (!success) {
                            dnsUpdateSuccess = false;
                            log.warn("Failed to update DNS record for {} -> {}", rr, ip);
                        }
                    }
                    
                    // Update database and APISIX regardless of DNS API result
                    record.setIpAddresses(ipAddresses);
                    record.setNodePort(nodePort);
                    dnsRecordRepository.save(record);

                    String externalHost = host.replace(properties.getHostSuffix(), properties.getExternalDomainSuffix());
                    apisixService.updateRoute(externalHost, host, nodePort);
                    
                    if (!dnsUpdateSuccess) {
                        log.error("DNS update partially failed for host {}, but database and APISIX were updated", host);
                    }
                }
            } else {
                String recordId = null;
                boolean dnsAddSuccess = false;
                for (String ip : nodeIps) {
                    String rid = aliyunDnsService.addDomainRecord(rr, "A", ip);
                    if (rid != null) {
                        recordId = rid;
                        dnsAddSuccess = true;
                    } else {
                        log.warn("Failed to add DNS record for {} -> {}", rr, ip);
                    }
                }

                // Save to database even if DNS API failed
                DnsRecord record = new DnsRecord();
                record.setHost(host);
                record.setRr(rr);
                record.setIpAddresses(ipAddresses);
                record.setNodePort(nodePort);
                record.setRecordId(recordId);
                dnsRecordRepository.save(record);

                String externalHost = host.replace(properties.getHostSuffix(), properties.getExternalDomainSuffix());
                apisixService.addRoute(externalHost, host, nodePort);
                
                if (!dnsAddSuccess) {
                    log.error("DNS add failed for host {}, but database and APISIX were updated. Manual DNS configuration may be required.", host);
                }
            }
            
            // Manage public DNS record for external host
            managePublicDnsRecord(host);
            
        } catch (Exception e) {
            log.error("Failed to handle add/update for host: {}", host, e);
        }
    }
    
    private void managePublicDnsRecord(String host) {
        try {
            // Get external host (e.g., test-k8s.jsccb.ltd -> test-nj.jsccb.ltd)
            String externalHost = host.replace(properties.getHostSuffix(), properties.getExternalDomainSuffix());
            String externalRr = externalHost.replace("." + properties.getAliyun().getDomain(), "");
            
            // Get public IP from PublicIpRecord table
            Optional<io.annopick.gateway.autodns.model.PublicIpRecord> publicIpRecord = 
                publicIpRecordRepository.findByIdentifier("default");
            
            if (!publicIpRecord.isPresent()) {
                log.warn("Public IP record not found (identifier=default). Agent may not have reported IP yet. Skipping public DNS record creation for {}", externalHost);
                return;
            }
            
            String publicIp = publicIpRecord.get().getIpAddress();
            
            // Determine record type based on IP version
            String recordType = isIPv6(publicIp) ? "AAAA" : "A";
            
            log.info("Managing public DNS record: {} -> {} (Type: {})", externalHost, publicIp, recordType);
            
            // Check if public DNS record already exists
            var existingPublicRecord = aliyunDnsService.queryDomainRecord(externalRr);
            
            if (existingPublicRecord != null) {
                // Update if IP changed or record type changed
                if (!publicIp.equals(existingPublicRecord.getValue()) || !recordType.equals(existingPublicRecord.getType())) {
                    boolean success = aliyunDnsService.updateDomainRecord(
                        existingPublicRecord.getRecordId(), externalRr, recordType, publicIp);
                    if (success) {
                        log.info("Updated public DNS record: {} -> {} (Type: {})", externalHost, publicIp, recordType);
                    } else {
                        log.error("Failed to update public DNS record for {}", externalHost);
                    }
                } else {
                    log.debug("Public DNS record {} already points to correct IP {} with type {}", externalHost, publicIp, recordType);
                }
            } else {
                // Create new public DNS record
                String recordId = aliyunDnsService.addDomainRecord(externalRr, recordType, publicIp);
                if (recordId != null) {
                    log.info("Created public DNS record: {} -> {} (Type: {}, RecordId: {})", externalHost, publicIp, recordType, recordId);
                } else {
                    log.error("Failed to create public DNS record for {}", externalHost);
                }
            }
        } catch (Exception e) {
            log.error("Failed to manage public DNS record for host: {}", host, e);
        }
    }
    
    private boolean isIPv6(String ip) {
        return ip != null && ip.contains(":");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void handleDelete(String host) {
        try {
            Optional<DnsRecord> existingRecord = dnsRecordRepository.findByHost(host);
            if (existingRecord.isPresent()) {
                DnsRecord record = existingRecord.get();
                
                // Delete APISIX route first (before database)
                String externalHost = host.replace(properties.getHostSuffix(), properties.getExternalDomainSuffix());
                apisixService.deleteRoute(externalHost);
                
                boolean dnsDeleteSuccess = aliyunDnsService.deleteDomainRecord(record.getRecordId());
                if (!dnsDeleteSuccess) {
                    log.error("Failed to delete DNS record for host {}, RecordId: {}", host, record.getRecordId());
                }
                
                // Delete from database regardless of DNS API result
                dnsRecordRepository.deleteByHost(host);
                
                if (!dnsDeleteSuccess) {
                    log.error("DNS delete failed for host {}, but database and APISIX were updated. Manual DNS cleanup may be required.", host);
                }
                
                // Delete public DNS record for external host
                deletePublicDnsRecord(host);
                
                log.info("Successfully deleted all resources for host: {}", host);
            }
        } catch (Exception e) {
            log.error("Failed to handle delete for host: {}", host, e);
        }
    }
    
    private void deletePublicDnsRecord(String host) {
        try {
            // Get external host (e.g., test-k8s.jsccb.ltd -> test-nj.jsccb.ltd)
            String externalHost = host.replace(properties.getHostSuffix(), properties.getExternalDomainSuffix());
            String externalRr = externalHost.replace("." + properties.getAliyun().getDomain(), "");
            
            // Query existing public DNS record
            var existingPublicRecord = aliyunDnsService.queryDomainRecord(externalRr);
            
            if (existingPublicRecord != null) {
                boolean success = aliyunDnsService.deleteDomainRecord(existingPublicRecord.getRecordId());
                if (success) {
                    log.info("Deleted public DNS record: {} (RecordId: {})", externalHost, existingPublicRecord.getRecordId());
                } else {
                    log.error("Failed to delete public DNS record for {} (RecordId: {})", externalHost, existingPublicRecord.getRecordId());
                }
            } else {
                log.debug("No public DNS record found for {}, nothing to delete", externalHost);
            }
        } catch (Exception e) {
            log.error("Failed to delete public DNS record for host: {}", host, e);
        }
    }

    private List<String> getNodeIps() {
        try {
            return kubernetesClient.nodes().list().getItems().stream()
                .flatMap(node -> node.getStatus().getAddresses().stream())
                .filter(addr -> "InternalIP".equals(addr.getType()))
                .map(addr -> addr.getAddress())
                .filter(ip -> !ip.startsWith("127."))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get node IPs", e);
            return Collections.emptyList();
        }
    }
}
