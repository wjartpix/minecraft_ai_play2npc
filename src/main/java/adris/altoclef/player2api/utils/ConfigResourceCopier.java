package adris.altoclef.player2api.utils;

import baritone.utils.DirUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 通用配置资源复制工具。
 * 负责将 classpath 中的默认配置模板复制到运行时配置目录 (run/config/)。
 * 所有 Mod 配置统一走此流程：先检查 run/config/ 是否存在用户自定义配置，
 * 不存在则从 src/main/resources (classpath) 复制默认模板。
 */
public class ConfigResourceCopier {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 确保运行时配置目录中存在指定配置文件。
     * 如果 run/config/ 下没有，则从 classpath 资源复制默认模板。
     *
     * @param configFileName        运行时配置文件名（如 "playerengine-llm.json"）
     * @param classpathResourcePath classpath 资源路径（如 "/playerengine-llm-default.json"）
     * @return 运行时配置文件的完整路径
     */
    public static Path ensureConfigExists(String configFileName, String classpathResourcePath) {
        Path configDir = DirUtil.getConfigDir();
        Path configFile = configDir.resolve(configFileName);

        if (!Files.exists(configFile)) {
            copyFromClasspath(classpathResourcePath, configFile, configFileName);
        }
        return configFile;
    }

    /**
     * 从 classpath 复制默认配置资源到目标路径。
     */
    private static void copyFromClasspath(String classpathResourcePath, Path targetPath, String configFileName) {
        try (InputStream is = ConfigResourceCopier.class.getResourceAsStream(classpathResourcePath)) {
            if (is != null) {
                Files.createDirectories(targetPath.getParent());
                String defaultJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Files.writeString(targetPath, defaultJson);
                LOGGER.info("[Config] Copied default config '{}' from classpath resource {} to {}",
                    configFileName, classpathResourcePath, targetPath);
            } else {
                LOGGER.warn("[Config] No default classpath resource found for '{}' at {}",
                    configFileName, classpathResourcePath);
            }
        } catch (IOException e) {
            LOGGER.error("[Config] Failed to copy default config '{}': {}", configFileName, e.getMessage());
        }
    }
}
