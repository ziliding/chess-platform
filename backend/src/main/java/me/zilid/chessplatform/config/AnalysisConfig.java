package me.zilid.chessplatform.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper.TypePrecedence;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues for AI analysis jobs. Spring declares everything and the analysis service only checks that it exists, so the
 * queue arguments below can only be changed by deleting the queues: RabbitMQ refuses to redeclare a queue with
 * different arguments. The same topology is repeated in {@code analysis/tests/topology.py}.
 *
 * <pre>
 * analysis (direct) --request--&gt; analysis.requests --dead letters--&gt; analysis.dlx --&gt; analysis.requests.dlq
 * analysis (direct) --result---&gt; analysis.results  --dead letters--&gt; analysis.dlx --&gt; analysis.results.dlq
 * </pre>
 */
@Configuration
public class AnalysisConfig {
    public static final String EXCHANGE = "analysis";
    public static final String DEAD_LETTER_EXCHANGE = "analysis.dlx";
    public static final String REQUEST_KEY = "request";
    public static final String RESULT_KEY = "result";
    public static final String REQUEST_QUEUE = "analysis.requests";
    public static final String RESULT_QUEUE = "analysis.results";
    public static final String REQUEST_DLQ = "analysis.requests.dlq";
    public static final String RESULT_DLQ = "analysis.results.dlq";

    private static final int REQUEST_DELIVERY_LIMIT = 3;
    private static final int RESULT_DELIVERY_LIMIT = 5;
    private static final int MAX_QUEUED_REQUESTS = 1000;

    @Bean
    public Declarables analysisTopology() {
        DirectExchange exchange =
                ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
        DirectExchange deadLetters = ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();

        // A full request queue rejects new jobs instead of dropping old ones
        Queue requests = QueueBuilder.durable(REQUEST_QUEUE)
                .quorum()
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(REQUEST_KEY)
                .deliveryLimit(REQUEST_DELIVERY_LIMIT)
                .maxLength(MAX_QUEUED_REQUESTS)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
        Queue results = QueueBuilder.durable(RESULT_QUEUE)
                .quorum()
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RESULT_KEY)
                .deliveryLimit(RESULT_DELIVERY_LIMIT)
                .build();
        Queue requestsDlq = QueueBuilder.durable(REQUEST_DLQ).quorum().build();
        Queue resultsDlq = QueueBuilder.durable(RESULT_DLQ).quorum().build();

        Binding requestBinding = BindingBuilder.bind(requests).to(exchange).with(REQUEST_KEY);
        Binding resultBinding = BindingBuilder.bind(results).to(exchange).with(RESULT_KEY);
        Binding requestDlqBinding =
                BindingBuilder.bind(requestsDlq).to(deadLetters).with(REQUEST_KEY);
        Binding resultDlqBinding =
                BindingBuilder.bind(resultsDlq).to(deadLetters).with(RESULT_KEY);
        return new Declarables(
                exchange,
                deadLetters,
                requests,
                results,
                requestsDlq,
                resultsDlq,
                requestBinding,
                resultBinding,
                requestDlqBinding,
                resultDlqBinding);
    }

    /**
     * Built from Boot's {@link JsonMapper}, so unknown fields are ignored on the path the listener really uses. The
     * Python service sends no {@code __TypeId__} header, so the target type comes from the listener's parameter.
     */
    @Bean
    public MessageConverter analysisMessageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);
        converter.setTypePrecedence(TypePrecedence.INFERRED);
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }
}
