package adris.altoclef.player2api.soul;

/**
 * 情绪触发器类型 - 定义所有可能导致 NPC 情绪变化的游戏事件。
 */
public enum EmotionTriggerType {
    // 玩家互动
    PLAYER_PRAISE,      // 玩家称赞 NPC
    PLAYER_BLAME,       // 玩家责备 NPC
    PLAYER_ATTACK,      // 玩家攻击 NPC
    PLAYER_GIFT,        // 玩家赠送物品
    PLAYER_DEATH,       // 玩家死亡
    PLAYER_JOIN,        // 玩家加入游戏
    PLAYER_LEAVE,       // 玩家离开游戏

    // 环境事件
    DAY_BREAK,          // 日出
    NIGHT_FALL,         // 日落
    RAIN_START,         // 开始下雨
    THUNDER,            // 打雷

    // 游戏事件
    FIND_DIAMOND,       // 发现钻石
    FIND_RARE_ITEM,     // 发现稀有物品
    ENTER_CAVE,         // 进入洞穴
    ENTER_NETHER,       // 进入下界
    ENTER_END,          // 进入末地
    CREEPER_NEARBY,     // 附近出现苦力怕
    LOW_HEALTH,         // NPC 低血量

    // 任务事件
    TASK_COMPLETE,      // 任务完成
    TASK_FAIL,          // 任务失败
    TASK_CANCELLED,     // 任务被取消

    // 社交事件
    MEET_NEW_NPC,       // 遇到新 NPC
    NPC_GREETING        // 被其他 NPC 问候
}
