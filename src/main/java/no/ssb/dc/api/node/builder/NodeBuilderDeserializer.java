package no.ssb.dc.api.node.builder;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import no.ssb.dc.api.Processor;
import no.ssb.dc.api.Specification;

import java.io.IOException;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
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
                        } else {
                            throw new IllegalArgumentException("The context variable type is not supoorted: " + entry.getKey());
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
                stepsNode.forEach(stepNode -> builder.pipe((NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, stepNode)));

                JsonNode publishJsonNode = currentNode.get("publish");
                if (publishJsonNode != null) {
                    PublishBuilder publishBuilder = (PublishBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, publishJsonNode);
                    builder.publish(publishBuilder);
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

            case QueryEval: {
                QueryBuilder queryBuilder = (QueryBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, currentNode.get("query"));
                String bindToVariableNode = currentNode.get("bindToVariable").textValue();
                String expression = currentNode.get("expression").textValue();
                return new EvalBuilder(queryBuilder, bindToVariableNode, expression);
            }

            case QueryXPath: {
                return new XPathBuilder(currentNode.get("expression").textValue());
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

            case AddContent: {
                return new AddContentBuilder(currentNode.get("positionVariableExpression").textValue(), currentNode.get("contentKey").textValue());
            }

            case Publish: {
                return new PublishBuilder(currentNode.get("positionVariableExpression").textValue());
            }

            case Get: {
                GetBuilder builder = new GetBuilder(currentNode.get("id").textValue());

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
                stepsNode.forEach(stepNode -> builder.pipe((NodeBuilder) handleNodeBuilder(depth + 1, context, ancestors, currentNode, stepNode)));

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