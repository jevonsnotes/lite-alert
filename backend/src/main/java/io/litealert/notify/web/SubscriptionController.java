package io.litealert.notify.web;

import io.litealert.auth.CurrentUser;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.notify.NotifyTargetService;
import io.litealert.notify.domain.Subscription;
import io.litealert.notify.domain.SubscriptionStore;
import io.litealert.topic.TopicService;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/topics/{topicId}/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final TopicService topicService;
    private final SubscriptionStore subscriptionStore;
    private final NotifyTargetService targetService;
    private final CurrentUser currentUser;

    @GetMapping
    public Subscription get(@PathVariable String topicId) {
        topicService.getOrThrow(topicId);
        return subscriptionStore.getOrEmpty(topicId);
    }

    @PutMapping
    public Subscription replace(@PathVariable String topicId, @RequestBody List<String> contactIds) {
        Topic t = topicService.getOrThrow(topicId);
        for (String tid : contactIds) {
            var target = targetService.getOrThrow(tid);
            if (!target.getUserId().equals(t.getOwnerId()) && !currentUser.isAdmin()) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "target not owned by topic owner: " + tid);
            }
        }
        return subscriptionStore.save(topicId, contactIds);
    }
}
