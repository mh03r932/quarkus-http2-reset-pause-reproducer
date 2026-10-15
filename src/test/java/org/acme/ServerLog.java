package org.acme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects everything the server logs at WARNING or above while a scenario runs. */
final class ServerLog implements AutoCloseable {

    private final List<String> records = Collections.synchronizedList(new ArrayList<>());
    private final Logger root = LogManager.getLogManager().getLogger("");
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record.getLevel() + " " + record.getMessage() + " / " + record.getThrown());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    ServerLog() {
        root.addHandler(handler);
    }

    List<String> records() {
        return new ArrayList<>(records);
    }

    @Override
    public void close() {
        root.removeHandler(handler);
    }
}
