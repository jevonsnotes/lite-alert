package io.litealert.scheduler.web;

import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.scheduler.SchedulerNotifyConfigService;
import io.litealert.scheduler.domain.SchedulerNotifyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduler/notify-configs")
@RequiredArgsConstructor
public class SchedulerNotifyConfigController {

    private final SchedulerNotifyConfigService service;
    private final PermissionService permissionService;

    /** Available variables/functions for the notify body template (scheduled-task scope). */
    @GetMapping("/variables")
    public List<Map<String, String>> variables() {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_VIEW);
        List<Map<String, String>> vars = new java.util.ArrayList<>();
        vars.add(v("执行上下文", "taskName", "任务名称", "{{taskName}}"));
        vars.add(v("执行上下文", "taskId", "任务 ID", "{{taskId}}"));
        vars.add(v("执行上下文", "status", "执行结果 SUCCESS/FAIL", "{{status}}"));
        vars.add(v("执行上下文", "httpStatus", "HTTP 状态码", "{{httpStatus}}"));
        vars.add(v("执行上下文", "durationMs", "耗时（毫秒）", "{{durationMs}}"));
        vars.add(v("执行上下文", "error", "错误信息", "{{error}}"));
        vars.add(v("执行上下文", "triggeredAt", "触发时间", "{{triggeredAt}}"));
        vars.add(v("执行上下文", "assertionPassed", "断言是否通过", "{{assertionPassed}}"));
        vars.add(v("响应体", "$.response", "整段响应体（非 JSON 时为纯文本）", "{{$.response}}"));
        vars.add(v("响应体", "$.response.xxx", "响应体 JSONPath 字段", "{{$.response.data.diff}}"));
        vars.add(v("函数", "@json", "JSON 转义，安全嵌入含特殊字符的值", "{{@json($.response)}}"));
        vars.add(v("函数", "@base64", "Base64 编码", "{{@base64($.response)}}"));
        vars.add(v("函数", "@md5", "MD5 哈希", "{{@md5($.response)}}"));
        vars.add(v("函数", "@upper", "转大写", "{{@upper($.response.code)}}"));
        vars.add(v("函数", "@lower", "转小写", "{{@lower($.response.code)}}"));
        vars.add(v("函数", "@trim", "去首尾空格", "{{@trim($.response.code)}}"));
        vars.add(v("函数", "@substr", "截取子串 start|len", "{{@substr($.response.code|0|5)}}"));
        return vars;
    }

    private Map<String, String> v(String group, String name, String desc, String usage) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("group", group); m.put("name", name); m.put("desc", desc); m.put("usage", usage);
        return m;
    }

    @GetMapping("/{id}/plain-url")
    public Map<String, String> plainUrl(@PathVariable String id) {
        // owner check via getOrThrow; only owner can view the plaintext URL
        io.litealert.scheduler.domain.SchedulerNotifyConfig c = service.getOrThrow(id);
        return Map.of("url", c.getUrl() == null ? "" : c.getUrl());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_VIEW);
        return service.listMine().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        permissionService.require(Permissions.SCHEDULER_NOTIFY_VIEW);
        return toView(service.getOrThrow(id));
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        return toView(service.setEnabled(id, false));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id) {
        return toView(service.setEnabled(id, true));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SchedulerNotifyConfigService.CreateRequest req) {
        return toView(service.create(req));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id,
                                      @RequestBody SchedulerNotifyConfigService.UpdateRequest req) {
        return toView(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("status", "deleted");
    }

    private Map<String, Object> toView(SchedulerNotifyConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("ownerId", c.getOwnerId());
        m.put("name", c.getName());
        m.put("method", c.getMethod());
        m.put("url", maskUrl(c.getUrl()));
        m.put("headers", c.getHeaders());
        m.put("bodyTemplate", c.getBodyTemplate());
        m.put("triggerOn", c.getTriggerOn() == null ? null : c.getTriggerOn().name());
        m.put("enabled", c.isEnabled());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        m.put("updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        return m;
    }

    /** Mask the query segment of a URL (where access tokens etc. usually live). */
    static String maskUrl(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return url;
        return url.substring(0, q) + "?***";
    }
}
