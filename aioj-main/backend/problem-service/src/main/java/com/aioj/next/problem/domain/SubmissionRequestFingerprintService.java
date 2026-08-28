package com.aioj.next.problem.domain;

import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.entity.SubmissionRequestFingerprintEntity;
import com.aioj.next.problem.persistence.mapper.SubmissionRequestFingerprintMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class SubmissionRequestFingerprintService {
    private final SubmissionRequestFingerprintMapper mapper;

    public SubmissionRequestFingerprintService(SubmissionRequestFingerprintMapper mapper) {
        this.mapper = mapper;
    }

    public void record(SubmissionEntity submission, SubmissionRequestMetadata metadata, Instant now) {
        if (submission == null || submission.getId() == null) {
            return;
        }
        String ip = clientIp(metadata);
        String userAgent = metadata == null ? null : normalize(metadata.userAgent());
        SubmissionRequestFingerprintEntity entity = new SubmissionRequestFingerprintEntity();
        entity.setSubmissionId(submission.getId());
        entity.setContestId(submission.getContestId());
        entity.setContestRunId(submission.getContestRunId());
        entity.setContestParticipantId(submission.getContestParticipantId());
        entity.setUserId(submission.getUserId());
        entity.setIpHash(hashOrNull(ip));
        entity.setIpPrefix(ipPrefix(ip));
        entity.setUserAgentHash(hashOrNull(userAgent));
        entity.setUserAgentSummary(userAgentSummary(userAgent));
        entity.setCreatedAt(now);
        mapper.insert(entity);
    }

    private String clientIp(SubmissionRequestMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        if (StringUtils.hasText(metadata.forwardedFor())) {
            String first = metadata.forwardedFor().split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return stripPort(first);
            }
        }
        return stripPort(normalize(metadata.remoteAddress()));
    }

    private String stripPort(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            return trimmed.substring(1, trimmed.indexOf(']'));
        }
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0 && trimmed.indexOf(':') == colon && trimmed.substring(colon + 1).matches("\\d+")) {
            return trimmed.substring(0, colon);
        }
        return trimmed;
    }

    private String ipPrefix(String ip) {
        if (!StringUtils.hasText(ip)) {
            return null;
        }
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".0/24";
            }
        }
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            StringBuilder prefix = new StringBuilder();
            int copied = 0;
            for (String part : parts) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                if (copied > 0) {
                    prefix.append(':');
                }
                prefix.append(part.toLowerCase(Locale.ROOT));
                copied++;
                if (copied == 4) {
                    break;
                }
            }
            return copied == 0 ? "ipv6/unknown" : prefix + "::/64";
        }
        return "unknown";
    }

    private String userAgentSummary(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return null;
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        String browser = lower.contains("edg/") ? "Edge"
                : lower.contains("chrome/") ? "Chrome"
                : lower.contains("firefox/") ? "Firefox"
                : lower.contains("safari/") ? "Safari"
                : "Other";
        String os = lower.contains("windows") ? "Windows"
                : lower.contains("android") ? "Android"
                : lower.contains("iphone") || lower.contains("ipad") ? "iOS"
                : lower.contains("mac os") || lower.contains("macintosh") ? "macOS"
                : lower.contains("linux") ? "Linux"
                : "Unknown OS";
        return browser + " / " + os;
    }

    private String hashOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
