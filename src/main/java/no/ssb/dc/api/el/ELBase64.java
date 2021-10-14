package no.ssb.dc.api.el;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

public class ELBase64 {

    private final static Logger LOG = LoggerFactory.getLogger(ELBase64.class);

    public String encode(String originalString) {
        return Base64.getEncoder().encodeToString(originalString.getBytes());
    }
}
