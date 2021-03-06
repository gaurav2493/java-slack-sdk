package config;

import com.slack.api.SlackConfig;
import com.slack.api.methods.metrics.MetricsDatastore;
import com.slack.api.methods.metrics.impl.RedisMetricsDatastore;
import com.slack.api.util.http.listener.HttpResponseListener;
import com.slack.api.util.json.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPool;
import util.sample_json_generation.JsonDataRecordingListener;

@Slf4j
public class SlackTestConfig {

    private static final JsonDataRecordingListener JSON_DATA_RECORDING_LISTENER = new JsonDataRecordingListener();
    private static final SlackConfig CONFIG = new SlackConfig();

    public boolean areAllAsyncOperationsDone() {
        return JSON_DATA_RECORDING_LISTENER.isAllDone();
    }

    private final SlackConfig config;

    public MetricsDatastore getMetricsDatastore() {
        return getConfig().getMethodsConfig().getMetricsDatastore();
    }

    private SlackTestConfig(SlackConfig config) {
        this.config = config;
        CONFIG.getHttpClientResponseHandlers().add(new HttpResponseListener() {
            @Override
            public void accept(State state) {
                String json = GsonFactory.createSnakeCase(CONFIG).toJson(getMetricsDatastore().getAllStats());
                log.debug("--- (MethodsStats) ---\n" + json);
            }
        });

        // Testing with Redis
        String redisEnabled = System.getenv(Constants.SLACK_SDK_TEST_REDIS_ENABLED);
        if (redisEnabled != null && redisEnabled.equals("1")) {
            // brew install redis
            // redis-server /usr/local/etc/redis.conf --loglevel verbose
            JedisPool jedis = new JedisPool("localhost");
            CONFIG.getMethodsConfig().setMetricsDatastore(new RedisMetricsDatastore("test", jedis));
        }
    }

    static {
        CONFIG.setLibraryMaintainerMode(true);
        CONFIG.setPrettyResponseLoggingEnabled(true);
        CONFIG.getHttpClientResponseHandlers().add(JSON_DATA_RECORDING_LISTENER);
    }

    public static SlackTestConfig getInstance() {
        return new SlackTestConfig(CONFIG);
    }

    public SlackConfig getConfig() {
        return config;
    }

    public static void awaitCompletion(SlackTestConfig testConfig) throws InterruptedException {
        while (!testConfig.areAllAsyncOperationsDone()) {
            Thread.sleep(1000);
        }
    }

}
