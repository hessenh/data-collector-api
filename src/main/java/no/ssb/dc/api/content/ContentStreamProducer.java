package no.ssb.dc.api.content;

import no.ssb.rawdata.api.RawdataMessage;

import java.util.List;

public interface ContentStreamProducer extends AutoCloseable {

    ContentStreamBuffer.Builder builder();

    RawdataMessage copy(ContentStreamBuffer buffer);

    RawdataMessage produce(ContentStreamBuffer.Builder bufferBuilder);

    default void publishBuilders(ContentStreamBuffer.Builder... builders) throws ClosedContentStreamException {
        for (ContentStreamBuffer.Builder builder : builders) {
            publish(produce(builder));
        }
    }

    default void publish(List<RawdataMessage> rawdataMessages) throws ClosedContentStreamException {
        publish(rawdataMessages.toArray(new RawdataMessage[rawdataMessages.size()]));
    }

    void publish(RawdataMessage... rawdataMessages);

}
