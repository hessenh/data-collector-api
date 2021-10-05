package no.ssb.dc.api.node.builder;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import no.ssb.dc.api.Processor;
import no.ssb.dc.api.Specification;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * TODO Rewrite to a generic deserializer
 */
public class NodeBuilderDeserializer extends StdDeserializer<AbstractBuilder> {

    public NodeBuilderDeserializer() {
        this(null);
    }

    protected NodeBuilderDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public AbstractBuilder deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
        JsonNode node = jsonParser.getCodec().readTree(jsonParser);
        return handleNodeBuilder(0, context, new LinkedList<>(), null, node);
    }

    private String nodePath(Deque<JsonNode> ancestors) {
        return "/" + ancestors.stream().filter(node -> node != null).map(node -> {
            String id = node.has("id") ? node.get("id").textValue() : null;
            String type = node.has("type") ? node.get("type").textValue() : null;
            return String.format("%s%s", type, id == null ? "" : String.format("[%s]", id));
        }).collect(Collectors.joining("/"));
    }

    private AbstractBuilder handleNodeBuilder(int depth, DeserializationContext context, Deque<JsonNode> ancestors,
                                              JsonNode previousNode, JsonNode currentNode) {
        if (currentNode == null || !currentNode.has("type")) {
            throw new RuntimeException(String.format("Error resolving node: %s => %s", nodePath(ancestors), ancestors.peekLast()));
        }
        BuilderType type = BuilderType.parse(currentNode.get("type").textValue());

        ancestors.add(previousNode);
        AbstractBuilder nextNodeBuilder = buildNodeBuilder(depth, context, ancestors, currentNode, type);
        ancestors.remove(previousNode);
        return nextNodeBuilder;
    }

    private AbstractBuilder buildNodeBuilder(int depth, DeserializationContext context, Deque<JsonNode> ancestors, JsonNode currentNode, BuilderType type) {
        Objects.requireNonNull(type);
        switch (type) {
            case Specification: {
                SpecificationBuilder builder = Specification.start(
                        currentNode.get("id").textValue(),
                        currentNode.get("name").textValue(),
                        currentNode.get("startFunctionId").textValue()
                );

                JsonNode configureNode = currentNode.get("configure");
                if (configureNode != null) {
                    for (JsonNode configurationNode : configureNode) {
                        ConfigurationBuilder configurationBuilder = (ConfigurationBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, configurationNode);
                        builder.configure(configurationBuilder);
                    }
                }

                currentNode.get("functions").fields().forEachRemaining(entry ->
                        builder.function((NodeWithIdBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, entry.getValue())));

                return builder;
            }

            case SpecificationContext: {
                SpecificationContextBuilder builder = new SpecificationContextBuilder();

                JsonNode headerNode = currentNode.get("headers");
                if (headerNode != null) {
                    headerNode.fields().forEachRemaining(entry -> {
                        entry.getValue().forEach(headerValueNode -> builder.header(entry.getKey(), headerValueNode.textValue()));
                    });
                }

                JsonNode variableNode = currentNode.get("variables");
                if (variableNode != null) {
                    variableNode.fields().forEachRemaining(entry -> {
                        Object value;
                        if (entry.getValue().isInt()) {
                            value = entry.getValue().intValue();
                        } else if (entry.getValue().isBoolean()) {
                            value = entry.getValue().booleanValue();
                        } else if (entry.getValue().isTextual()) {
                            value = entry.getValue().textValue();
                        } else if (entry.getValue().isNull()) {
                            value = null;
                        } else {
                            throw new IllegalArgumentException("The context variable type is not supported: " + entry.getKey());
                        }
                        builder.variable(entry.getKey(), value);
                    });
                }

                JsonNode globalStateNode = currentNode.get("globalState");
                if (globalStateNode != null) {
                    globalStateNode.fields().forEachRemaining(entry -> {
                        builder.globalState(entry.getKey(), entry.getValue().textValue());
                    });
                }

                return builder;
            }

            case Security: {
                SecurityBuilder builder = new SecurityBuilder();

                JsonNode sslBundleNameNode = currentNode.get("bundleName");
                if (sslBundleNameNode != null) {
                    builder.sslBundleName(sslBundleNameNode.textValue());
                }

                ArrayNode identityNodes = (ArrayNode) currentNode.get("identities");
                if (identityNodes != null) {
                    identityNodes.forEach(identityNode -> {
                        builder.identity((IdentityBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, identityNode));
                    });
                }

                return builder;
            }

            case JwtIdentity: {
                String id = currentNode.get("id").textValue();

                JwtHeaderClaims headerClaims = new JwtHeaderClaims();
                JsonNode headerClaimsNode = currentNode.get("headerClaims");
                if (headerClaimsNode != null) {
                    headerClaimsNode.fields().forEachRemaining(entry -> {
                        String value = entry.getValue() == null ? null : entry.getValue().textValue(); // todo only support string values
                        headerClaims.headerClaims.put(entry.getKey(), value);
                    });
                }

                JwtClaims claims = new JwtClaims();
                JsonNode claimsNode = currentNode.get("claims");
                if (claimsNode != null) {
                    claimsNode.fields().forEachRemaining(entry -> {
                        String value = entry.getValue() == null ? null : entry.getValue().textValue(); // todo only support string values
                        claims.claims.put(entry.getKey(), value);
                    });
                }

                IdentityBuilder builder = new JwtIdentityBuilder(id, headerClaims, claims);

                return builder;
            }

            case Paginate: {
                PaginateBuilder builder = new PaginateBuilder(currentNode.get("id").textValue());

                currentNode.get("variables").fields().forEachRemaining(entry -> builder.variable(entry.getKey(), entry.getValue().textValue()));

                if (currentNode.has("addPageContent") && currentNode.get("addPageContent").asBoolean()) {
                    builder.addPageContent(currentNode.get("positionVariable").textValue());
                }

                currentNode.get("iterate").forEach(child -> builder.iterate((ExecuteBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, child)));

                JsonNode thresholdNode = currentNode.get("threshold");
                builder.prefetchThreshold(thresholdNode.asInt());

                JsonNode conditionBuilderNode = currentNode.get("until");
                builder.until((ConditionBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, conditionBuilderNode));

                return builder;
            }

            case Sequence: {
                SequenceBuilder builder = new SequenceBuilder(null);

                QueryBuilder splitBuilder = (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, currentNode.get("splitQuery"));
                builder.splitBuilder(splitBuilder);

                QueryBuilder expectedBuilder = (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, currentNode.get("expectedQuery"));
                builder.expected(expectedBuilder);

                return builder;
            }

            case NextPage: {
                NextPageBuilder builder = new NextPageBuilder();

                currentNode.get("outputs").fields().forEachRemaining(entry ->
                        builder.output(
                                entry.getKey(),
                                (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, entry.getValue())
                        )
                );

                return builder;
            }

            case Parallel: {
                QueryBuilder splitBuilder = (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, currentNode.get("splitQuery"));
                ParallelBuilder builder = new ParallelBuilder(splitBuilder);

                currentNode.get("variables").fields().forEachRemaining(entry ->
                        builder.variable(entry.getKey(), (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, entry.getValue())));

                JsonNode stepsNode = currentNode.get("pipes");
                if (stepsNode != null) {
                    stepsNode.forEach(stepNode -> builder.pipe((NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, stepNode)));
                }

                JsonNode publishJsonNode = currentNode.get("publish");
                if (publishJsonNode != null) {
                    PublishBuilder publishBuilder = (PublishBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, publishJsonNode);
                    builder.publish(publishBuilder);
                }

                return builder;
            }

            case ForEach: {
                JsonNode splitQueryNode = currentNode.get("splitQuery");
                QueryBuilder splitQueryBuilder = (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, splitQueryNode);
                ForEachBuilder builder = new ForEachBuilder(splitQueryBuilder);
                if (currentNode.has("pipes")) {
                    JsonNode pipeNodes = currentNode.get("pipes");
                    pipeNodes.forEach(pipeNode -> {
                        NodeBuilder pipeBuilder = (NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, pipeNode);
                        builder.pipe(pipeBuilder);
                    });
                }
                return builder;
            }

            case Execute: {
                ExecuteBuilder builder = new ExecuteBuilder(currentNode.get("executeId").textValue());

                if (currentNode.has("requiredInputs")) {
                    currentNode.get("requiredInputs").forEach(requiredInput -> builder.requiredInput(requiredInput.textValue()));
                }

                if (currentNode.has("inputVariables")) {
                    currentNode.get("inputVariables").fields().forEachRemaining(entry ->
                            builder.inputVariable(entry.getKey(), (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, entry.getValue())));
                }
                return builder;
            }

            case Process: {
                try {
                    ProcessBuilder builder = new ProcessBuilder((Class<? extends Processor>) Class.forName(currentNode.get("processorClass").textValue()));
                    currentNode.get("requiredOutputs").forEach(output -> builder.output(output.textValue()));
                    return builder;
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }

            case Console: {
                return new ConsoleBuilder();
            }

            case QueryBody: {
                return new BodyBuilder();
            }

            case QueryEval: {
                QueryBuilder queryBuilder = currentNode.has("query") ? (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, currentNode.get("query")) : null;
                String bindToVariableNode = currentNode.has("bindToVariable") ? currentNode.get("bindToVariable").textValue() : null;
                String expression = currentNode.get("expression").textValue();
                return new EvalBuilder(queryBuilder, bindToVariableNode, expression);
            }

            case QueryXPath: {
                return new XPathBuilder(currentNode.get("expression").textValue());
            }

            case QueryJsonToken: {
                return new JsonTokenBuilder();
            }

            case QueryXmlToken: {
                return new XmlTokenBuilder();
            }

            case QueryJqPath: {
                return new JqPathBuilder(currentNode.get("expression").textValue());
            }

            case QueryRegEx: {
                return new RegExBuilder((QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, currentNode.get("query")), currentNode.get("expression").textValue());
            }

            case ConditionWhenVariableIsNull: {
                WhenVariableIsNullBuilder builder = new WhenVariableIsNullBuilder();
                builder.identifier(currentNode.get("identifier").textValue());
                return builder;
            }

            case ConditionWhenExpressionIsTrue: {
                WhenExpressionIsTrueBuilder builder = new WhenExpressionIsTrueBuilder();
                builder.identifier(currentNode.get("identifier").textValue());
                return builder;
            }

            case AddContent: {
                AddContentBuilder builder = new AddContentBuilder(currentNode.get("positionVariableExpression").textValue(), currentNode.get("contentKey").textValue());
                JsonNode stateNode = currentNode.get("state");
                if (stateNode != null) {

                    stateNode.fields().forEachRemaining(entry -> {
                        JsonNode value = entry.getValue();
                        Object stateValue;
                        if (value.isInt()) {
                            stateValue = value.intValue();
                        } else if (value.isBoolean()) {
                            stateValue = value.booleanValue();
                        } else if (value.isTextual()) {
                            stateValue = value.textValue();
                        } else {
                            throw new IllegalArgumentException("The content state type is not supported: " + entry.getKey());
                        }
                        builder.storeState(entry.getKey(), stateValue);
                    });
                }
                return builder;
            }

            case Publish: {
                return new PublishBuilder(currentNode.get("positionVariableExpression").textValue());
            }

            case Get: {
                GetBuilder builder = new GetBuilder(currentNode.get("id").textValue());

                builder.url(currentNode.get("url").textValue());

                JsonNode requestHeaders = currentNode.get("requestHeaders");
                if (requestHeaders != null) {
                    requestHeaders.fieldNames().forEachRemaining(headerName -> {
                        ArrayNode values = (ArrayNode) requestHeaders.get(headerName);
                        for (int i = 0; i < values.size(); i++) {
                            JsonNode value = values.get(i);
                            String headerValue;
                            if (value.isInt()) {
                                headerValue = String.valueOf(value.intValue());
                            } else if (value.isBoolean()) {
                                headerValue = Boolean.toString(value.booleanValue());
                            } else if (value.isTextual()) {
                                headerValue = value.textValue();
                            } else {
                                throw new IllegalArgumentException("The context variable type is not supported: " + headerName);
                            }
                            builder.header(headerName, headerValue);
                        }
                    });
                }

                JsonNode retryWhileNodeArray = currentNode.get("retryWhile");
                if (retryWhileNodeArray != null) {
                    retryWhileNodeArray.forEach(retryWhileNode -> {
                        LeafNodeBuilder retryWhileBuilder = (LeafNodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, retryWhileNode);
                        builder.retryWhile(retryWhileBuilder);
                    });
                }

                JsonNode validateResponseNode = currentNode.get("responseValidators");
                if (validateResponseNode != null) {
                    validateResponseNode.forEach(validatorNode -> {
                        LeafNodeBuilder validatorBuilder = (LeafNodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, validatorNode);
                        builder.validate(validatorBuilder);
                    });
                }

                ArrayNode returnVariablesNode = (ArrayNode) currentNode.get("returnVariables");
                if (returnVariablesNode != null) {
                    returnVariablesNode.forEach(node -> builder.returnVariables(node.textValue()));
                }

                JsonNode stepsNode = currentNode.get("pipes");
                if (stepsNode != null) {
                    stepsNode.forEach(stepNode -> builder.pipe((NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, stepNode)));
                }

                return builder;
            }

            case Post: {
                PostBuilder builder = new PostBuilder(currentNode.get("id").textValue());

                builder.url(currentNode.get("url").textValue());

                JsonNode bodyPublisherNode = currentNode.get("bodyPublisher");
                if (bodyPublisherNode != null) {
                    BodyPublisherBuilder bodyPublisherBuilder = (BodyPublisherBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, bodyPublisherNode);
                    builder.data(bodyPublisherBuilder);
                }

                JsonNode validateResponseNode = currentNode.get("responseValidators");
                if (validateResponseNode != null) {
                    validateResponseNode.forEach(validatorNode -> {
                        LeafNodeBuilder validatorBuilder = (LeafNodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, validatorNode);
                        builder.validate(validatorBuilder);
                    });
                }

                ArrayNode returnVariablesNode = (ArrayNode) currentNode.get("returnVariables");
                if (returnVariablesNode != null) {
                    returnVariablesNode.forEach(node -> builder.returnVariables(node.textValue()));
                }

                JsonNode stepsNode = currentNode.get("pipes");
                if (stepsNode != null) {
                    stepsNode.forEach(stepNode -> builder.pipe((NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, stepNode)));
                }

                return builder;
            }

            case Put: {
                PutBuilder builder = new PutBuilder(currentNode.get("id").textValue());

                builder.url(currentNode.get("url").textValue());

                JsonNode bodyPublisherNode = currentNode.get("bodyPublisher");
                if (bodyPublisherNode != null) {
                    BodyPublisherBuilder bodyPublisherBuilder = (BodyPublisherBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, bodyPublisherNode);
                    builder.data(bodyPublisherBuilder);
                }

                JsonNode validateResponseNode = currentNode.get("responseValidators");
                if (validateResponseNode != null) {
                    validateResponseNode.forEach(validatorNode -> {
                        LeafNodeBuilder validatorBuilder = (LeafNodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, validatorNode);
                        builder.validate(validatorBuilder);
                    });
                }

                ArrayNode returnVariablesNode = (ArrayNode) currentNode.get("returnVariables");
                if (returnVariablesNode != null) {
                    returnVariablesNode.forEach(node -> builder.returnVariables(node.textValue()));
                }

                JsonNode stepsNode = currentNode.get("pipes");
                if (stepsNode != null) {
                    stepsNode.forEach(stepNode -> builder.pipe((NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, stepNode)));
                }

                return builder;
            }

            case Delete: {
                DeleteBuilder builder = new DeleteBuilder(currentNode.get("id").textValue());

                builder.url(currentNode.get("url").textValue());

                JsonNode validateResponseNode = currentNode.get("responseValidators");
                if (validateResponseNode != null) {
                    validateResponseNode.forEach(validatorNode -> {
                        LeafNodeBuilder validatorBuilder = (LeafNodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, validatorNode);
                        builder.validate(validatorBuilder);
                    });
                }

                ArrayNode returnVariablesNode = (ArrayNode) currentNode.get("returnVariables");
                if (returnVariablesNode != null) {
                    returnVariablesNode.forEach(node -> builder.returnVariables(node.textValue()));
                }

                JsonNode stepsNode = currentNode.get("pipes");
                if (stepsNode != null) {
                    stepsNode.forEach(stepNode -> builder.pipe((NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, stepNode)));
                }

                return builder;
            }

            case BodyPublisher: {
                BodyPublisherBuilder builder = new BodyPublisherBuilder();

                JsonNode plainTextDataNode = currentNode.get("plainTextData");
                if (plainTextDataNode != null) {
                    BodyPublisherProducerBuilder bodyPublisherProducerBuilder =
                            (BodyPublisherProducerBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, plainTextDataNode);
                    builder.plainText(bodyPublisherProducerBuilder);
                }

                JsonNode jsonBodyNode = currentNode.get("json");
                if (plainTextDataNode != null) {
                    BodyPublisherProducerBuilder bodyPublisherProducerBuilder =
                            (BodyPublisherProducerBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, plainTextDataNode);
                    builder.json(bodyPublisherProducerBuilder);
                }

                JsonNode urlEncodedDataNode = currentNode.get("urlEncodedData");
                if (urlEncodedDataNode != null) {
                    BodyPublisherProducerBuilder bodyPublisherProducerBuilder =
                            (BodyPublisherProducerBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, urlEncodedDataNode);
                    builder.urlEncoded(bodyPublisherProducerBuilder);
                }

                // TODO add MultiPartFormData
                JsonNode partsData = currentNode.get("partsData");
                if (partsData != null) {
                    partsData.forEach(partNode -> {
                        String name = partNode.get("name").textValue();
                        String filename = Optional.ofNullable(partNode.get("filename")).map(JsonNode::textValue).orElse(null);
                        Charset charset = Optional.ofNullable(partNode.get("charset")).map(node -> Charset.forName(node.textValue())).orElse(StandardCharsets.UTF_8); // TODO add bodypart charset methods

                        JsonNode valueNode = partNode.get("value");
                        if (filename == null) {
                            builder.textPart(name, valueNode.textValue());
                        } else {
                            if (valueNode.isTextual()) {
                                builder.formPart(name, filename, valueNode.textValue());
                            } else {
                                try {
                                    byte[] bytes = valueNode.binaryValue();
                                    builder.formPart(name, filename, bytes);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    });
                }

                return builder;
            }


            case StringBodyPublisherProducer: {
                String data = currentNode.get("data").textValue();
                StringBodyPublisherProducerBuilder builder = new StringBodyPublisherProducerBuilder(data);
                return builder;
            }

            case JwtIdentityTokenBodyPublisherProducer: {
                String identityId = currentNode.get("identityId").textValue();
                String bindTo = currentNode.get("bindTo").textValue();
                String token = currentNode.get("token").textValue();
                JwtIdentityTokenBodyPublisherProducerBuilder builder = new JwtIdentityTokenBodyPublisherProducerBuilder();
                builder.identityId(identityId);
                builder.bindTo(bindTo);
                builder.token(token);
                return builder;
            }

            case HttpStatusRetryWhile: {
                HttpStatusRetryWhileBuilder builder = new HttpStatusRetryWhileBuilder();

                Integer statusCode = Integer.parseInt(currentNode.get("statusCode").asText());
                TimeUnit duration = TimeUnit.valueOf(currentNode.get("duration").asText());
                int amount = Integer.parseInt(currentNode.get("amount").asText());

                builder.is(statusCode, duration, amount);
                return builder;
            }

            case HttpStatusValidation: {
                HttpStatusValidationBuilder builder = new HttpStatusValidationBuilder();

                JsonNode successNode = currentNode.get("success");
                // {"type":"HttpStatusValidation","success":{"200":[],"404":[{"type":"HttpResponseBodyContains","queryBuilder":{"type":"QueryJqPath","expression":".kode"},"equalToStringLiteral":"SP-002"}]}}
                for (Iterator<Map.Entry<String, JsonNode>> it = successNode.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> elementNode = it.next();

                    int statusCode = Integer.parseInt(elementNode.getKey());

                    builder.success(statusCode);
                    elementNode.getValue().forEach(responsePredicateNode -> {
                        ResponsePredicateBuilder responsePredicateBuilder = (ResponsePredicateBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, responsePredicateNode);
                        builder.success(statusCode, responsePredicateBuilder);
                    });
                }

                JsonNode failedNode = currentNode.get("failed");
                if (failedNode != null) {
                    failedNode.forEach(code -> builder.fail(code.intValue()));
                }

                return builder;
            }

            case HttpResponseBodyContains: {
                QueryBuilder queryBuilder = (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, currentNode.get("queryBuilder"));
                String equalToStringLiteral = currentNode.get("equalToStringLiteral").asText();
                return new BodyContainsBuilder(queryBuilder, equalToStringLiteral);
            }

            default: {
                throw new UnsupportedOperationException("NodeBuilder type '" + type + "' NOT supported!");
            }
        }

    }

}