package uk.co.humboldt.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.util.InterruptUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LogDNA logging throughput is limited by roundtrip time to the ingest API.
 *
 * This appender queues logs internally until there is a pause in log generation,
 * or the accumulated log buffer reaches a size threshold. In our environment this allows
 * logging rates to burst as high as 100Hz.
 *
 * The appName and ingestKey parameters are required.
 */
public class LogDNABulkAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {


    private static final int DEFAULT_QUEUE_SIZE = 1000;
    private int queueSize = DEFAULT_QUEUE_SIZE;

    /**
     * Set size of the intermediate queue used to hold log lines
     * while the POST to LogDNA is running. Must hold your maximum logging output
     * during the time taken for a full log delivery to LogDNA. Default is 1000.
     */
    @SuppressWarnings("unused")
    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    private static final int DEFAULT_DISCARDING_THRESHOLD = 50;
    private int discardingThreshold = DEFAULT_DISCARDING_THRESHOLD;

    /**
     * Once the number of free queue places drops below this threshold, drop low
     * priority log messages.
     */
    @SuppressWarnings("unused")
    public void setDiscardingThreshold(int discardingThreshold) {
        this.discardingThreshold = discardingThreshold;
    }

    private static final long DEFAULT_MAX_BATCH_BYTES = 1000000;
    private long maxBatchBytes = DEFAULT_MAX_BATCH_BYTES;

    /**
     * Size of maximum batch to send to LogDNA. Smaller batch sizes will limit throughput,
     * but larger batch sizes will raise latency.
     * API limit is 10MB, but allow for encoding overhead
     */
    @SuppressWarnings("unused")
    public void setMaxBatchBytes(long maxBatchBytes) {
        this.maxBatchBytes = maxBatchBytes;
    }

    private static final int DEFAULT_IDLE_TIME = 50;
    private int idleTime = DEFAULT_IDLE_TIME;

    /**
     * Time in ms to wait for another log message before declaring logging idle and sending logs
     * to LogNDA.
     */
    @SuppressWarnings("unused")
    public void setIdleTime(int idleTime) {
        this.idleTime = idleTime;
    }

    private static final int DEFAULT_MAX_FLUSH_TIME = 1000;
    private int maxFlushTime = DEFAULT_MAX_FLUSH_TIME;

    /**
     * The maximum queue flush time in ms allowed during appender stop. If the
     * worker takes longer than this time it will exit, discarding any remaining
     * items in the queue. Default value is 1000ms.
     */
    @SuppressWarnings("unused")
    public void setMaxFlushTime(int maxFlushTime) {
        this.maxFlushTime = maxFlushTime;
    }

    private String appName;

    /**
     * Application name to be sent to LogDNA. Has no defaults,
     * and must be configured.
     */
    @SuppressWarnings("unused")
    public void setAppName(String appName) {
        this.appName = appName;
    }

    private String apikey;

    /**
     * API key for LogDNA.Has no defaults,
     * and must be configured.
     */
    @SuppressWarnings("unused")
    public void setIngestKey(String ingestKey) {
        this.apikey = ingestKey;
    }

    private boolean sendMDC = true;

    /**
     * If true, include Logback MDC in the properties
     * map sent to LogDNA. Default true.
     */
    @SuppressWarnings("unused")
    public void setSendMDC(boolean sendMDC) {
        this.sendMDC = sendMDC;
    }

    private boolean includeStacktrace = true;

    /**
     * If true, include full multi-line stacktraces for log lines
     * with an attached throwable. Default true.
     */
    @SuppressWarnings("unused")
    public void setIncludeStacktrace(boolean includeStacktrace) {
        this.includeStacktrace = includeStacktrace;
    }

    private boolean sendNow = false;

    /**
     * If true, include the current system timestamp in log POSTs to allow
     * LogDNA to adjust for clock drift. Default false.
     */
    @SuppressWarnings("unused")
    public void setSendNow(boolean sendNow) {
        this.sendNow = sendNow;
    }

    private String hostname;

    /**
     * Hostname to send to LogDNA. Default is obtained from
     * InetAddress.getLocalHost().getHostName(), falling back to localhost.
     */
    @SuppressWarnings("unused")
    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    private String tags;

