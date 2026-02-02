package io.annopick.gateway.autodns.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "autodns")
public class AutoDnsProperties {

    private String ingressClassPrefix = "kubesphere-router-namespace-";
    private String hostSuffix = "-k8s.jsccb.ltd";
    private String kubesphereNamespace = "kubesphere-controls-system";
    private String ingressClassSuffix = "-namespace";
    private String externalDomainSuffix = "-nj.jsccb.ltd";
    
    private AliyunConfig aliyun = new AliyunConfig();
    private ApisixConfig apisix = new ApisixConfig();
    private SecurityConfig security = new SecurityConfig();
    private DatabaseConfig database = new DatabaseConfig();

    @Data
    public static class AliyunConfig {
        private String accessKeyId;
        private String accessKeySecret;
        private String domain = "jsccb.ltd";
        private String regionId = "cn-hangzhou";
    }

    @Data
    public static class ApisixConfig {
        private String adminUrl;
        private String adminKey;
    }

    @Data
    public static class SecurityConfig {
        private String apiToken;
    }

    @Data
    public static class DatabaseConfig {
        private int recordRetentionDays = 365;
    }
}
