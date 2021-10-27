package no.ssb.dc.api.el;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.ssb.dapla.secrets.api.SecretManagerClient;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class Gsm {

    private final static Logger LOG = LoggerFactory.getLogger(Gsm.class);
    // Access the payload for the given secret version if one exists. The version
    // can be a version number as a string (e.g. "5") or an alias (e.g. "latest").
    public String access(String secretProvider, String secretId, String projectId, String saPath)
            throws IOException {
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



