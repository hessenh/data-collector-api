package no.ssb.dc.api.el;

import no.ssb.dapla.secrets.api.SecretManagerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class Gsm {

    private final static Logger LOG = LoggerFactory.getLogger(Gsm.class);

    public String access(String secretProvider, String secretId, String projectId, String saPath) {
        if ("google-secret-manager".equals(secretProvider)) {
            Map<String, String> secretProviderMap = new LinkedHashMap<>();
            secretProviderMap.put("secrets.provider",secretProvider);
            secretProviderMap.put("secrets.project-id",projectId);
            if(saPath != "ENGINE") {
                secretProviderMap.put("secrets.service-account-key-path",saPath);
            }
            try {
                SecretManagerClient secretManagerClient = SecretManagerClient.create(secretProviderMap);
                String secret = secretManagerClient.readString(secretId);
                return secret;
            } finally {
            }
        } else {
            return secretId;
        }
    }
}



