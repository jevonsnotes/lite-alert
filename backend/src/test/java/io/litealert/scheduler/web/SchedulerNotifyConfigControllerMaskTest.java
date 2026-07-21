package io.litealert.scheduler.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerNotifyConfigControllerMaskTest {

    @Test
    void maskUrlHidesQuerySegment() {
        assertThat(SchedulerNotifyConfigController.maskUrl("https://oapi.dingtalk.com/robot/send?access_token=SECRET"))
                .isEqualTo("https://oapi.dingtalk.com/robot/send?***");
    }

    @Test
    void maskUrlUnchangedWhenNoQuery() {
        assertThat(SchedulerNotifyConfigController.maskUrl("https://hook.example/path"))
                .isEqualTo("https://hook.example/path");
    }

    @Test
    void maskUrlHandlesNullAndEmpty() {
        assertThat(SchedulerNotifyConfigController.maskUrl(null)).isNull();
        assertThat(SchedulerNotifyConfigController.maskUrl("")).isEqualTo("");
    }
}
