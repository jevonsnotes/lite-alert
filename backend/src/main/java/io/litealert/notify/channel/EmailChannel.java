package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.notify.mail.MailConfig;
import io.litealert.notify.mail.MailService;
import io.litealert.topic.domain.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailChannel implements NotifyChannel {

    private final MailService mailService;

    @Override
    public NotifyTarget.Type type() { return NotifyTarget.Type.EMAIL; }

    @Override
    public void send(NotifyTarget target, Topic.ChannelTemplate template,
                     String subject, String body, JsonNode payload,
                     Map<String, String> systemVars) throws Exception {
        JavaMailSender sender = mailService.sender().orElse(null);
        if (sender == null) {
            throw new IllegalStateException("SMTP not configured; cannot send email to " + target.getId());
        }
        var msg = sender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
        applyFrom(h);
        h.setTo(target.getEndpoint());
        h.setSubject(subject);
        h.setText(body, true);
        sender.send(msg);
    }

    private void applyFrom(MimeMessageHelper h) throws Exception {
        MailConfig c = mailService.currentConfig();
        if (c == null) return;
        String addr = (c.getFromAddress() != null && !c.getFromAddress().isBlank())
                ? c.getFromAddress() : c.getUsername();
        if (addr == null || addr.isBlank()) return;
        if (c.getFromName() != null && !c.getFromName().isBlank()) h.setFrom(addr, c.getFromName());
        else h.setFrom(addr);
    }
}
