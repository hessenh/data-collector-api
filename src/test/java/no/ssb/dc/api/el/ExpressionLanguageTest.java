package no.ssb.dc.api.el;

import no.ssb.dc.api.ConfigurationMap;
import no.ssb.dc.api.context.ExecutionContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExpressionLanguageTest {

    @Disabled
    @Test
    public void testSimpleExpression() {
        String expr = "${a-b}";

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("a", "1");
        variables.put("b", "2");
        ExpressionLanguage el = new ExpressionLanguage(new ExecutionContext.Builder().variables(variables).build());
        System.out.printf("%s%n", el.evaluateExpression(expr));
    }

    @Test
    public void testCustomContext() {
        ExecutionContext context = ExecutionContext.empty();
        Map<String, String> map = new LinkedHashMap<>();
        map.put("foo.bar", "bar");
        ConfigurationMap config = new ConfigurationMap(map);
        context.services().register(ConfigurationMap.class, config);
        ExpressionLanguage el = new ExpressionLanguage(context);
        Object result = el.evaluateExpression("ENV.'foo.bar'");
        System.out.printf("eval: %s", result);
        assertEquals("bar", result);
    }

    @Test
    public void testConvertDateToEpoc() {
        TemporalAccessor ta = DateTimeFormatter.ISO_DATE_TIME.parse("2020-10-05T00:00:00.000Z");
        long epochSecond = LocalDateTime.from(ta).toEpochSecond(ZoneOffset.UTC);

        ExecutionContext context = ExecutionContext.empty();
        Map<String, String> map = new LinkedHashMap<>();
        map.put("datePosition", "2020-10-05T00:00:00.000Z");
        ConfigurationMap config = new ConfigurationMap(map);
        context.services().register(ConfigurationMap.class, config);
        ExpressionLanguage el = new ExpressionLanguage(context);
        Object result = el.evaluateExpression("${convert.utcDateToEpoc(ENV.datePosition)}");
        System.out.printf("eval: %s", result);
        assertEquals(Long.valueOf(epochSecond), result);
    }

    @Test
    public void testConCatandBase64() {
        String expr = "${base64.encode(a.concat(':').concat(b))}";

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("a", "language");
        variables.put("b", "testing");
        ExpressionLanguage el = new ExpressionLanguage(new ExecutionContext.Builder().variables(variables).build());
        assertEquals("bGFuZ3VhZ2U6dGVzdGluZw==", el.evaluateExpression(expr));
    }
    @Disabled
    @Test
    public void testGoogleSecretManger() {
        String sapath = "<path to sa secret file>";
        String projectID = "dev-sirius";
        String secretIdOrSecret = "testsecret";
        String provider = "google-secret-manager";
        String expr = "${gsm.access(secretProvider,secretId,projectID, sapath)}";
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("secretProvider", provider);
        variables.put("secretId", secretIdOrSecret);
        variables.put("projectID", projectID);
        variables.put("sapath",sapath);
        ExpressionLanguage el = new ExpressionLanguage(new ExecutionContext.Builder().variables(variables).build());

        assertEquals("any", el.evaluateExpression(expr));

    }

    @Disabled
    @Test
    public void testCombinedSecret() {
        String sapath = "";
        String projectID = "dev-sirius";
        String secretIdOrSecret = "testsecret";
        String provider = "google-secret-manager";
        String clientId = "test";
        String expr = "${secret.accessEncode(secretProvider, clientId, secretId, projectID, sapath)}";
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("secretProvider", provider);
        variables.put("secretId", secretIdOrSecret);
        variables.put("projectID", projectID);
        variables.put("clientId", clientId);
        variables.put("sapath",sapath);
        ExpressionLanguage el = new ExpressionLanguage(new ExecutionContext.Builder().variables(variables).build());

        assertEquals("any", el.evaluateExpression(expr));

    }
}


