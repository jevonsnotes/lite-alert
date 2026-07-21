package io.litealert.notify.channel;

import io.litealert.notify.channel.WebhookResponseAssertor.Condition;
import io.litealert.notify.channel.WebhookResponseAssertor.ConditionBodyType;
import io.litealert.notify.channel.WebhookResponseAssertor.Logic;
import io.litealert.notify.channel.WebhookResponseAssertor.Operator;
import io.litealert.topic.domain.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookResponseAssertorTest {

    private final WebhookResponseAssertor assertor = new WebhookResponseAssertor();

    @Test
    void assertsJsonResponseWithMultipleOperators() {
        String body = "{\"errcode\":0,\"errmsg\":\"ok\",\"count\":12}";

        assertThat(assertor.check(check("$.errcode", "0", "$.errmsg", "EQ", "AUTO"), 200, "application/json", body).success()).isTrue();
        assertThat(assertor.check(check("$.errcode", "1", "$.errmsg", "NE", "AUTO"), 200, "application/json", body).success()).isTrue();
        assertThat(assertor.check(check("$.errmsg", "o", "$.errmsg", "CONTAINS", "AUTO"), 200, "application/json", body).success()).isTrue();
        assertThat(assertor.check(check("$.errmsg", "^o.*", "$.errmsg", "REGEX", "AUTO"), 200, "application/json", body).success()).isTrue();
        assertThat(assertor.check(check("$.count", "10", "$.errmsg", "GT", "AUTO"), 200, "application/json", body).success()).isTrue();
        assertThat(assertor.check(check("$.count", "20", "$.errmsg", "LT", "AUTO"), 200, "application/json", body).success()).isTrue();
        assertThat(assertor.check(check("$.errmsg", "", "$.errmsg", "EXISTS", "AUTO"), 200, "application/json", body).success()).isTrue();
    }

    @Test
    void failsJsonResponseAndExtractsMessage() {
        String body = "{\"errcode\":40008,\"errmsg\":\"wrong json format\"}";

        WebhookResponseAssertor.Result result = assertor.check(
                check("$.errcode", "0", "$.errmsg", "EQ", "AUTO"), 200, "application/json", body);

        assertThat(result.success()).isFalse();
        assertThat(result.actual()).isEqualTo("40008");
        assertThat(result.message()).isEqualTo("wrong json format");
    }

    @Test
    void assertsXmlResponseWithXPath() {
        String body = "<xml><errcode>0</errcode><errmsg>ok</errmsg></xml>";

        WebhookResponseAssertor.Result result = assertor.check(
                check("/xml/errcode", "0", "/xml/errmsg", "EQ", "XML"), 200, "application/xml", body);

        assertThat(result.success()).isTrue();
    }

    private Topic.WebhookResponseCheck check(String successPath, String successValue,
                                             String messagePath, String operator, String bodyType) {
        Topic.WebhookResponseCheck c = new Topic.WebhookResponseCheck();
        c.setEnabled(true);
        c.setBodyType(Topic.WebhookResponseCheck.BodyType.valueOf(bodyType));
        c.setSuccessPath(successPath);
        c.setSuccessValue(successValue);
        c.setMessagePath(messagePath);
        c.setOperator(Topic.WebhookResponseCheck.Operator.valueOf(operator));
        return c;
    }

    // ---- multi-condition (scheduled API tasks) ----

    private static final String BODY = "{\"code\":0,\"msg\":\"ok\",\"data\":{\"status\":\"ok\"}}";

    private Condition cond(String path, Operator op, String expected) {
        return new Condition(path, op, expected, ConditionBodyType.AUTO);
    }

    @Test
    void andLogicAllPassIsSuccess() {
        List<Condition> conditions = List.of(cond("$.code", Operator.EQ, "0"), cond("$.data.status", Operator.EQ, "ok"));
        WebhookResponseAssertor.MultiResult r = assertor.check(conditions, Logic.AND, 200, "application/json", BODY);
        assertThat(r.success()).isTrue();
    }

    @Test
    void andLogicPartialFailIsFailure() {
        List<Condition> conditions = List.of(cond("$.code", Operator.EQ, "0"), cond("$.code", Operator.EQ, "1"));
        WebhookResponseAssertor.MultiResult r = assertor.check(conditions, Logic.AND, 200, "application/json", BODY);
        assertThat(r.success()).isFalse();
        assertThat(r.conditions()).hasSize(2);
        assertThat(r.conditions().get(0).passed()).isTrue();
        assertThat(r.conditions().get(1).passed()).isFalse();
    }

    @Test
    void orLogicAnyPassIsSuccess() {
        List<Condition> conditions = List.of(cond("$.code", Operator.EQ, "1"), cond("$.code", Operator.EQ, "0"));
        WebhookResponseAssertor.MultiResult r = assertor.check(conditions, Logic.OR, 200, "application/json", BODY);
        assertThat(r.success()).isTrue();
    }

    @Test
    void orLogicNonePassIsFailure() {
        List<Condition> conditions = List.of(cond("$.code", Operator.EQ, "1"), cond("$.code", Operator.EQ, "2"));
        WebhookResponseAssertor.MultiResult r = assertor.check(conditions, Logic.OR, 200, "application/json", BODY);
        assertThat(r.success()).isFalse();
    }

    @Test
    void non2xxIsFailureRegardlessOfConditions() {
        List<Condition> conditions = List.of(cond("$.code", Operator.EQ, "0"));
        WebhookResponseAssertor.MultiResult r = assertor.check(conditions, Logic.AND, 500, "application/json", BODY);
        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("HTTP 500");
    }

    @Test
    void emptyConditionsJudgeByStatusOnly() {
        WebhookResponseAssertor.MultiResult ok = assertor.check(List.of(), Logic.AND, 200, "application/json", BODY);
        assertThat(ok.success()).isTrue();
        WebhookResponseAssertor.MultiResult fail = assertor.check(null, Logic.OR, 404, "application/json", BODY);
        assertThat(fail.success()).isFalse();
    }
}
