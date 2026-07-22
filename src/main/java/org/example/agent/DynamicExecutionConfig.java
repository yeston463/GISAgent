package org.example.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 动态代码执行的安全配置。所有开关均支持环境变量覆盖，默认值以“最小化风险”为原则：
 * 功能默认关闭、鉴权默认本机、超时默认 3 秒。
 *
 * 安全分层（由外到内）：
 *   1) 功能开关 enabled —— 能否触发危险能力；
 *   2) 鉴权 authMode/token —— 谁能触发；
 *   3) 沙箱 allowHostAccess/IO/Thread=false —— 代码能接触的系统能力；
 *   4) 超时/配额 timeout/并发/大小 —— 即使绕过前三层，也能限制 CPU、内存、并发损害。
 */
@Component
public class DynamicExecutionConfig {

    /** 是否启用动态代码执行。默认 false（关闭），避免任何未授权触发。 */
    @Value("${DYNAMIC_EXECUTION_ENABLED:false}")
    private boolean enabled;

    /** 鉴权模式：token=独立令牌；local=仅本机回环地址。默认 local。 */
    @Value("${DYNAMIC_EXECUTION_AUTH_MODE:local}")
    private String authMode;

    /** 独立执行令牌，绝不复用 QWEN-APIKEY。token 模式下必填且非空。 */
    @Value("${GIS_AGENT_EXEC_TOKEN:}")
    private String token;

    /** 单次执行硬超时（毫秒）。超时后强制 Context.close(true) 终止 GraalJS。默认 3000。 */
    @Value("${DYNAMIC_EXECUTION_TIMEOUT_MS:3000}")
    private long timeoutMs;

    /** 服务绑定/允许的本地地址。local 模式下仅允许该地址或回环地址触发。默认 127.0.0.1。 */
    @Value("${SERVER_ADDRESS:127.0.0.1}")
    private String serverAddress;

    /** 输入数据（context+params JSON）最大字节数。默认 65536。 */
    @Value("${DYNAMIC_EXECUTION_MAX_INPUT_BYTES:65536}")
    private int maxInputBytes;

    /** 执行结果序列化后最大字节数。默认 65536。 */
    @Value("${DYNAMIC_EXECUTION_MAX_OUTPUT_BYTES:65536}")
    private int maxOutputBytes;

    /** 最大并发执行数（信号灯许可数）。默认 4。 */
    @Value("${DYNAMIC_EXECUTION_MAX_CONCURRENCY:4}")
    private int maxConcurrency;

    /** 执行线程池大小。默认 4。 */
    @Value("${DYNAMIC_EXECUTION_POOL_SIZE:4}")
    private int poolSize;

    public DynamicExecutionConfig() {
        // Spring 通过无参构造实例化后注入 @Value 字段。
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAuthMode() {
        return authMode;
    }

    public String getToken() {
        return token;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public int getMaxInputBytes() {
        return maxInputBytes;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public int getPoolSize() {
        return poolSize;
    }

    /** 测试/兜底用：返回安全默认值（启用、本机、3 秒）。 */
    public static DynamicExecutionConfig defaults() {
        DynamicExecutionConfig c = new DynamicExecutionConfig();
        c.enabled = true;
        c.authMode = "local";
        c.token = "";
        c.timeoutMs = 3000;
        c.serverAddress = "127.0.0.1";
        c.maxInputBytes = 65536;
        c.maxOutputBytes = 65536;
        c.maxConcurrency = 4;
        c.poolSize = 4;
        return c;
    }

    /** 测试用：构造指定开关与超时的配置（用于覆盖默认超时做快速超时测试）。 */
    public static DynamicExecutionConfig testConfig(boolean enabled, long timeoutMs) {
        DynamicExecutionConfig c = defaults();
        c.enabled = enabled;
        c.timeoutMs = timeoutMs;
        c.maxConcurrency = 2;
        c.poolSize = 2;
        return c;
    }
}
