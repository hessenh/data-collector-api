package no.ssb.dc.api.el;

import no.ssb.dc.api.ConfigurationMap;
import no.ssb.dc.api.content.EvaluateLastContentStreamPosition;
import no.ssb.dc.api.context.ExecutionContext;
import no.ssb.dc.api.util.CommonUtils;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExpressionLanguage {

    private static final Logger LOG = LoggerFactory.getLogger(ExpressionLanguage.class);

    static final Pattern EXPRESSION_REGEX = Pattern.compile("\\$\\{([^}]+)}");
    static final Pattern MULTI_EXPRESSION_REGEX = Pattern.compile("\\$\\{(.*?)}+");

    private final ConfigurationMap configuration;
    private final Map<String, Object> variables;
    private final EvaluateLastContentStreamPosition evaluateLastContentStreamPosition;

    private static class Jexl {
        private static final JexlEngine jexlEngine = new JexlBuilder()
                .cache(512)
                .strict(true)
                .silent(false)
                .create();

        static JexlEngine engine() {
            return jexlEngine;
        }
    }

    public ExpressionLanguage(ExecutionContext context) {
        this.configuration = context.services().get(ConfigurationMap.class);
        this.variables = new LinkedHashMap<>(context.variables());
        this.evaluateLastContentStreamPosition = new EvaluateLastContentStreamPosition(context);
    }

    // doc: http://commons.apache.org/proper/commons-jexl/reference/examples.html
    void initializeJexlFunctions(JexlContext jexlContext) {
        if (configuration != null) {
            jexlContext.set("ENV", new MapContext(new LinkedHashMap<>(configuration.asMap()))); // TODO dotted EL-vars not supported
        }
        jexlContext.set("cast", new ELCast());
        jexlContext.set("convert", new ELConvert());
        jexlContext.set("contentStream", new ELContentStream(evaluateLastContentStreamPosition));
        jexlContext.set("base64", new ELBase64());
        jexlContext.set("gsm", new Gsm());
        jexlContext.set("secret",new Secret());
    }

    public boolean isExpression(String expr) {
        if (expr == null) return false;
        Matcher m = EXPRESSION_REGEX.matcher(expr);
        return m.find();
    }

    public String getExpression(String expr) {
        Matcher m = EXPRESSION_REGEX.matcher(expr);
        if (m.find()) {
            return m.group(1);
        }
        return expr;
    }

    public Object evaluateExpression(String expr) {
        AtomicReference<String> expressionRef = new AtomicReference<>();
        try {
            final String expression = (isExpression(expr) ? getExpression(expr) : expr);
            expressionRef.set(expression);
            JexlExpression e = Jexl.engine().createExpression(expression);
            JexlContext jexlContext = new MapContext(variables);
            initializeJexlFunctions(jexlContext);
            return e.evaluate(jexlContext);
        } catch (RuntimeException | Error e) {
            LOG.error("Error evaluating expr: '{}', exprKey: '{}' in Map: {}\n{}", expr, expressionRef.get(), variables, CommonUtils.captureStackTrace(e));
            throw e;
        } catch (Exception e) {
            LOG.error("Error evaluating expr: '{}', exprKey: '{}' in Map: {}\n{}", expr, expressionRef.get(), variables, CommonUtils.captureStackTrace(e));
            throw new EvaluationException(e);
        }
    }

    public String evaluateExpressions(String expr) {
        Matcher m = MULTI_EXPRESSION_REGEX.matcher(expr);

        StringBuffer buf = new StringBuffer();
        int last = 0;
        while (m.find()) {
            for (int n = 0; n < m.groupCount(); n++) {
                String group = m.group(n);
                int current = m.start();
                String prev = expr.substring(last, current);
                buf.append(prev);
                buf.append(evaluateExpression(group));
                last = m.end();
            }
        }
        buf.append(expr.substring(last));
        return buf.toString();
    }

}