    /**
     * Tags to send to LogDNA, comma separated
     */
    @SuppressWarnings("unused")
    public void setTags(String tags) {
        this.tags = tags;
    }

    /**
     * Track count of dropped log messages
     */
    private final AtomicInteger dropped = new AtomicInteger();

    /**
     * Time when we last logged the count of dropped messages, used to avoid
     * flooding the log with messages.
     */
    private Instant lastDropLog = Instant.now();

    /**
     * Log messages sent to this logger can be logged to other loggers, but will not be sent to LogDNA.
     * Used to report errors within this logger.
     */
    private final Logger emergencyLog = LoggerFactory.getLogger(LogDNABulkAppender.class);

    public LogDNABulkAppender() {
        try {
            this.hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            this.hostname = "localhost";
        }
    }

    /**
     * In emergency, drop INFO and below events.
     */
    private boolean canDropEvent(ILoggingEvent event) {
        Level level = event.getLevel();
        boolean drop = level.toInt() <= 20000;
        if (drop)
            dropped.incrementAndGet();
        return drop;
    }

    private void logDroppedCount() {

        // Only called for events we are not dropping
        // Never more than every 60 seconds
        if (lastDropLog.plusMillis(60000)
                    .isBefore(Instant.now())) {

            int count = dropped.getAndSet(0);

            if (count > 0) {
                LoggingEvent warning = new LoggingEvent();
                warning.setLoggerName("uk.co.humboldt.logging.LogDNABulkAppender");
                warning.setLevel(Level.WARN);
                warning.setMessage("Dropped " + count + " log messages");
                append(warning);
                lastDropLog = Instant.now();
            }
        }
    }

    private BlockingQueue<String> blockingQueue;


    private final Worker worker = new Worker();

    @Override
    public void start() {
        if (isStarted())
            return;

        if (queueSize < 1) {
            addError("Invalid queue size [" + queueSize + "]");
            return;
        }
        blockingQueue = new ArrayBlockingQueue<>(queueSize);

        addInfo("Setting discardingThreshold to " + discardingThreshold);
        worker.setDaemon(true);
        worker.setName("LogDNABulkAppender-" + getName());
        // make sure this instance is marked as "started" before starting the worker Thread
        super.start();
        worker.start();
    }

