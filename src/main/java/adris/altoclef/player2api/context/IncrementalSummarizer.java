package adris.altoclef.player2api.context;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * 增量摘要器：本地摘要保底 + LLM 高质量摘要（可选）
 * 替代全量摘要，与 TieredConversationHistory 配合使用
 */
public class IncrementalSummarizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalSummarizer.class);
    private static final int MAX_SUMMARY_LENGTH = 750; // ~500 tokens

    private String runningLocalSummary = "";
    private int summaryVersion = 0;
    private int lastProcessedCount = 0; // 上次处理过的冷区消息数

    /**
     * 获取或更新摘要（被 TieredConversationHistory 调用）
     * 仅处理新增的冷区消息
     */
    public String getOrUpdateSummary(List<JsonObject> coldMessages) {
        if (coldMessages == null || coldMessages.isEmpty()) {
            return runningLocalSummary;
        }

        // 仅处理新增消息（增量）
        if (coldMessages.size() > lastProcessedCount) {
            List<JsonObject> newMessages = coldMessages.subList(lastProcessedCount, coldMessages.size());
            String newPart = localSummarize(newMessages);
            runningLocalSummary = mergeWithExisting(runningLocalSummary, newPart);
            lastProcessedCount = coldMessages.size();
            summaryVersion++;
            LOGGER.debug("Incremental summary updated (v{}), processed {} new cold messages",
                summaryVersion, newMessages.size());
        }

        return runningLocalSummary;
    }

    /**
     * 本地摘要：无需 LLM 调用，零失败率
     * 提取 user 指令核心 + assistant 执行命令
     */
    public String localSummarize(List<JsonObject> messages) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            String content = msg.has("content") ? msg.get("content").getAsString() : "";

            if ("user".equals(role)) {
                String core = extractCommandIntent(content);
                if (!core.isEmpty()) {
                    sb.append("User asked: ").append(core).append(". ");
                }
            } else if ("assistant".equals(role)) {
                String cmd = extractCommandFromResponse(content);
                if (!cmd.isEmpty()) {
                    sb.append("Did: ").append(cmd).append(". ");
                }
            }
        }

        String result = sb.toString().trim();
        // 单次摘要限制 300 字符
        if (result.length() > 300) {
            result = result.substring(result.length() - 300);
        }
        return result;
    }

    /**
     * 从用户消息中提取指令意图
     */
    private String extractCommandIntent(String content) {
        if (content == null || content.length() <= 5) return "";

        // 如果是包装过的 JSON（含 userMessage 字段），提取原始消息
        if (content.contains("userMessage")) {
            int start = content.indexOf("userMessage");
            if (start != -1) {
                int valueStart = content.indexOf(":", start) + 1;
                int valueEnd = content.indexOf(",", valueStart);
                if (valueEnd == -1) valueEnd = content.indexOf("}", valueStart);
                if (valueStart > 0 && valueEnd > valueStart) {
                    content = content.substring(valueStart, valueEnd).trim();
                    // 去除引号
                    content = content.replaceAll("^\"|\"$", "");
                }
            }
        }

        // 截取前 50 字符
        return content.length() > 50 ? content.substring(0, 50) : content;
    }

    /**
     * 从 assistant 响应中提取执行的命令
     */
    private String extractCommandFromResponse(String content) {
        if (content == null || content.isEmpty()) return "";

        // 尝试从 JSON 响应中提取 command 字段
        if (content.contains("\"command\"")) {
            int start = content.indexOf("\"command\"");
            int valueStart = content.indexOf(":", start) + 1;
            int valueEnd = content.indexOf(",", valueStart);
            if (valueEnd == -1) valueEnd = content.indexOf("}", valueStart);
            if (valueStart > 0 && valueEnd > valueStart) {
                String cmd = content.substring(valueStart, valueEnd).trim();
                cmd = cmd.replaceAll("^\"|\"$", "");
                if (!cmd.isEmpty() && !cmd.equals("null")) {
                    return cmd.length() > 40 ? cmd.substring(0, 40) : cmd;
                }
            }
        }

        // 如果是摘要消息或普通文本，截取前 30 字符
        if (content.startsWith("Earlier context:") || content.startsWith("Summary")) {
            return ""; // 不摘要摘要
        }
        return content.length() > 30 ? content.substring(0, 30) : content;
    }

    /**
     * 合并摘要，限制总长度
     */
    private String mergeWithExisting(String existing, String newPart) {
        if (newPart == null || newPart.isEmpty()) return existing;
        if (existing == null || existing.isEmpty()) return newPart;

        String merged = existing + " " + newPart;
        if (merged.length() > MAX_SUMMARY_LENGTH) {
            merged = merged.substring(merged.length() - MAX_SUMMARY_LENGTH);
        }
        return merged.trim();
    }

    /**
     * 重置摘要器（对话重新开始时调用）
     */
    public void reset() {
        runningLocalSummary = "";
        summaryVersion = 0;
        lastProcessedCount = 0;
    }

    public String getCurrentSummary() {
        return runningLocalSummary;
    }

    public int getVersion() {
        return summaryVersion;
    }
}
