package adris.altoclef.player2api.context;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

public class TieredConversationHistory {

    private static final Logger LOGGER = LoggerFactory.getLogger(TieredConversationHistory.class);

    // 三层窗口
    private static final int HOT_WINDOW = 6;      // 热区：最近6条，完整保留
    private static final int WARM_WINDOW = 12;    // 温区：6-18条，按重要度保留
    private static final int COLD_THRESHOLD = 18; // 冷区：18+条，强摘要

    public enum MessageImportance {
        CRITICAL,   // 命令执行结果、错误信息、用户明确要求记住的
        HIGH,       // 用户直接指令、NPC 重要决策
        NORMAL,     // 普通对话
        LOW         // 系统状态更新、bodylang反馈
    }

    /**
     * 评估消息重要度
     */
    public static MessageImportance evaluateImportance(JsonObject message) {
        String role = message.has("role") ? message.get("role").getAsString() : "";
        String content = message.has("content") ? message.get("content").getAsString() : "";

        // 用户主动说"记住"/"别忘了"
        if (content.matches(".*(?:记住|别忘|不要忘|remember|永远|一定要).*")) {
            return MessageImportance.CRITICAL;
        }
        // 包含命令执行结果
        if (content.contains("Command") && (content.contains("finished") || content.contains("failed"))) {
            return MessageImportance.CRITICAL;
        }
        // 系统状态消息（LOW）
        if ("system".equals(role) && !content.contains("Summary") && !content.contains("Earlier")) {
            return MessageImportance.LOW;
        }
        // bodylang 反馈
        if (content.contains("bodylang") && content.length() < 50) {
            return MessageImportance.LOW;
        }
        // 包含实质性用户指令
        if ("user".equals(role) && content.length() > 10) {
            return MessageImportance.HIGH;
        }
        // assistant 消息通常包含决策
        if ("assistant".equals(role) && content.length() > 20) {
            return MessageImportance.HIGH;
        }

        return MessageImportance.NORMAL;
    }

    /**
     * 对完整对话历史进行分层处理
     * @param fullHistory 完整对话历史（不含 system prompt，即 index 1 开始的消息）
     * @param summarizer 增量摘要器（用于冷区摘要），可为 null
     * @return 压缩后的消息列表
     */
    public static List<JsonObject> buildTieredHistory(List<JsonObject> fullHistory, IncrementalSummarizer summarizer) {
        int size = fullHistory.size();

        if (size <= HOT_WINDOW) {
            // 消息数很少，全部保留
            return new ArrayList<>(fullHistory);
        }

        List<JsonObject> result = new ArrayList<>();

        // 分区
        int hotStart = Math.max(0, size - HOT_WINDOW);
        int warmStart = Math.max(0, hotStart - WARM_WINDOW);

        // 冷区处理（0 ~ warmStart）
        if (warmStart > 0) {
            List<JsonObject> coldMessages = fullHistory.subList(0, warmStart);
            String coldSummary;
            if (summarizer != null) {
                coldSummary = summarizer.getOrUpdateSummary(coldMessages);
            } else {
                coldSummary = localFallbackSummarize(coldMessages);
            }
            if (coldSummary != null && !coldSummary.isEmpty()) {
                JsonObject summaryMsg = new JsonObject();
                summaryMsg.addProperty("role", "assistant");
                summaryMsg.addProperty("content", "Earlier context: " + coldSummary);
                result.add(summaryMsg);
            }
        }

        // 温区处理（warmStart ~ hotStart）
        if (warmStart < hotStart) {
            List<JsonObject> warmMessages = fullHistory.subList(warmStart, hotStart);
            List<JsonObject> compressed = compressWarmZone(warmMessages);
            result.addAll(compressed);
        }

        // 热区：完整保留
        List<JsonObject> hotMessages = fullHistory.subList(hotStart, size);
        result.addAll(hotMessages);

        return result;
    }

    /**
     * 压缩温区消息：保留 CRITICAL/HIGH，摘要 NORMAL，丢弃 LOW
     */
    public static List<JsonObject> compressWarmZone(List<JsonObject> warmMessages) {
        List<JsonObject> result = new ArrayList<>();
        List<JsonObject> toSummarize = new ArrayList<>();

        for (JsonObject msg : warmMessages) {
            MessageImportance imp = evaluateImportance(msg);
            if (imp == MessageImportance.CRITICAL || imp == MessageImportance.HIGH) {
                // 先将待摘要的消息合并为 mini summary
                if (!toSummarize.isEmpty()) {
                    result.add(createMiniSummary(toSummarize));
                    toSummarize.clear();
                }
                result.add(msg); // 关键消息完整保留
            } else if (imp == MessageImportance.NORMAL) {
                toSummarize.add(msg);
            }
            // LOW 直接丢弃
        }

        // 处理末尾剩余
        if (!toSummarize.isEmpty()) {
            result.add(createMiniSummary(toSummarize));
        }

        return result;
    }

    /**
     * 创建 mini 摘要：将多条普通消息合并为一条简短摘要
     */
    private static JsonObject createMiniSummary(List<JsonObject> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Summary of ").append(messages.size()).append(" messages: ");

        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "unknown";
            String content = msg.has("content") ? msg.get("content").getAsString() : "";
            // 取前30字符
            String brief = content.length() > 30 ? content.substring(0, 30) + "..." : content;
            sb.append(role).append(":\"").append(brief).append("\"; ");
        }
        sb.append("]");

        JsonObject summary = new JsonObject();
        summary.addProperty("role", "assistant");
        summary.addProperty("content", sb.toString());
        return summary;
    }

    /**
     * 本地摘要替代：当增量摘要器不可用时使用的简单摘要
     */
    private static String localFallbackSummarize(List<JsonObject> messages) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "unknown";
            String content = msg.has("content") ? msg.get("content").getAsString() : "";
            if ("user".equals(role) && content.length() > 5) {
                sb.append("User: ").append(content, 0, Math.min(50, content.length())).append("; ");
            }
        }
        return sb.length() > 0 ? sb.toString() : "Earlier conversation occurred.";
    }
}
