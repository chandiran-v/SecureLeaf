package com.secureleaf.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only Hibernate {@link StatementInspector} (registered in application-test.yml) that can
 * record the SQL executed <b>by HTTP request threads</b> while a test has switched it on.
 *
 * <p>Why not {@code Statistics.getPrepareStatementCount()}? That counter is global to the
 * SessionFactory, so a background {@code @Scheduled} job (the processing-job poller, the viewer
 * session sweeper) that happens to query during the measured request gets counted too. An
 * "exactly N statements" assertion then fails at random: {@code LibraryIT} saw 2 vs 3.
 * Filtering by thread name keeps only the request's own statements.
 *
 * <p>While not recording, {@link #inspect} returns immediately, so the rest of the suite pays
 * nothing.
 */
public class RequestSqlRecorder implements StatementInspector {

    private static volatile boolean recording;
    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    /** Tomcat request threads are named http-nio-&lt;port&gt;-exec-N. */
    private static boolean isRequestThread() {
        return Thread.currentThread().getName().startsWith("http-nio-");
    }

    @Override
    public String inspect(String sql) {
        if (recording && isRequestThread()) {
            STATEMENTS.add(sql);
        }
        return sql;
    }

    /** Clears previous statements and starts recording. */
    public static void start() {
        STATEMENTS.clear();
        recording = true;
    }

    /** Stops recording and returns the request-thread statements seen since {@link #start()}. */
    public static List<String> stop() {
        recording = false;
        return List.copyOf(STATEMENTS);
    }
}
