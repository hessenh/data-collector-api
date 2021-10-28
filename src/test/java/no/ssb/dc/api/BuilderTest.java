package no.ssb.dc.api;

import no.ssb.dc.api.context.ExecutionContext;
import no.ssb.dc.api.node.FlowContext;
import no.ssb.dc.api.node.builder.GetBuilder;
import no.ssb.dc.api.node.builder.NodeBuilder;
import no.ssb.dc.api.node.builder.PaginateBuilder;
import no.ssb.dc.api.node.builder.SpecificationBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static no.ssb.dc.api.Builders.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BuilderTest {

    static final SpecificationBuilder SPECIFICATION_BUILDER = Specification.start("test", "name of flow", "getstartposition")
            .configure(context()
                    .variable("foo", "bar")
                    .header("accept", "application/xml")
                    .globalState("key", "value")
            )
            .configure(security()
                    .sslBundleName("ske-test-certs")
                    .identity(jwt("test-identity", headerClaims().alg("alg").x509CertChain("ske-test-certs"), claims().claim("foo", "bar")))
            )
            .function(get("getstartposition")
                    .url("http://com.company/getstartposition")
                    .pipe(process(A.class).output("next-position"))
                    .pipe(process(B.class).output("another-position").output("yet-another"))
                    .pipe(execute("page-loop").requiredInput("next-position"))
            )
            .function(paginate("page-loop")
                    .variable("from-position", "${next-position}")
                    .iterate(execute("page"))
                    .addPageContent("from-position")
                    .prefetchThreshold(5)
                    .until(whenVariableIsNull("next-position"))
            )
            .function(post("authorize")
                    .url("http://com.company/authorize")
                    .header("Content-Type","application/x-www-form-urlencoded")
                    .data(bodyPublisher()
                            .plainText("PLAIN_TEXT")
                            .urlEncoded("username=user&password=pass")
                            .textPart("foo", "bar")
                            .formPart("foo", "file", "bar")
                    )
            )
            .function(post("authorize1")
                    .url("http://com.company/authorize")
                    .header("Authorization", "Bearer abc")
                    .header("Content-Type", "application/json")
                    .data(bodyPublisher()
                            .json("{json:\"json\"}")
                    )
            )
            .function(put("create-something")
                    .url("http://com.company/authorize")
                    .data(bodyPublisher()
                            .plainText("PLAIN_TEXT")
                            .urlEncoded("foo=bar")
                            .textPart("foo", "bar")
                            .formPart("foo", "file", "bar")

                    )
            )
            .function(post("jwt-token")
                    .url("http://com.company/auth")
                    .data(bodyPublisher()
                            .urlEncoded(jwtToken().identityId("test-identity").bindTo("JWT_GRANT").token("grant=${GRANT_TYPE"))
                    )
            )
            .function(get("page")
                    .url("http://com.company/endpoint?seq=${from-position}&pageSize=10")
                    // build expected position list
                    .validate(status().success(200))
                    .pipe(sequence(xpath("/feed/entry"))
                            .expected(xpath("/entry/content/ns2:lagretHendelse/ns2:sekvensnummer"))
                    )
                    // propagate next position to paginate
                    .pipe(nextPage().output("next-position", regex(xpath("/feed/link[@rel=\"next\"]/@href"), "(?<=[?&]position=)[^&]*")))
                    // parallel should take the sequence as input
                    .pipe(parallel(xpath("/feed/entry"))
                            .variable("position", xpath("/entry/content/ns2:lagretHendelse/ns2:sekvensnummer"))
                            .pipe(console())
                            .pipe(execute("person-doc")
                                    .inputVariable("person-id", xpath("/entry/content/ns2:lagretHendelse/ns2:hendelse/ns2:persondokument"))
                                    .requiredInput("person-id-blash")
                                    .inputVariable("person-id2", xpath("/entry/content/ns2:lagretHendelse/ns2:hendelse/ns2:persondokument"))
                            )
                            .pipe(execute("event-doc")
                                    .inputVariable("event-id", xpath("/entry/content/ns2:lagretHendelse/ns2:hendelse/ns2:hendelsesdokument"))
                            )
                            .pipe(execute("event-doc-404-error")
                                    .inputVariable("event-id", xpath("/entry/content/ns2:lagretHendelse/ns2:hendelse/ns2:hendelsesdokument"))
                            )
                            .pipe(forEach(jqpath(".array[]"))
                                    .pipe(execute("person-doc")
                                            .inputVariable("rawResponseBody", body())
                                    )
                            )
                            // publish completed position. Sequencing should occur in core
                            .pipe(publish("${position}"))
                    )
                    .pipe(process(ItemList.class).output("next-position")) // alternative to sequence() and parallel()
            )
            .function(delete("delete-something")
                    .url("http://com.company/resource/1")
            )
            .function(get("person-doc")
                    .url("http://com.company/endpoint/person/${person-id}}")
                    .pipe(addContent("${position}", "entry")
                            .storeState("stateString", "stateValue")
                            .storeState("stateInt", 10)
                            .storeState("stateBoolean", true)
                    )
                    .pipe(process(Processor.class).output("person-id"))
            )
            .function(get("event-doc")
                    .url("http://com.company/endpoint/event/${event-id}}")
                    .pipe(process(Processor.class).output("event-id"))
            )
            .function(get("event-doc-404-error")
                    .url("http://com.company/endpoint/event/${event-id}}?404withResponseError")
                    .validate(status().success(200).success(404, bodyContains(jqpath(".kode"), "SP-002")))
                    .pipe(process(Processor.class).output("event-id"))
            )
            ;

    @Disabled
    @Test
    public void printExecutionPlan() {
        System.out.printf("Execution-plan:%n%n%s%n", SPECIFICATION_BUILDER.end().startFunction().toPrintableExecutionPlan());
    }

    @Test
    public void thatFlowBuilderIsSerializedThenDeserialized() {
        SpecificationBuilder actual = SPECIFICATION_BUILDER;
        FlowContext actualFlowContext = actual.end().configurations.flowContext();
        String serialized = actual.serialize();
        assertNotNull(serialized);
        System.out.printf("serialized:%n%s%n", serialized);

        SpecificationBuilder deserialized = Specification.deserialize(serialized, SpecificationBuilder.class);
        assertNotNull(deserialized);
        //System.out.printf("deserialized:%n%s%n", deserialized.serialize());

        System.out.printf("deserialized:%n%s%n", deserialized.serialize());
        assertEquals(actual, deserialized);
        assertEquals(serialized, deserialized.serialize());

        Specification end = deserialized.end();
        FlowContext derserializedFlowContext = actual.end().configurations.flowContext();
        assertEquals(actualFlowContext, derserializedFlowContext);
    }

    @Test
    public void thatGettingStartedBuilderIsSerializedThenDeserialized() {
        NodeBuilder actual = SPECIFICATION_BUILDER.get("getstartposition");
        String serialized = actual.serialize();
        assertNotNull(serialized);

        GetBuilder deserialized = Specification.deserialize(serialized, GetBuilder.class);
        assertNotNull(deserialized);

        assertEquals(actual, deserialized);
    }

    @Test
    public void thatPageLoopBuilderIsSerializedThenDeserialized() {
        NodeBuilder actual = SPECIFICATION_BUILDER.get("page-loop");
        String serialized = actual.serialize();
        assertNotNull(serialized);

        PaginateBuilder deserialized = Specification.deserialize(serialized, PaginateBuilder.class);
        assertNotNull(deserialized);

        assertEquals(actual, deserialized);
    }

    @Test
    public void thatPageGetBuilderIsSerializedThenDeserialized() {
        NodeBuilder actual = SPECIFICATION_BUILDER.get("page");
        String serialized = actual.serialize();
        assertNotNull(serialized);

        GetBuilder deserialized = Specification.deserialize(serialized, GetBuilder.class);
        assertNotNull(deserialized);

        assertEquals(actual, deserialized);
    }

    @Test
    public void thatPersonDocGetBuilderIsSerializedThenDeserialized() {
        NodeBuilder actual = SPECIFICATION_BUILDER.get("person-doc");
        String serialized = actual.serialize();
        assertNotNull(serialized);

        GetBuilder deserialized = Specification.deserialize(serialized, GetBuilder.class);
        assertNotNull(deserialized);

        assertEquals(actual, deserialized);
    }

    @Test
    public void thatEventDocGetBuilderIsSerializedThenDeserialized() {
        NodeBuilder actual = SPECIFICATION_BUILDER.get("event-doc");
        String serialized = actual.serialize();
        assertNotNull(serialized);

        GetBuilder deserialized = Specification.deserialize(serialized, GetBuilder.class);
        assertNotNull(deserialized);

        assertEquals(actual, deserialized);
    }

    static class A implements Processor {
        @Override
        public ExecutionContext process(ExecutionContext input) {
            return null;
        }
    }

    static class B implements Processor {
        @Override
        public ExecutionContext process(ExecutionContext input) {
            return null;
        }
    }

}
