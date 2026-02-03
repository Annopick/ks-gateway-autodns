package io.annopick.gateway.autodns.service;

import com.aliyun.alidns20150109.Client;
import com.aliyun.alidns20150109.models.*;

import com.aliyun.credentials.models.Config;
import io.annopick.gateway.autodns.config.AutoDnsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AliyunDnsService {

    private final Client client;
    private final AutoDnsProperties properties;

    public AliyunDnsService(AutoDnsProperties properties) {
        this.properties = properties;
        try {
            Config config = new Config();
            config.setType("access_key");
            config.setAccessKeyId(properties.getAliyun().getAccessKeyId());
            config.setAccessKeySecret(properties.getAliyun().getAccessKeySecret());

            com.aliyun.credentials.Client credential = new com.aliyun.credentials.Client(config);

            com.aliyun.teaopenapi.models.Config modelConfig = new com.aliyun.teaopenapi.models.Config();
            modelConfig.setCredential(credential);
            modelConfig.setEndpoint("alidns.aliyuncs.com");

            this.client = new Client(modelConfig);
            log.info("Aliyun DNS client initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Aliyun DNS client", e);
            throw new RuntimeException("Failed to initialize Aliyun DNS client", e);
        }
    }

    public String addDomainRecord(String rr, String type, String value) {
        try {
            AddDomainRecordRequest request = new AddDomainRecordRequest()
                .setDomainName(properties.getAliyun().getDomain())
                .setRR(rr)
                .setType(type)
                .setValue(value);
            
            AddDomainRecordResponse response = client.addDomainRecord(request);
            String recordId = response.getBody().getRecordId();
            log.info("Added DNS record: {} -> {}, RecordId: {}, ResponseCode: {}", rr, value, recordId, response.getStatusCode());
            return recordId;
        } catch (Exception e) {
            log.error("Failed to add DNS record: {} -> {}", rr, value, e);
            return null;
        }
    }

    public boolean updateDomainRecord(String recordId, String rr, String type, String value) {
        try {
            UpdateDomainRecordRequest request = new UpdateDomainRecordRequest()
                .setRecordId(recordId)
                .setRR(rr)
                .setType(type)
                .setValue(value);

            UpdateDomainRecordResponse response =  client.updateDomainRecord(request);
            log.info("Updated DNS record: {} -> {}, RecordId: {}, ResponseCode: {}",
                    rr, value, recordId, response.getStatusCode());
            return true;
        } catch (Exception e) {
            log.error("Failed to update DNS record: {} -> {}, RecordId: {}", rr, value, recordId, e);
            return false;
        }
    }

    public boolean deleteDomainRecord(String recordId) {
        try {
            DeleteDomainRecordRequest request = new DeleteDomainRecordRequest()
                .setRecordId(recordId);

            DeleteDomainRecordResponse response = client.deleteDomainRecord(request);
            log.info("Deleted DNS record, RecordId: {}, RequestId:{}, ResponseCode: {}",
                    recordId,
                    response.getBody().getRequestId(),
                    response.getStatusCode());
            return true;
        } catch (Exception e) {
            log.error("Failed to delete DNS record, RecordId: {}", recordId, e);
            return false;
        }
    }

    public DescribeDomainRecordsResponseBody.DescribeDomainRecordsResponseBodyDomainRecordsRecord queryDomainRecord(String rr) {
        try {
            DescribeDomainRecordsRequest request = new DescribeDomainRecordsRequest()
                .setDomainName(properties.getAliyun().getDomain())
                .setRRKeyWord(rr);
            
            DescribeDomainRecordsResponse response = client.describeDomainRecords(request);
            List<DescribeDomainRecordsResponseBody.DescribeDomainRecordsResponseBodyDomainRecordsRecord> records = 
                response.getBody().getDomainRecords().getRecord();
            
            if (records != null && !records.isEmpty()) {
                for (DescribeDomainRecordsResponseBody.DescribeDomainRecordsResponseBodyDomainRecordsRecord record : records) {
                    if (record.getRR().equals(rr)) {
                        return record;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to query DNS record: {}", rr, e);
            return null;
        }
    }
}
