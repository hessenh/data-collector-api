package no.ssb.dc.api.node;

import java.util.List;

public interface BodyPublisher extends OperationPublisher {

    String BODY_PUBLISHER_RESULT = "BODY_PUBLISHER_RESULT";

    FormEncoding getEncoding();

    BodyPublisherProducer getPlainText();

    BodyPublisherProducer getUrlEncodedData();

    BodyPublisherProducer getJson();

    List<BodyPart> getParts();

}
