package no.ssb.dc.api.el;

import no.ssb.dapla.secrets.api.SecretManagerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class Secret {

    private final Logger LOG = LoggerFactory.getLogger(Secret.class);

    public String accessEncode(String secretProvider, String clientId, String secretId, String projectId, String saPath) {


        if ("google-secret-manager".equals(secretProvider)) {
            Map<String, String> secretProviderMap = new LinkedHashMap<>();
            secretProviderMap.put("secrets.provider", secretProvider);
            secretProviderMap.put("secrets.project-id", projectId);
            if (!saPath.contains("ENGINE") ) {
                secretProviderMap.put("secrets.service-account-key-path", saPath);
            }
            try {
                SecretManagerClient secretManagerClient = SecretManagerClient.create(secretProviderMap);
                String secret = secretManagerClient.readString(secretId);
                return Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes());
            } finally {
            }
        } else {
            return Base64.getEncoder().encodeToString((clientId + ":" + secretId).getBytes());
        }
    }
}


