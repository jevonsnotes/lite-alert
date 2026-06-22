package io.litealert.topic.web;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.topic.TopicService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicControllerTest {

    @Test
    void settingsReturnsTopicDefaultLimitWithTopicViewPermission() {
        TopicService topicService = mock(TopicService.class);
        PermissionService permissionService = mock(PermissionService.class);
        SystemSettingsService settingsService = mock(SystemSettingsService.class);
        SystemSettings settings = new SystemSettings();
        settings.setRateLimit(new SystemSettings.RateLimitConfig(88, 200, 30));
        when(settingsService.current()).thenReturn(settings);
        TopicController controller = new TopicController(topicService, permissionService, settingsService);

        Map<String, Object> result = controller.settings();

        verify(permissionService).require(Permissions.TOPIC_VIEW);
        assertThat(result).containsEntry("perTopicPerMinute", 88);
    }
}
