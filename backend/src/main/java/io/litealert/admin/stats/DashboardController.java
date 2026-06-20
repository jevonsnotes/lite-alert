package io.litealert.admin.stats;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SystemSettingsService settingsService;
    private final PermissionService permissionService;

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        permissionService.require(Permissions.STATS_VIEW);
        var trend = settingsService.current().getDashboardDefaultTrend();
        return Map.of(
                "dashboardDefaultTrend", Map.of(
                        "value", trend.getValue(),
                        "unit", trend.getUnit().name()
                )
        );
    }
}