    @Override
    public void stop() {
        if (!isStarted())
            return;

        super.stop();

        // interrupt the worker thread so that it can terminate.
        worker.interrupt();

        InterruptUtil interruptUtil = new InterruptUtil(context);

        try {
            interruptUtil.maskInterruptFlag();

            worker.join(maxFlushTime);

            // check to see if the thread ended and if not add a warning message
            if (worker.isAlive()) {
                addWarn("Max queue flush timeout (" + maxFlushTime + " ms) exceeded. Approximately " + blockingQueue.size()
                        + " queued events were possibly discarded.");
            } else {
                addInfo("Queue flush finished successfully within timeout.");
            }

        } catch (InterruptedException e) {
            int remaining = blockingQueue.size();
            addError("Failed to join worker thread. " + remaining + " queued events may be discarded.", e);
        } finally {
            interruptUtil.unmaskInterruptFlag();
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void append(ILoggingEvent eventObject) {
        // Prevent recursive logging of emergency messages
        if (eventObject.getLoggerName().equals(emergencyLog.getName()))
            return;

        // Drop low priority logs if queue close to threshold
        if (isQueueCapacityBelowDiscardingThreshold() && canDropEvent(eventObject))
            return;

        // If we are not dropping, attempt to log any previous dropped messages
        logDroppedCount();

        // Convert log line to JSON and store on queue
        Map<String,Object> eventMap = createLogDNAMap(eventObject);
        try {
            put(mapper.writeValueAsString(eventMap));
        } catch(IOException ex) {
            emergencyLog.warn("Unable to log JSON", ex);
        }
    }

    private boolean isQueueCapacityBelowDiscardingThreshold() {
        return (blockingQueue.remainingCapacity() < discardingThreshold);
    }

    private void put(String ev) {
        // Never freeze the application, even for high priority log events.
        if (! blockingQueue.offer(ev))
            dropped.incrementAndGet();
    }


    /**
     * Background thread that removes log lines from queue and POSTs to LogDNA.
     */
    class Worker extends Thread {

        public void run() {
            try {
                mainloop();
            } catch (Exception ex) {
                emergencyLog.error("Unexpected exit from LogDNA Appender", ex);
            }
        }

        private void mainloop() {
            LogDNABulkAppender parent = LogDNABulkAppender.this;
            // loop while the parent is started

            long bytes = 0;
            List<String> lines = new ArrayList<>();

            while (parent.isStarted()) {
                try {
                    // If we have lines, wait for the idle time before sending.
                    // If we have no lines, wait indefinitely for a line.
                    String e = (lines.size() > 0) ?
                            blockingQueue.poll(idleTime, TimeUnit.MILLISECONDS) :
                            blockingQueue.take();

                    if (e != null) {
                        bytes += e.getBytes(StandardCharsets.UTF_8).length;
                        lines.add(e);
                    }

                    // If we hit threshold or logging has slowed down, send
                    if (e == null || bytes > maxBatchBytes) {
                        send(lines);
                        lines.clear();
                        bytes = 0;
                    }

                } catch (InterruptedException ie) {
                    break;
                }
            }

            // Parent has stopped. Flush logs.
            addInfo("Worker thread will flush remaining events before exiting. ");
            do {
                send(lines);
                lines.clear();
            } while (blockingQueue.drainTo(lines, 10) > 0);
        }
    }

    /**
     * Convert the log event into a map, which will be serialised to JSON.
     */
    private Map<String,Object> createLogDNAMap(ILoggingEvent ev) {

        StringBuilder sb = (new StringBuilder()).append("[").append(ev.getThreadName()).append("] ").append(ev.getLoggerName()).append(" -- ").append(ev.getFormattedMessage());
        if (ev.getThrowableProxy() != null && this.includeStacktrace) {
            sb.append("\n");
            sb.append(ThrowableProxyUtil.asString(ev.getThrowableProxy()));
        }

        Map<String,Object> line = new HashMap<>();
        line.put("timestamp", ev.getTimeStamp());
        line.put("level", ev.getLevel().toString());
        line.put("app", this.appName);
        line.put("line", sb.toString());
        Map<String,Object> meta = new HashMap<>();
        meta.put("logger", ev.getLoggerName());
        meta.put("thread", ev.getThreadName());
        if (ev.getThrowableProxy() != null) {
            meta.put("exception", ev.getThrowableProxy().getClassName());
        }
        line.put("meta", meta);
        if (this.sendMDC)
            meta.putAll(ev.getMDCPropertyMap());
        meta.putAll(LogDNAMeta.getAll());

        return line;
    }


    private final CloseableHttpClient http = HttpClients.createDefault();

    private void send(List<String> lines) {

        try {

            if (lines.size() > 0) {
                // Construct the JSON payload from individual JSON elements.
                StringJoiner sb = new StringJoiner(
                        ",", "{ \"lines\": [", "]}");
                lines.forEach(sb::add);

                StringBuilder url = new StringBuilder("https://logs.logdna.com/logs/ingest?hostname=");
                url.append(encode(this.hostname));
                if (sendNow) {
                    url.append("&now=");
                    url.append(System.currentTimeMillis());
                }
                if (tags != null && ! tags.trim().isEmpty()) {
                    url.append("&tags=");
                    url.append(encode(tags));
                }

                HttpPost post = new HttpPost(url.toString());
                post.setHeader("User-Agent", "LogDNABulkAppender");
                post.setHeader("apikey", apikey);
                HttpEntity body = new StringEntity(sb.toString(),
                        ContentType.APPLICATION_JSON);
                post.setEntity(body);

                try (CloseableHttpResponse response = http.execute(post)) {

                    if (response.getStatusLine().getStatusCode() != 200) {
                        String msg = "Error posting to LogDNA ";
                        msg = msg + response.getStatusLine() + " ";

                        this.emergencyLog.error(msg);
                    }
                }
            }

        } catch(IOException ex) {
            this.emergencyLog.error("Unable to log to LogDNA", ex);
        }
    }


    private static String encode(String str) {
        try {
            return URLEncoder.encode(str, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            return str;
        }
    }
}
