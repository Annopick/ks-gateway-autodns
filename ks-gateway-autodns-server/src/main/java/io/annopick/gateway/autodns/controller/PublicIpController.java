package io.annopick.gateway.autodns.controller;

import io.annopick.gateway.autodns.config.AutoDnsProperties;
import io.annopick.gateway.autodns.model.DnsRecord;
import io.annopick.gateway.autodns.model.PublicIpRecord;
import io.annopick.gateway.autodns.repository.DnsRecordRepository;
import io.annopick.gateway.autodns.repository.PublicIpRecordRepository;
import io.annopick.gateway.autodns.service.AliyunDnsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PublicIpController {

    private final PublicIpRecordRepository publicIpRecordRepository;
    private final DnsRecordRepository dnsRecordRepository;
    private final AliyunDnsService aliyunDnsService;
    private final AutoDnsProperties properties;

    @PostMapping("/public-ip")
    public ResponseEntity<String> reportPublicIp(
            @RequestHeader("X-API-Token") String apiToken,
            @RequestBody PublicIpRequest request) {
        
        if (!properties.getSecurity().getApiToken().equals(apiToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API token");
        }

        try {
            Optional<PublicIpRecord> existingRecord = publicIpRecordRepository.findByIdentifier("default");
            
            if (existingRecord.isPresent()) {
                PublicIpRecord record = existingRecord.get();
                if (!record.getIpAddress().equals(request.getIpAddress())) {
                    log.info("Public IP changed from {} to {}", record.getIpAddress(), request.getIpAddress());
                    record.setIpAddress(request.getIpAddress());
                    publicIpRecordRepository.save(record);
                    
                    updateExternalDnsRecords(request.getIpAddress());
                }
            } else {
                PublicIpRecord record = new PublicIpRecord();
                record.setIdentifier("default");
                record.setIpAddress(request.getIpAddress());
                publicIpRecordRepository.save(record);
                
                updateExternalDnsRecords(request.getIpAddress());
            }

            return ResponseEntity.ok("Public IP updated successfully");
        } catch (Exception e) {
            log.error("Failed to update public IP", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update public IP");
        }
    }

    private void updateExternalDnsRecords(String ipAddress) throws Exception {
        List<DnsRecord> allRecords = dnsRecordRepository.findAll();
        
        for (DnsRecord record : allRecords) {
            String externalHost = record.getHost().replace(properties.getHostSuffix(), properties.getExternalDomainSuffix());
            String externalRr = externalHost.replace("." + properties.getAliyun().getDomain(), "");
            
            var existingDnsRecord = aliyunDnsService.queryDomainRecord(externalRr);
            if (existingDnsRecord != null) {
                String recordType = ipAddress.contains(":") ? "AAAA" : "A";
                aliyunDnsService.updateDomainRecord(existingDnsRecord.getRecordId(), externalRr, recordType, ipAddress);
            } else {
                String recordType = ipAddress.contains(":") ? "AAAA" : "A";
                aliyunDnsService.addDomainRecord(externalRr, recordType, ipAddress);
            }
            
            log.info("Updated external DNS record: {} -> {}", externalHost, ipAddress);
        }
    }

    @Data
    public static class PublicIpRequest {
        private String ipAddress;
    }
}
