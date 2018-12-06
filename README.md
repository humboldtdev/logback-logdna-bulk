# High Throughput LogDNA Appender for Logback

[![Release](https://jitpack.io/v/humboldtdev/logback-logdna-bulk.svg)](https://jitpack.io/#humboldtdev/logback-logdna-bulk)

[LogDNA](https://logdna.com) is a hosted logging platform. This library provides a [Logback](https://logback.qos.ch/) appender to send log entries to LogDNA over HTTPS. 

This library is inspired by [logback-logdna](https://github.com/robshep/logback-logdna), but has different performance goals:
 * Never block the calling thread: drop log lines if LogDNA cannot be reached or cannot keep up.
 * Submit log messages in a batch for maximum throughput.
 
In our tests this appender was capable of sending 100 log lines per second to LogDNA. It achieves this by holding log lines in a buffer until either a size threshold has accumulated, or no further log lines have arrived within a configured time limit.
 
## Parameters

The appender has two compulsory parameters:
 
Parameter | Description
--------- | -----------
appName | Application name to be sent to LogDNA
ingestKey | Your LogDNA API key for ingest

These parameters are optional:

Parameter | Default | Description
--------- | ------- | -----------
queueSize | 1000 | Maximum count of log lines that can be queued during the POST to LogDNA
discardingThreshold | 50 | When queue space drops below this threshold, lower priority log lines (INFO or lower) will be dropped.
maxBatchBytes | 1000000 | When accumulate log lines pass this size, send to LogDNA
idleTime | 50 | If log lines stop arriving for this many milliseconds, send the current batch to LogDNA
maxFlushTime | 1000 | Spend up to this many milliseconds flushing logs to LogDNA when shutting down
sendMDC | true | Send Logback Mapped Diagnostic Context as metadata to LogDNA
includeStacktrace | true | Include exception stack traces in log messages
sendNow | false | Include current system time in POST to LogDNA to allow adjustment of log times
hostname | | Set to override automatically discovered hostname.
tags | | Set to supply tags to LogDNA. Multiple tags may be supplied as a comma separated list.

## Behaviour

Any error in communicating with LogDNA will be logged to other configured loggers, but will not be sent to LogDNA to prevent looping.

If log messages are dropped the appender will attempt to log a count of dropped messages to LogDNA when logging resumes. 

The appender automatically adds the following fields to metadata:

Field | Description
----- | -----------
logger | Logger name
thread | Thread name
exception | Exception class name if present

## Typed Metadata

The appender supports inserting metadata via [MDC](https://logback.qos.ch/manual/mdc.html),
 but the MDC API only takes `String` values.  As LogDNA supports numeric values and nested objects in metadata, the library adds
a `LogDNAMeta` API, which behaves much like MDC except for the
allowed types. These are all public static functions on the `LogDNAMeta` class:
```java
    void put(String key, Object value);
    Closeable putCloseable(String key, Object value);
    void remove(String key);
    void clear();
``` 

## Usage

The plugin is hosted by [JitPack](https://jitpack.io/), which must be added to the repositories section in `pom.xml`:

```xml    
	<repositories>
		<repository>
			<id>jitpack.io</id>
			<url>https://jitpack.io</url>
		</repository>
		...
	</repositories>
```

It may then be included as a normal Maven dependency:
```xml
    <dependency>
        <groupId>com.github.humboldtdev</groupId>
        <artifactId>logback-logdna-bulk</artifactId>
        <version>1.0</version>
    </dependency>
```

A minimal `logback.xml` configuration looks like this:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="logdnaAppender" class="uk.co.humboldt.logging.LogDNABulkAppender">
        <appName>LogDNA-Logback-Bulk-Test</appName>
        <ingestKey>${LOGDNA_INGEST_KEY}</ingestKey>
    </appender>

    <root level="INFO">
        <appender-ref ref="logdnaAppender"/>
    </root>
</configuration>
```
